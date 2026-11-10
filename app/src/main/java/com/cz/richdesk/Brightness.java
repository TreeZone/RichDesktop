package com.cz.richdesk;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 按「日出日落 + 天气」自动调屏幕亮度。
 *
 * 取值顺序（越靠前越优先）：
 *   1) 日落后（勾了「跟随日出日落」）→ 夜间亮度
 *   2) 阴/雾/雨雪（勾了「跟随天气」）→ 阴天亮度
 *   3) 其余                          → 白天亮度
 *
 * 夜间为什么排天气前面：晚上不管阴不阴都得暗，否则一阴天反而更亮，很刺眼。
 *
 * 两个子开关互相独立（日出日落 / 天气），总开关关掉时这里什么都不做——
 * 有些车自带自动背光，用户不希望被抢。
 *
 * 写的是系统亮度（SCREEN_BRIGHTNESS），同时把亮度模式切成手动——否则系统
 * 自动亮度会马上把我们写的值盖掉。接管前会记下用户原本的模式/数值，
 * 关掉总开关时原样还回去。
 */
public class Brightness {

    private static final String TAG = "PipDesk";

    private Brightness() { }

    /** 当前应该用的亮度（0~255） */
    public static int target(Context c) {
        if (Prefs.brightUseSun(c) && SunTimes.isDarkNow(c, true)) {
            return Prefs.getBrightNight(c);
        }
        if (Prefs.brightUseWeather(c) && Weather.isCloudy(Prefs.getWeatherCode(c))) {
            return Prefs.getBrightCloudy(c);
        }
        return Prefs.getBrightDay(c);
    }

    /** 当前命中的是哪一档（日志/设置页文案用） */
    public static String reason(Context c) {
        if (Prefs.brightUseSun(c) && SunTimes.isDarkNow(c, true)) return "夜间";
        if (Prefs.brightUseWeather(c) && Weather.isCloudy(Prefs.getWeatherCode(c))) return "阴天";
        return "白天";
    }

    /** 亮度档位文案（设置页/日志用） */
    public static String describe(Context c) {
        if (!Prefs.brightAuto(c)) return "已关闭（不接管亮度）";
        return "现在按「" + reason(c) + "」用 " + target(c) + " / 255"
                + "；白天 " + Prefs.getBrightDay(c)
                + " / 夜间 " + Prefs.getBrightNight(c)
                + " / 阴天 " + Prefs.getBrightCloudy(c);
    }

    /**
     * 自动模式开着才生效。调用方：桌面 onResume、15s 定时器、设置页改完立即预览。
     * 写系统设置是轻量 binder 调用，可以直接在主线程做。
     */
    public static void applyIfAuto(Context c) {
        if (c == null || !Prefs.brightAuto(c)) return;
        apply(c, target(c));
    }

    public static void apply(Context c, int value) {
        int v = Math.max(Prefs.BRIGHT_MIN, Math.min(Prefs.BRIGHT_MAX, value));
        try {
            ContentResolver cr = c.getContentResolver();
            // 第一次接管前记下原本的状态，关掉总开关时好还原
            if (Prefs.getBrightSavedMode(c) < 0) {
                int mode = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
                Prefs.setBrightSaved(c, mode, cur);
                Log.i(TAG, "接管亮度前记录：mode=" + mode + " value=" + cur);
            }
            if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            }
            int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
            if (cur == v) return;      // 已经是这个值，别每 15 秒重复写一次
            boolean ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
            String old = lastLogged;
            lastLogged = reason(c) + v;
            // 只在「命中档位/数值」变化时记一行，避免刷屏
            if (!lastLogged.equals(old)) {
                Log.i(TAG, "亮度 → " + v + "（" + reason(c)
                        + "，日落后=" + SunTimes.isDarkNow(c, Prefs.brightUseSun(c))
                        + "，天气=" + Weather.text(Prefs.getWeatherCode(c))
                        + "，写入=" + ok + "）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "亮度写入失败（缺 WRITE_SETTINGS？）：" + t.getClass().getSimpleName());
        }
    }

    private static String lastLogged = "";

    /**
     * 关掉总开关时把亮度控制权还给系统：原来自动就恢复自动，原来手动就恢复原数值。
     */
    public static void restore(Context c) {
        int mode = Prefs.getBrightSavedMode(c);
        if (mode < 0) return;                    // 从没接管过，不用还原
        try {
            ContentResolver cr = c.getContentResolver();
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                Log.i(TAG, "已交还亮度控制权：恢复系统自动亮度");
            } else {
                int v = Prefs.getBrightSavedValue(c);
                if (v >= 0) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
                }
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Log.i(TAG, "已交还亮度控制权：恢复原数值 " + v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "还原亮度失败：" + t.getClass().getSimpleName());
        }
        Prefs.setBrightSaved(c, -1, -1);
        lastLogged = "";
    }
}
