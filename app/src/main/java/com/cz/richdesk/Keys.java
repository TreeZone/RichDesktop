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

    private Keys() { }

    public static void back(Context c) {
        send(c, KeyEvent.KEYCODE_BACK);
    }

    public static void home(Context c) {
        send(c, KeyEvent.KEYCODE_HOME);
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
        new Thread(() -> {
            if (inject(app, keyCode, android.view.Display.DEFAULT_DISPLAY)) {
                Log.i(TAG, "按键注入成功 keyCode=" + keyCode);
                return;
            }
            Log.w(TAG, "按键注入失败，改走 shell keyCode=" + keyCode);
            Shell.runSync("input keyevent " + keyCode);
        }, "pipdesk-key").start();
    }

    /**
     * 后台注入一次按键到指定 display。
     * 三层兜底：进程内定向反射 → shell `input -d` → 全局注入。
     * 返回的 boolean 只表示"已尝试"，结果看 logcat。
     */
    public static void send(final Context ctx, final int keyCode, final int displayId) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (displayId >= 0 && displayId != android.view.Display.DEFAULT_DISPLAY) {
                if (inject(app, keyCode, displayId)) {
                    Log.i(TAG, "按键定向注入成功 keyCode=" + keyCode + " d=" + displayId);
                    return;
                }
                Log.w(TAG, "定向注入失败，改走 shell -d keyCode=" + keyCode + " d=" + displayId);
                if (Shell.runSync("input -d " + displayId + " keyevent " + keyCode)) {
                    Log.i(TAG, "shell -d 注入成功 keyCode=" + keyCode + " d=" + displayId);
                    return;
                }
                Log.w(TAG, "shell -d 也失败，退回全局注入 keyCode=" + keyCode);
            }
            if (inject(app, keyCode, android.view.Display.DEFAULT_DISPLAY)) {
                Log.i(TAG, "按键注入成功 keyCode=" + keyCode);
                return;
            }
            Log.w(TAG, "按键注入失败，改走 shell keyCode=" + keyCode);
            Shell.runSync("input keyevent " + keyCode);
        }, "pipdesk-key").start();
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
