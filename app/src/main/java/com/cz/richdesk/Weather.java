package com.cz.richdesk;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 天气（Open-Meteo 公开接口，免费、不需要 API key）。
 *
 * 两个用途：
 *   1) Dock 右端功能区显示「多云 12°」；
 *   2) 亮度自动调节里判断「阴天」，用阴天亮度值。
 *
 * 位置与日落查询共用 {@link SunTimes#location(Context)}，保证两边说的是同一个地方。
 * 缓存 30 分钟；断网/失败一律沿用旧值，不会让界面出现报错。
 */
public class Weather {

    private static final String TAG = "PipDesk";
    private static final String API = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%f&longitude=%f&current=temperature_2m,weather_code&timezone=auto";

    /**
     * 同一份天气数据的有效期。
     *
     * 为什么不是 5 分钟：Open-Meteo 的 current 数据本身十几分钟才更新一次，
     * 查得再勤也是同一份结果——除了多耗流量和电，没有任何收益。15 分钟刚好
     * 卡在数据更新节奏上；一天 96 次，离免费额度（1 万次/天）还很远。
     */
    private static final long FRESH_MS = 15 * 60 * 1000L;

    private static volatile boolean fetching;
    private static volatile long lastFailAt;

    private Weather() { }

    public static boolean fresh(Context c) {
        return Prefs.getWeatherCode(c) >= 0
                && System.currentTimeMillis() - Prefs.getWeatherAt(c) < FRESH_MS;
    }

    /**
     * 现在有没有人真的需要天气：
     *   · Dock 右端要显示天气，或
     *   · 亮度自动调节开着且勾了「跟随天气」
     * 都不需要时就别联网了（省流量、省电）。
     */
    public static boolean needed(Context c) {
        if (c == null) return false;
        if (Prefs.dockRightEnabled(c)) return true;
        return Prefs.brightAuto(c) && Prefs.brightUseWeather(c);
    }

    /** 缓存过期就在后台拉一次（不阻塞界面）；不需要天气时不发请求 */
    public static void refreshIfStale(Context c) {
        if (c == null || !needed(c) || fresh(c) || fetching) return;
        // 断网时 5 分钟内不重试，避免 15 秒一跳的定时器把请求堆起来
        if (System.currentTimeMillis() - lastFailAt < 5 * 60 * 1000L) return;
        final Context app = c.getApplicationContext();
        fetching = true;
        new Thread(() -> {
            try {
                fetch(app);
            } catch (Throwable t) {
                lastFailAt = System.currentTimeMillis();
                Log.i(TAG, "天气获取失败，沿用旧值：" + t);
            } finally {
                fetching = false;
            }
        }, "pipdesk-weather").start();
    }

    /** 设置页手动刷新：不看缓存、不看开关，立刻拉一次 */
    public static void forceRefresh(Context c) {
        if (c == null || fetching) return;
        final Context app = c.getApplicationContext();
        fetching = true;
        new Thread(() -> {
            try {
                fetch(app);
            } catch (Throwable t) {
                lastFailAt = System.currentTimeMillis();
                Log.i(TAG, "天气获取失败：" + t);
            } finally {
                fetching = false;
            }
        }, "pipdesk-weather").start();
    }

    private static void fetch(Context c) throws Exception {
        double[] loc = SunTimes.location(c);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(Locale.US, API, loc[0], loc[1]));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
            int code = cur.getInt("weather_code");
            int tempX10 = (int) Math.round(cur.getDouble("temperature_2m") * 10);
            Prefs.setWeather(c, code, tempX10, System.currentTimeMillis());
            Log.i(TAG, "天气已更新：" + text(code) + " " + (tempX10 / 10f) + "° code=" + code);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** WMO 天气代码 → 中文 */
    public static String text(int code) {
        if (code < 0) return "天气 --";
        if (code == 0) return "晴";
        if (code == 1) return "少云";
        if (code == 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code >= 95) return "雷雨";
        return "多云";
    }

    /**
     * 是否算「阴天」（亮度用阴天档）。
     * 少云（1）、多云（2）都还算有太阳，按晴天处理；只有真正的阴/雾/雨雪才算，
     * 否则稍微有点云就把屏幕压暗，白天看着发闷。
     * 没数据时按晴天——宁可用白天亮度，也不要凭猜测把屏幕压暗。
     */
    public static boolean isCloudy(int code) {
        return code >= 3;
    }

    /** 天气数据新鲜度文案（设置页显示用） */
    public static String ageLabel(Context c) {
        long at = Prefs.getWeatherAt(c);
        if (at <= 0) return "还没查到";
        long min = (System.currentTimeMillis() - at) / 60000L;
        if (min <= 0) return "刚刚更新";
        if (min < 60) return min + " 分钟前更新";
        return (min / 60) + " 小时前更新";
    }

    /** Dock 右端显示用：「多云 12°」，没数据时「天气 --」 */
    public static String dockLabel(Context c) {
        int code = Prefs.getWeatherCode(c);
        if (code < 0) return "天气 --";
        int t = Prefs.getWeatherTempX10(c);
        if (t == Integer.MIN_VALUE) return text(code);
        return text(code) + " " + String.format(Locale.US, "%.0f°", t / 10f);
    }
}
