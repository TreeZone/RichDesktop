# RichDesktop

Android 多虚拟屏桌面（launcher）：把多块「画中画」铺在桌面上，每块画中画里跑一个独立应用，
由桌面统一负责启动、触摸转发与按键注入。

- 包名：`com.cz.richdesk`
- 当前版本：`0.13.0`（versionCode 50）
- 运行身份：`sharedUserId=android.uid.system`（uid 1000），需系统签名 / priv-app 部署

## 功能

- **多虚拟屏**：三块画中画，基于 `VirtualDisplay` + `TextureView` 渲染，可各自打开不同应用
- **触摸转发**：宿主视图收到的手势按 `displayId` 注入到对应虚拟屏（两种通道：常驻注入进程 / 兜底全局注入）
- **定向按键**：Dock 的返回/主页键按 `displayId` 定向注入，不再依赖主屏焦点窗口，
  因此「点 Dock 前焦点被桌面抢走」也能正确回到画中画里的应用
- **Dock 与应用列表**：应用列表按真实宽度自适应列数与图标尺寸，避免边缘图标被裁切
- **设置页**：固定 header + 大号返回键、外观模式选择框（跟随安卓/跟随时间/手动深色/手动浅色）、
  时间配置仅在「跟随时间」模式显示、切换外观后保持滚动位置
- **诊断**：`PerfMon` 每 60 秒记录一行资源心跳（heap / pss / threads / fds / 虚拟屏数量 / 各屏帧率），
  `PaneDoctor` 检测屏内应用 ANR，`AudioProbe` 记录 AudioService 卡顿

## 目录结构

```
app/src/main/java/com/cz/richdesk/
  MainActivity.java     主页：画中画画廊 + Dock + 应用列表，按键目标选择
  SettingsActivity.java 设置页
  PipManager.java       三块画中画的创建 / 复用 / 释放
  PipPane.java          单块画中画：VirtualDisplay、启动应用、task 回收
  PaneBox.java          画中画容器视图
  TouchBridge.java      手势 -> 注入（含通道自愈）
  InputServer.java      常驻注入进程（app_process 启动，脱离应用 uid 限制）
  Keys.java             按键定向注入（setDisplayId / shell input 兜底）
  AppListAdapter.java   应用网格，列数/图标自适应
  DockAdapter.java      Dock 图标
  Ui.java / WallTint.java / WallText.java  玻璃拟态外观、壁纸取色
privapp-module/          Magisk 模块：把 APK 放进 priv-app + 权限白名单
sysapp-module/           Magisk 模块：装进 system/app 的变体
```

## 构建

```bash
# sysuid 变体（系统签名 + sharedUserId=android.uid.system）
JAVA_HOME=/path/to/jdk-21 ./gradlew -Psysuid assembleDebug
```

本机（Windows）实测可用的命令：

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21" \
  "D:/gradle-dist/gradle-9.5.0/bin/gradle.bat" --no-daemon -Psysuid assembleDebug
```

> `local.properties`（SDK 路径）与签名文件（`*.jks` / `*.cer`）不入库，需本地自备。
> `settings.gradle` 里把腾讯 maven 镜像排在第一位，用于在 google maven 不可达的环境下解析 AGP。

## 部署

sysuid 变体**必须**进 priv-app（或以打过 `seapp_contexts` 补丁的方式安装），否则 zygote 会因
`sharedUserId` 不匹配直接杀掉进程。

1. 刷 `PipDesk-privapp-module.zip`（Magisk），或手动把 APK 放到 `/system/priv-app/PipDesk/PipDesk.apk`
2. 确认 `privapp-permissions-com.cz.richdesk.xml` 已进 `/system/etc/permissions/`
3. 重启后桌面进程应以 `system`(1000) 身份运行

## 日志

`/sdcard/Android/data/com.cz.richdesk/files/logs/pipdesk-YYYY-MM-DD.log`

卡顿排查先看 `PERF` 行：`displays` 持续上涨 = 虚拟屏泄漏，`threads` 上涨 = 线程泄漏，
`pss` 涨而 `heap` 不涨 = native / 显存问题。高频触摸日志默认关闭，
需要时创建 `/sdcard/pipdesk_verbose` 打开。
