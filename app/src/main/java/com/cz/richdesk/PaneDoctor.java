package com.cz.richdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 画中画「听诊器」：盯着每块屏上的应用有没有 ANR，必要时自动重启把它救回来。
 *
 * 背景（车机实测）：系统蓝牙 HMI 这类应用在虚拟屏上适配不良，跑着跑着整个进程
 * 会卡死 —— 画面停在最后一帧、注入的触摸毫无反应，而且只坏自己那一块屏。
 * 这种卡死发生在应用进程内部（不是我们的渲染管线、也不是主线程），宿主侧无法
 * 修复，只能"重启进程"恢复（用户实测有效）。
 *
 * 系统应用改不了，那就在宿主侧兜底：应用一旦被判 ANR，就自动重启它。
 * 选 ANR 作为判据而不是"画面不动"是有意的 —— 静态页面本来就不出帧，
 * 用帧率判断会大量误杀；而 ANR 是系统给的确定结论（输入 5 秒无响应）。
 *
 * 防抖：
 *  - 同一个 pane 的同一次 ANR 状态 60 秒内只处理一次（ANR 状态会持续存在）；
 *  - 10 分钟内同一块屏最多自动重启 2 次，超过只记日志 —— 防止"一启动就崩"
 *    的应用被无限重启；
 *  - 应用正被「全屏打开」时（用户在主屏上用）不打断，只记日志。
 */
public final class PaneDoctor {

    private static final String TAG = "PipDesk";

    private static final long PERIOD_MS = 8000L;           // 巡检周期（4s → 8s：getProcessesInErrorState 是全校举的 binder 调用，没必要那么勤）
    private static final long SAME_STATE_GAP_MS = 60_000L; // 同一次 ANR 的不应期
    private static final long WINDOW_MS = 10 * 60_000L;    // 重启次数统计窗口
    private static final int MAX_AUTO_RESTART = 2;         // 窗口内最多自动重启几次

    private static volatile boolean started;
    private static volatile PipManager pip;
    private static Context app;
    private static Handler main;

    /** key = "pane/包名" → [上次处理时刻, 窗口内已重启次数] */
    private static final Map<String, long[]> STATE = new HashMap<>();

    private PaneDoctor() { }

    public static synchronized void start(Context ctx, PipManager manager) {
        // 引用每次都要更新：桌面 recreate 之后是新的一套面板，
        // 只在第一次赋值就会一直读旧面板（还把旧 Activity 的视图树钉在静态变量里）
        app = ctx.getApplicationContext();
        pip = manager;
        main = new Handler(Looper.getMainLooper());
        if (started) return;
        started = true;
        Thread t = new Thread(PaneDoctor::loop, "pipdesk-doctor");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "听诊器已启动：屏内应用 ANR 自动记录，必要时重启恢复");
    }

    /** Activity 销毁：断开引用 + 清掉统计状态，别把旧面板留在静态变量里 */
    public static void detach() {
        pip = null;
        app = null;
        synchronized (STATE) {
            STATE.clear();
        }
        synchronized (stall) {
            stall.clear();
        }
    }

    private static void loop() {
        int tick = 0;
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                tick();
                // 每 8 个周期（约 32s）打一条帧心跳：卡死时对照时间轴，
                // 就能看出"三块屏是同一时刻一起停帧"，还是各自陆续停的。
                if (++tick % 8 == 0) heartbeat();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 各画中画的累计出帧数 + 虚拟屏电平状态（F=帧数，st=屏状态，fps=距上次心跳的平均帧率） */
    private static void heartbeat() {
        PipManager p = pip;
        if (p == null || p.count() == 0) return;
        StringBuilder b = new StringBuilder("FRAMES");
        boolean any = false;
        long total = 0;
        for (int i = 0; i < p.count(); i++) {
            PipPane pane = p.pane(i);
            if (pane == null || pane.pkg() == null) continue;
            any = true;
            long f = pane.frames();
            total += f;
            b.append(" p").append(i).append('=').append(pane.pkg())
                    .append(":f").append(f)
                    .append(":st").append(pane.displayState());
        }
        if (!any) { hbFrames = -1; return; }
        long now = android.os.SystemClock.uptimeMillis();
        if (hbFrames >= 0 && total >= hbFrames && now > hbAt) {
            float fps = (total - hbFrames) * 1000f / (now - hbAt);
            b.append(" fps=").append(String.format(java.util.Locale.US, "%.1f", fps));
        }
        hbFrames = total;
        hbAt = now;
        Log.i(TAG, b.toString());
    }

    /** 上次心跳的累计帧数/时刻：两次相减就是这 32s 的平均帧率（换应用后计数回退则重置） */
    private static long hbFrames = -1;
    private static long hbAt;

    private static void tick() {
        PipManager p = pip;
        if (p == null || p.count() == 0) return;
        checkStall(p);
        List<ActivityManager.ProcessErrorStateInfo> list;
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            list = am.getProcessesInErrorState();
        } catch (Throwable t) {
            return;   // 个别 ROM 需要 REAL_GET_TASKS，拿不到就静默跳过
        }
        if (list == null) return;
        for (ActivityManager.ProcessErrorStateInfo e : list) {
            if (e == null || e.condition != ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING) {
                continue;
            }
            String base = basePkg(e.processName);
            int idx = p.indexOfPkg(base);
            if (idx < 0) continue;   // 不是画中画里的应用，与本产品无关
            handle(p, idx, base, e);
        }
    }

    private static void handle(final PipManager p, final int idx, final String pkg,
                               ActivityManager.ProcessErrorStateInfo e) {
        String key = idx + "/" + pkg;
        long now = SystemClock.elapsedRealtime();
        long lastAt;
        int tries;
        synchronized (STATE) {
            long[] st = STATE.get(key);
            if (st == null) {
                st = new long[]{0L, 0L};
                STATE.put(key, st);
            }
            if (now - st[0] > WINDOW_MS) st[1] = 0;     // 超出统计窗口，重新计数
            if (st[0] != 0 && now - st[0] < SAME_STATE_GAP_MS) return;
            st[0] = now;
            st[1]++;
            lastAt = st[0];
            tries = (int) st[1];
        }
        boolean restart = tries <= MAX_AUTO_RESTART;
        FileLog.append("ANR pane=" + idx + " pkg=" + pkg + " proc=" + e.processName
                + " 第" + tries + "次" + (restart ? " -> 自动重启" : " -> 已达上限，仅记录")
                + " @ " + FileLog.nowStamp());
        Log.w(TAG, "屏内应用无响应：" + e.processName + " (pane " + idx + ", 第 " + tries + " 次)"
                + (restart ? "，自动重启" : "，已达重启上限"));
        if (!restart) return;

        main.post(() -> {
            PipPane pane = p.pane(idx);
            if (pane == null || !pkg.equals(pane.pkg())) return;   // 期间被关掉/换掉了
            if (pane.isFullscreened()) {
                Log.i(TAG, "应用正在主屏全屏使用，跳过自动重启 " + pkg);
                return;
            }
            Log.i(TAG, "自动重启卡死应用 " + pkg + "，上次处理于 +" + lastAt);
            pane.restartApp();
        });
    }

    /** "com.foo.bar:remote" → "com.foo.bar" */
    private static String basePkg(String proc) {
        if (proc == null) return "";
        int i = proc.indexOf(':');
        return i > 0 ? proc.substring(0, i) : proc;
    }

    // ------------------------------------------------------------ 同时停帧检测

    private static final long STALL_MS = 12_000L;      // 单块屏多久没新帧算"停住"
    private static final long STALL_LOG_GAP_MS = 60_000L;

    /** pane index → [上次帧数, 帧数最后变化时刻, 进入停帧的时刻(0=未停)] */
    private static final Map<Integer, long[]> stall = new HashMap<>();
    private static long lastStallLog;

    /**
     * 检测"多块屏在同一时间一起停止出帧"。
     *
     * 为什么要专门抓这个：现场是"三块画中画同时冻住，但音乐正常"。
     * 单块屏停帧很常见（静态页面本来就不出帧，别误判），**多块同时停**才是异常，
     * 而且这个时刻能直接对上用户的操作（点蓝牙播放），是定位触发源最硬的证据。
     * 只写日志不自愈：静态页面容易误报，自愈交给 ANR / 手动重启。
     */
    private static void checkStall(PipManager p) {
        long now = SystemClock.uptimeMillis();
        int recentlyStopped = 0;
        StringBuilder detail = new StringBuilder();
        synchronized (stall) {
            for (int i = 0; i < p.count(); i++) {
                PipPane pane = p.pane(i);
                if (pane == null) continue;
                long f = pane.frames();
                long[] st = stall.get(i);
                if (st == null) {
                    stall.put(i, new long[]{f, now, 0L});
                    continue;
                }
                if (f != st[0]) {          // 有新帧：清掉停帧标记
                    st[0] = f;
                    st[1] = now;
                    st[2] = 0L;
                    continue;
                }
                if (pane.pkg() == null) continue;                       // 空闲屏不算
                if (!"ON".equals(pane.displayState())) continue;        // 屏被系统关了，另一类问题
                if (st[2] == 0L && now - st[1] > STALL_MS) st[2] = now;  // 刚停住
                if (st[2] == 0L) continue;
                if (now - st[2] > 45_000L) continue;                    // 太久远的不算"一起"
                recentlyStopped++;
                detail.append(" p").append(i).append('=').append(pane.pkg())
                        .append("(停于 ").append((now - st[2]) / 1000).append("s 前)");
            }
        }
        if (recentlyStopped < 2 || now - lastStallLog < STALL_LOG_GAP_MS) return;
        lastStallLog = now;
        FileLog.append("MULTI-STALL " + recentlyStopped + " 块屏在相近时刻一起停帧：" + detail
                + "  @ " + FileLog.nowStamp());
        Log.w(TAG, "MULTI-STALL " + recentlyStopped + " 块屏一起停帧 " + detail);
    }
}
