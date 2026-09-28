package com.nebulaforge.app.database

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.database.DatabaseDiscovery
import com.nebulaforge.core.database.DbColumnInfo
import com.nebulaforge.core.database.DbFileInfo
import com.nebulaforge.core.database.DbResultSheet
import com.nebulaforge.core.database.DbTableInfo
import com.nebulaforge.core.database.SqlStatements
import com.nebulaforge.core.database.SqliteBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据库管理（全屏路由页）。
 *
 * 能力：发现项目内的 SQLite 文件 → 浏览表/视图、列定义与索引 → 分页查看数据 → 执行任意 SQL。
 * 安全：默认只读打开，写语句在数据库层被拒绝；只有显式打开"允许写操作"开关并再次确认后才放开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen(app: NebulaForgeApplication, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val workspace by app.workspaceState.state.collectAsStateWithLifecycle()
    val projectPath = workspace.projectPath

    var dbFiles by remember { mutableStateOf<List<DbFileInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var browser by remember { mutableStateOf<SqliteBrowser?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var tables by remember { mutableStateOf<List<DbTableInfo>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var columns by remember { mutableStateOf<List<DbColumnInfo>>(emptyList()) }
    var sheet by remember { mutableStateOf<DbResultSheet?>(null) }
    var rowCount by remember { mutableStateOf<Long?>(null) }
    var page by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }
    var sqlText by remember { mutableStateOf("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') ORDER BY name;") }
    var allowWrite by remember { mutableStateOf(false) }
    var writeConfirm by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pageSize = 50

    fun loadTable(name: String, targetPage: Int) {
        val b = browser ?: return
        runCatching {
            columns = b.columns(name)
            rowCount = b.rowCount(name)
            sheet = b.rows(name, pageSize, targetPage * pageSize)
            page = targetPage
        }.onFailure { message = "读取表失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun openDatabase(path: String) {
        runCatching {
            browser?.close()
            val opened = SqliteBrowser(path, writable = allowWrite)
            browser = opened
            selectedPath = path
            openError = null
            tables = opened.tables()
            selectedTable = tables.firstOrNull()?.name
            selectedTable?.let { loadTable(it, 0) } ?: run { sheet = null; columns = emptyList(); rowCount = null }
            message = "已打开：$path（${tables.size} 个表/视图）"
        }.onFailure {
            browser = null
            selectedPath = null
            tables = emptyList()
            sheet = null
            openError = it.message ?: "打开数据库失败"
            message = null
        }
    }

    // 进入页面时扫描项目内的数据库文件
    LaunchedEffect(projectPath) {
        if (projectPath.isNullOrBlank()) return@LaunchedEffect
        scanning = true
        dbFiles = withContext(Dispatchers.IO) { runCatching { DatabaseDiscovery.findDatabases(projectPath) }.getOrDefault(emptyList()) }
        scanning = false
        if (selectedPath == null) dbFiles.firstOrNull()?.let { openDatabase(it.path) }
    }

    fun executeSql(sql: String) {
        val b = browser ?: run { message = "请先选择并打开一个数据库"; return }
        val statements = SqlStatements.split(sql)
        if (statements.isEmpty()) { message = "请输入要执行的 SQL"; return }
        val writeStatement = statements.firstOrNull { SqlStatements.isWrite(it) }
        if (writeStatement != null && !allowWrite) {
            message = "当前为只读模式：已拒绝写语句。如需修改数据，请打开上方「允许写操作」开关。"
            return
        }
        scope.launch {
            busy = true
            val outcome = runCatching { withContext(Dispatchers.IO) { b.run(sql) } }
            sheet = outcome.getOrNull()
            message = outcome.fold(
                onSuccess = { it.notice ?: "执行成功" },
                onFailure = { "执行失败：${it.message ?: it.javaClass.simpleName}" }
            )
            // 结构可能变化，刷新表列表
            if (outcome.isSuccess && statements.any { SqlStatements.isWrite(it) }) {
                tables = withContext(Dispatchers.IO) { runCatching { b.tables() }.getOrDefault(tables) }
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据库管理") },
                navigationIcon = {
                    IconButton(onClick = { browser?.close(); onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            scanning = true
                            dbFiles = withContext(Dispatchers.IO) {
                                projectPath?.let { runCatching { DatabaseDiscovery.findDatabases(it) }.getOrDefault(emptyList()) }.orEmpty()
                            }
                            scanning = false
                            message = if (dbFiles.isEmpty()) "项目内未发现 SQLite 数据库文件" else "发现 ${dbFiles.size} 个数据库文件"
                        }
                    }) { Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描") }
                    TextButton(onClick = { pickerOpen = true }) { Text("选择数据库") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("当前数据库", style = MaterialTheme.typography.titleMedium)
                    if (selectedPath == null) {
                        Text(
                            when {
                                projectPath.isNullOrBlank() -> "尚未打开项目，请先在编辑器打开一个项目后再回来。"
                                scanning -> "正在扫描项目内的 SQLite 文件…"
                                dbFiles.isEmpty() -> "项目内未发现 .db/.sqlite 文件，可点击右上角「选择数据库」手动指定路径。"
                                else -> "已在项目内发现 ${dbFiles.size} 个数据库文件，点击右上角「选择数据库」选择。"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(selectedPath!!, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        Text(
                            "表/视图 ${tables.size} 个 · ${if (browser?.writable == true) "读写模式" else "只读模式"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    openError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = allowWrite,
                            enabled = selectedPath == null,
                            onCheckedChange = { if (it) writeConfirm = true else allowWrite = false }
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("允许写操作（危险）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (selectedPath == null) "仅对即将打开的数据库生效：开启后写语句才会被接受" else "已打开数据库；如需切换模式请重新选择数据库",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("表与数据") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("SQL 执行") })
            }

            if (selectedPath == null) {
                Text("请先选择一个数据库。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            } else if (tab == 0) {
                TableBrowserTab(
                    tables = tables,
                    selectedTable = selectedTable,
                    columns = columns,
                    sheet = sheet,
                    rowCount = rowCount,
                    page = page,
                    pageSize = pageSize,
                    onSelectTable = { loadTable(it, 0) },
                    onPrev = { selectedTable?.let { loadTable(it, (page - 1).coerceAtLeast(0)) } },
                    onNext = { selectedTable?.let { loadTable(it, page + 1) } },
                    modifier = Modifier.weight(1f)
                )
            } else {
                SqlConsoleTab(
                    sqlText = sqlText,
                    onSqlChange = { sqlText = it },
                    sheet = sheet,
                    busy = busy,
                    allowWrite = allowWrite,
                    onExecute = { executeSql(sqlText) },
                    onClear = { sqlText = ""; sheet = null; message = null },
                    modifier = Modifier.weight(1f)
                )
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (writeConfirm) {
        AlertDialog(
            onDismissRequest = { writeConfirm = false },
            title = { Text("允许写操作") },
            text = {
                Text(
                    "将以后续打开数据库时使用读写模式。之后执行的 INSERT/UPDATE/DELETE/DDL 会真实修改磁盘上的数据库文件，" +
                        "且无法撤销。请确认你已备份数据。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    writeConfirm = false
                    allowWrite = true
                    selectedPath?.let { openDatabase(it) }
                    message = "已开启写操作；重新打开数据库后可执行写语句。"
                }) { Text("我已备份，允许写") }
            },
            dismissButton = { TextButton(onClick = { writeConfirm = false }) { Text("取消") } }
        )
    }

    if (pickerOpen) {
        ModalBottomSheet(onDismissRequest = { pickerOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "选择数据库",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (dbFiles.isEmpty()) {
                    Text(
                        "项目中未发现数据库文件，可在下方手动输入绝对路径。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(dbFiles, key = { it.path }) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = {
                                    Text(
                                        "${formatSize(file.sizeBytes)} · ${file.path}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    pickerOpen = false
                                    openDatabase(file.path)
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("绝对路径") },
                        supportingText = { Text("例如 /storage/emulated/0/App/demo.db") }
                    )
                    Button(
                        enabled = manualPath.isNotBlank(),
                        onClick = { pickerOpen = false; openDatabase(manualPath.trim()) }
                    ) { Text("打开") }
                }
            }
        }
    }
}

@Composable
private fun TableBrowserTab(
    tables: List<DbTableInfo>,
    selectedTable: String?,
    columns: List<DbColumnInfo>,
    sheet: DbResultSheet?,
    rowCount: Long?,
    page: Int,
    pageSize: Int,
    onSelectTable: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tables.isEmpty()) {
            Text("该数据库没有可浏览的表或视图。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tables, key = { it.name }) { table ->
                FilterChip(
                    selected = table.name == selectedTable,
                    onClick = { onSelectTable(table.name) },
                    label = { Text(if (table.type == "view") "${table.name}（视图）" else table.name) }
                )
            }
        }
        selectedTable?.let { name ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("表结构：$name（${columns.size} 列）", style = MaterialTheme.typography.titleMedium)
                    columns.forEach { c ->
                        Text("· ${c.name}：${c.describe()}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(enabled = page > 0, onClick = onPrev) { Text("上一页") }
                OutlinedButton(
                    enabled = rowCount == null || (page + 1) * pageSize < rowCount!!,
                    onClick = onNext
                ) { Text("下一页") }
                Text(
                    "第 ${page + 1} 页" + (rowCount?.let { " · 共 $it 行" } ?: " · 总行数未知"),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        ResultGrid(sheet, Modifier.weight(1f))
    }
}

@Composable
private fun SqlConsoleTab(
    sqlText: String,
    onSqlChange: (String) -> Unit,
    sheet: DbResultSheet?,
    busy: Boolean,
    allowWrite: Boolean,
    onExecute: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = sqlText,
            onValueChange = onSqlChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            label = { Text("SQL（支持多条语句，用分号分隔）") },
            supportingText = { Text(if (allowWrite) "写模式：已允许修改数据" else "只读模式：写语句会被拒绝") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy && sqlText.isNotBlank(), onClick = onExecute) { Text(if (busy) "执行中…" else "执行") }
            OutlinedButton(enabled = !busy, onClick = onClear) { Text("清空") }
        }
        ResultGrid(sheet, Modifier.weight(1f))
    }
}

/** 结果表格：横向滚动 + 纵向滚动，单元格等宽、NULL 用灰色标记。 */
@Composable
private fun ResultGrid(sheet: DbResultSheet?, modifier: Modifier = Modifier) {
    val cellWidth = 150.dp
    if (sheet == null) {
        Spacer(modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!sheet.isQuery) {
            Text(
                sheet.notice ?: "执行成功",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text("影响行数：${sheet.affectedRows ?: 0} · 耗时 ${sheet.elapsedMs}ms", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            "${sheet.rows.size} 行 · ${sheet.columns.size} 列 · 耗时 ${sheet.elapsedMs}ms" + if (sheet.truncated) " · 已截断" else "",
            style = MaterialTheme.typography.bodyMedium
        )
        if (sheet.rows.isEmpty()) {
            Text("查询结果为空（0 行）。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth()) {
                    sheet.columns.forEach { name ->
                        Text(
                            name,
                            Modifier.width(cellWidth).padding(6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sheet.rows) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { value ->
                                Text(
                                    value ?: "NULL",
                                    Modifier.width(cellWidth).padding(6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = if (value == null) FontFamily.Default else FontFamily.Monospace,
                                    color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        sheet.notice?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
