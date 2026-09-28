# Round59 — 第九章逆向模块补全与前轮遗漏修复

## 文档逐项核对

### 9.1 APK
- 保留真实 apktool + JADX 命令执行链。
- 增加真实工具安装器：优先 assets，缺失时下载官方发行包；apktool 3.0.3 SHA-256 固定校验。
- JADX 使用官方跨平台发行包并从 zip 中提取 CLI jar。
- 增加用户 keystore 回编译签名 API；debug keystore 仍作为本地测试默认路径。
- AXML Manifest 解析继续独立于 apktool。

### 9.2 Web
- 保留 WebViewClient/WebChromeClient 网络观察。
- GET/HEAD 由 WebViewClient 代理捕获；POST 元数据限制仍存在。
- WebSocket 升级识别仍不是 WebSocket 帧级代理；不能把它宣称成完整 WS 抓包。

### 9.3 API
- 本机 127.0.0.1 CONNECT MITM。
- 动态 CA + 域名证书。
- WebView ProxyController。
- 请求体预览。
- SSE / chunked 响应采用流式转发，避免之前读取 1MiB 后截断导致客户端收到错误 Content-Length。
- Endpoint 数字/UUID/长十六进制路径模板化。
- OpenAPI 3.0.3。
- 请求 JSON 基础 Schema。
- 逆向 MCP Tool contributor。

### 9.4 合规
- 逆向入口一次性声明继续保留。
- API 页面明确说明本地 CA 安装和抓包安全影响。

## 发现并修复的 Round58 真实遗漏

- C++ ProjectType 虽然存在，但 Application 没有注册；Round59 已注册。
- C++ 没有默认 RunConfiguration；Round59 增加 C++ Debug Build 配置。
- API Endpoint 仍按原始 path 去重，Round59 增加模板化。
- MITM 对 SSE/chunked 使用有限 readBytes 会破坏长连接；Round59 改为流式转发。
- Round58 声称 APK 工具链已具备，但实际源码没有安装器；Round59 增加真实安装器，并保留无法在当前沙箱下载二进制的事实。

## 当前仍不能声称 100% 完成

- WebSocket 帧级抓包需要专门的 WS relay/decoder。
- Android 系统全局 Wi-Fi 代理向导目前仍是说明式流程；当前实现优先 WebView ProxyController。
- JADX 发行包体积较大，当前沙箱不能联网，因此 ZIP 不伪造内置 100MB 二进制。
- 完整 MCP Host 与逆向 contributor 的应用级注册仍需统一 Runtime wiring。
