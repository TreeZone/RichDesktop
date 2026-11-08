package com.cz.richdesk;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 音频「控制面」探针。
 *
 * 为什么需要它（车机现场）：点蓝牙播放按钮（获取音频焦点 + 读音频流）后，
 * 三块画中画的画面同时冻住、触摸无响应，但音乐播放完全正常。
 * 这个组合指向音频子系统的两层分裂：
 *   - 数据面 AudioFlinger 的 playback thread —— 一直正常，所以音乐在响；
 *   - 控制面 AudioService（在 system_server 里）—— 处理焦点变更/路由/设备切换
 *     是**全局串行**的，一旦在蓝牙路由切换时卡住，所有进程只要在主线程
 *     调 AudioManager 就会阻塞在 binder 上（binder 调用默认无超时）。
 * 焦点变更又是一次全局广播，于是三块屏的应用同时被挂住 → 看起来"一起卡死"。
 *
 * 这台车机上没有 adb，所以用探针代替 dumpsys：
 * 后台线程周期性调 AudioManager.isMusicActive()（走 AudioService 的 binder，
 * 无副作用），测量往返耗时。
 *   - 正常：几毫秒；
 *   -  > SLOW_MS：记一条预警；
 *   -  > BLOCK_MS：判定 AudioService 被阻塞，把耗时 + 全部画中画状态写进日志。
 * 日志里出现 AUDIO-BLOCK，就等于在设备上拿到了"dumpsys audio 会挂住"的等价证据。
 */
public final class AudioProbe {

    private static final String TAG = "PipDesk";

    private static final long PERIOD_MS = 15_000L;    // 探测周期（6s → 15s：只读探针，没必要那么密）
    private static final long SLOW_MS = 600L;        // 慢调用预警阈值
    private static final long BLOCK_MS = 2500L;      // 判定阻塞阈值
    private static final long WARN_GAP_MS = 30_000L; // 同类告警最小间隔
    private static final int MAX_CALLERS = 6;        // 卡死的调用线程会被抛弃，最多再起几个

    private static volatile boolean started;
    private static AudioManager am;
    private static volatile PipManager pip;

    /** 当前正在进行的调用开始时刻；0 表示空闲 */
    private static volatile long callStart;
    private static volatile long lastWarn;
    private static volatile int callers;

    private AudioProbe() { }

    public static synchronized void start(Context ctx, PipManager manager) {
        // 引用每次都要刷新：recreate 后是新面板，旧的会把整个旧视图树挂住
        am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        pip = manager;
        if (started) return;
        started = true;
        if (am == null) return;
        newCaller();
        Thread mon = new Thread(AudioProbe::monitor, "pipdesk-audio-mon");
        mon.setDaemon(true);
        mon.start();
        Log.i(TAG, "音频控制面探针已启动：每 " + PERIOD_MS + "ms 测一次 AudioService 往返耗时");
    }

    /** Activity 销毁：断开面板引用 */
    public static void detach() {
        pip = null;
    }

    /** 一个调用线程：卡住就废掉，由监控线程补一个新的 */
    private static void newCaller() {
        if (callers >= MAX_CALLERS) return;
        callers++;
        Thread t = new Thread(AudioProbe::callerLoop, "pipdesk-audio-call-" + callers);
        t.setDaemon(true);
        t.start();
    }

    private static void callerLoop() {
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            long t0 = SystemClock.uptimeMillis();
            callStart = t0;
            try {
                am.isMusicActive();   // 走 IAudioService.isMusicActive()，只读，无副作用
            } catch (Throwable ignored) {
            }
            long cost = SystemClock.uptimeMillis() - t0;
            callStart = 0;
            if (cost >= SLOW_MS) {
                Log.w(TAG, "AUDIO 调用偏慢 " + cost + "ms（AudioService 可能正在处理路由切换）");
            }
        }
    }

    /** 监控：调用发起后迟迟不返回 = AudioService 被锁住 */
    private static void monitor() {
        while (true) {
            try {
                Thread.sleep(700L);
                long start = callStart;
                if (start == 0) continue;
                long waited = SystemClock.uptimeMillis() - start;
                if (waited < BLOCK_MS) continue;
                long now = SystemClock.uptimeMillis();
                if (now - lastWarn < WARN_GAP_MS) continue;
                lastWarn = now;

                String state = paneState();
                FileLog.append("AUDIO-BLOCK AudioService 调用已阻塞 " + waited + "ms"
                        + "（isMusicActive 未返回）\n"
                        + "  画中画: " + state + "\n"
                        + "  判断: 音频控制面被锁，屏内应用若在主线程调 AudioManager 会一起挂住\n"
                        + "  @ " + FileLog.nowStamp());
                Log.e(TAG, "AUDIO-BLOCK " + waited + "ms  " + state);
                // 旧调用线程已经废了（binder 无超时），补一个新的继续观察是否恢复
                newCaller();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static String paneState() {
        PipManager p = pip;
        if (p == null) return "(pip=null)";
        StringBuilder b = new StringBuilder("panes=").append(p.count());
        for (int i = 0; i < p.count(); i++) {
            PipPane pane = p.pane(i);
            if (pane == null) continue;
            b.append(" p").append(i).append('=')
                    .append(pane.pkg() == null ? "idle" : pane.pkg())
                    .append(":f").append(pane.frames())
                    .append(":st").append(pane.displayState());
        }
        return b.toString();
    }
}
