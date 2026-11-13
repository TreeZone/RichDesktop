package com.cz.richdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画中画守护：**所有屏同时冻住**时立刻恢复。
 *
 * 为什么只查「全部」：单块屏静止是常态（蓝牙页、设置页、停着的地图都不出帧），
 * 拿它当判据必然反复误杀；而车机上真正的故障是**全局焦点丢失** —— 系统把
 * 两三块屏的 activity 同时 stop 掉，画面一起停在最后一帧，触摸也没反应。
 * 「同时」本身就是这个故障最干净的指纹。
 *
 * 判据只看一个数字：每块屏的 {@link PipPane#lastFrameAt()}（最后一帧的时刻）。
 * 全员静止超过阈值 = 一次冻结事件。**没有事件时一次 binder 都不发**，稳态零成本。
 *
 * 一次事件只探测一次（一条线程、一次全系统 task 扫描，所有屏共用），然后：
 *   · task 还在本屏但 activity 被 stop → 只补 resume（画面接着走，用户看不出）；
 *   · task 被系统拽到主屏 → 删 task 在本屏重建（forceToDisplay 内部处理）。
 *
 * 为什么要「武装」（arm）：静态页面早就不出帧了，光看静止永远等不到「从动到静」
 * 那一刻。所以用户刚碰过画中画 / 刚打开应用 / 音频播放状态一变，就进入 10 秒
 * 快判窗口，窗口内静止 1.2 秒就探；窗口外用 500ms 的「静止起点」判据。
 */
public final class PaneGuard {

    private static final String TAG = "PipDesk";

    /** 巡检周期：只读几个 long，起来就是一次比较 */
    private static final long TICK_MS = 250L;
    /** 窗口外：全员静止多久算冻住（同时要求「刚开始静止」，见 tick） */
    private static final long FROZEN_MS = 500L;
    /** 窗口内（用户刚操作过）：等这么久还没帧才判，避免把静态页面当冻结 */
    private static final long FROZEN_ARMED_MS = 1_200L;
    /** 快判窗口长度 */
    private static final long ARMED_MS = 10_000L;
    /** 两次探测之间的最小间隔：静止页面偶尔出一帧会重开一个静止期，别被它带着狂探 */
    private static final long PROBE_GAP_MS = 5_000L;
    /** 发现「task 跑在另一块虚拟屏」时的回收节流 */
    private static final long RECLAIM_GAP_MS = 10_000L;

    private static volatile boolean started;
    private static volatile PipManager pip;
    private static volatile Context app;
    private static volatile boolean foreground = true;

    private static volatile long armedUntil;
    private static volatile String armedWhy = "";

    /** 上一次看到的「最新一帧的时刻」：变了就说明有新帧，静止期要重新开始 */
    private static long lastMaxFrameAt;
    /** 本次静止期是否已经探过 */
    private static boolean episodeProbed;
    private static long lastProbeAt;
    private static long lastReclaimAt;

    private static final Object WAKE = new Object();

    private PaneGuard() { }

    public static synchronized void start(Context ctx, PipManager manager) {
        // 引用每次都换新：桌面 recreate 之后是新一套面板，只赋一次会一直读旧面板
        app = ctx.getApplicationContext();
        pip = manager;
        if (started) return;
        started = true;
        Thread t = new Thread(PaneGuard::loop, "pipdesk-guard");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        watchPlayback();
        Log.i(TAG, "画中画守护已启动：全员静止 → 一次探测 → 无感恢复（稳态零轮询）");
    }

    /** Activity 销毁：断开引用，别把旧面板留在静态变量里 */
    public static void detach() {
        pip = null;
        app = null;
        synchronized (PaneGuard.class) {
            lastMaxFrameAt = 0;
            episodeProbed = false;
        }
    }

    /** 桌面是否在前台：不在前台时画中画本来就该停，别乱救 */
    public static void setForeground(boolean f) {
        foreground = f;
        if (!f) {
            synchronized (PaneGuard.class) {
                episodeProbed = false;
                lastMaxFrameAt = 0;
            }
        }
    }

    /**
     * 进入快判窗口。调用点都是「用户刚跟画中画交互过」或「播放状态变了」——
     * 这些正是焦点被系统重算、最容易把整屏拽掉的时刻。
     */
    public static void arm(String why) {
        long now = SystemClock.uptimeMillis();
        if (now < armedUntil) return;      // 已经在窗口里就不续期，避免窗口永久常开
        armedUntil = now + ARMED_MS;
        armedWhy = why;
        synchronized (WAKE) {
            WAKE.notifyAll();
        }
    }

    /**
     * 盯音频播放状态：现场两条必现路径（点蓝牙播放 / 手机首次连上后点「媒体」）
     * 都是「播放状态一变 → 焦点重算 → 屏内 activity 被 stop」。所以这一刻起武装。
     */
    private static void watchPlayback() {
        try {
            Context c = app;
            if (c == null) return;
            android.media.AudioManager am = (android.media.AudioManager)
                    c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.registerAudioPlaybackCallback(new android.media.AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<android.media.AudioPlaybackConfiguration> configs) {
                    arm("音频播放状态变化");
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "音频播放回调注册失败", t);
        }
    }

    private static void loop() {
        while (true) {
            synchronized (WAKE) {
                try {
                    WAKE.wait(TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            try {
                tick();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 判定

    private static void tick() {
        PipManager p = pip;
        if (p == null || !foreground) return;
        int n = p.count();
        if (n <= 0) return;

        long now = SystemClock.uptimeMillis();
        long maxFrame = 0;
        int live = 0;
        for (int i = 0; i < n; i++) {
            PipPane pane = p.pane(i);
            if (pane == null || pane.pkg() == null || pane.isFullscreened()) continue;
            live++;
            long f = pane.lastFrameAt();
            if (f > maxFrame) maxFrame = f;
        }
        if (live == 0) {                 // 没有正在跑的画中画，不用管
            episodeProbed = false;
            lastMaxFrameAt = 0;
            return;
        }
        if (maxFrame == 0) return;       // 一帧都还没来过（刚打开），等它先出图

        // 有新帧：静止期重新开始计数
        if (maxFrame != lastMaxFrameAt) {
            lastMaxFrameAt = maxFrame;
            episodeProbed = false;
            return;
        }

        long silent = now - maxFrame;
        boolean armed = now < armedUntil;
        if (silent < (armed ? FROZEN_ARMED_MS : FROZEN_MS)) return;
        if (episodeProbed) return;       // 本次静止期已探过，不再重复
        episodeProbed = true;
        if (now - lastProbeAt < PROBE_GAP_MS) return;
        lastProbeAt = now;

        probe(p, silent, armed ? armedWhy : "全员静止");
    }

    /**
     * 一次探测：所有屏共用一次 task 扫描 + 一次进程表扫描，然后只对真正需要的屏动作。
     * 跑在独立线程（binder 调用不占主线程，也不占守护线程的巡检节拍）。
     */
    private static void probe(final PipManager p, final long silent, final String why) {
        final List<PipPane> panes = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        for (int i = 0; i < p.count(); i++) {
            PipPane pane = p.pane(i);
            if (pane == null || pane.pkg() == null || pane.isFullscreened()) continue;
            panes.add(pane);
            pkgs.add(pane.pkg());
        }
        if (panes.isEmpty()) return;
        if (!foreground) return;

        new Thread(() -> {
            try {
                Context c = app;
                if (c == null) return;
                Map<String, Integer> disp = PipPane.taskDisplays(c, pkgs);
                Set<String> visible = visiblePkgs(c, pkgs);

                List<PipPane> needResume = new ArrayList<>();
                List<PipPane> needRebuild = new ArrayList<>();
                boolean dup = false;
                for (PipPane pane : panes) {
                    String pkg = pane.pkg();
                    if (pkg == null) continue;
                    Integer d = disp.get(pkg);
                    int ours = pane.displayId();
                    if (d == null) {
                        // 查不到 = 列表不全，别乱动（老实现也是「读不到就别救」）
                        continue;
                    }
                    if (d == ours) {
                        // task 在本屏：进程连 visible 都不是 → activity 被系统 stop 了
                        if (!visible.contains(pkg)) needResume.add(pane);
                        continue;
                    }
                    if (d == android.view.Display.DEFAULT_DISPLAY) {
                        needRebuild.add(pane);       // 被系统拽回主屏
                        continue;
                    }
                    dup = true;                      // 跑在另一块虚拟屏 = 重复桌面实例
                }

                if (dup && SystemClock.uptimeMillis() - lastReclaimAt > RECLAIM_GAP_MS) {
                    lastReclaimAt = SystemClock.uptimeMillis();
                    Log.w(TAG, "冻结探测：有 task 跑到别的虚拟屏，回收重复桌面实例");
                    MainActivity.reclaimDuplicateInstances();
                }

                if (needResume.isEmpty() && needRebuild.isEmpty()) {
                    // 全员静止但状态都正常 = 就是静态页面，什么都不做（这是常态）
                    return;
                }
                Log.w(TAG, "冻结探测：静止 " + silent + "ms，需补 resume=" + needResume.size()
                        + " 需重建=" + needRebuild.size() + "（" + why + "）");
                FileLog.append("FREEZE silent=" + silent + "ms resume=" + needResume.size()
                        + " rebuild=" + needRebuild.size() + " why=" + why
                        + " @ " + FileLog.nowStamp());
                for (PipPane pane : needResume) pane.reviveAllFrozen(why);
                for (PipPane pane : needRebuild) pane.reviveAllFrozen(why);
            } catch (Throwable t) {
                Log.w(TAG, "冻结探测失败：" + t);
            }
        }, "pipdesk-guard-probe").start();
    }

    /**
     * 这些包里哪些进程还是 visible/foreground。
     *
     * 为什么用它复核：这台车机的 ROM 对停在虚拟屏上的 task 恒报 numRunning=0，
     * 只信那个字段会把正常出图的应用判成"被 stop"，然后每隔几秒拉一次前台，
     * 每拉一次就是一次 pause/stop —— 画面反而被自己搅成"卡死"。
     */
    private static Set<String> visiblePkgs(Context c, List<String> pkgs) {
        Set<String> out = new HashSet<>();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return out;
            for (ActivityManager.RunningAppProcessInfo pi : am.getRunningAppProcesses()) {
                if (pi == null || pi.processName == null) continue;
                if (pi.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) continue;
                for (String pkg : pkgs) {
                    if (pi.processName.equals(pkg) || pi.processName.startsWith(pkg + ":")) {
                        out.add(pkg);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
