# Codex Mobile Bridge

> 在 Android 手机上远程使用自己 Mac 上的 Codex。面向中国大陆网络环境的开源、自托管 Codex 手机客户端。

[中文说明](README.md) · [English](docs/README_EN.md)

Codex Mobile Bridge 是一个非官方 Android 客户端。它把手机直接连接到你自己的 macOS 电脑，让你在手机上查看 Codex 项目与会话、继续对话，并打开会话中引用的 Mac 本地文件。

## 为什么需要这个项目

在中国大陆，手机连接 OpenAI 和 Codex 的官方远程服务时，可能受到跨境网络延迟、连接不稳定和消息同步缓慢的影响。有时电脑上的 Codex 已经更新，手机端却仍停留在很早以前的内容。

这个项目提供一条自托管链路：

```text
Android 手机
    │ 加密 WebSocket
    ▼
你自己的 HTTPS/WSS 内网穿透或反向代理
    ▼
Mac 本地中继（默认仅监听 127.0.0.1）
    ▼
Mac Bridge
    ▼
Codex Desktop / codex app-server
```

因此，手机不再依赖 Codex 官方的手机同步链路，而是通过你自己搭建的服务直接操控 Mac 上正在运行的 Codex。它适合搜索“Codex 手机端”“Codex Android”“手机远程控制 Codex”“Codex 中国连接慢”“Codex 内网穿透”时找到并使用。

需要说明：本项目解决的是“手机 ↔ 自己的 Mac”之间的连接和操控，不是 OpenAI API 代理、VPN 或网络加速器。Mac 上的 Codex 仍然使用你自己的 OpenAI/Codex 登录和网络连接。

## 主要功能

- 按照 Codex Desktop 的项目层级和会话名称显示内容。
- 在 Android 手机上继续现有 Codex 会话并发送消息。
- 手机发送后自动把控制权交还给 Mac，不占用或破坏桌面会话。
- 用心跳图标显示手机与 Mac、当前会话的连接健康状态。
- 自动渲染 Markdown，内置沉浸式 HTML 阅读器。
- 点击会话里的本地文件链接，将允许访问的 Mac 文件安全传到手机预览。
- 支持大文件分块传输，并限制文件访问范围。
- 支持手机、平板、折叠屏和 Android 自适应图标。
- 服务器地址完全可配置，公开源码不包含作者自己的服务器地址或账号信息。

## 适合谁

- 已经在 Mac 上使用 Codex Desktop，但官方手机连接经常缓慢或不稳定的用户。
- 希望在通勤、实验室或外出时，用 Android 手机继续 Mac 上 Codex 工作的用户。
- 拥有内网穿透、反向代理、云服务器或公网域名，并愿意自托管服务的用户。
- 需要在手机上阅读 Codex 生成的 Markdown、HTML 和本地文件的用户。

## 系统要求

- 一台运行 Codex Desktop 或兼容 `codex` CLI 的 macOS 电脑
- Node.js 20 或更高版本
- JDK 17 或更高版本
- Android SDK 和 `adb`
- Android 9 / API 28 或更高版本的手机
- 手机不在 Mac 局域网时，需要支持 TLS 的内网穿透或反向代理

Mac 端使用的 Codex 本地接口可能随 Codex 更新发生变化，详情见[兼容性说明](docs/COMPATIBILITY.md)。

## 快速安装

```bash
git clone https://github.com/Jhe1004/codex-mobile-bridge.git
cd codex-mobile-bridge

# 检查本机开发环境
./scripts/doctor.sh

# 填写你自己的 HTTPS/WSS 公网地址，它需要转发到 127.0.0.1:18880
./scripts/generate_pairing.sh https://bridge.example.com/v1/ws

# 安装依赖并启动两个 Mac 后台服务
./scripts/bootstrap_macos.sh

# 打开手机 USB 调试，连接一台 Android 设备，然后检查、安装并完成配对
./scripts/inspect_device.sh
./scripts/install_android.sh
```

完整步骤见[安装指南](docs/INSTALL.md)，内网穿透和反向代理配置见[隧道指南](docs/TUNNELS.md)。可以使用 SakuraFrp/NATFRP、Cloudflare Tunnel、Nginx 或其他能够提供 HTTPS/WSS 的方案；仓库不绑定任何服务商。

仅在可信局域网测试时，也可以使用 Mac 的局域网地址，例如 `http://192.168.1.20:18880/v1/ws`，并让中继以 `RELAY_HOST=0.0.0.0` 运行。通过公网访问时应使用 TLS，并保持中继只监听 `127.0.0.1`。

## 手机与系统适配

应用支持 API 28+，界面使用自适应布局，不依赖某个手机型号的固定坐标。修改界面前先运行：

```bash
./scripts/inspect_device.sh
```

脚本会生成不包含设备序列号的本地设备信息。手机、平板、折叠屏、刘海/挖孔屏、软键盘以及不同厂商后台限制的适配方法见[设备适配说明](docs/DEVICE_PROFILES.md)。

## 开发与验证

```bash
npm --prefix local_relay ci
npm --prefix mac_bridge install
npm test
npm --prefix local_relay run test:integration
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 也会自动测试并生成 APK 构建产物。

## 安全与隐私

- 仓库不包含真实令牌、私人服务器地址、账号、设备序列号、个人目录、证书或签名密钥。
- 配对信息保存在 Git 忽略的 `config/pairing.json` 中，并在导入手机后由 Android Keystore 保护。
- Mac 端密钥保存在 macOS 钥匙串中。
- 本地文件只能从当前会话项目目录和 `CODEX_MOBILE_FILE_ROOTS` 明确允许的目录中读取。
- 将服务暴露到公网前，请先阅读[安全说明](SECURITY.md)。

## 给 AI Agent

如果你准备让 Codex、Claude Code 或其他 AI 编程 Agent 安装、修改或适配这个项目，请先让它阅读 [AGENTS.md](AGENTS.md)。其中包含仓库结构、配置约定、安全边界、验证命令，以及适配新手机时应遵守的规则。

## 当前状态

这是一个早期社区版本，目前主要在 macOS Codex Desktop 和 Android 16 级别设备上开发验证。Codex 更新其本地 app-server 或桌面通信协议后，本项目可能需要同步适配。

## 许可证

代码使用 [MIT License](LICENSE)。这是非官方项目，与 OpenAI 没有隶属或背书关系，详见 [NOTICE.md](NOTICE.md)。

**相关关键词：** Codex 手机端、Codex Android、OpenAI Codex、Codex 中国、Codex 连接慢、手机远程控制 Mac Codex、Codex 自托管、Codex 内网穿透、Android Codex client、self-hosted Codex remote client。
