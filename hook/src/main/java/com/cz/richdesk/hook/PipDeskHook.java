/*
 * PipDesk 卡死补丁（LSPosed 模块，作用域：系统框架 android）v0.4
 *
 * 链路（车机日志钉死的）：
 *   蓝牙/音乐自己 relaunch（换 task / 换主题）
 *     → DisplayContent.setFocusedApp(null)        ← 上游：这块屏没有 focused app 了
 *     → InputDispatcher 找不到 focused window     ← 用户感知的「点不动」= 卡死
 *     → 焦点塌回 display 0
 *     → 各虚拟屏 TaskDisplayArea.pauseBackTasks   ← 下游：把别屏 resumed 的 activity 一起暂停
 *     → 虚拟屏停在最后一帧（音频还在放）
 *
 * v0.3 的问题（12:xx 日志实证）：
 *   - 全程观察模式，两个开关都是 false，「已拦截」计数为 0 —— 一次都没拦过；
 *   - 主拦截点放在下游 pauseBackTasks，但 ANR 的直接原因是
 *     「Input dispatching timed out (Application does not have a focused window)」，
 *     即上游 focusedApp 已被清空。拦下游时焦点早丢了，卡死已成事实。
 *   - 单 task 兜底只在批量层挂不上时才注册，本次批量层成功 → 兜底根本没挂。
 *
 * v0.4 改法：
 *   1) 主拦截点前移到 DisplayContent.setFocusedApp(null)（从源头断链）；
 *   2) 下游 pauseBackTasks 仍在，作为第二道闸；
 *   3) 单 task 兜底**始终注册**（不再二选一），用独立开关控制；
 *   4) 每个拦截点配独立开关 + 累计计数器，日志里直接能看到「补丁到底有没有干活」；
 *   5) 保护范围从「只认 TYPE_VIRTUAL」放宽到「除主屏外的所有屏」，主屏要单独开关才动。
 *
 * 铁律（0.1 黑屏事故换来的，一个字都不能松）：
 *   1) **绝不抛异常**。hook 回调里的一切都吞掉：异常冒出去就是 system_server 崩溃 =
 *      整机黑屏。所有回调体 try-catch(Throwable) 包到底，连打日志都再包一层。
 *   2) **判不清就放行**。resuming 拿不到、displayId 拿不到、type 拿不到、原焦点读不到
 *      —— 一律 return 放行原逻辑。宁可补丁无效，不可状态机错乱。
 *   3) **默认全关**。四个开关默认 false，装上只记日志不改行为；确认日志判得准了再
 *      setprop 打开。setprop 后**立即生效**（每次事件都重读属性），不用重启。
 *   4) **同屏放行**。同屏 pauseBackTasks 是正常流程（屏内切换应用），永远不拦。
 *   5) **主屏默认不动**。主屏清空 focusedApp 常属正常切换，误拦风险最高；
 *      要动主屏必须显式打开 persist.pipdesk.hook.main。
 *
 * 开关（全部 persist.*，setprop 立即生效）：
 *   persist.pipdesk.hook.focus  拦截 setFocusedApp(null)      ← 首选，治本
 *   persist.pipdesk.hook.pause  拦截跨屏 TaskDisplayArea.pauseBackTasks
 *   persist.pipdesk.hook.task   拦截跨屏 Task.startPausingLocked（兜底）
 *   persist.pipdesk.hook.main   把主屏也纳入保护（默认 false，风险最高）
 */
package com.cz.richdesk.hook;

import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class PipDeskHook implements IXposedHookLoadPackage {

    private static final String TAG = "PipDeskHook";

    /** android.view.Display.TYPE_INTERNAL = 1 */
    private static final int TYPE_INTERNAL = 1;
    /** android.view.Display.TYPE_VIRTUAL = 5（UNKNOWN=0, INTERNAL=1, EXTERNAL=2, WIFI=3, OVERLAY=4, VIRTUAL=5） */
    private static final int TYPE_VIRTUAL = 5;
    private static final String PAUSE_REASON = "pauseBackTasks";

    private static final String KEY_FOCUS = "persist.pipdesk.hook.focus";
    private static final String KEY_PAUSE = "persist.pipdesk.hook.pause";
    private static final String KEY_TASK = "persist.pipdesk.hook.task";
    private static final String KEY_MAIN = "persist.pipdesk.hook.main";

    /** 默认全关：装了只观察，不改任何系统行为 */
    private static final boolean DEFAULT_ON = false;

    /** 拦截计时器：日志里能直接看出补丁有没有真的干活（0 = 从没拦过） */
    private static final AtomicLong nFocus = new AtomicLong();
    private static final AtomicLong nPause = new AtomicLong();
    private static final AtomicLong nTask = new AtomicLong();

    /** 观察日志节流：同一秒内的重复事件只打一条，避免刷爆 LSPosed 日志 */
    private static volatile long lastLogAt;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        try {
            if (lp == null || !"android".equals(lp.packageName)) return;   // 只进 system_server

            // 三个点各自独立 try-catch：任何一个挂掉都不影响其它两个继续装
            hookSetFocusedApp(lp);      // 主拦截点（上游，v0.4 新增拦截能力）
            hookPauseBackTasks(lp);     // 第二道闸（下游，v0.3 原有的主拦截点）
            hookStartPausing(lp);       // 兜底：v0.4 起始终注册，不再二选一

            logSwitches();
        } catch (Throwable t) {
            // 初始化异常绝不能冒出去：冒出去就是 system_server 崩溃 = 整机黑屏
            log("初始化异常（模块自动停用，系统不受影响）：" + t);
        }
    }

    // ------------------------------------------------------------ 主拦截点：focusedApp 清空

    /**
     * hook DisplayContent.setFocusedApp(ActivityRecord) : boolean
     *
     * 只处理「调用方传 null」这一种情况，也就是焦点被清空。
     * 拦住之后原方法不执行，mFocusedApp 保持原值、返回 false —— 输入仍然指向原来那个窗口，
     * 不会出现 "Application does not have a focused window"。
     *
     * 保守判据（缺一放行）：
     *   - 原 mFocusedApp 读不到或已经是 null → 本来就没焦点，没什么可保
     *   - displayId 拿不到 → 判不清
     *   - 主屏且没开 main 开关 → 不动（主屏清空多属正常切换）
     */
    private void hookSetFocusedApp(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.DisplayContent",
                    lp.classLoader, "setFocusedApp", "com.android.server.wm.ActivityRecord",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object[] a = p.args;
                                if (a == null || a.length == 0) return;
                                if (a[0] != null) return;          // 只关心清空
                                Object dc = p.thisObject;
                                if (dc == null) return;

                                int disp = displayId(dc);
                                int type = displayType(dc);
                                if (disp < 0) return;              // 判不清 → 放行

                                Object prev = focusedAppOf(dc);
                                boolean on = enabled(KEY_FOCUS, DEFAULT_ON);
                                logThrottled("focusedApp 将被清空 display=" + disp
                                        + " type=" + type + " 原值=" + prev + " 开关=" + on);

                                if (!on) return;                   // 观察模式：不改行为
                                if (prev == null) return;          // 本来就没焦点
                                if (!protectedDisplay(disp, type)) return;

                                p.setResult(Boolean.FALSE);        // 跳过原方法，保住 mFocusedApp
                                countAndLog(nFocus, "已阻止清空 focusedApp display=" + disp);
                            } catch (Throwable t) {
                                log("setFocusedApp 回调异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            log("hook DisplayContent.setFocusedApp ✓（主拦截点，默认只记日志）");
        } catch (Throwable t) {
            log("hook DisplayContent.setFocusedApp 失败（不影响其它拦截点）：" + t);
        }
    }

    // ------------------------------------------------------------ 第二道闸：跨屏批量暂停

    /**
     * hook TaskDisplayArea.pauseBackTasks(ActivityRecord) : boolean
     *
     * 它遍历**自己这块屏**的 leaf tasks，逐个 startPausingLocked(reason="pauseBackTasks")。
     * 批量层一次判断就能决定整屏去留：resuming 不在本屏 → 跨屏污染 → 整批拒绝（返回 false）。
     */
    private void hookPauseBackTasks(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.TaskDisplayArea",
                    lp.classLoader, "pauseBackTasks", "com.android.server.wm.ActivityRecord",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object self = p.thisObject;
                                if (self == null) return;
                                Object[] a = p.args;
                                Object resuming = (a != null && a.length > 0) ? a[0] : null;
                                if (resuming == null) return;

                                int disp = displayId(self);
                                int rDisp = displayId(resuming);
                                if (disp < 0 || rDisp < 0) return;   // 判不清 → 放行
                                if (rDisp == disp) return;           // 同屏，正常流程

                                int type = displayType(self);
                                boolean on = enabled(KEY_PAUSE, DEFAULT_ON);
                                logThrottled("跨屏 pauseBackTasks：本屏=" + disp + " type=" + type
                                        + " resuming屏=" + rDisp + " 开关=" + on);

                                if (!on) return;
                                if (!protectedDisplay(disp, type)) return;

                                p.setResult(Boolean.FALSE);          // 整批拒绝，"没暂停任何 activity"
                                countAndLog(nPause, "已拦截整批跨屏 pauseBackTasks：display=" + disp);
                            } catch (Throwable t) {
                                log("pauseBackTasks 回调异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            log("hook TaskDisplayArea.pauseBackTasks ✓");
        } catch (Throwable t) {
            log("hook TaskDisplayArea.pauseBackTasks 失败（不影响其它拦截点）：" + t);
        }
    }

    // ------------------------------------------------------------ 兜底层：单 task

    /**
     * hook Task.startPausingLocked(boolean, ActivityRecord, String) : boolean
     *
     * v0.3 只在批量层挂不上时才注册，等于「二选一」；v0.4 起**始终注册**，
     * 但默认只观察（KEY_TASK 默认 false），避免在不确认的情况下动到最核心的暂停路径。
     * 判据同批量层：reason 必须是 pauseBackTasks，且 resuming 与被暂停 task 不同屏。
     */
    private void hookStartPausing(final LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.Task", lp.classLoader,
                    "startPausingLocked", boolean.class, "com.android.server.wm.ActivityRecord",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object[] a = p.args;
                                if (a == null || a.length < 3) return;
                                if (!PAUSE_REASON.equals(a[2])) return;
                                Object resuming = a[1];
                                if (resuming == null) return;
                                Object self = p.thisObject;
                                if (self == null) return;

                                int tDisp = displayId(self);
                                int rDisp = displayId(resuming);
                                if (tDisp < 0 || rDisp < 0 || tDisp == rDisp) return;

                                int type = displayType(self);
                                boolean on = enabled(KEY_TASK, DEFAULT_ON);
                                logThrottled("跨屏 startPausingLocked：task屏=" + tDisp
                                        + " resuming屏=" + rDisp + " type=" + type + " 开关=" + on);

                                if (!on) return;
                                if (!protectedDisplay(tDisp, type)) return;

                                p.setResult(Boolean.FALSE);
                                countAndLog(nTask, "已拦截跨屏 startPausingLocked：display=" + tDisp);
                            } catch (Throwable t) {
                                log("startPausingLocked 回调异常（放行原逻辑）：" + t);
                            }
                        }
                    });
            log("hook Task.startPausingLocked ✓（兜底，始终注册，默认只记日志）");
        } catch (Throwable t) {
            log("hook Task.startPausingLocked 失败（不影响其它拦截点）：" + t);
        }
    }

    // ------------------------------------------------------------ 判据与工具（全部不抛）

    /**
     * 这块屏是否在保护范围内。
     * 主屏（displayId 0 / TYPE_INTERNAL）默认**不保护**：主屏清空 focusedApp 多为正常切换，
     * 误拦风险最高，必须显式打开 persist.pipdesk.hook.main 才纳入。
     * 其余屏（虚拟 5 / 外接 2 / overlay 4 ...）一律保护。
     */
    private static boolean protectedDisplay(int disp, int type) {
        try {
            if (disp < 0) return false;                       // 判不清 → 不拦
            if (disp == 0 || type == TYPE_INTERNAL) {
                return enabled(KEY_MAIN, DEFAULT_ON);
            }
            return true;
        } catch (Throwable t) {
            return false;                                     // 任何异常都当作「不保护」
        }
    }

    /** 取 DisplayContent 当前 focusedApp：先读字段 mFocusedApp，读不到再试 getFocusedApp() */
    private static Object focusedAppOf(Object dc) {
        try {
            Object v = XposedHelpers.getObjectField(dc, "mFocusedApp");
            if (v != null) return v;
        } catch (Throwable ignored) { }
        try {
            return XposedHelpers.callMethod(dc, "getFocusedApp");
        } catch (Throwable ignored) { }
        return null;                                          // 读不到 → 上层据此放行
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

    /** 拦截计数：前 3 次每次都报，之后每 10 次报一条，既不刷屏又能证明补丁在干活 */
    private static void countAndLog(AtomicLong counter, String what) {
        try {
            long n = counter.incrementAndGet();
            if (n <= 3 || n % 10 == 0) log(what + "（累计 " + n + " 次）");
        } catch (Throwable ignored) { }
    }

    /** 加载时把四个开关的实际值打出来，省得再猜「到底开没开」 */
    private static void logSwitches() {
        try {
            log("已加载到 system_server（v0.4）开关："
                    + "focus=" + enabled(KEY_FOCUS, DEFAULT_ON)
                    + " pause=" + enabled(KEY_PAUSE, DEFAULT_ON)
                    + " task=" + enabled(KEY_TASK, DEFAULT_ON)
                    + " main=" + enabled(KEY_MAIN, DEFAULT_ON));
            log("默认全关=观察模式；setprop 后立即生效，无需重启");
        } catch (Throwable ignored) { }
    }

    /** 带节流的日志：同一秒只打一条，防止热路径刷爆日志拖慢 system_server */
    private static void logThrottled(String msg) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastLogAt < 1000) return;
            lastLogAt = now;
            log(msg);
        } catch (Throwable ignored) { }   // SystemClock 自己出问题也绝不能抛
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
}
