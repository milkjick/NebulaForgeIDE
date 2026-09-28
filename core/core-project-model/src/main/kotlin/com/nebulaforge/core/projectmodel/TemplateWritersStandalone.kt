package com.nebulaforge.core.projectmodel

import java.io.File

/**
 * 独立语言项目模板写出器：Java / Python / JavaScript(Node) / HTML / CSS / C。
 *
 * 设计约束（与其它模板一致，避免"能新建不能编译运行"）：
 * 1. 只依赖**已由工具链面板确实可安装**的东西：JDK17（javac/java）、python、nodejs-lts、clang。
 *    不引入 Maven/Gradle/npm 依赖，避免新建后还要联网拉包才能跑起来。
 * 2. 每个模板都带一条**真实验证步骤**（javac 编译 / python -m compileall / node --check /
 *    静态资源自检脚本 / CSS 自检脚本 / clang 编译），而不是只有源码没有校验。
 * 3. 目录结构按各语言的惯例组织（Java 包路径、C 的 include/src 分离），
 *    构建命令由 [com.nebulaforge.stack.server.StandaloneCommands] 扫描真实源码得出，
 *    不写死可能不存在的可执行名或路径。
 */

// --------------------------------------------------------------------------
// Java（纯 JDK，无 Maven/Gradle）
// --------------------------------------------------------------------------

internal fun createJavaConsole(root: File, packageName: String) {
    val pkg = packageName.ifBlank { "com.example.nebulaforge" }
    val pkgPath = pkg.replace('.', '/')
    val dir = "src/$pkgPath"
    writeTemplateFile(
        root, "$dir/Main.java",
        """
        package $pkg;

        /**
         * 入口类。编译：javac -encoding UTF-8 -d build <所有 .java>
         * 运行：java -cp build $pkg.Main
         */
        public final class Main {

            private Main() {
            }

            public static void main(String[] args) {
                Greeter greeter = new Greeter();
                String who = args.length > 0 ? args[0] : "Nebula Forge";
                System.out.println(greeter.greeting(who));
            }
        }
        """.trimIndent().replace("__PKG__", pkg) + "\n"
    )
    writeTemplateFile(
        root, "$dir/Greeter.java",
        """
        package __PKG__;

        /** 最小可复用单元：方便后续补 JUnit 或纯 main 断言测试。 */
        public final class Greeter {

            public String greeting(String name) {
                String who = (name == null || name.isEmpty()) ? "world" : name;
                return "Hello, " + who + "! (Java " + System.getProperty("java.version") + ")";
            }
        }
        """.trimIndent().replace("__PKG__", pkg) + "\n"
    )
    writeTemplateFile(
        root, "README.md",
        """
        # ${root.name}

        纯 JDK 工程（不依赖 Maven / Gradle），源码位于 `$dir`。

        ## 编译

        在 IDE 里点「构建」，等价于：

        ```sh
        javac -encoding UTF-8 -d build $dir/Main.java $dir/Greeter.java
        ```

        ## 运行

        ```sh
        java -cp build $pkg.Main
        ```

        ## 工具链

        设置 → 工具链 → 安装 **JDK 17**（提供 `javac` 与 `java`）。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// Python（标准库）
// --------------------------------------------------------------------------

internal fun createPythonScript(root: File, moduleName: String) {
    val mod = moduleName.lowercase().replace('-', '_').replace('.', '_')
    writeTemplateFile(
        root, "greeter.py",
        """
        # $mod · 可复用模块
        def greeting(name=None):
            '''返回问候语；缺省名回退到 world。'''
            who = name or "world"
            return "Hello, %s! (Python %s)" % (who, __import__("platform").python_version())
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "main.py",
        """
        # $mod · 入口文件：python main.py [名字]
        from greeter import greeting


        def main(argv):
            who = argv[1] if len(argv) > 1 else "Nebula Forge"
            print(greeting(who))
            return 0


        if __name__ == "__main__":
            import sys

            raise SystemExit(main(sys.argv))
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "tests/test_greeter.py",
        """
        # 只依赖标准库 unittest：python -m unittest discover -s tests -t .
        import unittest

        from greeter import greeting


        class GreeterTest(unittest.TestCase):

            def test_contains_name(self):
                self.assertIn("Nebula", greeting("Nebula"))

            def test_default_name(self):
                self.assertIn("world", greeting())

        if __name__ == "__main__":
            unittest.main()
        """.trimIndent() + "\n"
    )
    // 没有 __init__.py 时，Python 3.10 及更早版本的 `unittest discover -s tests -t .`
    // 会直接抛 "Start directory is not importable"，所以必须补上包标记文件。
    writeTemplateFile(
        root, "tests/__init__.py",
        "# 让 unittest discover 在旧版 Python（<3.11）下也能把 tests 当作包导入\n"
    )
    writeTemplateFile(
        root, "README.md",
        """
        # $mod

        标准库 Python 工程（无第三方依赖）。

        ## 运行

        ```sh
        python main.py
        ```

        ## 编译校验

        ```sh
        python -m compileall -q main.py greeter.py tests
        ```

        ## 测试

        ```sh
        python -m unittest discover -s tests -t .
        ```

        ## 工具链

        设置 → 工具链 → 安装 **Python**。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// JavaScript（纯 Node.js，CommonJS，无 npm 依赖）
// --------------------------------------------------------------------------

internal fun createJavascriptNode(root: File, moduleName: String) {
    writeTemplateFile(
        root, "lib/greeter.js",
        """
        'use strict';

        // 最小可复用模块；不依赖任何 npm 包，装好 Node 即可 require。
        function greeting(name) {
            const who = name && name.length > 0 ? name : 'world';
            return 'Hello, ' + who + '! (Node ' + process.version + ')';
        }

        module.exports = { greeting: greeting };
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "index.js",
        """
        'use strict';

        // 入口：node index.js [名字]
        const greeter = require('./lib/greeter');

        const who = process.argv[2] || 'Nebula Forge';
        console.log(greeter.greeting(who));
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "tests/greeter.test.js",
        """
        'use strict';

        // 使用 Node 内置测试运行器：node --test tests
        const test = require('node:test');
        const assert = require('node:assert');
        const greeter = require('../lib/greeter');

        test('greeting 包含传入的名字', () => {
            assert.ok(greeter.greeting('Nebula').indexOf('Nebula') >= 0);
        });

        test('greeting 缺省回退到 world', () => {
            assert.ok(greeter.greeting().indexOf('world') >= 0);
        });
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "README.md",
        """
        # $moduleName

        纯 Node.js（CommonJS）工程，无 npm 依赖，不做 `npm install` 也能跑。

        ## 运行

        ```sh
        node index.js
        ```

        ## 语法校验（构建）

        ```sh
        node --check index.js lib/greeter.js
        ```

        ## 测试

        ```sh
        node --test tests
        ```

        ## 工具链

        设置 → 工具链 → 安装 **Node.js**（`nodejs-lts`）。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// HTML 静态网站
// --------------------------------------------------------------------------

internal fun createHtmlSite(root: File, moduleName: String) {
    writeTemplateFile(
        root, "index.html",
        """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>$moduleName</title>
          <link rel="stylesheet" href="assets/styles.css">
        </head>
        <body>
          <header class="hero">
            <h1>$moduleName</h1>
            <p class="subtitle">由星弦 IDE 生成的静态站点骨架。</p>
            <button id="greet" type="button">点我</button>
            <p id="output" class="output" role="status"></p>
          </header>
          <main class="content">
            <section>
              <h2>开始编辑</h2>
              <ul>
                <li><code>index.html</code>：页面结构</li>
                <li><code>assets/styles.css</code>：样式</li>
                <li><code>assets/app.js</code>：交互脚本</li>
              </ul>
            </section>
          </main>
          <footer class="footer">© $moduleName</footer>
          <script src="assets/app.js" defer></script>
        </body>
        </html>
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "assets/styles.css",
        """
        :root {
          --bg: #0f1115;
          --fg: #e7e9ee;
          --accent: #4f8cff;
        }

        * { box-sizing: border-box; }

        body {
          margin: 0;
          background: var(--bg);
          color: var(--fg);
          font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
          line-height: 1.6;
        }

        .hero { padding: 48px 20px; text-align: center; }
        .hero h1 { margin: 0 0 8px; font-size: 28px; }
        .subtitle { color: #9aa3b2; margin: 0 0 20px; }
        button {
          background: var(--accent);
          color: #fff;
          border: 0;
          border-radius: 8px;
          padding: 10px 18px;
          font-size: 15px;
        }
        .output { min-height: 24px; }
        .content { padding: 0 20px 32px; max-width: 720px; margin: 0 auto; }
        code { background: #1b1f27; padding: 2px 6px; border-radius: 4px; }
        .footer { padding: 16px 20px; text-align: center; color: #6d7686; }
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "assets/app.js",
        """
        'use strict';

        document.addEventListener('DOMContentLoaded', function () {
          var button = document.getElementById('greet');
          var output = document.getElementById('output');
          if (!button || !output) {
            return;
          }
          button.addEventListener('click', function () {
            output.textContent = 'Hello from ' + document.title + '!';
          });
        });
        """.trimIndent() + "\n"
    )
    writeTemplateFile(root, "scripts/check_site.py", SITE_CHECK_SCRIPT)
    writeTemplateFile(
        root, "README.md",
        """
        # $moduleName

        静态站点骨架：`index.html` + `assets/styles.css` + `assets/app.js`，无框架、无构建工具。

        ## 构建（资源引用自检）

        ```sh
        python scripts/check_site.py
        ```

        该脚本会解析 HTML 里所有本地 `href` / `src` 引用，逐个确认文件真实存在，
        引用写错时以非零退出码报错——这就是静态站点可用的"编译"步骤。

        ## 运行（本地预览服务器）

        ```sh
        python -m http.server 8080
        ```

        然后浏览器打开 `http://127.0.0.1:8080/`。

        ## 工具链

        设置 → 工具链 → 安装 **Python**（预览服务器与自检脚本都只用标准库）。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// CSS 样式工程
// --------------------------------------------------------------------------

internal fun createCssProject(root: File, moduleName: String) {
    writeTemplateFile(
        root, "demo.html",
        """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>$moduleName · CSS 预览</title>
          <link rel="stylesheet" href="css/tokens.css">
          <link rel="stylesheet" href="css/base.css">
          <link rel="stylesheet" href="css/components.css">
        </head>
        <body>
          <main class="demo">
            <h1 class="demo__title">$moduleName</h1>
            <p class="demo__text">三层样式：设计令牌 → 基础层 → 组件层。</p>
            <div class="demo__row">
              <button class="btn btn--primary">主要按钮</button>
              <button class="btn btn--ghost">次要按钮</button>
            </div>
            <div class="card">
              <h2 class="card__title">卡片标题</h2>
              <p class="card__body">所有颜色与间距都来自 <code>var(--…)</code> 令牌，改一处即可全局换肤。</p>
            </div>
          </main>
        </body>
        </html>
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "css/tokens.css",
        """
        /* 第 1 层：设计令牌。所有取值集中在这里，组件层只允许引用变量。 */
        :root {
          --color-bg: #0f1115;
          --color-surface: #171b22;
          --color-text: #e7e9ee;
          --color-muted: #9aa3b2;
          --color-primary: #4f8cff;
          --color-primary-text: #ffffff;

          --space-xs: 4px;
          --space-sm: 8px;
          --space-md: 16px;
          --space-lg: 24px;
          --space-xl: 40px;

          --radius-sm: 6px;
          --radius-md: 10px;

          --font-sans: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
        }
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "css/base.css",
        """
        /* 第 2 层：基础层。只做重置与元素级默认样式，不出现组件类名。 */
        * {
          box-sizing: border-box;
        }

        html,
        body {
          margin: 0;
          padding: 0;
        }

        body {
          background: var(--color-bg);
          color: var(--color-text);
          font-family: var(--font-sans);
          line-height: 1.6;
          padding: var(--space-lg);
        }

        code {
          background: var(--color-surface);
          padding: 2px var(--space-xs);
          border-radius: var(--radius-sm);
        }
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "css/components.css",
        """
        /* 第 3 层：组件层。只使用 tokens.css 里的变量，不写字面量颜色/间距。 */
        .demo {
          max-width: 720px;
          margin: 0 auto;
        }

        .demo__title {
          margin: 0 0 var(--space-sm);
        }

        .demo__text {
          color: var(--color-muted);
          margin: 0 0 var(--space-lg);
        }

        .demo__row {
          display: flex;
          gap: var(--space-sm);
          margin-bottom: var(--space-lg);
        }

        .btn {
          border: 1px solid transparent;
          border-radius: var(--radius-sm);
          padding: var(--space-sm) var(--space-md);
          font-size: 15px;
          cursor: pointer;
        }

        .btn--primary {
          background: var(--color-primary);
          color: var(--color-primary-text);
        }

        .btn--ghost {
          background: transparent;
          color: var(--color-primary);
          border-color: var(--color-primary);
        }

        .card {
          background: var(--color-surface);
          border-radius: var(--radius-md);
          padding: var(--space-lg);
        }

        .card__title {
          margin: 0 0 var(--space-sm);
        }

        .card__body {
          margin: 0;
          color: var(--color-muted);
        }
        """.trimIndent() + "\n"
    )
    writeTemplateFile(root, "scripts/check_css.py", CSS_CHECK_SCRIPT)
    writeTemplateFile(
        root, "README.md",
        """
        # $moduleName

        纯 CSS 工程，分三层维护：

        | 文件 | 职责 |
        | --- | --- |
        | `css/tokens.css` | 设计令牌（颜色 / 间距 / 圆角 / 字体） |
        | `css/base.css` | 重置与元素级默认样式 |
        | `css/components.css` | 组件类，只引用令牌变量 |

        ## 构建（样式自检）

        ```sh
        python scripts/check_css.py
        ```

        自检内容：① 每个文件的 `{` `}` 是否配对；② 每个 `var(--x)` 是否真有 `--x:` 定义
        （防止改名后留下悬空引用）；③ 文件非空。

        ## 运行（预览页面）

        ```sh
        python -m http.server 8081
        ```

        然后浏览器打开 `http://127.0.0.1:8081/demo.html`。

        ## 工具链

        设置 → 工具链 → 安装 **Python**。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// C（标准 C17）
// --------------------------------------------------------------------------

internal fun createCConsoleProject(root: File, moduleName: String) {
    writeTemplateFile(
        root, "include/greeter.h",
        """
        #ifndef NEBULA_GREETER_H
        #define NEBULA_GREETER_H

        /* 把问候语写入 out，返回写入的字符数。out 必须至少有 128 字节。 */
        int greeter_greeting(const char *name, char *out);

        #endif /* NEBULA_GREETER_H */
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "src/greeter.c",
        """
        #include "greeter.h"

        #include <stdio.h>

        int greeter_greeting(const char *name, char *out) {
            const char *who = (name == NULL || name[0] == '\0') ? "world" : name;
            return snprintf(out, 128, "Hello, %s! (C %ld)", who, __STDC_VERSION__);
        }
        """.trimIndent() + "\n"
    )
    writeTemplateFile(
        root, "src/main.c",
        """
        #include "greeter.h"

        #include <stdio.h>

        int main(int argc, char **argv) {
            char buffer[128];
            const char *who = (argc > 1) ? argv[1] : "Nebula Forge";
            greeter_greeting(who, buffer);
            puts(buffer);
            return 0;
        }
        """.trimIndent() + "\n"
    )
    // Makefile 必须用真正的 TAB 缩进，所以这里用普通字符串拼装（原始字符串不会把 \t 转义）。
    writeTemplateFile(
        root, "Makefile",
        "# 便捷构建；IDE 的构建/运行默认直接调用 clang，不依赖 make 是否安装。\n" +
            "CC ?= clang\n" +
            "CFLAGS ?= -std=c17 -Wall -Wextra -O2 -Iinclude\n" +
            "TARGET = build/$moduleName\n" +
            "SOURCES = src/main.c src/greeter.c\n\n" +
            ".PHONY: all clean run\n\n" +
            "all: $(TARGET)\n\n" +
            "$(TARGET): $(SOURCES) include/greeter.h\n" +
            "\t@mkdir -p build\n" +
            "\t$(CC) $(CFLAGS) -o $(TARGET) $(SOURCES)\n\n" +
            "run: all\n" +
            "\t./$(TARGET)\n\n" +
            "clean:\n" +
            "\trm -rf build\n"
    )
    writeTemplateFile(
        root, "README.md",
        """
        # $moduleName

        标准 C17 工程：`include/` 放头文件，`src/` 放实现与入口。

        ## 编译

        ```sh
        mkdir -p build && clang -std=c17 -Wall -Wextra -Iinclude -o build/$moduleName src/main.c src/greeter.c
        ```

        或直接 `make`（Makefile 里已经带 `mkdir -p build`）。

        > 注意：clang 不会自动创建输出目录，漏掉 `mkdir -p build` 会报
        > `ld: cannot open output file build/...: No such file or directory`。

        ## 运行

        ```sh
        ./build/$moduleName
        ```

        ## 工具链

        设置 → 工具链 → 安装 **C 编译器 (Clang)**（同时安装 `make`）。
        """.trimIndent() + "\n"
    )
}

// --------------------------------------------------------------------------
// 自检脚本（只用 Python 标准库，作为静态工程的"编译"步骤）
// --------------------------------------------------------------------------

private val SITE_CHECK_SCRIPT = """
    # 静态站点自检：确认 HTML 引用的本地资源都真实存在。
    # 用法：python scripts/check_site.py    退出码 0 通过 / 1 有缺失。
    import os
    import re
    import sys

    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    PAGES = ["index.html"]
    EXTERNAL_PREFIXES = ("#", "http://", "https://", "//", "mailto:", "data:", "javascript:")
    REF = re.compile(r"(?:href|src)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)


    def local_refs(text):
        for raw in REF.findall(text):
            ref = raw.strip()
            if not ref or ref.startswith(EXTERNAL_PREFIXES):
                continue
            yield ref.split("?")[0].split("#")[0]


    def main():
        problems = []
        for page in PAGES:
            path = os.path.join(ROOT, page)
            if not os.path.isfile(path):
                problems.append("缺少入口页面：" + page)
                continue
            with open(path, "r", encoding="utf-8") as handle:
                text = handle.read()
            for ref in local_refs(text):
                if not os.path.exists(os.path.join(os.path.dirname(path), ref)):
                    problems.append(page + " 引用了不存在的资源：" + ref)
        if problems:
            for item in problems:
                print("错误：" + item)
            return 1
        print("静态站点自检通过：" + ", ".join(PAGES))
        return 0


    if __name__ == "__main__":
        sys.exit(main())
""".trimIndent() + "\n"

private val CSS_CHECK_SCRIPT = """
    # CSS 自检：括号配对 + var() 引用是否有定义 + 文件非空。
    # 用法：python scripts/check_css.py    退出码 0 通过 / 1 有问题。
    import os
    import re
    import sys

    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    CSS_DIR = os.path.join(ROOT, "css")
    VAR_USE = re.compile(r"var\(\s*(--[A-Za-z0-9_-]+)")
    VAR_DEF = re.compile(r"(--[A-Za-z0-9_-]+)\s*:")


    def css_files():
        if not os.path.isdir(CSS_DIR):
            return []
        return sorted(
            os.path.join(CSS_DIR, name)
            for name in os.listdir(CSS_DIR)
            if name.endswith(".css")
        )


    def main():
        files = css_files()
        if not files:
            print("错误：css 目录下没有任何 .css 文件")
            return 1
        problems = []
        text_all = ""
        for path in files:
            with open(path, "r", encoding="utf-8") as handle:
                text = handle.read()
            name = os.path.relpath(path, ROOT)
            if not text.strip():
                problems.append(name + " 是空文件")
            opened = text.count("{")
            closed = text.count("}")
            if opened != closed:
                problems.append(name + " 括号不配对：{ %d 个，} %d 个" % (opened, closed))
            text_all += text
        defined = set(VAR_DEF.findall(text_all))
        for used in sorted(set(VAR_USE.findall(text_all))):
            if used not in defined:
                problems.append("悬空变量引用：" + used + "（没有对应的定义）")
        if problems:
            for item in problems:
                print("错误：" + item)
            return 1
        print("CSS 自检通过：%d 个文件，%d 个令牌" % (len(files), len(defined)))
        return 0


    if __name__ == "__main__":
        sys.exit(main())
""".trimIndent() + "\n"
