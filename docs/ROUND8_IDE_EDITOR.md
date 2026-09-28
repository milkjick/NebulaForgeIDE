# Round 8 — IDE Editor / Session 工作区

本轮在 Round 7 工作区基础上继续推进，不把占位 UI 宣称为完成。

## 已实现

- Project Explorer：项目、目录展开/折叠、文件打开。
- Workspace 编辑器：手机窄屏上下布局，宽屏左右布局。
- 多文件 Tab：同一工作区可以保持多个打开文件。
- Dirty 状态：修改后显示 `•`，保存后清除。
- 保存：直接写回项目文件。
- Back/切换文件不会强制丢弃未保存内容。
- 查找/全部替换基础能力。
- 行号栏。
- Build Center 自动选择工作区中的首个项目。
- Problems 点击回到对应文件。
- Gradle Wrapper 优先、Toolchain Gradle 兜底。
- Gradle 环境 PATH 增加 platform-tools 和最新 build-tools。
- 构建输出保留最近 1500 行，避免 Compose 列表无限增长。

## 明确未宣称完成

- 当前编辑器还不是 Sora Editor。
- 还没有真正的 Tree-sitter parser。
- 还没有 Kotlin/Java LSP。
- Build Session 尚未持久化到数据库。
- 当前 Build/Run 仍缺少完整取消/暂停 API。
- 模板仍不能凭空产生缺失的 `gradle-wrapper.jar`；没有安装 Gradle 时不能宣称离线构建已经完成。
