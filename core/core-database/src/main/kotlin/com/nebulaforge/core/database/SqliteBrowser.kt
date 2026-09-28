package com.nebulaforge.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** 发现到的数据库文件。 */
data class DbFileInfo(val path: String, val sizeBytes: Long, val modifiedAt: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** 表或视图。 */
data class DbTableInfo(val name: String, val type: String, val sql: String?)

/** 列定义（来自 PRAGMA table_info）。 */
data class DbColumnInfo(
    val position: Int,
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Boolean,
    val defaultValue: String?
) {
    fun describe(): String = buildString {
        append(type.ifBlank { "未声明类型" })
        if (primaryKey) append(" · 主键")
        if (notNull) append(" · NOT NULL")
        defaultValue?.let { append(" · 默认 $it") }
    }
}

/**
 * 一次查询/执行的结果。
 * [columns] 为空表示非查询语句（DDL/DML），此时看 [affectedRows] 与 [notice]。
 */
data class DbResultSheet(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
    val affectedRows: Int?,
    val elapsedMs: Long,
    val notice: String?
) {
    val isQuery: Boolean get() = columns.isNotEmpty()
}

/** 数据库文件发现结果（项目内扫描）。 */
object DatabaseDiscovery {

    private val EXTENSIONS = setOf("db", "sqlite", "sqlite3", "db3", "s3db")
    private const val MAGIC = "SQLite format 3\u0000"

    /**
     * 在项目目录内递归查找 SQLite 数据库。
     *
     * 判定方式：扩展名命中 **或** 文件头为 SQLite 魔数（后者可发现被改名/无扩展名的库）。
     * 为避免在大仓库上卡顿，限制扫描文件数与深度，并跳过 .git/build/node_modules 等目录。
     */
    fun findDatabases(root: String, maxDepth: Int = 4, maxFiles: Int = 4000): List<DbFileInfo> {
        val rootFile = File(root)
        if (!rootFile.isDirectory) return emptyList()
        val skip = setOf(".git", "build", "node_modules", ".gradle", ".idea", "vendor", "target", "dist", ".nebulaforge")
        val out = mutableListOf<DbFileInfo>()
        var scanned = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(rootFile to 0)
        while (queue.isNotEmpty() && scanned < maxFiles) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (scanned >= maxFiles) break
                val lower = child.name.lowercase()
                if (child.isDirectory) {
                    if (depth < maxDepth && lower !in skip && !lower.startsWith(".")) queue.add(child to depth + 1)
                    continue
                }
                scanned++
                val ext = lower.substringAfterLast('.', "")
                val looksLikeDb = ext in EXTENSIONS || hasSqliteMagic(child)
                if (looksLikeDb) out.add(DbFileInfo(child.absolutePath, child.length(), child.lastModified()))
            }
        }
        return out.sortedBy { it.path }
    }

    private fun hasSqliteMagic(file: File): Boolean = runCatching {
        if (file.length() < 16) return@runCatching false
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(16)
            raf.readFully(buf)
            String(buf, Charsets.ISO_8859_1) == MAGIC
        }
    }.getOrDefault(false)
}

/** SQL 语句工具：拆分多语句、判断语句类别。 */
object SqlStatements {

    /** 按分号拆分脚本，正确跳过字符串/标识符/注释内的分号。 */
    fun split(sql: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> {
                    sb.append(c)
                    if (c == quote) {
                        // SQLite 用两个连续引号转义
                        if (i + 1 < sql.length && sql[i + 1] == quote) { sb.append(sql[i + 1]); i++ } else quote = null
                    }
                    i++
                }
                c == '\'' || c == '"' || c == '`' -> { quote = c; sb.append(c); i++ }
                c == '[' -> { quote = ']'; sb.append(c); i++ }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i += 2
                }
                c == ';' -> {
                    val stmt = sb.toString().trim()
                    if (stmt.isNotEmpty()) out.add(stmt)
                    sb.setLength(0)
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        sb.toString().trim().let { if (it.isNotEmpty()) out.add(it) }
        return out
    }

    /** 是否返回结果集（query）。 */
    fun isQuery(stmt: String): Boolean = keyword(stmt) in setOf("select", "with", "pragma", "explain", "values")

    /** 是否会修改数据或结构。 */
    fun isWrite(stmt: String): Boolean = keyword(stmt) in setOf(
        "insert", "update", "delete", "replace", "create", "drop", "alter", "vacuum", "reindex", "attach", "detach"
    )

    private fun keyword(stmt: String): String {
        var s = stmt.trimStart()
        // 跳过前导注释与左括号
        while (true) {
            when {
                s.startsWith("--") -> s = s.substringAfter('\n', "")
                s.startsWith("/*") -> s = s.substringAfter("*/", "")
                s.startsWith("(") -> s = s.substring(1)
                else -> break
            }
            s = s.trimStart()
        }
        return s.takeWhile { it.isLetter() }.lowercase()
    }
}

/**
 * 单个 SQLite 数据库的浏览/执行会话。
 *
 * 安全模型：
 *  - 默认以 **只读** 方式打开（SQLiteDatabase.OPEN_READONLY），任何写语句在数据库层即被拒绝；
 *  - 只有 UI 显式打开"允许写操作"开关才会以 OPEN_READWRITE 打开，且写语句仍需用户逐条确认；
 *  - 结果集强制行数上限，避免 `SELECT * FROM 大表` 把内存打爆。
 */
class SqliteBrowser(path: String, val writable: Boolean = false) : Closeable {

    val path: String = path

    private val db: SQLiteDatabase = runCatching {
        val flags = if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        SQLiteDatabase.openDatabase(path, null, flags)
    }.getOrElse { e ->
        throw IllegalStateException("无法打开数据库 $path：${e.message ?: e.javaClass.simpleName}")
    }

    /** 表与视图列表（排除 sqlite_ 内部表）。 */
    fun tables(): List<DbTableInfo> {
        val out = mutableListOf<DbTableInfo>()
        db.rawQuery(
            "SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(DbTableInfo(c.getString(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    fun columns(table: String): List<DbColumnInfo> {
        val out = mutableListOf<DbColumnInfo>()
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { c ->
            val iName = c.getColumnIndex("name")
            val iType = c.getColumnIndex("type")
            val iNotNull = c.getColumnIndex("notnull")
            val iPk = c.getColumnIndex("pk")
            val iDflt = c.getColumnIndex("dflt_value")
            val iCid = c.getColumnIndex("cid")
            while (c.moveToNext()) {
                out.add(
                    DbColumnInfo(
                        position = if (iCid >= 0) c.getInt(iCid) else out.size,
                        name = c.getString(iName) ?: "",
                        type = c.getString(iType) ?: "",
                        notNull = iNotNull >= 0 && c.getInt(iNotNull) != 0,
                        primaryKey = iPk >= 0 && c.getInt(iPk) != 0,
                        defaultValue = if (iDflt >= 0 && c.getType(iDflt) != Cursor.FIELD_TYPE_NULL) c.getString(iDflt) else null
                    )
                )
            }
        }
        return out
    }

    fun indexes(table: String): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("PRAGMA index_list(${quoteIdentifier(table)})", null).use { c ->
            val iName = c.getColumnIndex("name")
            if (iName < 0) return emptyList()
            while (c.moveToNext()) c.getString(iName)?.let { out.add(it) }
        }
        return out
    }

    fun rowCount(table: String): Long? = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }.getOrNull()

    /** 分页读取表数据。 */
    fun rows(table: String, limit: Int = PAGE_SIZE, offset: Int = 0): DbResultSheet {
        val started = System.currentTimeMillis()
        val rows = mutableListOf<List<String?>>()
        var columns = emptyList<String>()
        var truncated = false
        db.rawQuery(
            "SELECT * FROM ${quoteIdentifier(table)} LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        ).use { c ->
            columns = c.columnNames.toList()
            while (c.moveToNext()) {
                rows.add(List(columns.size) { i -> cell(c, i) })
            }
            truncated = rows.size >= limit
        }
        return DbResultSheet(columns, rows, truncated, null, System.currentTimeMillis() - started, null)
    }

    /**
     * 执行用户 SQL（支持多语句脚本）。
     *
     * 返回最后一个查询语句的结果集；若脚本只有写语句，则返回累计影响行数。
     * 只读会话下遇到写语句直接抛错，不静默跳过（避免用户以为执行成功）。
     */
    fun run(sql: String, maxRows: Int = MAX_ROWS): DbResultSheet {
        val statements = SqlStatements.split(sql)
        require(statements.isNotEmpty()) { "没有可执行的 SQL 语句" }
        val notices = mutableListOf<String>()
        var affectedTotal = 0
        var lastResult: DbResultSheet? = null
        var executed = 0
        for (stmt in statements) {
            val started = System.currentTimeMillis()
            when {
                SqlStatements.isQuery(stmt) -> {
                    lastResult = runQuery(stmt, maxRows)
                    executed++
                }
                SqlStatements.isWrite(stmt) -> {
                    if (!writable) throw IllegalStateException("当前为只读模式，已拒绝执行写语句：${stmt.take(60)}…")
                    val affected = runWrite(stmt)
                    affectedTotal += affected
                    executed++
                    notices.add("影响 $affected 行：${stmt.take(28)}…")
                }
                else -> {
                    // 其余语句（如 BEGIN/COMMIT/ROLLBACK）直接执行
                    db.execSQL(stmt)
                    executed++
                }
            }
        }
        val base = lastResult
        val notice = buildString {
            if (notices.isNotEmpty()) append(notices.joinToString("；"))
            if (statements.size > 1) {
                if (isNotEmpty()) append(" · ")
                append("共执行 $executed 条语句")
            }
        }.ifBlank { null }
        return base?.copy(affectedRows = affectedTotal.takeIf { it > 0 }, notice = notice)
            ?: DbResultSheet(emptyList(), emptyList(), false, affectedTotal, 0, notice ?: "执行成功")
    }

    private fun runQuery(stmt: String, maxRows: Int): DbResultSheet {
        val started = System.currentTimeMillis()
        var rows = mutableListOf<List<String?>>()
        var columns = emptyList<String>()
        var truncated = false
        db.rawQuery(stmt, null).use { c ->
            columns = c.columnNames.toList()
            while (c.moveToNext()) {
                if (rows.size >= maxRows) { truncated = true; break }
                rows.add(List(columns.size) { i -> cell(c, i) })
            }
        }
        return DbResultSheet(
            columns = columns,
            rows = rows,
            truncated = truncated,
            affectedRows = null,
            elapsedMs = System.currentTimeMillis() - started,
            notice = if (truncated) "结果超过 $maxRows 行，已截断显示" else null
        )
    }

    /** 写语句：优先取受影响行数，DDL 走 execSQL。 */
    private fun runWrite(stmt: String): Int {
        val head = stmt.trimStart().takeWhile { it.isLetter() }.lowercase()
        return if (head in setOf("insert", "update", "delete", "replace")) {
            db.compileStatement(stmt).use { it.executeUpdateDelete() }
        } else {
            db.execSQL(stmt)
            0
        }
    }

    override fun close() {
        runCatching { if (db.isOpen) db.close() }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_ROWS = 500
        const val MAX_CELL_CHARS = 400
    }
}

/** 把单元格渲染为字符串：BLOB 只显示大小，长文本截断，NULL 显示为空。 */
private fun cell(c: Cursor, index: Int): String? = when (c.getType(index)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_BLOB -> {
        val n = runCatching { c.getBlob(index)?.size ?: 0 }.getOrDefault(0)
        "<BLOB $n 字节>"
    }
    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
    Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
    else -> c.getString(index)?.let { if (it.length > 400) it.take(400) + "…" else it }
}

/** SQLite 标识符引用（PRAGMA 不支持绑定参数，必须自行转义）。 */
internal fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
