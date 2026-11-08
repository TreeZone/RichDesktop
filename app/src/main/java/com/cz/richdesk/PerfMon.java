package com.cz.richdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;

import java.io.File;

/**
 * 资源心跳（每分钟一行）：用来定位「跑一段时间后越来越卡」这类问题。
 *
 * 为什么需要它：这类问题不是某一行代码崩了，而是某项资源在缓慢增长 ——
 * 可能是 Java 堆、native/PSS、线程数、fd（没关的管道/进程）、或者**虚拟屏实例数**
 * （每块屏都是一个真实的 Display + 合成通道，泄漏几块就会把 SurfaceFlinger 拖死）。
 * 只有把这些数字按时间摆在一起，才能看出是哪一条在涨。
 *
 * 输出格式（tag=PipDesk，FileLog 会照常落盘）：
 *   PERF heap=12M/256M pss=180000K native=90000K threads=28 fds=142 displays=4
 *        inject=0 panes=[p0 com.tencent.mm f=1203 8s] ...
 */
public final class PerfMon {

    private static final String TAG = "PipDesk";
    private static final long PERIOD_MS = 60_000L;
    private static final long LOG_GAP_MS = 5 * 60_000L;   // 落盘节流：日志文件里 5 分钟一行就够

    private static volatile boolean started;
    private static volatile PipManager pip;
    private static volatile Context app;
    private static long lastFileLog;

    private PerfMon() { }

    /** 每次 Activity 起来都调一次：重建后要把面板引用换成新的（否则读到的是旧面板） */
    public static synchronized void start(Context ctx, PipManager manager) {
        app = ctx.getApplicationContext();
        pip = manager;
        if (started) return;
        started = true;
        Thread t = new Thread(PerfMon::loop, "pipdesk-perf");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Activity 销毁：断开引用，别把整棵旧视图树（3 个 TextureView）留在静态变量里 */
    public static void detach() {
        pip = null;
        app = null;
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                sample();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sample() {
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;
        int threads = Thread.activeCount();
        int fds = fdCount();
        int displays = displayCount();
        int inject = TouchBridge.pendingFrames();

        StringBuilder b = new StringBuilder(160);
        b.append("PERF heap=").append(heapMb).append('M').append('/').append(maxMb).append('M')
                .append(" pss=").append(pssKb()).append('K')
                .append(" threads=").append(threads)
                .append(" fds=").append(fds)
                .append(" displays=").append(displays)
                .append(" inject=").append(inject);
        PipManager p = pip;
        if (p != null) {
            for (int i = 0; i < p.count(); i++) {
                PipPane pane = p.pane(i);
                if (pane == null) continue;
                b.append(" p").append(i).append('=')
                        .append(pane.pkg() == null ? "idle" : pane.pkg())
                        .append(" f=").append(pane.frames())
                        .append(' ').append(pane.msSinceLastFrame()).append("ms")
                        .append(" st=").append(pane.displayState());
            }
        }
        String line = b.toString();
        Log.i(TAG, line);

        // Display 数 = 1（主屏）+ 每块画中画 1 块虚拟屏。多出来的就是**没释放干净的虚拟屏**，
        // 它们会一直占用合成通道/显存，是"跑一阵子越来越卡"最典型的元凶，必须显式报出来。
        PipManager m = pip;
        if (m != null && displays > m.count() + 1) {
            Log.w(TAG, "PERF 虚拟屏数量异常 displays=" + displays
                    + " 面板=" + m.count() + "（有多余的虚拟屏没释放）");
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFileLog > LOG_GAP_MS) {
            lastFileLog = now;
            FileLog.append(line + " @ " + FileLog.nowStamp());
        }
    }

    /** 打开着的 fd 数：管道/进程/socket 泄漏最先在这里体现 */
    private static int fdCount() {
        try {
            String[] list = new File("/proc/self/fd").list();
            return list == null ? -1 : list.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 当前系统里的 Display 数（主屏 + 我们的虚拟屏）：比面板数多说明有屏没释放 */
    private static int displayCount() {
        Context c = app;
        if (c == null) return -1;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            return dm == null ? -1 : dm.getDisplays().length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long pssKb() {
        try {
            return android.os.Debug.getPss();
        } catch (Throwable t) {
            return -1;
        }
    }
}
