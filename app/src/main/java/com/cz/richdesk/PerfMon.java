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
    /** /proc/meminfo 在车机上常被 SELinux 拒（每次拒绝都会写一条审计）：拒一次就改用官方 API */
    private static boolean procMemBlocked;

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
                .append(" rss=").append(rssKb()).append('K')
                // 系统可用内存：车机 RAM 小，这个掉到几十 MB 就是"越用越卡"的直接原因
                // （系统和应用都在换页）。手机内存大永远看不到，所以只有车机日志才有意义。
                .append(" avail=").append(availKb()).append('K')
                .append(" win=").append(FocusAnchor.count())
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
            // 单画专用屏也要记：用户报「打开百度地图汽车版就卡」，而单画走的是独立虚拟屏，
            // 原来这一路一个帧数都没有，日志里根本看不出它出帧多少 —— 没法判断"卡"是
            // 屏内应用自己慢，还是它在拖着整个窗口重绘。
            PipPane single = p.singlePane();
            if (single != null) {
                b.append(" ps=").append(single.pkg() == null ? "idle" : single.pkg())
                        .append(" f=").append(single.frames())
                        .append(' ').append(single.msSinceLastFrame()).append("ms")
                        .append(" st=").append(single.displayState());
            }
        }
        String line = b.toString();
        Log.i(TAG, line);

        // 期望 = 主页画中画 + 单画专用屏（用过单画就一直留着，下次秒开）
        // 注意只数**我们自己的**虚拟屏：别的应用也会建虚拟屏（实测应用宝就常驻一块 1x1 的
        // install_display_yyb），按 DisplayManager 的总数判断会天天误报"泄漏"。
        PipManager m = pip;
        if (m != null) {
            int expect = m.count() + (m.singlePane() != null ? 1 : 0);
            int mine = PipDisplays.size();
            if (mine > expect) {
                Log.w(TAG, "PERF 虚拟屏泄漏 我方=" + mine + " 期望=" + expect
                        + "（全系统 displays=" + displays + "）");
            }
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

    /**
     * 本进程实际驻留内存。
     *
     * 原来用 {@code android.os.Debug.getPss()}：它已废弃，而且实测读数 365MB，
     * 与 {@code dumpsys meminfo} 的 PSS Total（101MB）差 3.5 倍 —— 拿它判断
     * "有没有涨"会一直看到虚高值。直接读 /proc/self/status 的 VmRSS，和系统面板一致。
     */
    private static long rssKb() {
        return statusKb("VmRSS:");
    }

    /**
     * 系统可用内存（MemAvailable）：比 MemFree 真实，已扣掉不可回收的缓存。
     *
     * /proc/meminfo 被 SELinux 拒的设备（实测展锐车机）退回 ActivityManager.MemoryInfo：
     * 数值语义相同（都是「还能拿到多少」），而且不需要读 proc、不产生审计拒绝。
     */
    private static long availKb() {
        if (!procMemBlocked) {
            long v = availKbFromProc();
            if (v >= 0) return v;
        }
        try {
            Context c = app;
            if (c == null) return -1;
            android.app.ActivityManager am =
                    (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / 1024L;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long availKbFromProc() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) return Long.parseLong(p[1]);
                }
            }
        } catch (Throwable t) {
            procMemBlocked = true;      // 拒一次就够，之后一直走 AM
            Log.i(TAG, "读不到 /proc/meminfo（" + t.getClass().getSimpleName() + "），改用 AM");
        }
        return -1;
    }

    private static long statusKb(String key) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) return Long.parseLong(p[1]);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
