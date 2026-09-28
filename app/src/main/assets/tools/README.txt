NebulaForge IDE APK 逆向工具目录

本目录用于随 APK 分发受许可证允许的工具二进制文件。
当前运行时查找：
- $HOME/.nebulaforge/tools/apktool.jar
- $HOME/.nebulaforge/tools/jadx-cli.jar

JDK 17 与 Android SDK build-tools/apksigner 使用 IDE 内置运行时中的已安装版本。
请在发行构建中将经过许可证核验的 apktool 与 JADX JAR 放入此目录，或由工具链安装器复制到运行时目录。

本源码不会伪造工具 JAR；缺少 JAR 时 UI 会明确显示“未安装”，不会把文件存在性错误地标记为可用。
