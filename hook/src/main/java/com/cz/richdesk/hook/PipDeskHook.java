/*
 * PipDesk 卡死补丁（LSPosed 模块，作用域：系统框架 android）
 *
 * 为什么要在 framework 里下手：
 *   车机上「点蓝牙播放 → 所有画中画一起卡死」的链路已经被日志钉死了：
 *     1) 屏内系统蓝牙开始播放，自己 relaunch 切"播放中"主题（ATBluetoothService: onThemeSwitch）
 *     2) 系统在重新计算焦点时把 focusedApp 清空（WindowManager: setFocusedApp mFocusedApp = null）
 *     3) 输入焦点整块塌回 display 0，主屏 home 变 resumed
 *     4) ROM 私有的 TaskDisplayArea.pauseBackTasks 把各屏 resumed 的 activity 一起
 *        startPausingLocked(z, resuming, "pauseBackTasks")
 *     5) 被 pause 的 activity 不再提交新帧 → 虚拟屏停在最后一帧 = 卡死（音频还在放）
 *
 *   第 4 步里那个 reason 字符串 "pauseBackTasks" 就是最精准的抓手：它本该只用来处理
 *   同一屏内（分屏/PiP）的"后面那些 task"，在这台 ROM 上却被卷进了跨屏焦点兜底，
 *   把别的屏上正在显示的 activity 也一起暂停了。所以这里只拦这一类暂停，
 *   且只拦**虚拟屏**上的 task —— 主屏行为、用户主动返回/切后台的暂停一律不碰。
 *
 * 不做什么：不劫持焦点、不给画中画加窗口、不动音频。
 *   之前试过"在虚拟屏上常驻可聚焦窗口顶住焦点"，确实不卡了，但蓝牙一旦不是焦点窗口
 *   就会自己静音（requestSource: windowFocusChangedBT=false → ON_MUSIC_MUTE → set_a2dpmute 1），
 *   变成"有画面没声音"。所以补丁必须落在 framework，让焦点和音频都保持原样。
 */
package com.cz.richdesk.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class PipDeskHook implements IXposedHookLoadPackage {

    private static final String TAG = "PipDeskHook";
    /**
     * android.view.Display.TYPE_VIRTUAL = 5（TYPE_INTERNAL=1, TYPE_EXTERNAL=2,
     * TYPE_WIFI=3, TYPE_OVERLAY=4, TYPE_VIRTUAL=5，已核对 AOSP 源码）。
     */
    private static final int TYPE_VIRTUAL = 5;
    private static final String PAUSE_REASON = "pauseBackTasks";
    /**
     * 主补丁开关默认 **关**。
     *
     * 0.1 的教训：装上就生效，一旦判据在任何真实场景下误伤（虚拟屏上的**同屏**
     * 正常切换也走 pauseBackTasks），就会把 ActivityRecord 状态机搞乱，
     * system_server 崩溃 = 整机黑屏卡死。所以 0.2 起默认只观察不拦截，
     * 确认日志里判得准之后再 setprop persist.pipdesk.hook.pause 1 打开。
     */
    private static final boolean PAUSE_DEFAULT = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;   // 只进 system_server
        try {
            XposedBridge.log(TAG + " 已加载到 system_server（版本 0.2，默认观察模式）");
            hookStartPausing(lp);
            hookSetFocusedApp(lp);
        } catch (Throwable t) {
            // 任何初始化异常都不能冒出去：这里抛了就是 system_server 崩溃 = 整机黑屏
            XposedBridge.log(TAG + " 初始化异常（模块停用，系统不受影响）：" + t);
        }
    }

    // ------------------------------------------------------------ 主补丁

    /**
     * 拦掉「跨屏的 pauseBackTasks」——只拦**真正的跨屏污染**。
     *
     * 目标方法（Android 12 / 本 ROM，已用 dexdump 核对过签名）：
     *   com.android.server.wm.Task#startPausingLocked(boolean uiSleeping, ActivityRecord resuming, String reason) : boolean
     *
     * 0.1 的严重教训：pauseBackTasks 也是**同屏正常切换**的标准路径（在虚拟屏里
     * 打开新应用时，系统用它暂停屏内旧应用）。0.1 一刀切把虚拟屏上所有
     * pauseBackTasks 都拦了，屏内旧应用保持 resumed、新应用也 resumed，
     * ActivityRecord 状态机错乱 → system_server 崩溃 → 整机黑屏。
     *
     * 0.2 的判据：startPausingLocked 的第二个参数 resuming 是"正在被恢复的那个
     * activity"。跨屏污染时，调用方（别的屏的恢复链）传进来的 resuming 和
     * 被暂停的 task **不在同一块屏**；正常的同屏 pauseBackTasks 里两者同屏。
     * 所以：resuming 为 null → 放行；resuming 与 task 同屏 → 放行；
     * 不同屏 + task 在虚拟屏 + 开关打开 → 才拦。
     *
     * 返回 false = "没暂停任何 activity"，调用方只会少加一个计数。
     */
    private void hookStartPausing(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.Task", lp.classLoader,
                    "startPausingLocked", boolean.class, "com.android.server.wm.ActivityRecord",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!PAUSE_REASON.equals(p.args[2])) return;
                                Object resuming = p.args[1];
                                if (resuming == null) return;   // 没有恢复方上下文，不敢判
                                int taskDisp = displayId(p.thisObject);
                                int resumingDisp = displayId(resuming);
                                if (taskDisp < 0 || resumingDisp < 0) return;  // 判不了 → 放行
                                boolean crossScreen = resumingDisp != taskDisp;
                                if (!crossScreen) return;       // 同屏切换，正常流程，放行
                                // 跨屏才看类型与开关
                                int type = displayType(p.thisObject);
                                boolean enabled = enabled("persist.pipdesk.hook.pause", PAUSE_DEFAULT);
                                XposedBridge.log(TAG + " 跨屏 pauseBackTasks：task屏=" + taskDisp
                                        + " resuming屏=" + resumingDisp + " type=" + type
                                        + " 开关=" + enabled);
                                if (!enabled) return;           // 观察模式：只记日志
                                if (type != TYPE_VIRTUAL) return;  // 只拦虚拟屏，type 不明一律放行
                                p.setResult(Boolean.FALSE);
                                XposedBridge.log(TAG + " 已拦截跨屏 pauseBackTasks：display=" + taskDisp);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " 拦截异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " hook Task.startPausingLocked ✓");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook Task.startPausingLocked 失败：" + t);
        }
    }

    /**
     * 备用/诊断：记录 focusedApp 被清空的那一下。
     * 默认只记日志（不改行为），用来确认主补丁生效后这条链路是不是真的没再发生；
     * 需要时可以打开 persist.pipdesk.hook.focus=1 让它"拒绝清空"（保持原 focusedApp）。
     */
    private void hookSetFocusedApp(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.DisplayContent", lp.classLoader,
                    "setFocusedApp", "com.android.server.wm.ActivityRecord", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args[0] != null) return;
                                int disp = displayId(p.thisObject);
                                int type = displayType(p.thisObject);
                                Object prev = XposedHelpers.getObjectField(p.thisObject, "mFocusedApp");
                                XposedBridge.log(TAG + " focusedApp 将被清空 display=" + disp
                                        + " 原值=" + prev + " type=" + type);
                                // 类型必须确认为虚拟屏才动手，不再按 displayId 猜
                                if (enabled("persist.pipdesk.hook.focus", false)
                                        && type == TYPE_VIRTUAL && prev != null) {
                                    p.setResult(Boolean.FALSE);   // 保持原 focusedApp
                                    XposedBridge.log(TAG + " 已阻止清空 focusedApp display=" + disp);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " setFocusedApp 诊断异常：" + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " hook DisplayContent.setFocusedApp ✓（默认只记日志）");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook DisplayContent.setFocusedApp 失败：" + t);
        }
    }

    // ------------------------------------------------------------ 工具

    /**
     * 读系统属性。android.os.SystemProperties 是隐藏类，编译期拿不到，
     * 但运行时在 system_server 里一定存在，所以走反射。
     */
    private static boolean enabled(String key, boolean def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object r = XposedHelpers.callStaticMethod(c, "getBoolean", key, def);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) { }
        return def;
    }

    /** WindowContainer(#getDisplayId) → 尽力取 displayId，取不到返回 -1 */
    private static int displayId(Object wc) {
        try {
            Object r = XposedHelpers.callMethod(wc, "getDisplayId");
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) { }
        try {
            Object dc = XposedHelpers.callMethod(wc, "getDisplayContent");
            Object r = XposedHelpers.callMethod(dc, "getDisplayId");
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) { }
        return -1;
    }

    /** display 类型：Display.TYPE_VIRTUAL=5 / TYPE_BUILT_IN=1 …，取不到返回 -1 */
    private static int displayType(Object wc) {
        try {
            Object dc = XposedHelpers.callMethod(wc, "getDisplayContent");
            Object info = XposedHelpers.callMethod(dc, "getDisplayInfo");
            return XposedHelpers.getIntField(info, "type");
        } catch (Throwable ignored) { }
        try {
            Object info = XposedHelpers.callMethod(wc, "getDisplayInfo");
            return XposedHelpers.getIntField(info, "type");
        } catch (Throwable ignored) { }
        return -1;
    }
}
