package com.cz.richdesk;

import android.os.SystemClock;
import android.view.Choreographer;

/**
 * 桌面主线程（UI 线程）的 vsync 抖动统计。只在性能悬浮窗打开时运行。
 *
 * 为什么需要它：画中画是把虚拟屏画面当纹理画进桌面自己的窗口，纹理的绘制跑在
 * 主线程的绘制流程里。所以「屏内卡顿」有两个完全不同的来源：
 *   ① 屏内应用自己出帧慢 / 合成慢  → 主线程是顺的（UI 行正常），只有那块的 fps 低；
 *   ② 桌面主线程被占住（触摸注入、列表重载、信号回调…）→ UI 行会出现同样的
 *      200ms 级断档，同时所有画中画一起卡。
 * 有这一行就能一眼分开，不用瞎猜。
 *
 * 实现：Choreographer.postFrameCallback 每帧回调一次，量相邻两次回调的间隔。
 * 它只测量「主线程有没有按时跑到下一个 vsync」，不会额外触发绘制，对结果没有干扰。
 */
public final class UiJank {

    private static boolean running;
    private static long lastVsync;
    private static long winFrames;
    private static long winMaxGap;
    private static long winOver32;
    private static long winOver100;

    private UiJank() { }

    private static final Choreographer.FrameCallback CB = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            long now = SystemClock.uptimeMillis();
            synchronized (UiJank.class) {
                if (lastVsync > 0) {
                    long gap = now - lastVsync;
                    winFrames++;
                    if (gap > winMaxGap) winMaxGap = gap;
                    if (gap > 32) winOver32++;      // 掉 1 帧以上
                    if (gap > 100) winOver100++;    // 肉眼可见的顿
                }
                lastVsync = now;
            }
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** 必须在主线程调用（Choreographer 绑定当前线程） */
    public static void start() {
        if (running) return;
        running = true;
        synchronized (UiJank.class) {
            lastVsync = 0;
            winFrames = winMaxGap = winOver32 = winOver100 = 0;
        }
        Choreographer.getInstance().postFrameCallback(CB);
    }

    public static void stop() {
        running = false;
        synchronized (UiJank.class) {
            lastVsync = 0;
        }
    }

    /**
     * 取走本统计窗口（约 1 秒）的数据并清零：
     *   out[0] = 帧数（≈fps）  out[1] = 最大间隔 ms
     *   out[2] = >32ms 次数（掉帧）  out[3] = >100ms 次数（明显顿）
     */
    public static synchronized void take(long[] out) {
        out[0] = winFrames;
        out[1] = winMaxGap;
        out[2] = winOver32;
        out[3] = winOver100;
        winFrames = winMaxGap = winOver32 = winOver100 = 0;
    }
}
