package com.cz.richdesk;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 系统按键注入：返回 / 主页。
 *
 * 为什么需要它：这个桌面把系统导航栏藏了起来（车机上导航栏还会挡住画中画），
 * 全屏打开的应用就没有"返回 / 回主屏"的入口了。这里直接往系统里注一次按键，
 * 效果和按实体/导航栏的返回键完全一致（走的是同一个 InputManager）。
 *
 * 【带 displayId 的定向注入（0.12.4 新增，修 Dock 返回键失效）】
 *
 * 以前 back() 只做全局注入：不带 displayId 的 KeyEvent 会被 InputDispatcher
 * 送进「焦点屏（主屏）的焦点窗口」——也就是桌面自己。而用户点 Dock 的那一刻，
 * 手指已经把窗口焦点抢给了桌面（屏内应用收到 onWindowFocusChanged(false)），
 * 于是返回键永远落在桌面身上，等于没按。
 *
 * 修法：给 KeyEvent 带上目标虚拟屏的 displayId 再注入。Android 11+ 的
 * InputDispatcher 按 displayId 维护「每块屏自己的焦点窗口」，注入键事件时
 * 用的是事件里 displayId 那块屏的焦点窗口，而不是全局焦点屏 ——
 * 所以就算桌面抢走了全局焦点，键还是能进到画中画的应用里。
 *
 * 兜底链（每层失败才走下一层，全部打日志）：
 *   1) 进程内反射：InputManager.injectInputEvent + KeyEvent.setDisplayId；
 *      注入后回读 getDisplayId 校验，防止"设不上却报成功"的静默失败；
 *   2) shell `input -d <id> keyevent <key>`（本进程 uid 1000 子进程，
 *      INJECT_EVENTS 可用；TouchBridge 的 cmd 通道同款）；
 *   3) 全局注入（老行为）。
 *
 * 权限：需要 INJECT_EVENTS（signature|privileged 级），manifest 里已声明，
 * 装进 priv-app / uid 1000 后生效。
 */
public class Keys {

    private static final String TAG = "PipDesk";

    /**
     * 按键注入统一走这条单线程队列（旧实现每次按键 new 一个线程，连点/长按重复
     * 触发时会堆出多条线程，还可能各自阻塞在 shell 兜底上）。
     */
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pipdesk-key");
                t.setPriority(Thread.NORM_PRIORITY + 1);
                t.setDaemon(true);
                return t;
            });

    private Keys() { }

    public static void back(Context c) {
        send(c, KeyEvent.KEYCODE_BACK);
    }

    public static void home(Context c) {
        send(c, KeyEvent.KEYCODE_HOME);
    }

    /** 最近任务：和导航栏那个方块键同一个 keyCode */
    public static void recents(Context c) {
        send(c, KeyEvent.KEYCODE_APP_SWITCH);
    }

    /**
     * 音量加/减。返回 {当前音量, 最大值}，拿不到就是 null（调用方用来显示音量条）。
     * 优先走 AudioManager（带系统的音量条、按音频流语义走，车机上也生效）；
     * 拿不到 AudioManager 才退回按键注入。
     */
    public static int[] volume(Context c, boolean up) {
        if (c == null) return null;
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        up ? android.media.AudioManager.ADJUST_RAISE
                                : android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.FLAG_SHOW_UI
                                | android.media.AudioManager.FLAG_PLAY_SOUND);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                Log.i(TAG, "音量" + (up ? "+" : "-") + " → " + cur + "/" + max);
                // 有些车机把 FLAG_SHOW_UI 也吞掉了，桌面自己再浮一个提示（见 MainActivity）
                return new int[]{cur, max};
            }
        } catch (Throwable t) {
            Log.w(TAG, "AudioManager 调音量失败：" + t.getClass().getSimpleName());
        }
        send(c, up ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN);
        return null;
    }

    private static volatile boolean dt2wTried;

    /**
     * 打开「双击屏幕唤醒」。
     *
     * Android 没有统一的这个开关，各家放在 System/Secure/Global 的不同键名里，
     * 这里把常见键名挨个试一遍（键不存在时 getInt 返回 -1，直接跳过），
     * 最后再试几个常见 sysfs 节点。一个都没成功就沿用系统设置。
     */
    public static void enableDoubleTapToWake(Context c) {
        if (dt2wTried) return;
        dt2wTried = true;
        String[] keys = {"double_tap_to_wake", "double_tap_screen_wake",
                "double_click_wake", "dt2w", "double_tap_wake"};
        boolean ok = false;
        for (String k : keys) {
            ok |= putSetting(c, android.provider.Settings.System.class, k);
            ok |= putSetting(c, android.provider.Settings.Secure.class, k);
            ok |= putSetting(c, android.provider.Settings.Global.class, k);
        }
        // 部分平台（MTK / 高通）双击唤醒落在 sysfs 上
        for (String p : new String[]{"/sys/android_touch/wake_gestures",
                "/sys/devices/virtual/touch/tp_wake/dt2w", "/sys/class/touchscreen/wake_gesture"}) {
            if (Shell.runSync("echo 1 > " + p + " 2>/dev/null")) ok = true;
        }
        Log.i(TAG, ok ? "双击唤醒已开启" : "双击唤醒：系统未提供可写开关，沿用原设置");
    }

    /** 设置项存在（getInt 不是 -1）才写；写成功返回 true */
    private static boolean putSetting(Context c, Class<?> cls, String key) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            Method get = cls.getMethod("getInt",
                    android.content.ContentResolver.class, String.class, int.class);
            Method put = cls.getMethod("putInt",
                    android.content.ContentResolver.class, String.class, int.class);
            int cur = (Integer) get.invoke(null, cr, key, -1);
            if (cur < 0) return false;
            if (cur == 1) return true;
            return Boolean.TRUE.equals(put.invoke(null, cr, key, 1));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把按键定向发到指定虚拟屏（发给该屏上正在运行的应用）。
     * displayId < 0 时退回全局注入。
     */
    public static void backOnDisplay(Context c, int displayId) {
        if (displayId < 0) {
            back(c);
            return;
        }
        send(c, KeyEvent.KEYCODE_BACK, displayId);
    }

    /** 后台注入一次按键（全局；失败自动降级，不会抛到调用方） */
    public static void send(final Context ctx, final int keyCode) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            if (inject(app, keyCode, android.view.Display.DEFAULT_DISPLAY)) {
                Log.i(TAG, "按键注入成功 keyCode=" + keyCode);
                return;
            }
            Log.w(TAG, "按键注入失败，改走 shell keyCode=" + keyCode);
            Shell.runSync("input keyevent " + keyCode);
        });
    }

    /**
     * 后台注入一次按键到指定 display。
     *
     * 【0.21.0 关键修复：先点一下目标屏再发键】
     * 这台 ROM 只有「顶层焦点屏」的窗口是可聚焦的（dumpsys input 里其余屏一律
     * FocusRequests result=NOT_FOCUSABLE）。用户点 Dock 上的返回按钮那一刻，
     * 手指已经把焦点抢回主屏，所以直接注入到画中画屏的 BACK 没有任何窗口接收。
     * 现在的顺序是：触摸探针把焦点搬到目标屏（DOWN+CANCEL，不触发点击）→
     * 等 60ms 让 WMS/InputDispatcher 落位 → 注入按键。
     * 失败链：进程内反射（hidden API 被限）→ shell `input -d`（uid 1000 有 INJECT_EVENTS）。
     * **不再退回全局注入**：全局 BACK 只会打给桌面自己，等于误触。
     */
    public static void send(final Context ctx, final int keyCode, final int displayId) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            if (displayId < 0 || displayId == android.view.Display.DEFAULT_DISPLAY) {
                if (inject(app, keyCode, android.view.Display.DEFAULT_DISPLAY)) return;
                Shell.runSync("input keyevent " + keyCode);
                return;
            }
            // 1) 焦点探针：把这块虚拟屏变成「顶层焦点屏」
            try {
                TouchBridge.get(app).pokeFocus(displayId);
            } catch (Throwable t) {
                Log.w(TAG, "焦点探针异常：" + t.getClass().getSimpleName());
            }
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                return;
            }
            // 2) 进程内定向注入（hidden API 放行时最省事）
            if (inject(app, keyCode, displayId)) {
                Log.i(TAG, "按键定向注入成功 keyCode=" + keyCode + " d=" + displayId);
                releaseFocus(app, displayId);
                return;
            }
            // 3) shell `input -d`（uid 1000 下 INJECT_EVENTS 可用；只打日志不吹"成功"，
            //    因为 input 命令把异常打在 stderr 上时退出码不一定是非 0）
            boolean ok = Shell.runSync("input -d " + displayId + " keyevent " + keyCode);
            Log.i(TAG, "shell -d 注入 keyCode=" + keyCode + " d=" + displayId + " ok=" + ok);
            releaseFocus(app, displayId);
        });
    }

    /**
     * 把「顶层焦点屏」还给主屏。
     *
     * 探针把焦点搬到画中画屏之后，焦点就**留在那儿**了 —— 而桌面自己在 0 屏，
     * 于是接下来桌面上的点击、单画里应用的点击全部失去接收窗口，
     * 用户看到的就是「单画打开 app 完全无法获得焦点 / 整个桌面点不动」。
     * 所以键注完立刻还一次，别让焦点悬在虚拟屏上。
     */
    private static void releaseFocus(Context app, int fromDisplay) {
        try {
            if (fromDisplay == android.view.Display.DEFAULT_DISPLAY) return;
            TouchBridge.get(app).pokeFocus(android.view.Display.DEFAULT_DISPLAY);
            Log.i(TAG, "焦点已还给主屏（原 d=" + fromDisplay + "）");
        } catch (Throwable t) {
            Log.w(TAG, "焦点归还失败：" + t.getClass().getSimpleName());
        }
    }

    /** 注入一次 DOWN+UP；displayId >= 0 时把事件定向到该屏 */
    private static boolean inject(Context app, int keyCode, int displayId) {
        try {
            Object svc = app.getSystemService(Context.INPUT_SERVICE);
            if (!(svc instanceof InputManager)) return false;
            InputManager im = (InputManager) svc;
            Method m = InputManager.class.getDeclaredMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            m.setAccessible(true);
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, keyCode, 0);
            if (displayId >= 0 && !setDisplayId(down, displayId)) {
                // 设不上 displayId 就注入 = 键会落到主屏焦点窗口（老 bug），宁可不做
                Log.w(TAG, "KeyEvent 无法携带 displayId，放弃本次注入 keyCode=" + keyCode);
                return false;
            }
            if (displayId >= 0) setDisplayId(up, displayId);
            // INJECT_EVENTS 只对"系统身份"放行，普通应用会抛 SecurityException —— 上面兜住
            boolean ok = Boolean.TRUE.equals(m.invoke(im, down, 0));
            ok &= Boolean.TRUE.equals(m.invoke(im, up, 0));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "按键注入异常：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 给 KeyEvent 设 displayId（隐藏 API：先试 setDisplayId 方法，再试 mDisplayId 字段），
     * 设完回读校验。返回 false 表示这台 ROM 上设不上 —— 调用方必须放弃定向注入。
     */
    private static boolean setDisplayId(KeyEvent e, int displayId) {
        try {
            Method m = KeyEvent.class.getDeclaredMethod("setDisplayId", int.class);
            m.setAccessible(true);
            m.invoke(e, displayId);
            return readDisplayId(e) == displayId;
        } catch (Throwable ignored) { }
        try {
            Field f = KeyEvent.class.getDeclaredField("mDisplayId");
            f.setAccessible(true);
            f.setInt(e, displayId);
            return readDisplayId(e) == displayId;
        } catch (Throwable ignored) { }
        return false;
    }

    /** 回读 displayId（读不到返回 Integer.MIN_VALUE，当作"设不上"处理） */
    private static int readDisplayId(KeyEvent e) {
        try {
            Method m = KeyEvent.class.getDeclaredMethod("getDisplayId");
            m.setAccessible(true);
            Object r = m.invoke(e);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) { }
        try {
            Field f = KeyEvent.class.getDeclaredField("mDisplayId");
            f.setAccessible(true);
            return f.getInt(e);
        } catch (Throwable ignored) { }
        return Integer.MIN_VALUE;
    }
}
