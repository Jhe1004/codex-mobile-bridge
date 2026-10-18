# Codex 手机桥接管理器

这是 Codex Mobile Bridge 的原生 macOS 控制中心。它只管理本机服务，不读取聊天内容，也不会显示令牌或密码。

## 可以做什么

- 查看本地中继、Mac 桥接以及可选公网隧道是否运行。
- 查看 Codex 后台是否连接、手机在线数量和本地响应延迟。
- 单独或批量启动、停止、重启服务。
- 自动刷新状态，在菜单栏快速查看连接情况。
- 打开配置目录和日志目录。

## 构建与安装

```bash
./scripts/build_app.sh
./scripts/install_app.sh
```

应用默认安装到 `~/Applications/Codex 手机桥接.app`。

## 安全边界

- 应用调用当前用户的 `launchctl` 管理项目安装的 LaunchAgent。
- 应用不会修改 FRP 配置，也不会读取 Keychain。
- 停止服务只会卸载本次登录会话中的 LaunchAgent；原配置文件仍然保留。

默认管理的服务标签是 `org.codexmobile.bridge.relay` 和
`org.codexmobile.bridge.mac`。如果需要让管理器同时控制隧道，可使用可选标签
`org.codexmobile.bridge.tunnel` 创建用户 LaunchAgent；不配置时不会影响整体健康状态。
