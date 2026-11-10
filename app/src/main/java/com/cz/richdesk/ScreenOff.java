package com.cz.richdesk;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * 一键息屏。两条路径，安全性优先：
 *
 * 【真息屏】PowerManager.goToSleep（等价于按电源键，uid 1000 才有权限）
 *   唤醒：触摸/电源键；同时提前设一个兜底闹钟（默认 180 秒）——
 *   万一这台车机的触摸面板唤不醒系统，到点也会被强制点亮，不会「黑了回不来」。
 *
 * 【黑屏遮罩】不碰系统亮度、也不真关屏：往窗口最上层盖一张纯黑视图，
 *   任意触摸立即移除。功率上不如真息屏，但 100% 可恢复，作为兜底方案。
 *
 * 用户如果担心唤不醒，可以在设置里把「息屏方式」切成遮罩，或把兜底时间调长/关掉。
 */
public class ScreenOff {

    private static final String TAG = "PipDesk";
    private static final String CURTAIN_TAG = "pipdesk-sleep-curtain";
    static final String ACTION_GUARD = "com.cz.richdesk.SLEEP_GUARD";
    private static final int REQ_GUARD = 1024;

    private ScreenOff() { }

    /** 入口：按设置选择息屏方式，真息屏失败自动退回避罩（绝不留下黑屏回不来的状态） */
    public static void sleep(final MainActivity a) {
        if (a == null) return;
        if (Prefs.getSleepMode(a) == 1) {
            showCurtain(a);
            return;
        }
        // 打开双击唤醒要跑几条 shell，别卡主线程；关屏本身也可以在后台线程调
        new Thread(() -> {
            if (!realSleep(a)) a.runOnUiThread(() -> showCurtain(a));
        }, "pipdesk-sleep").start();
    }

    // ------------------------------------------------------------ 真息屏

    private static boolean realSleep(Context c) {
        try {
            // 先把「双击唤醒」打开：不然息屏后用户只能按实体键
            Keys.enableDoubleTapToWake(c);
            int guard = Prefs.getSleepGuardSec(c);
            if (guard > 0) scheduleGuard(c, guard);
            PowerManager pm = (PowerManager) c.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (pm == null) throw new IllegalStateException("没有 PowerManager");
            Method m = PowerManager.class.getMethod("goToSleep", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
            Log.i(TAG, "息屏：goToSleep 已调用（兜底 " + guard + " 秒后自动亮屏）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "goToSleep 不可用，改用黑屏遮罩：" + t.getClass().getSimpleName());
            cancelGuard(c);
            return false;
        }
    }

    private static PendingIntent guardIntent(Context c) {
        Intent i = new Intent(c, SleepGuardReceiver.class).setAction(ACTION_GUARD);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_GUARD, i, flags);
    }

    /** 兜底闹钟：到点由 SleepGuardReceiver 强制唤醒屏幕 */
    static void scheduleGuard(Context c, int sec) {
        try {
            AlarmManager am = (AlarmManager) c.getApplicationContext()
                    .getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + sec * 1000L;
            PendingIntent pi = guardIntent(c);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable t) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);   // 精确闹钟被限时退普通闹钟
            }
            Log.i(TAG, "兜底唤醒已计划：" + sec + " 秒后");
        } catch (Throwable t) {
            Log.w(TAG, "兜底唤醒计划失败：" + t.getClass().getSimpleName());
        }
    }

    /** 屏幕已经亮了就把闹钟撤掉，免得半夜吓一跳 */
    public static void cancelGuard(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getApplicationContext()
                    .getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(guardIntent(c));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ 黑屏遮罩

    public static void showCurtain(final Activity a) {
        if (a == null) return;
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag(CURTAIN_TAG) != null) return;

        final FrameLayout layer = new FrameLayout(a);
        layer.setTag(CURTAIN_TAG);
        layer.setBackgroundColor(Color.BLACK);
        layer.setClickable(true);

        // 极暗的提示：够看清但不会吸引小孩盯着屏幕
        final int needTaps = Prefs.getWakeTaps(a);
        final TextView hint = new TextView(a);
        hint.setText(needTaps == 1 ? "轻触屏幕恢复"
                : needTaps == 2 ? "双击屏幕恢复" : "三击屏幕恢复");
        hint.setTextColor(0xFF6B6B6B);
        hint.setTextSize(13);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.CENTER;
        layer.addView(hint, hl);

        final long shownAt = SystemClock.uptimeMillis();
        // 点击计数：要几次点击才亮屏由设置决定（单击/双击/三击）。
        // 600ms 内连着点才算同一组；超时重新从 1 计。
        final int[] taps = {0};
        final long[] lastTapAt = {0};
        layer.setOnTouchListener((v, e) -> {
            // 触发这次息屏的那一下还没完全结束，500ms 内不吃事件，避免瞬间又被自己点亮
            if (SystemClock.uptimeMillis() - shownAt < 500) return true;
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt[0] > 600) taps[0] = 0;
                lastTapAt[0] = now;
                taps[0]++;
                if (taps[0] >= needTaps) dismissCurtain(a);
            }
            return true;
        });
        decor.addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 遮罩期间保持屏幕常亮：不靠关屏，靠黑屏，所以一定能被触摸唤醒
        a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 状态栏也藏掉，整块屏幕才是全黑
        if (a instanceof MainActivity) ((MainActivity) a).sleepBars(true);

        // 提示 1.5 秒后浮现、再淡出，只在刚盖上的时候给个方向感
        hint.postDelayed(() -> hint.animate().alpha(0.55f).setDuration(300).start(), 1200);
        hint.postDelayed(() -> hint.animate().alpha(0f).setDuration(600).start(), 4200);

        Log.i(TAG, "息屏（安全遮罩）：全屏黑，" + needTaps + " 次点击恢复");
    }

    public static void dismissCurtain(Activity a) {
        if (a == null) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View v = decor.findViewWithTag(CURTAIN_TAG);
        if (v == null) return;
        decor.removeView(v);
        a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (a instanceof MainActivity) ((MainActivity) a).sleepBars(false);
        Log.i(TAG, "遮罩已移除，屏幕恢复");
    }

    public static boolean curtainShown(Activity a) {
        if (a == null) return false;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        return decor.findViewWithTag(CURTAIN_TAG) != null;
    }
}
