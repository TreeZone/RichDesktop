# PipDesk 系统身份：为什么后装 APK 就能用

## 一句话结论

**不需要预装到 /system/priv-app。** 氢桌面就是一个普通 APK，装在 `/data/app` 下，靠清单里的
`android:sharedUserId="android.uid.system"` 拿到 uid 1000。真正卡住它的只有一条 SELinux 规则。

---

## 一、实测证据（本机 HyperOS）

氢桌面（包名 `com.mcar.auto.uid`）的安装信息：

```
codePath      = /data/app/~~23Hy-2c_ug1PxNnqw2EAsg==/com.mcar.auto.uid-.../base.apk
userId        = 1000
sharedUser    = SharedUserSetting{... android.uid.system/1000}
privateFlags  = [ ... PRIVILEGED ... ]        ← 后装却带了特权标记
权限 granted  = 877 条
```

路径在 `/data/app`，与 `/system/priv-app` 毫无关系，`PRIVILEGED` 标记是 uid 1000 带来的副作用。

但直接启动它会崩：

```
E SELinux : seapp_context_lookup: No match for app with uid 1000,
             seinfo default, name com.mcar.auto.uid
F zygote64: selinux_android_setcontext(1000, 0,
             "default:privapp:targetSdkVersion=29:complete",
             "com.mcar.auto.uid") failed
F libc    : Fatal signal 6 (SIGABRT)
```

也就是说：**能装 = 签名校验被破了；不能跑 = SELinux 域映射没破。**

---

## 二、三道门，缺一不可

| # | 门槛 | 谁在拦 | 怎么过 |
|---|------|--------|--------|
| 1 | 签名校验 | `PackageManagerService` 校验 `sharedUserId` 的签名是否一致 | 核心破解（Lucky Patcher / CorePatch）放行 → 安装成功、uid=1000 |
| 2 | SELinux 域 | `seapp_contexts` 里唯一匹配 uid 1000 的规则要求 `seinfo=platform`，而 seinfo 来自证书映射 | **补一条不带 seinfo 的规则**（本模块） |
| 3 | 数据目录标签 | PMS 给 uid 1000 的应用打 `system_data_file`，而 `system_app` 域只能写 `system_app_data_file` | 安装后 `chcon` 一次（模块开机自动补） |

原生规则只有这一条能匹配 uid 1000：

```
user=system seinfo=platform domain=system_app type=system_app_data_file
```

`seinfo` 由 `/system/etc/selinux/plat_mac_permissions.xml` 的证书映射算出。后装 APK 用自己的证书，
不在这个映射表里 → `seinfo=default` → 一条规则都匹配不上 → zygote 直接 abort。

---

## 三、本模块做了什么

只改一个文件：`/system/etc/selinux/plat_seapp_contexts`，在 `user=system seinfo=platform` 那行之前插入：

```
user=system domain=system_app type=system_app_data_file
```

不带 `seinfo` 条件，任何 uid 1000 的进程都能进 `system_app` 域。

外加一个开机脚本 `post-fs-data.sh`，把 PipDesk 的数据目录标签对齐成 `system_app_data_file`
（只在标签不对时才动，幂等；重装 APK 后标签会复位，开机自动修）。

**APK 保持普通安装，不进 /system，不动 priv-app，不写白名单。**

---

## 四、使用步骤

1. 装 APK（`PipDesk-sysuid.apk`，清单里已带 `sharedUserId="android.uid.system"`）
   - 如果安装被拒，说明核心破解没生效——先确认 Lucky Patcher / CorePatch 已启用
2. 刷入 `PipDesk-sysapp-module.zip`（KernelSU / Magisk 里安装）
3. 重启

重启后验证：

```sh
adb shell "dumpsys package com.cz.richdesk | grep userId"   # 应为 1000
adb shell "ps -A -o USER,NAME | grep pipdesk"                   # 应为 system
adb shell "su -c 'cat /proc/$(pidof com.cz.richdesk)/attr/current'"  # 应为 u:r:system_app:s0
```

---

## 五、风险与回滚

**风险**：加了这条兜底规则后，任何声明 `sharedUserId="android.uid.system"` 的 APK 只要装得上，
就自动是 uid 1000 + `system_app` 域 = 完整系统权限。核心破解状态下这个门槛本来就形同虚设，
但这条规则让它变成"装上即拥有"。

更精确的替代方案是只在 `plat_mac_permissions.xml` 里映射 PipDesk 自己的证书
（`privapp-module/` 里保留了这个做法），代价是改错会导致 PMS 读不出策略、系统起不来，
且必须重启 framework 才生效。本模块选了风险更低的 seapp_contexts 路线。

**回滚**：

```sh
su -c "rm -rf /data/adb/modules/pipdesk_sysapp"
# 重启即可，/system 分区本身没有做任何修改
```

---

## 六、文件清单

| 文件 | 说明 |
|------|------|
| `PipDesk-sysuid.apk` | 带 `sharedUserId` 的构建产物 |
| `PipDesk-sysapp-module.zip` | **推荐**。只补 SELinux 规则，APK 后装 |
| `sysapp-module/` | 上者的源码（module.prop / customize.sh / post-fs-data.sh） |
| `privapp-module/` | 旧方案（v1.2，预装 priv-app + 证书映射），**已废弃，不要刷** |
| `seapp_contexts.orig` | 本机原始 seapp_contexts 备份 |

---

## 七、配置存不住 / Dock 绑定丢失（数据目录标签）

**现象**：Dock 绑定、圆角、屏数等设置在重启后全部回到默认；启动日志里有

```
W/ContextImpl: Failed to ensure /data/user/0/com.cz.richdesk/shared_prefs: mkdir failed: EACCES
W/ContextImpl: Failed to ensure /data/user/0/com.cz.richdesk/files: mkdir failed: EACCES
```

**原因**：APK 以 uid 1000 安装时，PMS 给数据目录打的标签是 `u:object_r:system_data_file:s0`，
而进程跑在 `system_app` 域，只允许写 `system_app_data_file` → 目录建不出来，SharedPreferences 落不了盘。
**每次重装/升级 APK，标签都会被重置**。

**处理**（两种，任一即可）：

1. 已刷 `PipDesk-sysapp-module.zip`：模块的 `post-fs-data.sh` 每次开机自动 `chcon`，重启一次就好。
2. 手动补一次：

```sh
su -c "chcon -R u:object_r:system_app_data_file:s0 /data/data/com.cz.richdesk /data/user_de/0/com.cz.richdesk"
```

APK 内部也有自愈（`DataDirFix`）：启动时真写一次文件探测，不可写就尝试 `chcon`，
仍失败会在桌面上弹一次「配置暂时存不住：请安装权限模块后重启手机」。
注意应用进程内的 `su` 需要 KernelSU/Magisk 授权，默认往往被拒，所以**模块里的 post-fs-data 才是可靠路径**。

---

## 八、v0.3：模块改成开机 bind mount（重要）

**问题**：v2.0 的模块是把补丁文件放在 `$MODPATH/system/etc/selinux/plat_seapp_contexts`，
指望模块系统把它覆盖到 `/system`。实测本机 HyperOS 13 **不挂载模块对 `/system/etc` 的覆盖**
（`mount | grep seapp` 为空，文件躺在 `/data/adb/modules/pipdesk_sysapp/system/...` 里但系统看不见），
于是重启后 zygote 仍然 abort：

```
JNI FatalError called: selinux_android_setcontext(1000, 0,
    "default:privapp:targetSdkVersion=29:complete", "com.cz.richdesk") failed
```

**改法**：模块的 `post-fs-data.sh` 自己干活，时间点在 zygote 之前：

1. 若 `/system/etc/selinux/plat_seapp_contexts` 里没有兜底规则，就复制一份到
   `/data/adb/pipdesk/seapp_patched`，插入
   `user=system domain=system_app type=system_app_data_file`，
   然后 `mount -o bind` 覆盖原文件；
2. 顺带把 PipDesk 数据目录 `chcon` 成 `system_app_data_file`。

两条都在开机时完成，重启后直接可用，不再需要手动 `su -c chcon` 或手动 mount。

**验证**：

```sh
su -c "grep -c 'user=system domain=system_app' /system/etc/selinux/plat_seapp_contexts"  # 1
su -c "ls -Z /data/data/com.cz.richdesk"                                             # system_app_data_file
```

注意：**覆盖安装 APK 会把数据目录标签打回 `system_data_file`**，模块只在开机时修，
所以装完新 APK 要么重启一次，要么临时补一条 `chcon`。装完之后配置就不会再丢。

---

## 九、v0.4：玻璃观感 / 沉浸式设置页 / 应用"跳全屏"根因

### 9.1 对话框"黑乎乎"的两个原因

1. 窗口底色写死成 `0xF2xxxxxx`（≈95% 不透明）——看着就是一块大黑屏；
2. 对话框走的是 `Theme.AppCompat.DayNight`，浅色模式下列表项文字是**深灰**，压在深底上就等于看不见。

改法：

* 新增 `Theme.PipDesk.Dialog`（继承 `Theme.AppCompat.Dialog.Alert` → 深色底 → 白字，
  `windowIsTranslucent` + 透明窗底），在 `Theme.PipDesk` 里挂 `alertDialogTheme`，
  所有 `AlertDialog.Builder` 自动生效；
* `Ui.glassPanel()`：Android 12+ 用 55% 半透明，安卓 9~11 用 88% 兜底；
* `Ui.blurWindow()`：`FLAG_BLUR_BEHIND` + `Window#setBackgroundBlurRadius`，只在 SDK >= 31 调用。

### 9.2 背景模糊的版本边界

| 版本 | 表现 |
| --- | --- |
| Android 12+（API 31） | 真模糊（本机 `ro.surface_flinger.supports_background_blur=1`），对话框后内容被糊开 |
| Android 9~11 | 没有该能力，`blurWindow()` 直接 return，靠更高的不透明度保证白字可读 |

代码里所有新 API 都有 `Build.VERSION.SDK_INT` 判断 + `try/catch`，minSdk 28 / targetSdk 30，
Android 9 ~ 16 都能跑。

### 9.3 应用"打开后弹一下变成全屏"的根因

`VirtualDisplay#release()` 销毁虚拟屏时，**跑在这块屏上的 task 会被系统搬到主屏并顶到 Activity 栈最前**，
于是桌面被目标应用盖住，看起来就是"画中画没了，应用全屏了"。

修法（`PipPane.releaseDisplay()`）：

1. 释放前先记下"桌面此刻是否在前台"（`AppState.mainResumed`）——注意 task 被搬过去的瞬间
   MainActivity 会被 pause，**不能在延迟任务里再判断**；
2. 释放后 180ms / 600ms 各显式 `startActivity(MainActivity)` 一次，把桌面拉回最前，
   应用就退回后台。

实测：在画中画里切换应用、关闭画中画后，桌面都还在，不会再被应用顶掉。

> 顺带尝试过用隐藏 API `ActivityTaskManager#moveTaskToDisplay` 把跑偏的 task 搬回虚拟屏，
> 但本机对 uid 1000 也做了隐藏 API 过滤（反射直接 `NoSuchMethodException`），已降级为只记日志。

---

## 十、v0.5：单层玻璃 / 底部半屏选择器 / 换应用不重建 / 默认应用 / 日志落盘 / Dock 可调

### 10.1 弹窗"两层玻璃"的根因

`Ui.glassWindow()` 把**窗口**背景画成玻璃，但 AppCompat 的 AlertDialog 内部还有一个面板
（`alertDialogStyle` → `abc_dialog_material_background`，自带一层灰色圆角矩形），
两层叠起来就是"玻璃里套玻璃"。

修法（`res/values/themes.xml`）：

```xml
<style name="Theme.PipDesk.Dialog" parent="Theme.AppCompat.Dialog.Alert">
    <item name="alertDialogStyle">@style/PipDesk.AlertPanel</item>
    ...
</style>
<style name="PipDesk.AlertPanel" parent="AlertDialog.AppCompat">
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="colorBackgroundFloating">@android:color/transparent</item>
</style>
```

内层面板改为全透明后，只剩 `Ui.glassWindow()` 画的一层玻璃（长按菜单、选择器、授权弹窗全部生效）。

### 10.2 绑定应用弹窗 → 底部半屏（`AppPickerSheet`）

- `Dialog` 贴底、初始高度 = 屏幕 1/2；
- 顶部小横条（含标题）是拖动热区：下拉 > 90dp 关闭、上拉 > 48dp 一次性展开到 88%；
- 点击面板外暗区即可关闭（`setCanceledOnTouchOutside(true)`）——不再只能按返回键；
- 没有引入 material `BottomSheetDialog` 依赖，安卓 9~16 通用。

### 10.3 换应用不再销毁虚拟屏

旧逻辑切换应用会 `releaseDisplay() → createDisplay() → 重新拉起`，代价是：
黑屏一大截 + 屏内 task 被系统搬到主屏（桌面被顶掉，再被 `restoreDesktop()` 拉回来）。

新逻辑（`PipPane.open()`）：同一块虚拟屏上直接 `launchOn(newPkg)`，**纹理保持可见**，
新应用画出第一帧前显示的还是旧画面，视觉上是"旧画面无缝变新画面"。
仅当纹理尺寸/DPI 变化导致 `resize` 失败时才走重建兜底。

### 10.4 设置页：删除「多屏分配」，改为「画中画默认应用」

- 单应用固定屏位仍在长按菜单里（`指定画中画位置`，写 `pane_map`）；
- 新增 `pane_default_1..3`：每块屏可指定一个默认应用，桌面启动时**空闲屏自动拉起**；
- 屏数滑块变化会即时重建这几行。

### 10.5 日志落盘（`FileLog`）

- 子进程 `logcat -v time PipDesk:V AndroidRuntime:E CRASH:W *:S`，逐行 flush；
- 路径：`/sdcard/Android/data/com.cz.richdesk/files/logs/pipdesk-YYYY-MM-DD.log`
  （外部不可用时退回内部 filesDir；设置页「说明」里直接显示实际路径）；
- 跨天自动换文件，单文件 > 4MB 轮转为 `.old`，只保留 7 天；
- 每行带时间戳和 pid，车机上出现蓝牙媒体流卡死时可直接把文件拷出来对齐时间轴。

### 10.6 Dock 高度 / 图标可调

- `dock_height`（56~120dp，默认 70）：改 `dockWrap` 的 LinearLayout 高度；
- `dock_icon`（28~64dp，默认 42）：`DockAdapter` 的实际图标 = min(配置值, 格宽×62%)，
  格数多时自动压缩，不会互相挤住；
- 两者都在设置页 Dock 分组里，返回桌面即时生效。

> 兼容性不变：minSdk 28 / targetSdk 30，所有新 API（模糊、decorFitsSystemWindows）
> 仍带版本判断。
