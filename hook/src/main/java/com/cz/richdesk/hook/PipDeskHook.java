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
    /** Display.TYPE_VIRTUAL */
    private static final int TYPE_VIRTUAL = 5;
    private static final String PAUSE_REASON = "pauseBackTasks";

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;   // 只进 system_server
        XposedBridge.log(TAG + " 已加载到 system_server（版本 0.1）");
        hookStartPausing(lp);
        hookSetFocusedApp(lp);
    }

    // ------------------------------------------------------------ 主补丁

    /**
     * 拦掉「跨屏的 pauseBackTasks」。
     *
     * 目标方法（Android 12 / 本 ROM，已用 dexdump 核对过签名）：
     *   com.android.server.wm.Task#startPausingLocked(boolean z, ActivityRecord resuming, String reason) : boolean
     * pauseBackTasks 的 lambda 里就是用它，reason 固定是 "pauseBackTasks"。
     * 返回 false = "没暂停任何 activity"，调用方只会少加一个计数，不影响其它逻辑。
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
                                if (!enabled("persist.pipdesk.hook.pause", true)) return;
                                int type = displayType(p.thisObject);
                                int disp = displayId(p.thisObject);
                                // 只拦虚拟屏：主屏（display 0 / TYPE_BUILT_IN）一律放行。
                                // 取不到 type 时按 displayId 兜底判断，避免 SDK 差异导致整条补丁失效。
                                boolean virtual = (type == TYPE_VIRTUAL) || (type == -1 && disp > 0);
                                if (!virtual) return;
                                p.setResult(Boolean.FALSE);
                                XposedBridge.log(TAG + " 已拦截跨屏 pauseBackTasks：display=" + disp
                                        + " type=" + type + " task=" + p.thisObject);
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
                                boolean virtual = (type == TYPE_VIRTUAL) || (type == -1 && disp > 0);
                                if (enabled("persist.pipdesk.hook.focus", false) && virtual && prev != null) {
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
