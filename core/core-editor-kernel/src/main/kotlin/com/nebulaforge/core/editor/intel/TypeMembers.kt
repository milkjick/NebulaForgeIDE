package com.nebulaforge.core.editor.intel

/**
 * 常见类型的成员表（用于 `obj.` 之后的补全）+ 轻量初始化表达式类型推断。
 *
 * 只是"够用的近似"：真正的成员解析需要编译器前端，这里用启发式覆盖高频场景。
 */
object TypeMembers {

    private fun m(name: String, detail: String, kind: CompletionKind = CompletionKind.METHOD, insert: String = name) =
        ApiEntry(name, insert, detail, kind)

    /** 去掉泛型、可空后缀、包名前缀，得到裸类型名。 */
    fun normalize(raw: String): String {
        var t = raw.trim()
        // 去掉泛型参数
        val lt = t.indexOf('<')
        if (lt > 0) t = t.substring(0, lt)
        // 去掉数组后缀
        t = t.removeSuffix("[]").removeSuffix("?").removeSuffix("!")
        // 去掉包名前缀
        t = t.substringAfterLast('.')
        return t.trim()
    }

    private val STRING_MEMBERS = listOf(
        m("length", "字符个数", CompletionKind.PROPERTY),
        m("isEmpty()", "是否为空串"),
        m("isNotEmpty()", "是否非空串"),
        m("isBlank()", "是否仅空白字符"),
        m("trim()", "去掉首尾空白"),
        m("trimIndent()", "去掉公共缩进"),
        m("uppercase()", "转大写"),
        m("lowercase()", "转小写"),
        m("toUpperCase()", "转大写（Java 风格）"),
        m("toLowerCase()", "转小写（Java 风格）"),
        m("substring(", "取子串"),
        m("substringBefore(", "取分隔符之前"),
        m("substringAfter(", "取分隔符之后"),
        m("replace(", "替换内容"),
        m("replaceAll(", "全部替换"),
        m("split(", "按分隔符切分"),
        m("joinToString(", "拼接为字符串"),
        m("contains(", "是否包含"),
        m("startsWith(", "是否以…开头"),
        m("endsWith(", "是否以…结尾"),
        m("indexOf(", "查找位置"),
        m("lastIndexOf(", "从后查找位置"),
        m("toInt()", "转整数"),
        m("toIntOrNull()", "安全转整数"),
        m("toLong()", "转长整数"),
        m("toDouble()", "转浮点数"),
        m("toFloat()", "转单精度"),
        m("toBoolean()", "转布尔"),
        m("format(", "格式化（含占位符时）"),
        m("matches(", "全串正则匹配"),
        m("toRegex()", "转正则"),
        m("toCharArray()", "转字符数组"),
        m("toByteArray()", "转字节数组"),
        m("lines()", "按行拆分"),
        m("padStart(", "左侧补齐"),
        m("padEnd(", "右侧补齐"),
        m("repeat(", "重复拼成新串"),
        m("compareTo(", "比较大小"),
        m("equals(", "内容相等判断"),
        m("hashCode()", "哈希值"),
        m("toString()", "转字符串")
    )

    private val NUMBER_MEMBERS = listOf(
        m("toString()", "转字符串"),
        m("toInt()", "转整数"),
        m("toLong()", "转长整数"),
        m("toDouble()", "转双精度"),
        m("toFloat()", "转单精度"),
        m("toChar()", "转字符（码点）"),
        m("coerceAtLeast(", "下限截断"),
        m("coerceAtMost(", "上限截断"),
        m("coerceIn(", "区间截断"),
        m("compareTo(", "比较大小"),
        m("plus(", "加"),
        m("minus(", "减"),
        m("times(", "乘"),
        m("div(", "除"),
        m("rem(", "取余"),
        m("absoluteValue", "绝对值（Kotlin 1.5+）", CompletionKind.PROPERTY)
    )

    private val BOOLEAN_MEMBERS = listOf(
        m("and(", "逻辑与"),
        m("or(", "逻辑或"),
        m("not()", "逻辑非"),
        m("xor(", "逻辑异或"),
        m("toString()", "转字符串")
    )

    private val LIST_MEMBERS = listOf(
        m("size", "元素个数", CompletionKind.PROPERTY),
        m("isEmpty()", "是否为空"),
        m("isNotEmpty()", "是否非空"),
        m("get(", "按下标取元素"),
        m("first()", "取第一个"),
        m("firstOrNull()", "取第一个或 null"),
        m("last()", "取最后一个"),
        m("lastOrNull()", "取最后一个或 null"),
        m("add(", "添加元素"),
        m("addAll(", "批量添加"),
        m("remove(", "移除元素"),
        m("removeAt(", "按下标移除"),
        m("clear()", "清空"),
        m("contains(", "是否包含"),
        m("indexOf(", "查找下标"),
        m("forEach(", "遍历"),
        m("forEachIndexed(", "带下标遍历"),
        m("map(", "映射为新列表"),
        m("mapNotNull(", "映射并过滤空"),
        m("filter(", "过滤"),
        m("filterNot(", "反向过滤"),
        m("find(", "查找第一个匹配"),
        m("any(", "是否存在满足项"),
        m("all(", "是否全部满足"),
        m("none(", "是否全不满足"),
        m("count(", "计数"),
        m("sum()", "求和"),
        m("sumOf(", "按取值求和"),
        m("maxOrNull()", "最大值"),
        m("minOrNull()", "最小值"),
        m("sorted()", "排序"),
        m("sortedBy(", "按条件排序"),
        m("sortedDescending()", "降序排序"),
        m("reversed()", "反转"),
        m("distinct()", "去重"),
        m("flatten()", "展平嵌套"),
        m("take(", "取前 n 个"),
        m("drop(", "跳过前 n 个"),
        m("chunked(", "分块"),
        m("windowed(", "滑动窗口"),
        m("groupBy(", "分组"),
        m("associateBy(", "按 key 建映射"),
        m("associate(", "转为映射"),
        m("joinToString(", "拼接为字符串"),
        m("toList()", "转列表"),
        m("toSet()", "转集合"),
        m("toMutableList()", "转可变列表"),
        m("toTypedArray()", "转数组"),
        m("iterator()", "取迭代器"),
        m("copyOf()", "复制")
    )

    private val MAP_MEMBERS = listOf(
        m("size", "键值对数量", CompletionKind.PROPERTY),
        m("keys", "所有键", CompletionKind.PROPERTY),
        m("values", "所有值", CompletionKind.PROPERTY),
        m("entries", "所有条目", CompletionKind.PROPERTY),
        m("isEmpty()", "是否为空"),
        m("isNotEmpty()", "是否非空"),
        m("get(", "按键取值"),
        m("getOrDefault(", "取值或默认"),
        m("getOrElse(", "取值或计算默认"),
        m("containsKey(", "是否含键"),
        m("containsValue(", "是否含值"),
        m("put(", "放入键值"),
        m("putAll(", "批量放入"),
        m("remove(", "按键移除"),
        m("clear()", "清空"),
        m("forEach(", "遍历键值"),
        m("mapValues(", "映射值"),
        m("mapKeys(", "映射键"),
        m("filterKeys(", "过滤键"),
        m("filterValues(", "过滤值"),
        m("toList()", "转列表"),
        m("toMap()", "转映射"),
        m("toMutableMap()", "转可变映射")
    )

    private val SET_MEMBERS = listOf(
        m("size", "元素个数", CompletionKind.PROPERTY),
        m("isEmpty()", "是否为空"),
        m("add(", "添加元素"),
        m("remove(", "移除元素"),
        m("contains(", "是否包含"),
        m("union(", "并集"),
        m("intersect(", "交集"),
        m("subtract(", "差集"),
        m("toList()", "转列表"),
        m("toSet()", "转集合"),
        m("forEach(", "遍历"),
        m("map(", "映射"),
        m("filter(", "过滤")
    )

    private val ARRAY_MEMBERS = LIST_MEMBERS + listOf(
        m("contentToString()", "数组转字符串"),
        m("contentEquals(", "数组内容比较"),
        m("copyOf(", "复制数组"),
        m("fill(", "填充元素")
    )

    private val FILE_MEMBERS = listOf(
        m("name", "文件名", CompletionKind.PROPERTY),
        m("nameWithoutExtension", "不含扩展名的文件名", CompletionKind.PROPERTY),
        m("extension", "扩展名", CompletionKind.PROPERTY),
        m("path", "路径字符串", CompletionKind.PROPERTY),
        m("absolutePath", "绝对路径", CompletionKind.PROPERTY),
        m("canonicalPath", "规范路径", CompletionKind.PROPERTY),
        m("parent", "父路径", CompletionKind.PROPERTY),
        m("parentFile", "父目录 File", CompletionKind.PROPERTY),
        m("exists()", "是否存在"),
        m("isFile", "是否文件", CompletionKind.PROPERTY),
        m("isDirectory", "是否目录", CompletionKind.PROPERTY),
        m("length()", "文件大小（字节）"),
        m("lastModified()", "最后修改时间"),
        m("canRead()", "是否可读"),
        m("canWrite()", "是否可写"),
        m("mkdir()", "创建目录"),
        m("mkdirs()", "递归创建目录"),
        m("createNewFile()", "创建空文件"),
        m("delete()", "删除"),
        m("deleteRecursively()", "递归删除"),
        m("renameTo(", "重命名或移动"),
        m("list()", "列出子文件名"),
        m("listFiles()", "列出子文件"),
        m("listFiles(", "按条件列出子文件"),
        m("walk()", "递归遍历"),
        m("walkTopDown()", "自上而下遍历"),
        m("readText()", "读全部文本"),
        m("readText(", "按编码读文本"),
        m("readBytes()", "读全部字节"),
        m("readLines()", "按行读取"),
        m("writeText(", "写入文本"),
        m("writeBytes(", "写入字节"),
        m("appendText(", "追加文本"),
        m("copyTo(", "复制到"),
        m("bufferedReader()", "取缓冲读取器"),
        m("bufferedWriter()", "取缓冲写入器"),
        m("inputStream()", "取输入流"),
        m("outputStream()", "取输出流"),
        m("toURI()", "转 URI"),
        m("toPath()", "转 Path")
    )

    private val STRING_BUILDER_MEMBERS = listOf(
        m("append(", "追加内容"),
        m("insert(", "插入内容"),
        m("delete(", "删除区间"),
        m("deleteCharAt(", "删除指定字符"),
        m("replace(", "替换区间"),
        m("reverse()", "反转"),
        m("length()", "当前长度"),
        m("setLength(", "设置长度"),
        m("toString()", "转字符串"),
        m("indexOf(", "查找位置"),
        m("charAt(", "取字符")
    )

    private val REGEX_MEMBERS = listOf(
        m("pattern", "模式串", CompletionKind.PROPERTY),
        m("matches(", "全串匹配"),
        m("containsMatchIn(", "是否包含匹配"),
        m("find(", "查找第一个匹配"),
        m("findAll(", "查找全部匹配"),
        m("replace(", "替换匹配"),
        m("replaceFirst(", "替换首个匹配"),
        m("split(", "按匹配切分")
    )

    private val JSON_OBJECT_MEMBERS = listOf(
        m("getString(", "取字符串字段"),
        m("optString(", "取字符串字段或默认"),
        m("getInt(", "取整数字段"),
        m("optInt(", "取整数字段或默认"),
        m("getLong(", "取长整数字段"),
        m("optLong(", "取长整数字段或默认"),
        m("getDouble(", "取浮点字段"),
        m("optDouble(", "取浮点字段或默认"),
        m("getBoolean(", "取布尔字段"),
        m("optBoolean(", "取布尔字段或默认"),
        m("getJSONObject(", "取子对象"),
        m("optJSONObject(", "取子对象或 null"),
        m("getJSONArray(", "取子数组"),
        m("optJSONArray(", "取子数组或 null"),
        m("has(", "是否含字段"),
        m("isNull(", "字段是否为 null"),
        m("keys()", "取键迭代器"),
        m("put(", "写入字段"),
        m("remove(", "移除字段"),
        m("toString()", "序列化"),
        m("toString(", "按缩进序列化"),
        m("length()", "字段数量")
    )

    private val JSON_ARRAY_MEMBERS = listOf(
        m("length()", "元素个数"),
        m("get(", "按下标取元素"),
        m("getString(", "取字符串元素"),
        m("getInt(", "取整数元素"),
        m("getJSONObject(", "取子对象"),
        m("getJSONArray(", "取子数组"),
        m("optString(", "取字符串或默认"),
        m("put(", "追加或设置元素"),
        m("remove(", "按下标移除"),
        m("isNull(", "元素是否为 null"),
        m("toString()", "序列化")
    )

    /** 查询某个类型的成员；未收录则返回空表。 */
    fun members(typeName: String, lang: IntelLanguage): List<ApiEntry> {
        val t = normalize(typeName)
        if (t.isEmpty()) return emptyList()
        return when (lang) {
            IntelLanguage.DART -> dartMembers(t)
            IntelLanguage.PYTHON -> pythonMembers(t)
            else -> jvmLikeMembers(t)
        }
    }

    private fun jvmLikeMembers(t: String): List<ApiEntry> = when (t) {
        "String", "CharSequence", "StringBuilder0" -> STRING_MEMBERS
        "Int", "Long", "Double", "Float", "Short", "Byte", "Number", "Integer" -> NUMBER_MEMBERS
        "Boolean" -> BOOLEAN_MEMBERS
        "MutableList", "ArrayList", "LinkedList", "List" -> LIST_MEMBERS
        "MutableMap", "HashMap", "LinkedHashMap", "Map" -> MAP_MEMBERS
        "MutableSet", "HashSet", "Set" -> SET_MEMBERS
        "Array", "IntArray", "LongArray", "DoubleArray", "ByteArray", "CharArray" -> ARRAY_MEMBERS
        "File" -> FILE_MEMBERS
        "StringBuilder", "StringBuffer" -> STRING_BUILDER_MEMBERS
        "Regex", "Pattern" -> REGEX_MEMBERS
        "JSONObject" -> JSON_OBJECT_MEMBERS
        "JSONArray" -> JSON_ARRAY_MEMBERS
        else -> AndroidMembers.members(t) + ComposeMembers.members(t)
    }

    private fun dartMembers(t: String): List<ApiEntry> = when (t) {
        "String" -> listOf(
            m("length", "字符个数", CompletionKind.PROPERTY),
            m("isEmpty", "是否为空", CompletionKind.PROPERTY),
            m("isNotEmpty", "是否非空", CompletionKind.PROPERTY),
            m("trim()", "去首尾空白"),
            m("toUpperCase()", "转大写"),
            m("toLowerCase()", "转小写"),
            m("substring(", "取子串"),
            m("replaceAll(", "全部替换"),
            m("split(", "切分"),
            m("contains(", "是否包含"),
            m("startsWith(", "是否以…开头"),
            m("endsWith(", "是否以…结尾"),
            m("indexOf(", "查找位置"),
            m("padLeft(", "左侧补齐"),
            m("padRight(", "右侧补齐"),
            m("codeUnitAt(", "取字符码元")
        )
        "int", "double", "num" -> listOf(
            m("toString()", "转字符串"),
            m("toInt()", "转整数"),
            m("toDouble()", "转浮点"),
            m("abs()", "绝对值"),
            m("round()", "四舍五入"),
            m("floor()", "向下取整"),
            m("ceil()", "向上取整"),
            m("clamp(", "区间截断"),
            m("isEven", "是否偶数", CompletionKind.PROPERTY),
            m("isOdd", "是否奇数", CompletionKind.PROPERTY)
        )
        "List", "Iterable" -> listOf(
            m("length", "元素个数", CompletionKind.PROPERTY),
            m("isEmpty", "是否为空", CompletionKind.PROPERTY),
            m("isNotEmpty", "是否非空", CompletionKind.PROPERTY),
            m("add(", "添加元素"),
            m("addAll(", "批量添加"),
            m("remove(", "移除元素"),
            m("removeAt(", "按下标移除"),
            m("clear()", "清空"),
            m("contains(", "是否包含"),
            m("forEach(", "遍历"),
            m("map(", "映射"),
            m("where(", "过滤"),
            m("firstWhere(", "查找第一个满足项"),
            m("any(", "是否存在满足项"),
            m("every(", "是否全部满足"),
            m("reduce(", "归约"),
            m("fold(", "带初值归约"),
            m("sort(", "排序"),
            m("sublist(", "取子列表"),
            m("toList()", "转列表"),
            m("join(", "拼接为字符串")
        )
        "Map" -> listOf(
            m("length", "键值对数量", CompletionKind.PROPERTY),
            m("isEmpty", "是否为空", CompletionKind.PROPERTY),
            m("keys", "所有键", CompletionKind.PROPERTY),
            m("values", "所有值", CompletionKind.PROPERTY),
            m("containsKey(", "是否含键"),
            m("containsValue(", "是否含值"),
            m("remove(", "按键移除"),
            m("clear()", "清空"),
            m("forEach(", "遍历"),
            m("map(", "映射"),
            m("putIfAbsent(", "缺省写入")
        )
        "Future" -> listOf(
            m("then(", "成功回调"),
            m("catchError(", "错误回调"),
            m("whenComplete(", "完成回调"),
            m("timeout(", "超时控制")
        )
        "BuildContext" -> listOf(
            m("size", "屏幕尺寸", CompletionKind.PROPERTY),
            m("theme", "当前主题", CompletionKind.PROPERTY),
            m("mediaQuery", "媒体查询", CompletionKind.PROPERTY),
            m("mounted", "是否仍挂载", CompletionKind.PROPERTY),
            m("pushNamed(", "命名路由跳转"),
            m("pop(", "返回上一页"),
            m("findAncestorStateOfType(", "查找祖先状态")
        )
        "Color" -> listOf(
            m("value", "色值", CompletionKind.PROPERTY),
            m("withOpacity(", "设置透明度"),
            m("withAlpha(", "设置 alpha"),
            m("isDark", "是否深色", CompletionKind.PROPERTY)
        )
        "Container", "Widget" -> listOf(
            m("build(", "构建子组件"),
            m("createElement()", "创建元素"),
            m("key", "组件键", CompletionKind.PROPERTY)
        )
        "State" -> listOf(
            m("setState(", "触发重建"),
            m("context", "构建上下文", CompletionKind.PROPERTY),
            m("mounted", "是否仍挂载", CompletionKind.PROPERTY),
            m("initState()", "初始化"),
            m("dispose()", "销毁"),
            m("didChangeDependencies()", "依赖变化"),
            m("didUpdateWidget(", "组件更新")
        )
        "TextEditingController" -> listOf(
            m("text", "当前文本", CompletionKind.PROPERTY),
            m("clear()", "清空"),
            m("selection", "选区", CompletionKind.PROPERTY),
            m("dispose()", "销毁")
        )
        "DateTime" -> listOf(
            m("year", "年", CompletionKind.PROPERTY),
            m("month", "月", CompletionKind.PROPERTY),
            m("day", "日", CompletionKind.PROPERTY),
            m("hour", "时", CompletionKind.PROPERTY),
            m("minute", "分", CompletionKind.PROPERTY),
            m("second", "秒", CompletionKind.PROPERTY),
            m("add(", "加时长"),
            m("subtract(", "减时长"),
            m("difference(", "时间差"),
            m("isBefore(", "是否早于"),
            m("isAfter(", "是否晚于"),
            m("toIso8601String()", "ISO 8601 字符串")
        )
        else -> emptyList()
    }

    private fun pythonMembers(t: String): List<ApiEntry> = when (t) {
        "str" -> listOf(
            m("strip()", "去首尾空白"),
            m("split(", "切分"),
            m("join(", "拼接"),
            m("replace(", "替换"),
            m("upper()", "转大写"),
            m("lower()", "转小写"),
            m("startswith(", "是否以…开头"),
            m("endswith(", "是否以…结尾"),
            m("find(", "查找位置"),
            m("format(", "格式化"),
            m("isdigit()", "是否全数字")
        )
        "list" -> listOf(
            m("append(", "追加元素"),
            m("extend(", "扩展列表"),
            m("insert(", "插入元素"),
            m("remove(", "移除元素"),
            m("pop(", "弹出元素"),
            m("sort(", "排序"),
            m("reverse()", "反转"),
            m("index(", "查找下标"),
            m("count(", "计数"),
            m("copy()", "浅拷贝"),
            m("clear()", "清空")
        )
        "dict" -> listOf(
            m("get(", "按键取值"),
            m("keys()", "所有键"),
            m("values()", "所有值"),
            m("items()", "所有键值对"),
            m("update(", "批量更新"),
            m("pop(", "弹出键值"),
            m("setdefault(", "缺省值"),
            m("clear()", "清空")
        )
        else -> emptyList()
    }

    /**
     * 从初始化表达式推断类型（`val x = listOf(1)` → `List`）。
     *
     * @param expr 等号右侧表达式（可含换行）
     * @param knownClasses 文档内已声明的类名集合
     */
    fun inferFromInitializer(expr: String, knownClasses: Set<String> = emptySet()): String? {
        val e = expr.trim().removePrefix("=").trim()
        if (e.isEmpty()) return null
        // 显式构造调用：Foo(...) / Foo.bar() / Foo()
        val ctor = Regex("^([A-Z][A-Za-z0-9_]*)").find(e)?.groupValues?.get(1)
        if (ctor != null) {
            when (ctor) {
                "StringBuilder" -> return "StringBuilder"
                "File" -> return "File"
                "Intent" -> return "Intent"
                "Bundle" -> return "Bundle"
                "Regex" -> return "Regex"
                "JSONObject" -> return "JSONObject"
                "JSONArray" -> return "JSONArray"
                "ArrayList", "MutableList" -> return "MutableList"
                "LinkedList" -> return "MutableList"
                "HashMap", "LinkedHashMap" -> return "MutableMap"
                "HashSet", "LinkedHashSet" -> return "MutableSet"
                "ArrayList0" -> return "MutableList"
                else -> if (ctor in knownClasses) return ctor
            }
        }
        // 字面量
        when {
            e.startsWith("\"\"\"") || e.startsWith("\"") || e.startsWith("'") -> return "String"
            e.toIntOrNull() != null -> return "Int"
            e.toLongOrNull() != null -> return "Long"
            e.toDoubleOrNull() != null -> return "Double"
            e == "true" || e == "false" -> return "Boolean"
            e.startsWith("null") -> return null
        }
        // 标准库工厂函数
        val factory = Regex("^([a-zA-Z_][A-Za-z0-9_]*)\\s*[<(]").find(e)?.groupValues?.get(1)
        return when (factory) {
            "listOf", "arrayListOf", "listOfNotNull", "toList", "asList", "buildList" -> "List"
            "mutableListOf" -> "MutableList"
            "mapOf", "toMap", "buildMap" -> "Map"
            "mutableMapOf" -> "MutableMap"
            "setOf", "toSet", "buildSet" -> "Set"
            "mutableSetOf" -> "MutableSet"
            "arrayOf", "intArrayOf", "longArrayOf", "doubleArrayOf", "byteArrayOf", "charArrayOf" -> "Array"
            "mutableStateOf", "remember" -> "State"
            "flowOf", "flow", "stateIn" -> "Flow"
            "mutableStateFlow", "MutableStateFlow" -> "StateFlow"
            "emptyList" -> "List"
            "emptyMap" -> "Map"
            "kotlinx" -> null
            "String" -> "String"
            "Int" -> "Int"
            "Long" -> "Long"
            "Double" -> "Double"
            "Boolean" -> "Boolean"
            "array" -> "Array"
            "dict" -> "dict"
            "list" -> "list"
            "str" -> "str"
            "Future" -> "Future"
            "DateTime" -> "DateTime"
            "TextEditingController" -> "TextEditingController"
            else -> if (factory != null && factory.firstOrNull()?.isUpperCase() == true) factory else null
        }
    }
}
