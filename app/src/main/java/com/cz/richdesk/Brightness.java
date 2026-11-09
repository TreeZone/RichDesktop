package com.cz.richdesk;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * 按「日出日落 + 天气」自动调屏幕亮度。
 *
 * 取值顺序（越靠前越优先）：
 *   阴天/雨雪  → 用户设的「阴天亮度」
 *   日落后     → 用户设的「夜间亮度」
 *   其余       → 用户设的「白天亮度」
 *
 * 「日落后」用 {@link SunTimes#isDarkNow(Context)}：能联网就按真实日落/日出，
 * 断网退回设置里的固定时段，所以这个功能不依赖网络也能用。
 *
 * 写的是系统亮度（SCREEN_BRIGHTNESS），同时把亮度模式切成手动——否则系统
 * 自动亮度会马上把我们写的值盖掉。
 */
public class Brightness {

    private static final String TAG = "PipDesk";

    private Brightness() { }

    /** 当前应该用的亮度（0~255） */
    public static int target(Context c) {
        if (Weather.isCloudy(Prefs.getWeatherCode(c))) return Prefs.getBrightCloudy(c);
        return SunTimes.isDarkNow(c) ? Prefs.getBrightNight(c) : Prefs.getBrightDay(c);
    }

    /** 亮度档位文案（设置页/日志用） */
    public static String describe(Context c) {
        return "白天 " + Prefs.getBrightDay(c)
                + " / 夜间 " + Prefs.getBrightNight(c)
                + " / 阴天 " + Prefs.getBrightCloudy(c);
    }

    /**
     * 自动模式开着才生效。调用方：桌面 onResume、定时器、设置页改完立即预览。
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
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
            if (cur == v) return;      // 已经是这个值，别每 15 秒重复写一次
            boolean ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
            Log.i(TAG, "亮度 → " + v + "（日落后=" + SunTimes.isDarkNow(c)
                    + " 天气=" + Weather.text(Prefs.getWeatherCode(c))
                    + " 写入=" + ok + "）");
        } catch (Throwable t) {
            Log.w(TAG, "亮度写入失败（缺 WRITE_SETTINGS？）：" + t.getClass().getSimpleName());
        }
    }
}
