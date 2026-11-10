package com.cz.richdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 性能悬浮窗（调试工具，开关在「设置 → 测试功能」）。
 *
 * 每秒采样一行，实时显示：
 *   CPU   —— 系统整体占用 + 各核占用（/proc/stat 差分，所有 Android 设备通用）；
 *   GPU   —— GPU 占用（内核节点探测：Adreno / Mali / MTK / 通用 gpu_busy，读不到显示 -）；
 *   RAM   —— 系统内存（/proc/meminfo，失败退回 ActivityManager.MemoryInfo）；
 *   APP   —— 本进程 PSS / Java 堆 used/max；
 *   thr/fd/dsp —— 线程数 / fd 数 / 系统 Display 数（dsp 比 pane 数多 = 虚拟屏泄漏）；
 *   p0..  —— 每块画中画：包名 / 累计帧数 / 距上一帧毫秒 / 虚拟屏状态。
 *
 * 卡顿排查先看这几条：
 *   · displays 持续上涨 = 虚拟屏泄漏；
 *   · threads 上涨     = 线程泄漏；
 *   · pss 涨而 heap 不涨 = native / 显存问题。
 * 高频触摸日志默认关闭，需要时创建 /sdcard/pipdesk_verbose 打开（见 PipDebug）。
 *
 * 兼容性策略（要求尽量兼容所有安卓设备）：
 *   · CPU / 内存只依赖 /proc 文件系统 —— 所有 Linux 内核都提供，无厂商差异；
 *     内存再配 ActivityManager.MemoryInfo 兜底（官方 API，全版本可用）。
 *   · GPU 没有跨厂商的公开 API，只能读内核节点：按「目录扫描」探测，
 *     不写死具体型号路径 —— Adreno(kgsl) / Mali(mali0..N) / MTK(gpustat/gpufreq) /
 *     通用 gpu_busy_percent 都能扫到；节点需要 root 的设备（部分 PowerVR）显示 -。
 */
public final class PerfOverlay {

    private static final String TAG = "PerfDesk";

    private static WindowManager wm;
    private static TextView view;
    private static volatile boolean shown;
    private static Context appCtx;
    private static PipManager pip;
    private static HandlerThread worker;
    private static Handler workerHandler;

    /** CPU 差分的上次快照（/proc/stat） */
    private static long[] lastCpu;              // [all, idle+iowait]，整体
    private static long[] lastCoreAll;          // 每核差分用
    /** GPU 节点：探测到哪条用哪条（缓存路径，避免每秒全扫） */
    private static String gpuNode;
    private static boolean gpuBusyPair;         // gpubusy 是 busy/total 两列，需差分
    private static long gpuLastBusy, gpuLastTotal;

    private PerfOverlay() { }

    /** MainActivity 起来时调用：按设置开关决定是否显示（引用每次换成新面板） */
    public static synchronized void apply(Context ctx, PipManager manager) {
        pip = manager;
        appCtx = ctx.getApplicationContext();
        if (Prefs.perfOverlay(ctx)) show(ctx);
    }

    public static synchronized boolean isShown() {
        return shown;
    }

    public static synchronized void show(Context ctx) {
        if (shown) return;
        try {
            Context app = ctx.getApplicationContext();
            appCtx = app;
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            TextView tv = new TextView(app);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(10);
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(0xB3000000);
            int pad = Ui.dp(app, 5);
            tv.setPadding(pad, pad, pad, pad);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = Ui.dp(app, 8);
            lp.y = Ui.dp(app, 8);
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;

            wm.addView(tv, lp);
            view = tv;
            shown = true;
            startLoop();
            Log.i(TAG, "性能悬浮窗已显示");
        } catch (Throwable t) {
            Log.w(TAG, "性能悬浮窗显示失败：" + t);
            view = null;
            shown = false;
        }
    }

    public static synchronized void hide() {
        if (!shown) return;
        try {
            if (wm != null && view != null) wm.removeView(view);
        } catch (Throwable ignored) {
        }
        view = null;
        shown = false;
        stopLoop();
        Log.i(TAG, "性能悬浮窗已隐藏");
    }

    // ------------------------------------------------------------ 采样循环

    private static void startLoop() {
        if (worker != null) return;
        worker = new HandlerThread("pipdesk-perf-overlay");
        worker.setPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        worker.start();
        workerHandler = new Handler(worker.getLooper());
        workerHandler.postDelayed(PerfOverlay::tick, 300);
    }

    private static void stopLoop() {
        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.quitSafely();
            worker = null;
            workerHandler = null;
        }
        lastCpu = null;
        lastCoreAll = null;
    }

    private static void tick() {
        String text;
        try {
            text = sample();
        } catch (Throwable t) {
            text = "perf err: " + t.getClass().getSimpleName();
        }
        final String s = text;
        TextView tv = view;
        if (tv != null) {
            tv.post(() -> {
                try {
                    tv.setText(s);
                } catch (Throwable ignored) {
                }
            });
        }
        Handler h = workerHandler;
        if (h != null) h.postDelayed(PerfOverlay::tick, 1000);
    }

    private static String sample() {
        StringBuilder b = new StringBuilder(320);

        // CPU：整体 + 每核（读不到差分就只显示整体）
        int cpu = cpuUsage();
        b.append("CPU ").append(cpu < 0 ? "-" : cpu + "%");
        int[] cores = coreUsages();
        if (cores != null) {
            for (int i = 0; i < cores.length; i++) {
                if (i % 4 == 0) b.append('\n').append("  c");
                b.append(i).append(':').append(cores[i] < 0 ? "-" : cores[i]);
                if (i < cores.length - 1 && (i + 1) % 4 != 0) b.append(' ');
            }
        }
        b.append('\n');

        b.append("GPU ").append(gpuUsage()).append('\n');

        long[] mem = sysMem();
        if (mem != null) {
            b.append("RAM ").append(fmtM(mem[0])).append('/').append(fmtM(mem[1])).append('M');
            b.append(" (").append(mem[1] <= 0 ? "-" : mem[0] * 100 / mem[1]).append("%)\n");
        }
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;
        b.append("APP pss ").append(pssMb()).append("M")
                .append(" heap ").append(heapMb).append('/').append(maxMb).append("M\n");
        b.append("thr ").append(Thread.activeCount())
                .append(" fd ").append(fdCount())
                .append(" dsp ").append(displayCount()).append('\n');
        PipManager p = pip;
        if (p != null) {
            for (int i = 0; i < p.count(); i++) {
                PipPane pane = p.pane(i);
                if (pane == null) continue;
                b.append("p").append(i).append('=')
                        .append(pane.pkg() == null ? "idle" : pane.pkg())
                        .append(" f=").append(pane.frames())
                        .append(' ').append(pane.msSinceLastFrame()).append("ms")
                        .append(" st=").append(pane.displayState()).append('\n');
            }
        }
        int len = b.length();
        return len > 0 && b.charAt(len - 1) == '\n' ? b.substring(0, len - 1) : b.toString();
    }

    // ------------------------------------------------------------ CPU（全设备通用：/proc/stat）

    /** /proc/stat 第一行 → {全部 jiffies, idle+iowait}，解析失败返回 null */
    private static long[] readCpuLine(String line) {
        if (line == null || !line.startsWith("cpu")) return null;
        String[] parts = line.trim().split("\\s+");
        long all = 0, idle;
        try {
            for (int i = 1; i < parts.length; i++) all += Long.parseLong(parts[i]);
            idle = Long.parseLong(parts[4]);
            if (parts.length > 5) idle += Long.parseLong(parts[5]);     // iowait 也算空闲
        } catch (Throwable t) {
            return null;
        }
        return new long[]{all, idle};
    }

    private static int pct(long dAll, long dIdle) {
        if (dAll <= 0) return -1;
        return (int) Math.max(0, Math.min(100, (dAll - dIdle) * 100 / dAll));
    }

    /** 系统整体 CPU 占用（相邻两次采样差分；首次返回 -1） */
    private static int cpuUsage() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            long[] cur = readCpuLine(r.readLine());
            if (cur == null) return -1;
            if (lastCpu == null) {
                lastCpu = cur;
                return -1;
            }
            int v = pct(cur[0] - lastCpu[0], cur[1] - lastCpu[1]);
            lastCpu = cur;
            return v;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 每核 CPU 占用：cpu0..cpuN 差分（核数变化/读不到会自动重建） */
    private static int[] coreUsages() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            int n = 0;
            long[] cur = null;
            StringBuilder idx = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu") || line.startsWith("cpu ")) {
                    if (n > 0) break;
                    continue;
                }
                long[] v = readCpuLine(line);
                if (v == null) break;
                if (cur == null) cur = new long[16 * 2];
                if (n * 2 + 1 >= cur.length) break;
                cur[n * 2] = v[0];
                cur[n * 2 + 1] = v[1];
                idx.append(n).append(',');
                n++;
            }
            if (n == 0) return null;
            int[] out = new int[n];
            if (lastCoreAll == null || lastCoreAll.length != n * 2) {
                lastCoreAll = new long[n * 2];
                System.arraycopy(cur, 0, lastCoreAll, 0, n * 2);
                return null;                    // 首次只记快照
            }
            for (int i = 0; i < n; i++) {
                out[i] = pct(cur[i * 2] - lastCoreAll[i * 2],
                        cur[i * 2 + 1] - lastCoreAll[i * 2 + 1]);
                lastCoreAll[i * 2] = cur[i * 2];
                lastCoreAll[i * 2 + 1] = cur[i * 2 + 1];
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ GPU（多厂商内核节点探测）

    /** 已知 GPU 节点形态：单列百分比 / busy total 两列 / 文本行里带 load xx% */
    private static String probeGpuNode() {
        // ① Adreno（高通）：/sys/class/kgsl/kgsl-N/gpubusy —— 扫目录，不写死 kgsl-3d0
        File kgsl = new File("/sys/class/kgsl");
        String[] dirs = kgsl.list();
        if (dirs != null) {
            for (String d : dirs) {
                File f = new File(kgsl, d + "/gpubusy");
                String v = readFirstLine(f.getPath());
                if (v != null && v.trim().split("\\s+").length >= 2) {
                    gpuNode = f.getPath();
                    gpuBusyPair = true;
                    return gpuNode;
                }
            }
        }
        // ② Mali 通用导出：/sys/kernel/gpu/ 下任何含 busy/load/util 的数字文件
        File kdir = new File("/sys/kernel/gpu");
        String[] names = kdir.list((dir, name) ->
                name.contains("busy") || name.contains("load") || name.contains("util"));
        if (names != null) {
            for (String name : names) {
                File f = new File(kdir, name);
                String v = readFirstLine(f.getPath());
                if (v != null && !v.replaceAll("[^0-9]", "").isEmpty()) {
                    gpuNode = f.getPath();
                    return gpuNode;
                }
            }
        }
        // ③ Mali 设备节点：/sys/class/misc/mali0..N/device/utilisation（扫目录）
        File misc = new File("/sys/class/misc");
        String[] mdirs = misc.list((dir, name) -> name.startsWith("mali"));
        if (mdirs != null) {
            for (String d : mdirs) {
                File f = new File(misc, d + "/device/utilisation");
                String v = readFirstLine(f.getPath());
                if (v != null && !v.replaceAll("[^0-9]", "").isEmpty()) {
                    gpuNode = f.getPath();
                    return gpuNode;
                }
            }
        }
        // ④ 通用 Linux 形态：/sys/class/drm/card0/device/gpu_busy_percent
        String v = readFirstLine("/sys/class/drm/card0/device/gpu_busy_percent");
        if (v != null && !v.replaceAll("[^0-9]", "").isEmpty()) {
            gpuNode = "/sys/class/drm/card0/device/gpu_busy_percent";
            return gpuNode;
        }
        // ⑤ MTK：/proc/gpustat 文本里带 "GPU load xx%"
        if (new File("/proc/gpustat").canRead()) {
            gpuNode = "/proc/gpustat";
            return gpuNode;
        }
        return null;                            // 一条都读不到：下个采样周期重试
    }

    private static String gpuUsage() {
        String node = gpuNode;
        if (node == null) node = probeGpuNode();
        if (node == null) return "-";
        try {
            if ("/proc/gpustat".equals(node)) {
                try (BufferedReader r = new BufferedReader(new FileReader(node))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf("load");
                        if (i >= 0) {
                            String num = line.substring(i + 4).replaceAll("[^0-9]", "");
                            if (!num.isEmpty()) return Integer.parseInt(num) + "%";
                        }
                    }
                }
                return "-";
            }
            try (BufferedReader r = new BufferedReader(new FileReader(node))) {
                String line = r.readLine();
                if (line == null) return "-";
                line = line.trim();
                if (gpuBusyPair) {              // gpubusy：busy/total 两列，跨采样差分
                    String[] two = line.split("\\s+");
                    if (two.length < 2) return "-";
                    long busy = parseLong(two[0]), total = parseLong(two[1]);
                    long dB = busy - gpuLastBusy, dT = total - gpuLastTotal;
                    gpuLastBusy = busy;
                    gpuLastTotal = total;
                    if (dT <= 0) return "-";
                    return Math.max(0, Math.min(100, dB * 100 / dT)) + "%";
                }
                String num = line.replaceAll("[^0-9]", "");
                return num.isEmpty() ? "-" : Math.min(100, parseLong(num)) + "%";
            }
        } catch (Throwable t) {
            gpuNode = null;                     // 节点失效，下个周期重新探测
            return "-";
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 内存（/proc/meminfo + ActivityManager 兜底）

    /** 系统内存 {used(KB), total(KB)}；/proc/meminfo 读不到退回 ActivityManager */
    private static long[] sysMem() {
        long[] m = memFromProc();
        if (m != null) return m;
        try {
            Context c = appCtx;
            if (c == null) return null;
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am == null) return null;
            am.getMemoryInfo(mi);
            long total = mi.totalMem / 1024L, avail = mi.availMem / 1024L;
            if (total <= 0) return null;
            return new long[]{Math.max(0, total - avail), total};
        } catch (Throwable t) {
            return null;
        }
    }

    private static long[] memFromProc() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1, avail = -1;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    avail = parseKb(line);
                    break;
                }
            }
            // 老内核没有 MemAvailable：用 MemFree + Buffers + Cached 估算
            if (total > 0 && avail < 0) {
                avail = memFallbackAvail(r);
            }
            if (total <= 0 || avail < 0) return null;
            return new long[]{Math.max(0, total - avail), total};
        } catch (Throwable t) {
            return null;
        }
    }

    private static long memFallbackAvail(BufferedReader r) {
        long free = -1, buffers = 0, cached = 0;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemFree:")) free = parseKb(line);
                else if (line.startsWith("Buffers:")) buffers = parseKb(line);
                else if (line.startsWith("Cached:") && !line.startsWith("CachedSwap")
                        && free >= 0) {
                    cached = parseKb(line);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (free < 0) return -1;
        return free + Math.max(0, buffers) + Math.max(0, cached);
    }

    private static long parseKb(String line) {
        String num = line.replaceAll("[^0-9]", "");
        return num.isEmpty() ? -1 : Long.parseLong(num);
    }

    private static long pssMb() {
        try {
            return android.os.Debug.getPss() / 1024L;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int fdCount() {
        try {
            String[] list = new File("/proc/self/fd").list();
            return list == null ? -1 : list.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int displayCount() {
        Context c = appCtx;
        if (c == null) return -1;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            return dm == null ? -1 : dm.getDisplays().length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String fmtM(long kb) {
        long m = kb / 1024L;
        if (m >= 1024) {
            float g = m / 1024f;
            return (g >= 10 ? String.format("%.0fG", g) : String.format("%.1fG", g));
        }
        return String.valueOf(m);
    }
}
