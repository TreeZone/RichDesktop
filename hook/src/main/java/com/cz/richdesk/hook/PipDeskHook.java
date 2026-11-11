/*
 * PipDesk 卡死补丁（LSPosed 模块，作用域：系统框架 android）v0.3
 *
 * 链路（车机日志钉死的）：
 *   蓝牙播放 → 自己 relaunch 切主题 → setFocusedApp=null → 焦点塌回 display 0
 *   → ROM 把各虚拟屏上 resumed 的 activity 一起 startPausingLocked(reason="pauseBackTasks")
 *   → 虚拟屏停在最后一帧 = 卡死（音频还在放）
 *
 * 铁律（0.1 黑屏事故换来的）：
 *   1) **绝不抛异常**。hook 回调里的一切都吞掉：异常冒出去就是 system_server
 *      崩溃 = 整机黑屏。所有回调体 try-catch 包到底，连 XposedBridge.log 都包。
 *   2) **判不清就放行**。resuming 拿不到、displayId 拿不到、type 不是确认的
 *      TYPE_VIRTUAL —— 一律放行原逻辑。宁可补丁无效，不可状态机错乱。
 *   3) **默认只观察**。persist.pipdesk.hook.pause 默认 false，装上只记日志不改行为；
 *      日志里判得准了再 setprop 打开。
 *   4) 同屏的 pauseBackTasks 是正常流程（屏内切换应用），永远放行；
 *      只有「正在恢复的 activity 和被暂停的 task 不在同一块屏」才算跨屏污染。
 *
 * 拦截点：首选 TaskDisplayArea.pauseBackTasks（批量层，一屏一判，dexdump 核过）；
 * 这个方法挂不上才退回 Task.startPausingLocked（单 task 层，判据相同）。
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
     * android.view.Display.TYPE_VIRTUAL = 5（已核对 AOSP 源码：
     * UNKNOWN=0, INTERNAL=1, EXTERNAL=2, WIFI=3, OVERLAY=4, VIRTUAL=5）。
     */
    private static final int TYPE_VIRTUAL = 5;
    private static final String PAUSE_REASON = "pauseBackTasks";
    /** 主补丁默认关：先观察，确认日志判得准再 setprop persist.pipdesk.hook.pause 1 */
    private static final boolean PAUSE_DEFAULT = false;
    /** 观察日志节流：同一秒内的重复事件只打一条，避免刷爆 LSPosed 日志 */
    private static volatile long lastLogAt;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;   // 只进 system_server
        try {
            log("已加载到 system_server（v0.3，默认观察模式，开关 persist.pipdesk.hook.pause）");
            boolean primary = hookPauseBackTasks(lp);
            if (!primary) {
                log("批量层挂不上，退回单 task 层");
                hookStartPausing(lp);
            }
            hookSetFocusedApp(lp);
        } catch (Throwable t) {
            // 初始化异常绝不能冒出去：冒出去就是 system_server 崩溃 = 整机黑屏
            log("初始化异常（模块自动停用，系统不受影响）：" + t);
        }
    }

    // ------------------------------------------------------------ 首选：批量层

    /**
     * hook TaskDisplayArea.pauseBackTasks(ActivityRecord) : boolean
     *
     * 它遍历**自己这块屏**的 leaf tasks，逐个 startPausingLocked(reason="pauseBackTasks")。
     * 所以在批量层一次判断就能决定整屏的去留：
     *   - 传入的 resuming（正在恢复的 activity）和本屏不同 → 跨屏污染，整批拒绝（返回 false）
     *   - 同屏（屏内正常切换）→ 放行
     * 一屏一次调用，比单 task 层快得多，判据也更自然。
     */
    private boolean hookPauseBackTasks(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.TaskDisplayArea", lp.classLoader,
                    "pauseBackTasks", "com.android.server.wm.ActivityRecord",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                int disp = displayId(p.thisObject);
                                int type = displayType(p.thisObject);
                                Object resuming = p.args.length > 0 ? p.args[0] : null;
                                int rDisp = resuming == null ? -1 : displayId(resuming);
                                // resuming 拿不到 / 本屏拿不到：判不清 → 放行
                                if (disp < 0 || rDisp < 0) return;
                                if (rDisp == disp) return;      // 同屏，正常流程
                                // 跨屏：先记录（观察模式也有日志），再看开关和类型
                                boolean on = enabled("persist.pipdesk.hook.pause", PAUSE_DEFAULT);
                                logThrottled("跨屏 pauseBackTasks：本屏=" + disp + " type=" + type
                                        + " resuming屏=" + rDisp + " 开关=" + on);
                                if (!on) return;                 // 观察模式：不改行为
                                if (type != TYPE_VIRTUAL) return; // 只拦虚拟屏
                                p.setResult(Boolean.FALSE);      // 整批拒绝，"没暂停任何 activity"
                                log("已拦截整批跨屏 pauseBackTasks：display=" + disp);
                            } catch (Throwable t) {
                                log("pauseBackTasks 回调异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            log("hook TaskDisplayArea.pauseBackTasks ✓");
            return true;
        } catch (Throwable t) {
            log("hook TaskDisplayArea.pauseBackTasks 失败：" + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 兜底：单 task 层

    /**
     * hook Task.startPausingLocked(boolean, ActivityRecord, String) : boolean
     *
     * 批量层挂不上时的退路（签名已用 dexdump 核对）。判据同上：
     * reason 必须是 "pauseBackTasks"，且 resuming 与被暂停 task 不同屏，
     * 且 task 确认在虚拟屏上 —— 三条缺一放行。
     */
    private void hookStartPausing(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.Task", lp.classLoader,
                    "startPausingLocked", boolean.class, "com.android.server.wm.ActivityRecord",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args.length < 3 || !PAUSE_REASON.equals(p.args[2])) return;
                                Object resuming = p.args[1];
                                if (resuming == null) return;
                                int taskDisp = displayId(p.thisObject);
                                int rDisp = displayId(resuming);
                                if (taskDisp < 0 || rDisp < 0 || rDisp == taskDisp) return;
                                boolean on = enabled("persist.pipdesk.hook.pause", PAUSE_DEFAULT);
                                int type = displayType(p.thisObject);
                                logThrottled("跨屏 startPausingLocked：task屏=" + taskDisp
                                        + " resuming屏=" + rDisp + " type=" + type + " 开关=" + on);
                                if (!on) return;
                                if (type != TYPE_VIRTUAL) return;
                                p.setResult(Boolean.FALSE);
                                log("已拦截跨屏 startPausingLocked：display=" + taskDisp);
                            } catch (Throwable t) {
                                log("startPausingLocked 回调异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            log("hook Task.startPausingLocked ✓");
        } catch (Throwable t) {
            log("hook Task.startPausingLocked 失败：" + t);
        }
    }

    // ------------------------------------------------------------ 诊断

    /**
     * 记录 focusedApp 被清空的那一下。默认只记日志；persist.pipdesk.hook.focus=1
     * 才会拒绝清空（保持原 focusedApp），且同样只对确认的虚拟屏生效。
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
                                Object prev;
                                try {
                                    prev = XposedHelpers.getObjectField(p.thisObject, "mFocusedApp");
                                } catch (Throwable t) {
                                    prev = "?";
                                }
                                logThrottled("focusedApp 将被清空 display=" + disp
                                        + " 原值=" + prev + " type=" + type);
                                if (enabled("persist.pipdesk.hook.focus", false)
                                        && type == TYPE_VIRTUAL && prev != null) {
                                    p.setResult(Boolean.FALSE);
                                    log("已阻止清空 focusedApp display=" + disp);
                                }
                            } catch (Throwable t) {
                                log("setFocusedApp 诊断异常：" + t);
                            }
                        }
                    });
            log("hook DisplayContent.setFocusedApp ✓（默认只记日志）");
        } catch (Throwable t) {
            log("hook DisplayContent.setFocusedApp 失败：" + t);
        }
    }

    // ------------------------------------------------------------ 工具（全部不抛）

    /** 带节流的日志：同一秒只打一条，防止热路径刷爆日志拖慢 system_server */
    private static void logThrottled(String msg) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLogAt < 1000) return;
        lastLogAt = now;
        log(msg);
    }

    /** 日志本身也绝不能抛：LSPosed 侧任何问题都吞掉 */
    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable ignored) { }
    }

    /** 读系统属性（SystemProperties 是隐藏类，system_server 里反射拿），失败给默认值 */
    private static boolean enabled(String key, boolean def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object r = XposedHelpers.callStaticMethod(c, "getBoolean", key, def);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) { }
        return def;
    }

    /**
     * WindowContainer → displayId。三级尝试，全失败返回 -1（上层据此放行）：
     * getDisplayId() → getDisplayContent().getDisplayId() → getRootDisplayId()
     */
    private static int displayId(Object wc) {
        if (wc == null) return -1;
        try {
            Object r = XposedHelpers.callMethod(wc, "getDisplayId");
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) { }
        try {
            Object dc = XposedHelpers.callMethod(wc, "getDisplayContent");
            if (dc != null) {
                Object r = XposedHelpers.callMethod(dc, "getDisplayId");
                if (r instanceof Integer) return (Integer) r;
            }
        } catch (Throwable ignored) { }
        try {
            Object r = XposedHelpers.callMethod(wc, "getRootDisplayId");
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) { }
        return -1;
    }

    /** display 类型：TYPE_VIRTUAL=5 等。取不到返回 -1（上层据此放行，不猜） */
    private static int displayType(Object wc) {
        try {
            Object dc = XposedHelpers.callMethod(wc, "getDisplayContent");
            if (dc != null) {
                Object info = XposedHelpers.callMethod(dc, "getDisplayInfo");
                if (info != null) return XposedHelpers.getIntField(info, "type");
            }
        } catch (Throwable ignored) { }
        try {
            Object info = XposedHelpers.callMethod(wc, "getDisplayInfo");
            if (info != null) return XposedHelpers.getIntField(info, "type");
        } catch (Throwable ignored) { }
        return -1;
    }
}
