# Codex Mobile Bridge

> 通过自建隧道在 Android 手机上直连并控制你 Mac 上的 Codex。

[中文说明](README.md) · [English](docs/README_EN.md)

平时在 Mac 上用 Codex Desktop 写代码、跑任务很方便，但在国内网络环境下，使用官方移动端远程连接时经常会遇到同步慢、延迟高，甚至电脑上早就跑完的任务在手机上迟迟刷不出来的问题。

**Codex Mobile Bridge** 的思路很简单：绕开官方的移动端远程同步链路，通过你自己的内网穿透或反向代理（如 FRP、Cloudflare Tunnel、Tailscale、Nginx 等），让 Android 手机直接连回你自己的 Mac，在手机上实时查看项目与历史会话、继续发送指令，并直接预览会话里生成的 Markdown、HTML 报告和 Mac 本地文件。

## 工作原理

```text
Android 客户端
    │ 加密 WebSocket (WSS)
    ▼
自建内网穿透 / 反向代理
    ▼
Mac 本地中继 (local_relay，默认仅监听 127.0.0.1:18880)
    ▼
Mac 桥接服务 (mac_bridge)
    ▼
Codex Desktop / codex app-server
```

> **说明**：本项目只负责「Android 手机 ↔ 你自己的 Mac」之间的连接与控制，不是 OpenAI API 代理或网络加速工具。Mac 端依然使用你原有的 Codex 账号登录状态与网络环境。

## 核心功能

- **同步桌面端项目与会话**：直接读取 Mac 上 Codex Desktop 的项目目录层级与会话列表，手机端与桌面端视图保持一致。
- **无缝接力对话**：在手机上直接向现有会话发送消息。任务运行期间，桥接服务只读跟随原来的桌面会话，避免切换页面后桌面一直停在“正在思考”；任务结束后立即退出跟随，不会产生第二个写入者。
- **连接状态与心跳检测**：实时展示手机、中继服务与当前会话的连接状态。点击主页心跳按钮会立即核对最近 24 小时的全部会话，并显示逐会话同步进度、更新数与失败数。
- **原生 Mac 管理器**：默认安装“Codex 手机桥接”控制中心，可查看本地中继、Mac 桥接、Codex 与手机连接状态，并启动、停止或重启后台服务。
- **小米澎湃 OS 4 超级岛（可选）**：进入会话后，可在超级岛持续看到待命、生成中、待确认、传输、完成或断线状态；其他系统默认关闭。此能力需要按小米官方流程登记应用并申请场景权限。
- **Markdown 与沉浸式 HTML 阅读器**：对话内容原生支持 Markdown 渲染；大体积 HTML 会从手机本地文件流式载入，避免整页复制进内存导致白屏。右上角菜单也可改用手机上的其他应用打开。
- **会话文字选择与复制**：长按任意用户消息或 Codex 回复即可拖动选区并调用系统复制，方便摘取句子继续追问。
- **会话状态提示**：主页直接显示“运行中”“新回复”“空闲”；项目行同时汇总运行中和未读数量，打开会话后自动清除未读状态。
- **最近会话自动核对**：只要手机与 Mac 保持连接，App 每 5 秒重新核对一次最近 24 小时内活动过的全部会话，正在运行的旧会话也会继续跟踪。有新内容时会提前写入 Android 私有缓存；打开会话时先显示最近 120 条可见消息，再向 Codex 确认。单个会话失败不影响其他会话，5 秒后会自动重试。关闭 App 或重启 Mac 不会删除缓存。
- **本地文件安全预览与缓存**：点击对话中引用的 Mac 本地文件路径，即可在手机上分块拉取并校验内容；首次成功后保留在有容量上限的本地缓存中，再次打开无需重复下载。图片、SVG、PDF、HTML、文本、代码、音频和视频可在 App 内直接预览；Office 文档与压缩包会显示文件信息，并可通过右上角图标交给手机上的其他应用打开。
- **从手机发送图片与文件**：会话输入框可直接选择多张图片或其他文件。手机先计算 SHA-256，再分块上传到 Mac；Mac 完整校验后才会把附件加入 Codex 回合。单个文件上限 100 MB，一次最多 10 个。
- **多尺寸屏幕适配**：基于 Jetpack Compose 构建，适配主流手机、折叠屏与平板布局。

## 环境要求

- **macOS**：已安装 Codex Desktop（或终端可直接调用兼容的 `codex` CLI）
- **Node.js**：20 及以上
- **JDK**：17 及以上
- **Android 工具链**：Android SDK Platform-Tools（包含 `adb`）
- **Android 设备**：Android 9（API 28）及以上
- **跨网连接条件**：当手机与 Mac 不在同一局域网时，需要一条支持 HTTPS/WSS 的内网穿透或反向代理链路（如 SakuraFrp、自建 frp、Cloudflare Tunnel、Caddy/Nginx 等）

## 快速开始

```bash
git clone https://github.com/Jhe1004/codex-mobile-bridge.git
cd codex-mobile-bridge

# 1. 检查本机开发环境
./scripts/doctor.sh

# 2. 生成配对密钥与配置（将地址替换为你自己的 HTTPS/WSS 公网地址，需转发至本机 127.0.0.1:18880）
./scripts/generate_pairing.sh https://bridge.example.com/v1/ws

# 3. 安装依赖、启动 Mac 后台服务，并安装原生管理器
./scripts/bootstrap_macos.sh

# 4. 手机开启 USB 调试并连接电脑，编译安装 App 并自动导入配对信息
./scripts/inspect_device.sh
./scripts/install_android.sh
```

**局域网测试说明**：如果仅在家庭或实验室等可信局域网内测试，可直接使用 Mac 的局域网 IP（例如 `http://192.168.1.20:18880/v1/ws`），并在启动中继时设置 `RELAY_HOST=0.0.0.0`。通过公网访问时请务必启用 TLS（HTTPS/WSS），并保持本地中继仅监听 `127.0.0.1`。

安装完成后，可从 `~/Applications/Codex 手机桥接.app` 打开管理器。它会显示真实连接状态、手机在线数量和本地延迟，也可以管理后台服务。若只想安装服务而不安装管理器，可执行 `INSTALL_MACOS_MANAGER=0 ./scripts/bootstrap_macos.sh`。

如需卸载 Mac 端的后台自启服务，可随时运行：

```bash
./scripts/uninstall_macos_services.sh
```

## 开发与构建验证

```bash
npm --prefix local_relay ci
npm --prefix mac_bridge install
npm test
npm --prefix local_relay run test:integration
./gradlew :app:testDebugUnitTest :app:assembleDebug
./macos_manager/scripts/build_app.sh
```

编译生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，Mac 管理器位于 `macos_manager/artifacts/`。每次提交或推送时，GitHub Actions 也会自动验证两端构建。

## 安全机制

- **独立随机令牌**：`generate_pairing.sh` 会为 Mac 桥接端与 Android 客户端分别生成独立的 256-bit 随机令牌；Mac 端存储在系统钥匙串（Keychain）中，Android 端导入后由系统 Keystore 加密保存。
- **本地配对文件隔离**：生成的 `config/pairing.json` 仅用于首次连接配对，已被 `.gitignore` 排除。
- **受控的文件访问范围**：手机端默认仅能读取当前会话所属项目的根目录；如需额外开放其他目录，可在启动服务前通过环境变量 `CODEX_MOBILE_FILE_ROOTS` 显式指定（以冒号分隔）。外网部署前建议先阅读 [SECURITY.md](SECURITY.md)。
- **受控的反向上传**：手机不能指定 Mac 上的保存路径。上传文件由 Mac 桥接服务随机命名，写入当前用户的 Application Support 目录，完成大小与 SHA-256 校验后才可发送；未发送的附件默认保留 7 天，已经进入会话的附件会持久保留，避免旧会话中的文件路径失效。
- **私有会话缓存**：会话文本只保存在 Android 应用的私有目录中，其他 App 不能直接读取。缓存最多占用 64 MB 并保留最近 40 个会话；清除 App 数据或卸载 App 会同时删除缓存。

## 相关文档

- [安装指南](docs/INSTALL.md)：完整的环境准备、密钥生成、文件目录授权与服务管理步骤
- [Mac 管理器](macos_manager/README.md)：原生状态面板、服务控制和独立构建说明
- [隧道与反向代理指南](docs/TUNNELS.md)：WebSocket 转发要求与穿透配置说明
- [兼容性说明](docs/COMPATIBILITY.md)：Mac 端依赖的 Codex 本地接口说明与升级检查清单
- [设备与界面适配说明](docs/DEVICE_PROFILES.md)：不同屏幕形态、系统栏内边距及电池后台策略说明
- [澎湃 OS 4 超级岛接入](docs/HYPEROS_SUPER_ISLAND.md)：系统检测、开发者平台登记、App ID、测试白名单与合规边界
- [AI Agent 协作指南](AGENTS.md)：供 Codex、Claude Code 等编程 Agent 参考的仓库结构与测试规范

## 许可证

本项目基于 [MIT License](LICENSE) 开源。本项目为非官方社区工具，与 OpenAI 无隶属或背书关系，详见 [NOTICE.md](NOTICE.md)。
