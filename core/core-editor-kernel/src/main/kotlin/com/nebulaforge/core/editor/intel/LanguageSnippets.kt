package com.nebulaforge.core.editor.intel

/** 各语言的常用代码片段（插入后光标停在缩进占位处，由编辑器自动缩进接管）。 */
object LanguageSnippets {

    private fun s(name: String, detail: String, insert: String) =
        ApiEntry(name, insert, detail, CompletionKind.SNIPPET)

    fun of(lang: IntelLanguage): List<ApiEntry> = when (lang) {
        IntelLanguage.KOTLIN -> listOf(
            s("fun", "声明函数", "fun name(): Unit {\n    \n}"),
            s("fun return", "带返回值的函数", "fun name(): Type {\n    return \n}"),
            s("class", "声明类", "class Name {\n    \n}"),
            s("data class", "声明数据类", "data class Name(\n    val id: String\n)"),
            s("object", "声明单例", "object Name {\n    \n}"),
            s("enum class", "声明枚举", "enum class Name {\n    FIRST,\n    SECOND\n}"),
            s("interface", "声明接口", "interface Name {\n    \n}"),
            s("sealed class", "声明密封类", "sealed class Name {\n    \n}"),
            s("companion object", "伴生对象", "companion object {\n    \n}"),
            s("if", "条件语句", "if (condition) {\n    \n}"),
            s("ifelse", "条件分支", "if (condition) {\n    \n} else {\n    \n}"),
            s("when", "分支表达式", "when (value) {\n    1 -> \n    else -> \n}"),
            s("for", "遍历循环", "for (item in list) {\n    \n}"),
            s("while", "while 循环", "while (condition) {\n    \n}"),
            s(
                "try", "异常捕获",
                "try {\n    \n} catch (e: Exception) {\n    e.printStackTrace()\n}"
            ),
            s("runCatching", "安全执行", "runCatching {\n    \n}.onFailure { error -> \n}"),
            s("launch", "启动协程", "scope.launch {\n    \n}"),
            s("@Composable", "Compose 可组合函数", "@Composable\nfun Name() {\n    \n}"),
            s("remember", "Compose 记忆状态", "val state = remember { mutableStateOf(0) }"),
            s("TODO", "待办占位", "TODO()")
        )

        IntelLanguage.GROOVY -> listOf(
            s("plugins", "插件块", "plugins {\n    \n}"),
            s(
                "android", "Android 配置块",
                "android {\n    namespace = \"\"\n    compileSdk = 34\n\n    defaultConfig {\n        minSdk = 24\n        targetSdk = 34\n    }\n}"
            ),
            s("dependencies", "依赖块", "dependencies {\n    implementation(\"\")\n}"),
            s("tasks.register", "注册任务", "tasks.register(\"name\") {\n    \n}"),
            s(
                "signingConfigs", "签名配置",
                "signingConfigs {\n    create(\"release\") {\n        storeFile = file(\"\")\n        storePassword = \"\"\n        keyAlias = \"\"\n        keyPassword = \"\"\n    }\n}"
            ),
            s(
                "buildTypes", "构建类型",
                "buildTypes {\n    release {\n        isMinifyEnabled = false\n    }\n}"
            )
        )

        IntelLanguage.JAVA -> listOf(
            s("class", "声明类", "public class Name {\n    \n}"),
            s("method", "声明方法", "public void method() {\n    \n}"),
            s("main", "程序入口", "public static void main(String[] args) {\n    \n}"),
            s("if", "条件语句", "if (condition) {\n    \n}"),
            s("for", "for 循环", "for (int i = 0; i < n; i++) {\n    \n}"),
            s(
                "try", "异常捕获",
                "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}"
            )
        )

        IntelLanguage.DART -> listOf(
            s("class", "声明类", "class Name {\n    \n}"),
            s(
                "StatelessWidget", "无状态组件",
                "class Name extends StatelessWidget {\n  const Name({super.key});\n\n  @override\n  Widget build(BuildContext context) {\n    return \n  }\n}"
            ),
            s(
                "StatefulWidget", "有状态组件",
                "class Name extends StatefulWidget {\n  const Name({super.key});\n\n  @override\n  State<Name> createState() => _NameState();\n}\n\nclass _NameState extends State<Name> {\n  @override\n  Widget build(BuildContext context) {\n    return \n  }\n}"
            ),
            s("main", "程序入口", "void main() {\n  runApp(const MyApp());\n}"),
            s("if", "条件语句", "if (condition) {\n  \n}"),
            s("for", "for 循环", "for (var i = 0; i < n; i++) {\n  \n}"),
            s(
                "try", "异常捕获",
                "try {\n  \n} catch (e) {\n  print(e);\n}"
            )
        )

        IntelLanguage.PYTHON -> listOf(
            s("def", "声明函数", "def name():\n    pass"),
            s("class", "声明类", "class Name:\n    def __init__(self):\n        pass"),
            s("if", "条件语句", "if condition:\n    pass"),
            s("ifelse", "条件分支", "if condition:\n    pass\nelse:\n    pass"),
            s("for", "遍历循环", "for item in items:\n    pass"),
            s("while", "while 循环", "while condition:\n    pass"),
            s(
                "try", "异常捕获",
                "try:\n    pass\nexcept Exception as e:\n    print(e)"
            ),
            s("main", "入口判断", "if __name__ == \"__main__\":\n    main()")
        )

        IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT -> listOf(
            s("function", "声明函数", "function name() {\n    \n}"),
            s("arrow", "箭头函数", "const name = () => {\n    \n};"),
            s("class", "声明类", "class Name {\n    \n}"),
            s("if", "条件语句", "if (condition) {\n    \n}"),
            s("for", "for 循环", "for (let i = 0; i < n; i++) {\n    \n}"),
            s(
                "try", "异常捕获",
                "try {\n    \n} catch (error) {\n    console.error(error);\n}"
            ),
            s("promise", "异步函数", "async function name() {\n    const result = await something();\n    return result;\n}")
        )

        IntelLanguage.SHELL -> listOf(
            s("shebang", "脚本头", "#!/usr/bin/env bash\nset -euo pipefail\n\n"),
            s("function", "声明函数", "name() {\n    \n}"),
            s("if", "条件判断", "if [  ]; then\n    \nfi"),
            s("for", "遍历循环", "for item in \"\$@\"; do\n    echo \"\$item\"\ndone")
        )

        IntelLanguage.C, IntelLanguage.CPP -> listOf(
            s("main", "入口函数", "int main(int argc, char** argv) {\n    return 0;\n}"),
            s("if", "条件语句", "if (condition) {\n    \n}"),
            s("for", "for 循环", "for (int i = 0; i < n; i++) {\n    \n}"),
            s("struct", "声明结构体", "struct Name {\n    \n};"),
            s("include", "包含头文件", "#include <>")
        )

        IntelLanguage.RUST -> listOf(
            s("fn", "声明函数", "fn name() {\n    \n}"),
            s("main", "入口函数", "fn main() {\n    \n}"),
            s("struct", "声明结构体", "struct Name {\n    field: Type,\n}"),
            s("impl", "实现块", "impl Name {\n    \n}"),
            s("match", "模式匹配", "match value {\n    _ => \n}")
        )

        IntelLanguage.GO -> listOf(
            s("func", "声明函数", "func name() {\n    \n}"),
            s("main", "入口函数", "func main() {\n    \n}"),
            s("struct", "声明结构体", "type Name struct {\n    Field string\n}"),
            s("if", "条件语句", "if condition {\n    \n}"),
            s("for", "for 循环", "for i := 0; i < n; i++ {\n    \n}")
        )

        IntelLanguage.SQL -> listOf(
            s("select", "查询", "SELECT * FROM table WHERE condition;"),
            s("insert", "插入", "INSERT INTO table (col) VALUES (val);"),
            s("update", "更新", "UPDATE table SET col = val WHERE condition;"),
            s("delete", "删除", "DELETE FROM table WHERE condition;"),
            s(
                "create table", "建表",
                "CREATE TABLE name (\n    id INTEGER PRIMARY KEY,\n    \n);"
            )
        )

        IntelLanguage.XML -> listOf(
            s("layout", "布局根标签", "<LinearLayout\n    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\">\n\n</LinearLayout>"),
            s("textview", "文本控件", "<TextView\n    android:id=\"@+id/name\"\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"\" />"),
            s("compose", "Compose 布局", "<androidx.compose.ui.platform.ComposeView\n    android:id=\"@+id/compose\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\" />")
        )

        IntelLanguage.PROPERTIES -> listOf(
            s("key", "键值对", "key=value"),
            s("comment", "注释", "# ")
        )

        IntelLanguage.MARKDOWN -> listOf(
            s("h1", "一级标题", "# "),
            s("h2", "二级标题", "## "),
            s("list", "无序列表", "- "),
            s("todo", "待办项", "- [ ] "),
            s("code", "代码块", "```\n\n```"),
            s("table", "表格", "| 列1 | 列2 |\n| --- | --- |\n|  |  |")
        )

        IntelLanguage.JSON -> listOf(
            s("object", "对象", "{\n    \n}"),
            s("array", "数组", "[\n    \n]"),
            s("pair", "键值对", "\"key\": \"value\"")
        )

        IntelLanguage.YAML -> listOf(
            s("pair", "键值对", "key: value"),
            s("list", "列表", "items:\n  - item")
        )

        IntelLanguage.PLAIN -> listOf(
            s("todo", "待办", "TODO: "),
            s("note", "备注", "NOTE: ")
        )
    }
}
