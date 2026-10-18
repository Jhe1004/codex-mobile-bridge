# 澎湃 OS 4 超级岛接入

Codex Mobile Bridge 可以在小米澎湃 OS 4 的超级岛中显示当前 Codex 会话状态。它会告诉用户：会话是否待命、Codex 是否正在生成回答、是否需要确认、文件是否正在传输、任务是否完成，以及手机与 Mac 是否断开。

这个功能不会在普通 Android、其他厂商系统或旧版澎湃 OS 上开启。即使设备满足条件，如果应用尚未在小米开发者平台登记，或用户没有授予焦点通知权限，它也会安静地保持关闭，不影响 App 的其他功能。

## 开启条件

App 会同时检查以下条件，缺少任何一项都不会发送超级岛通知：

1. 设备厂商属于 Xiaomi、Redmi 或 POCO。
2. 系统属性 `ro.mi.os.version.name` 以 `OS4` 开头。
3. 系统属性 `persist.sys.feature.island` 为 `true`。
4. `notification_focus_protocol` 为 3 或更高。
5. 编译时已配置小米开发者平台分配的 App ID。
6. 系统返回该应用已获得焦点通知权限。

它是“活跃会话状态”，不是永久连接指示灯：用户进入一个会话时开始，离开会话时结束，单次最长 12 小时。这样既能在手机锁屏或切换应用后继续看到任务状态，也符合小米对岛服务开始、结束和持续时间的要求。

## 小米开发者平台准备

源代码本身不能替代平台授权。要在真机上看到超级岛，还需要在小米澎湃 OS 开发者平台完成以下步骤：

1. 登记 Android 应用，包名使用 `org.codexmobile.bridge`，取得 App ID。
2. 分别登记调试版和发布版证书指纹。
3. 在联调阶段，把测试手机加入设备白名单。设备 OAID 只填写到小米平台，不要写进代码、文档、Issue 或提交记录。
4. 申请与“远程任务 / 活跃会话状态”相符的超级岛业务场景。正式环境需要审核通过。

小米官方入口：

- [超级岛接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)
- [超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- [超级岛业务规则](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)

## 本地配置与编译

不要把自己的 App ID 写进仓库。把它放在本机用户级 Gradle 配置 `~/.gradle/gradle.properties`：

```properties
xiaomiSuperIslandAppId=你在小米平台取得的AppID
```

然后正常构建：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试包会自动写入小米要求的 debug 标记；release 构建会切换为正式标记。App ID 会进入 APK 的 Android Manifest，这是平台识别应用所必需的，但账号密码、OAID、签名文件和令牌都不应进入仓库。

## 真机检查

连接已授权 USB 调试的手机后，可以只读取这些非敏感能力值：

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.mi.os.version.name
adb shell getprop persist.sys.feature.island
adb shell settings get system notification_focus_protocol
```

期望结果分别为小米系厂商、`OS4.x`、`1` 和 `3`（或更高）。焦点通知权限必须由 App 自己向系统查询，不能用 ADB shell 代替 App 身份完成判断。

安装后查看诊断日志：

```bash
adb logcat -s CodexSuperIsland
```

如果日志显示设备符合条件但 App ID 未配置，先完成本地 Gradle 配置并重新编译。如果显示焦点通知权限不可用，请检查小米平台白名单、场景审核和手机的通知设置。

## 显示规则

- 只在用户已经进入某个 Codex 会话时显示。
- 切换会话会开始一个新的岛会话；返回项目主页会立即结束。
- 通知使用固定 ID 原地更新，不会为每段生成内容堆积新通知。
- 断线使用红色状态，准备或待确认使用橙色，正常待命、工作和完成使用绿色。
- 点击超级岛或对应通知会回到 Codex Mobile Bridge。
- 单次会话到达 12 小时后自动结束；用户重新进入会话才会开始新的周期。
