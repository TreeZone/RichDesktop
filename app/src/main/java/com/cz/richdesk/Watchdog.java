package com.cz.richdesk;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;

/**
 * 主线程卡死看门狗（车机专用排查工具）。
 *
 * 为什么要有它：车机上出现「三个画中画时整个桌面卡死、触摸无响应」，
 * 但车机上连 adb 抓 logcat 很麻烦，等把设备拿回来现场早就滚没了。
 *
 * 做法：后台线程每 800ms 给主线程 post 一个空任务。如果 2.4 秒内主线程
 * 还没把上一个空任务跑完，就认定主线程卡住 —— 立刻把**主线程堆栈**、
 * **全部线程堆栈**、**最近一次触摸事件**、**各画中画状态**写进日志文件。
 *
 * 这个堆栈能直接回答「卡在哪一行」：
 *  - 卡在 Binder.transact / createVirtualDisplay → 显示服务或 SurfaceFlinger 反压
 *  - 卡在 ViewRootImpl.performTraversals / onLayout → 布局死循环
 *  - 卡在 Thread.sleep / 锁 → 我们自己阻塞了主线程
 *  - 主线程堆栈正常但用户仍觉得卡 → 是渲染/合成层的问题，不是主线程
 *
 * 手动触发：创建文件 /sdcard/pipdesk_dump（内容随意），看门狗发现后立刻 dump 一次，
 * 然后自动删掉这个文件。用文件管理器或 adb 都行。
 */
public final class Watchdog {

    private static final String TAG = "PipDesk";

    private static final long TICK_MS = 800L;     // 检查周期
    private static final long STUCK_MS = 2400L;   // 超过它算卡死（ANR 是 5s，这里提前留证据）
    private static final long REPEAT_MS = 6000L;  // 同一次卡死最多每 6s 打一份，防止刷爆日志
    private static final int FILE_CHECK_EVERY = 10;   // 每 10 个周期查一次手动触发文件
    private static final String TRIGGER_FILE = "/sdcard/pipdesk_dump";

    private static volatile boolean started;
    private static volatile long acked;           // 主线程已完成 ping 的次数
    private static volatile String lastTouch = "(无)";
    private static volatile Probe probe;
    private static long lastDump;
    private static Handler main;

    private static final Runnable PING = () -> acked++;

    /** 外部注入的状态描述（各画中画 display/尺寸/包名），卡死时一起写进日志 */
    public interface Probe {
        String describe();
    }

    private Watchdog() { }

    /** 记录最近一次触摸（面板监听器调用），卡死时能看出手势停在哪一步。
     *  只在排查模式下记录：否则每次按下/抬起都要格式化一个时间戳，纯属白烧 CPU。 */
    public static void touch(String desc) {
        if (!PipDebug.VERBOSE) return;
        lastTouch = FileLog.nowStamp() + " " + desc;
    }

    public static void setProbe(Probe p) {
        probe = p;
    }

    public static synchronized void start() {
        if (started) return;
        // 默认不跑：每 800ms 给主线程塞一个空任务、永远不停，纯属「隐形埋点」式的常驻开销。
        // 只有排查时才开：/sdcard/pipdesk_dump 或 /sdcard/pipdesk_verbose 存在。
        if (!PipDebug.DIAG) {
            Log.i(TAG, "看门狗未启用（需要 /sdcard/pipdesk_dump 或 pipdesk_verbose）");
            return;
        }
        started = true;
        main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(Watchdog::loop, "pipdesk-watchdog");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "看门狗已启动：主线程 >" + STUCK_MS + "ms 无响应即 dump 堆栈到 "
                + FileLog.dirPath());
    }

    private static void loop() {
        int tick = 0;
        while (true) {
            try {
                long before = acked;
                main.post(PING);
                long t0 = SystemClock.uptimeMillis();
                Thread.sleep(TICK_MS);

                if (acked == before) {
                    // 一个周期没响应，再等一个"卡死判定"窗口确认（避免正常重负载误报）
                    Thread.sleep(STUCK_MS - TICK_MS);
                    if (acked == before) dump(SystemClock.uptimeMillis() - t0);
                } else if (++tick % FILE_CHECK_EVERY == 0 && consumeTrigger()) {
                    dump(-1);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 手动触发：存在 /sdcard/pipdesk_dump 就吃掉它并返回 true */
    private static boolean consumeTrigger() {
        try {
            java.io.File f = new java.io.File(TRIGGER_FILE);
            if (!f.exists()) return false;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void dump(long stuckMs) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDump < REPEAT_MS) return;
        lastDump = now;

        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("\n######## 主线程无响应 @ ").append(FileLog.nowStamp());
        if (stuckMs >= 0) sb.append("  已持续 ").append(stuckMs).append("ms");
        else sb.append("  (手动触发)");
        sb.append(" ########\n");
        sb.append("最近触摸: ").append(lastTouch).append('\n');
        Probe p = probe;
        if (p != null) {
            try {
                sb.append("画中画状态: ").append(p.describe()).append('\n');
            } catch (Throwable ignored) {
                sb.append("画中画状态: (读取失败)\n");
            }
        }

        Thread mt = Looper.getMainLooper().getThread();
        sb.append("--- 主线程 \"").append(mt.getName())
                .append("\" state=").append(mt.getState()).append(" ---\n");
        appendStack(sb, mt.getStackTrace(), "  ");

        sb.append("--- 全部线程 ---\n");
        for (Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
            Thread th = en.getKey();
            if (th == mt) continue;
            sb.append('"').append(th.getName()).append("\" ")
                    .append(th.getState()).append('\n');
            appendStack(sb, en.getValue(), "  ");
        }

        FileLog.append(sb.toString());
        Log.e(TAG, "主线程无响应，堆栈已写入 " + FileLog.dirPath());
    }

    private static void appendStack(StringBuilder sb, StackTraceElement[] st, String indent) {
        if (st == null || st.length == 0) {
            sb.append(indent).append("(无堆栈)\n");
            return;
        }
        for (StackTraceElement e : st) {
            sb.append(indent).append("at ").append(e.toString()).append('\n');
        }
    }
}
