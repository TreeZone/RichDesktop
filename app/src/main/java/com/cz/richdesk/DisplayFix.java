package com.cz.richdesk;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * 车机「75% 分辨率」检测与修复。
 *
 * 现象：系统被强制成比物理屏更小的逻辑分辨率（1440x3200 的屏跑 1080x2400），
 * 车机的合成层不会把它放大铺满，而是 1:1 贴在左上角 —— 右边和下面一整块黑，
 * 但触摸事件仍按物理屏坐标缩放投递，于是「画面缩小 + 点击错位」。
 *
 * 这不是本应用能画回来的：窗口缓冲区本身就只有 75% 那么大，右边那块像素
 * 根本不存在。能做的只有检测出来 + 帮用户把系统分辨率清回原生。
 *
 * 应用以 uid 1000 + WRITE_SECURE_SETTINGS 运行，可以直接调
 * IWindowManager.clearForcedDisplaySize；失败再退回 shell 的 wm size reset。
 */
public class DisplayFix {

    private static final String TAG = "PipDesk";

    private DisplayFix() { }

    /** 物理像素（未旋转）：{w, h} */
    public static int[] physical(Context c) {
        try {
            Display d = display(c);
            if (d == null) return null;
            Display.Mode m = d.getMode();
            if (m != null) return new int[]{m.getPhysicalWidth(), m.getPhysicalHeight()};
            Point p = new Point();
            d.getRealSize(p);
            return new int[]{p.x, p.y};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 系统当前实际使用的逻辑像素（被强制后就是被压小的那个） */
    public static int[] logical(Context c) {
        try {
            Display d = display(c);
            if (d == null) return null;
            Point p = new Point();
            d.getRealSize(p);
            return new int[]{p.x, p.y};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Display display(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        return dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    /** 逻辑 / 物理 的比例：1.0 原生，0.75 = 被压到 75% */
    public static float ratio(Context c) {
        int[] ph = physical(c), lo = logical(c);
        if (ph == null || lo == null) return 1f;
        int pw = Math.max(ph[0], ph[1]), phh = Math.min(ph[0], ph[1]);
        int lw = Math.max(lo[0], lo[1]), lh = Math.min(lo[0], lo[1]);
        if (pw <= 0 || phh <= 0) return 1f;
        return Math.min(lw / (float) pw, lh / (float) phh);
    }

    /** 是否被强制成非原生分辨率 */
    public static boolean overridden(Context c) {
        float r = ratio(c);
        return r < 0.97f || r > 1.03f;
    }

    public static String describe(Context c) {
        int[] ph = physical(c), lo = logical(c);
        if (ph == null || lo == null) return "读不到显示信息";
        String s = "物理屏 " + ph[0] + "x" + ph[1]
                + "，系统按 " + lo[0] + "x" + lo[1] + " 渲染";
        if (!overridden(c)) return s + "（原生 1:1，正常）";
        return s + "（" + Math.round(ratio(c) * 100) + "%）\n"
                + "画面会被车机 1:1 贴到左上角、右边留黑，触摸却按物理屏缩放 ——"
                + " 这是车机强制分辨率的已知问题，跟「屏内渲染分辨率」无关。";
    }

    /**
     * 清掉强制分辨率。成功也只是「发出请求」，系统重排要 1~2 秒。
     */
    public static boolean reset(Context c) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Method gs = wmg.getDeclaredMethod("getWindowManagerService");
            gs.setAccessible(true);
            Object iwm = gs.invoke(null);
            if (iwm != null) {
                Method clr = iwm.getClass().getMethod("clearForcedDisplaySize", int.class);
                clr.invoke(iwm, Display.DEFAULT_DISPLAY);
                Log.i(TAG, "已清除强制分辨率（clearForcedDisplaySize）");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearForcedDisplaySize 不可用：" + t.getClass().getSimpleName());
        }
        boolean ok = Shell.runSync("wm size reset");
        Log.i(TAG, "wm size reset -> " + ok);
        return ok;
    }
}
