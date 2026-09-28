# Round70：逆向 + 插件市场综合增强

本轮不是占位页面，重点把“分析→产物→注册/市场→运行时”链路继续接起来。

## APK 逆向
- `DexInspector` 读取 DEX header、字符串数量、类型数量、方法数量、类数量。
- 从 DEX 字符串池提取 HTTP/HTTPS URL，便于建立静态 API/域名线索。
- 扫描 `lib/*.so`、`assets/`、`res/`，并展示数量。
- 读取传统 JAR/V1 签名条目中的 X.509 证书并计算 SHA-256 指纹。
- 原 APK 仍只读；apktool/JADX/回编译链保持独立工作目录。

## Web/API 逆向
- WebView 记录继续区分 API/SSE/WebSocket/chunked，并明确 WebView 拦截边界。
- API MITM 捕获请求/响应头、请求体摘要、SSE/chunked 流式响应。
- API 端点支持 OpenAPI 3.0 导出和只读 cURL 重放模板复制，不在 IDE 内自动发送重放请求。

## 插件市场
市场仓库是 HTTPS JSON，格式：

```json
{
  "plugins": [
    {
      "id": "com.example.plugin",
      "name": "示例插件",
      "version": "1.0.0",
      "vendor": "Example",
      "description": "插件说明",
      "downloadUrl": "https://example.com/plugin.zip",
      "sha256": "可选的64位十六进制SHA-256",
      "dependencies": [],
      "aiEnhanced": true,
      "extensions": ["PROJECT_TYPE", "MCP_SERVICE"]
    }
  ]
}
```

下载链路：HTTPS → 下载临时文件 → SHA-256（若提供）→ plugin.xml → ID/版本一致性 → 写入 `$HOME/.nebulaforge/plugins`。

插件运行时继续执行 API 兼容性、权限策略、依赖检查、DexClassLoader 隔离、扩展注册和生命周期回滚。市场的“AI 增强”徽标只来自仓库元数据，不代表宿主自动授予额外权限。

## 逆向修改补充
- APK 反编译完成后增加独立“逆向修改工作区”。
- 工作区只允许访问 apktool 解包目录，使用 canonical path 校验防止路径穿越。
- 支持直接修改 XML、Smali、properties、JSON、Java/Kotlin、Gradle、JS/HTML/CSS、apktool.yml 等文本文件。
- 保存修改前自动生成 `<文件名>.nebulaforge.bak`，可在工作区恢复上一份原始内容。
- 修改后的文件通过现有 `apktool b` 回编译，再通过 `apksigner` 签名生成新的 APK；不会覆盖用户选择的原 APK。
- 单文件编辑限制为 2 MiB，并限制最多展示 2000 个可编辑文件，避免移动端一次性扫描超大工程。
