package com.cz.richdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 息屏兜底：真息屏之后如果一直没人唤醒，到点强制把屏幕点亮。
 *
 * 为什么需要：goToSleep 是系统级关屏，如果这台车机的触摸面板唤不醒系统、
 * 用户又找不到电源键，桌面就「黑了回不来」了。这个闹钟是最后一道保险。
 */
public class SleepGuardReceiver extends BroadcastReceiver {

    private static final String TAG = "PipDesk";

    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager pm = (PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        if (pm.isInteractive()) {
            Log.i(TAG, "兜底唤醒：屏幕已经亮了，跳过");
            return;
        }
        boolean ok = false;
        try {
            Method m = PowerManager.class.getMethod("wakeUp", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "wakeUp 不可用：" + t.getClass().getSimpleName());
        }
        if (!ok) {
            // 老接口兜底：带 ACQUIRE_CAUSES_WAKEUP 的屏幕锁能直接点亮
            try {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "pipdesk:sleep-guard");
                wl.acquire(5000);
                wl.release();
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "兜底唤醒失败：" + t.getClass().getSimpleName());
            }
        }
        Log.w(TAG, "兜底唤醒：" + (ok ? "已强制亮屏" : "失败，请按电源键"));
    }
}
