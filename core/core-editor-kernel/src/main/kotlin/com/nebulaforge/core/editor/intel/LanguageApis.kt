package com.nebulaforge.core.editor.intel

/**
 * 常用标准库 / 框架 API 表（离线）。
 *
 * 这些条目只在"没有更精确的上下文"时作为兜底候选，权重低于文档内符号。
 */
object LanguageApis {

    private fun api(
        name: String,
        detail: String,
        kind: CompletionKind = CompletionKind.FUNCTION,
        insert: String = name
    ) = ApiEntry(name, insert, detail, kind)

    private val KOTLIN_APIS = listOf(
        api("println", "向标准输出打印一行"),
        api("print", "打印不换行"),
        api("readLine", "读取一行输入"),
        api("listOf", "创建只读列表"),
        api("mutableListOf", "创建可变列表"),
        api("mapOf", "创建只读映射"),
        api("mutableMapOf", "创建可变映射"),
        api("setOf", "创建只读集合"),
        api("arrayOf", "创建数组"),
        api("emptyList", "空列表"),
        api("requireNotNull", "断言非空，否则抛异常"),
        api("checkNotNull", "断言非空（状态检查）"),
        api("runCatching", "捕获异常并返回 Result"),
        api("require", "条件断言，失败抛 IllegalArgumentException"),
        api("check", "条件断言，失败抛 IllegalStateException"),
        api("TODO", "占位，调用即抛 NotImplementedError"),
        api("error", "抛出 IllegalStateException"),
        api("lazy", "惰性初始化委托"),
        api("repeat", "重复执行 n 次"),
        api("withContext", "切换协程上下文"),
        api("launch", "启动协程（不阻塞）"),
        api("async", "启动协程（返回 Deferred）"),
        api("await", "等待协程结果"),
        api("delay", "挂起指定毫秒"),
        api("collect", "收集 Flow 数据"),
        api("collectAsState", "把 Flow 收集为 Compose State"),
        api("remember", "Compose：跨重组记忆状态"),
        api("mutableStateOf", "Compose：创建可观察状态"),
        api("LaunchedEffect", "Compose：进入组合时启动协程副作用"),
        api("DisposableEffect", "Compose：需要清理的副作用"),
        api("Modifier", "Compose：修饰符链入口", CompletionKind.PROPERTY),
        api("Column", "Compose：纵向排列"),
        api("Row", "Compose：横向排列"),
        api("Box", "Compose：层叠布局"),
        api("Text", "Compose：文本组件"),
        api("Button", "Compose：按钮组件"),
        api("Icon", "Compose：图标组件"),
        api("Image", "Compose：图片组件"),
        api("Scaffold", "Compose：页面脚手架"),
        api("LazyColumn", "Compose：懒加载纵向列表"),
        api("LazyRow", "Compose：懒加载横向列表"),
        api("TextField", "Compose：输入框"),
        api("OutlinedTextField", "Compose：带边框输入框"),
        api("Card", "Compose：卡片容器"),
        api("Surface", "Compose：表面容器"),
        api("IconButton", "Compose：图标按钮"),
        api("TextButton", "Compose：文本按钮"),
        api("TopAppBar", "Material3：顶部应用栏"),
        api("AlertDialog", "Compose：对话框"),
        api("ModalBottomSheet", "Material3：底部抽屉"),
        api("NavigationBar", "Material3：底部导航"),
        api("TabRow", "Material3：标签行"),
        api("CoroutineScope", "协程作用域", CompletionKind.CLASS),
        api("Flow", "冷数据流", CompletionKind.CLASS),
        api("StateFlow", "热数据流", CompletionKind.CLASS),
        api("Result", "成功/失败包装", CompletionKind.CLASS),
        api("File", "文件与目录操作", CompletionKind.CLASS),
        api("Intent", "Android：意图", CompletionKind.CLASS),
        api("Bundle", "Android：键值参数包", CompletionKind.CLASS),
        api("Context", "Android：上下文", CompletionKind.CLASS),
        api("Log", "Android：日志", CompletionKind.CLASS),
        api("JSONObject", "JSON 对象", CompletionKind.CLASS),
        api("JSONArray", "JSON 数组", CompletionKind.CLASS),
        api("Regex", "正则表达式", CompletionKind.CLASS),
        api("StringBuilder", "可变字符串", CompletionKind.CLASS),
        api("String.format", "格式化字符串"),
        api("joinToString", "集合拼接为字符串"),
        api("forEach", "遍历集合"),
        api("filter", "集合过滤"),
        api("firstOrNull", "取第一个或 null"),
        api("associateBy", "按 key 建映射"),
        api("groupBy", "按 key 分组"),
        api("sortedBy", "按条件排序"),
        api("isEmpty", "是否为空"),
        api("isNotEmpty", "是否非空"),
        api("isBlank", "是否空白（仅空白字符）"),
        api("systemProperty", "读取系统属性"),
        api("currentTimeMillis", "当前毫秒时间戳"),
        api("Thread.sleep", "线程休眠")
    )

    private val JAVA_APIS = listOf(
        api("System.out.println", "打印一行"),
        api("System.exit", "退出进程"),
        api("System.currentTimeMillis", "当前毫秒时间戳"),
        api("System.getProperty", "读取系统属性"),
        api("String.valueOf", "转字符串"),
        api("Objects.requireNonNull", "非空断言"),
        api("Arrays.asList", "数组转列表"),
        api("Collections.sort", "排序"),
        api("List", "列表接口", CompletionKind.CLASS),
        api("ArrayList", "数组列表实现", CompletionKind.CLASS),
        api("LinkedList", "链表实现", CompletionKind.CLASS),
        api("HashMap", "哈希映射实现", CompletionKind.CLASS),
        api("Map", "映射接口", CompletionKind.CLASS),
        api("Set", "集合接口", CompletionKind.CLASS),
        api("Optional", "可空包装", CompletionKind.CLASS),
        api("Stream", "流式操作", CompletionKind.CLASS),
        api("File", "文件与目录", CompletionKind.CLASS),
        api("Files", "文件工具", CompletionKind.CLASS),
        api("Paths", "路径工具", CompletionKind.CLASS),
        api("IOException", "IO 异常", CompletionKind.CLASS),
        api("RuntimeException", "运行时异常", CompletionKind.CLASS),
        api("IllegalArgumentException", "非法参数异常", CompletionKind.CLASS),
        api("IllegalStateException", "非法状态异常", CompletionKind.CLASS),
        api(
            "main", "程序入口", CompletionKind.SNIPPET,
            "public static void main(String[] args) {\n    \n}"
        ),
        api("Override", "重写注解", CompletionKind.SNIPPET, "@Override")
    )

    private val GROOVY_APIS = listOf(
        api("plugins", "Gradle：插件声明块", CompletionKind.MODULE, "plugins {\n    \n}"),
        api("dependencies", "Gradle：依赖声明块", CompletionKind.MODULE, "dependencies {\n    \n}"),
        api("repositories", "Gradle：仓库声明块", CompletionKind.MODULE, "repositories {\n    \n}"),
        api("implementation", "Gradle：实现依赖"),
        api("api", "Gradle：传递依赖"),
        api("compileOnly", "Gradle：仅编译期依赖"),
        api("testImplementation", "Gradle：测试依赖"),
        api("androidTestImplementation", "Gradle：仪器测试依赖"),
        api("classpath", "Gradle：构建脚本依赖"),
        api("mavenCentral", "Gradle：Maven 中央仓库"),
        api("google", "Gradle：Google Maven 仓库"),
        api("gradlePluginPortal", "Gradle：插件门户"),
        api("flatDir", "Gradle：本地目录仓库"),
        api("include", "settings.gradle：包含子模块"),
        api("rootProject", "根项目引用", CompletionKind.PROPERTY),
        api("subprojects", "子项目集合", CompletionKind.PROPERTY),
        api("allprojects", "所有项目配置块", CompletionKind.MODULE, "allprojects {\n    \n}"),
        api("buildTypes", "Android：构建类型块", CompletionKind.MODULE, "buildTypes {\n    \n}"),
        api("defaultConfig", "Android：默认配置块", CompletionKind.MODULE, "defaultConfig {\n    \n}"),
        api("signingConfigs", "Android：签名配置块", CompletionKind.MODULE, "signingConfigs {\n    \n}"),
        api("buildFeatures", "Android：构建特性开关", CompletionKind.MODULE, "buildFeatures {\n    \n}"),
        api("compileOptions", "Android：编译选项块", CompletionKind.MODULE, "compileOptions {\n    \n}"),
        api("kotlinOptions", "Kotlin 编译选项块", CompletionKind.MODULE, "kotlinOptions {\n    \n}"),
        api("compileSdk", "Android：编译 SDK 版本", CompletionKind.PROPERTY),
        api("minSdk", "Android：最低支持版本", CompletionKind.PROPERTY),
        api("targetSdk", "Android：目标版本", CompletionKind.PROPERTY),
        api("applicationId", "Android：应用包名", CompletionKind.PROPERTY),
        api("versionCode", "Android：版本号", CompletionKind.PROPERTY),
        api("versionName", "Android：版本名", CompletionKind.PROPERTY),
        api("namespace", "Android：命名空间", CompletionKind.PROPERTY),
        api("manifestPlaceholders", "Android：清单占位符", CompletionKind.PROPERTY),
        api("ndkVersion", "Android：NDK 版本", CompletionKind.PROPERTY),
        api("println", "打印一行"),
        api("fileTree", "构建文件树文件集合")
    )

    private val DART_APIS = listOf(
        api("runApp", "Flutter：启动应用"),
        api("setState", "Flutter：触发重建"),
        api("StatelessWidget", "Flutter：无状态组件", CompletionKind.CLASS),
        api("StatefulWidget", "Flutter：有状态组件", CompletionKind.CLASS),
        api("State", "Flutter：组件状态", CompletionKind.CLASS),
        api("BuildContext", "Flutter：构建上下文", CompletionKind.CLASS),
        api("Scaffold", "Flutter：页面脚手架", CompletionKind.CLASS),
        api("AppBar", "Flutter：顶部栏", CompletionKind.CLASS),
        api("Container", "Flutter：容器", CompletionKind.CLASS),
        api("Column", "Flutter：纵向布局", CompletionKind.CLASS),
        api("Row", "Flutter：横向布局", CompletionKind.CLASS),
        api("Text", "Flutter：文本", CompletionKind.CLASS),
        api("Icon", "Flutter：图标", CompletionKind.CLASS),
        api("Image", "Flutter：图片", CompletionKind.CLASS),
        api("ListView", "Flutter：列表", CompletionKind.CLASS),
        api("Navigator", "Flutter：路由导航", CompletionKind.CLASS),
        api("MaterialApp", "Flutter：Material 应用", CompletionKind.CLASS),
        api("EdgeInsets", "Flutter：内边距", CompletionKind.CLASS),
        api("SizedBox", "Flutter：固定尺寸盒", CompletionKind.CLASS),
        api("Expanded", "Flutter：剩余空间填充", CompletionKind.CLASS),
        api("Padding", "Flutter：内边距组件", CompletionKind.CLASS),
        api("Center", "Flutter：居中", CompletionKind.CLASS),
        api("Widget", "Flutter：组件基类", CompletionKind.CLASS),
        api("Future", "异步结果", CompletionKind.CLASS),
        api("async", "异步函数修饰"),
        api("await", "等待异步结果"),
        api("Navigator.push", "Flutter：压栈新页面"),
        api("Navigator.pop", "Flutter：出栈"),
        api("Theme.of", "Flutter：取主题"),
        api("debugPrint", "Flutter：调试打印"),
        api("print", "打印"),
        api("main", "程序入口", CompletionKind.SNIPPET, "void main() {\n    runApp(const MyApp());\n}"),
        api(
            "build", "Flutter：构建界面", CompletionKind.SNIPPET,
            "@override\nWidget build(BuildContext context) {\n    return \n}"
        ),
        api(
            "initState", "Flutter：初始化生命周期", CompletionKind.SNIPPET,
            "@override\nvoid initState() {\n    super.initState();\n}"
        ),
        api(
            "dispose", "Flutter：销毁生命周期", CompletionKind.SNIPPET,
            "@override\nvoid dispose() {\n    super.dispose();\n}"
        )
    )

    private val PYTHON_APIS = listOf(
        api("print", "打印"),
        api("len", "取长度"),
        api("range", "区间序列"),
        api("enumerate", "带下标遍历"),
        api("zip", "并行遍历"),
        api("sorted", "排序"),
        api("open", "打开文件"),
        api("isinstance", "类型判断"),
        api("getattr", "取属性"),
        api("setattr", "设属性"),
        api("str", "转字符串"),
        api("int", "转整数"),
        api("float", "转浮点"),
        api("list", "转列表"),
        api("dict", "转字典"),
        api("Exception", "异常基类", CompletionKind.CLASS),
        api("__main__", "入口判断片段", CompletionKind.SNIPPET, "if __name__ == \"__main__\":\n    main()"),
        api("def main", "主函数片段", CompletionKind.SNIPPET, "def main():\n    pass")
    )

    private val JS_APIS = listOf(
        api("console.log", "打印"),
        api("JSON.stringify", "对象转 JSON"),
        api("JSON.parse", "JSON 转对象"),
        api("require", "CommonJS 引入"),
        api("module.exports", "CommonJS 导出", CompletionKind.PROPERTY),
        api("Promise", "异步承诺", CompletionKind.CLASS),
        api("async", "异步函数修饰"),
        api("await", "等待异步结果"),
        api("setTimeout", "延时执行"),
        api("setInterval", "定时执行"),
        api("document", "DOM 文档", CompletionKind.PROPERTY),
        api("window", "浏览器窗口", CompletionKind.PROPERTY),
        api("import", "ES 引入", CompletionKind.SNIPPET, "import  from ''"),
        api("export default", "ES 默认导出", CompletionKind.SNIPPET, "export default "),
        api("function", "声明函数", CompletionKind.SNIPPET, "function name() {\n    \n}"),
        api("arrow", "箭头函数", CompletionKind.SNIPPET, "const name = () => {\n    \n};")
    )

    private val SHELL_APIS = listOf(
        api("echo", "输出"),
        api("cd", "切换目录"),
        api("ls", "列目录"),
        api("cp", "复制"),
        api("mv", "移动或改名"),
        api("rm", "删除"),
        api("mkdir", "建目录"),
        api("grep", "文本搜索"),
        api("find", "查找文件"),
        api("sed", "流式替换"),
        api("awk", "文本处理"),
        api("chmod", "改权限"),
        api("curl", "HTTP 请求"),
        api("wget", "下载"),
        api("tar", "打包解包"),
        api("unzip", "解压 zip"),
        api("export", "导出环境变量"),
        api("source", "加载脚本"),
        api("if", "条件片段", CompletionKind.SNIPPET, "if [  ]; then\n    \nfi")
    )

    private val CPP_APIS = listOf(
        api("include", "包含头文件", CompletionKind.SNIPPET, "#include <>"),
        api("printf", "格式化输出"),
        api("scanf", "格式化输入"),
        api("malloc", "分配内存"),
        api("free", "释放内存"),
        api("memset", "内存填充"),
        api("memcpy", "内存拷贝"),
        api("std::cout", "标准输出流", CompletionKind.PROPERTY),
        api("std::cin", "标准输入流", CompletionKind.PROPERTY),
        api("std::vector", "动态数组", CompletionKind.CLASS),
        api("std::string", "标准字符串", CompletionKind.CLASS),
        api("std::map", "有序映射", CompletionKind.CLASS),
        api("std::unordered_map", "哈希映射", CompletionKind.CLASS),
        api("std::shared_ptr", "共享指针", CompletionKind.CLASS),
        api("std::unique_ptr", "独占指针", CompletionKind.CLASS),
        api("nullptr", "空指针", CompletionKind.CONSTANT),
        api(
            "int main", "入口函数", CompletionKind.SNIPPET,
            "int main(int argc, char** argv) {\n    return 0;\n}"
        )
    )

    private val RUST_APIS = listOf(
        api("println!", "格式化打印"),
        api("print!", "打印不换行"),
        api("format!", "格式化字符串"),
        api("Vec", "动态数组", CompletionKind.CLASS),
        api("String", "字符串", CompletionKind.CLASS),
        api("HashMap", "哈希映射", CompletionKind.CLASS),
        api("Option", "可空类型", CompletionKind.CLASS),
        api("Result", "结果类型", CompletionKind.CLASS),
        api("Some", "Some 构造", CompletionKind.CONSTANT),
        api("None", "None 值", CompletionKind.CONSTANT),
        api("Ok", "Ok 构造", CompletionKind.CONSTANT),
        api("Err", "Err 构造", CompletionKind.CONSTANT),
        api("unwrap", "取值或 panic"),
        api("fn main", "入口函数", CompletionKind.SNIPPET, "fn main() {\n    \n}"),
        api("impl", "实现块片段", CompletionKind.SNIPPET, "impl  {\n    \n}")
    )

    private val GO_APIS = listOf(
        api("fmt.Println", "打印一行"),
        api("fmt.Printf", "格式化打印"),
        api("fmt.Sprintf", "格式化字符串"),
        api("errors.New", "创建错误"),
        api("make", "创建切片或映射或通道"),
        api("append", "追加元素"),
        api("len", "取长度"),
        api("defer", "延迟执行"),
        api("go", "启动协程"),
        api("context.Context", "上下文", CompletionKind.CLASS),
        api("sync.WaitGroup", "等待组", CompletionKind.CLASS),
        api("func main", "入口函数", CompletionKind.SNIPPET, "func main() {\n    \n}")
    )

    private val SQL_APIS = listOf(
        api("SELECT * FROM", "查询全部"),
        api("INSERT INTO", "插入"),
        api("UPDATE SET", "更新"),
        api("DELETE FROM", "删除"),
        api("CREATE TABLE", "建表"),
        api("DROP TABLE", "删表"),
        api("COUNT(*)", "计数"),
        api("GROUP BY", "分组"),
        api("ORDER BY", "排序"),
        api("JOIN ON", "连接")
    )

    private val JSON_APIS = listOf(
        api("true", "布尔真", CompletionKind.CONSTANT),
        api("false", "布尔假", CompletionKind.CONSTANT),
        api("null", "空值", CompletionKind.CONSTANT)
    )

    private val MARKDOWN_APIS = listOf(
        api("# 标题", "一级标题", CompletionKind.SNIPPET, "# "),
        api("- 列表项", "无序列表", CompletionKind.SNIPPET, "- "),
        api("```", "代码块", CompletionKind.SNIPPET, "```\n\n```"),
        api("- [ ] 待办", "待办项", CompletionKind.SNIPPET, "- [ ] ")
    )

    /** 按语言取常用 API 表。 */
    fun commonApis(lang: IntelLanguage): List<ApiEntry> = when (lang) {
        IntelLanguage.KOTLIN -> KOTLIN_APIS
        IntelLanguage.JAVA -> JAVA_APIS
        IntelLanguage.GROOVY -> GROOVY_APIS
        IntelLanguage.DART -> DART_APIS
        IntelLanguage.PYTHON -> PYTHON_APIS
        IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT -> JS_APIS
        IntelLanguage.SHELL -> SHELL_APIS
        IntelLanguage.C, IntelLanguage.CPP -> CPP_APIS
        IntelLanguage.RUST -> RUST_APIS
        IntelLanguage.GO -> GO_APIS
        IntelLanguage.SQL -> SQL_APIS
        IntelLanguage.JSON, IntelLanguage.YAML -> JSON_APIS
        IntelLanguage.MARKDOWN -> MARKDOWN_APIS
        else -> emptyList()
    }
}
