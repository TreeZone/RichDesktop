package com.cz.richdesk;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Locale;

/**
 * 「跟随时间」需要的日出/日落时刻。
 *
 * 两条数据来源，按优先级：
 *   1) 用户设定的经纬度（设置页可填，填了就不动系统定位）
 *   2) 系统最后已知位置（车机没授权时拿不到，直接退回默认坐标：北京）
 *
 * 联网接口是 sunrise-sunset.org 的公开 JSON（免费、无需 key）。每天只查一次，
 * 结果缓存到 prefs，跨天自动失效。任何一步失败都不抛异常——
 * {@link #isDarkNow(Context)} 会退回用户配置的固定时段，功能不会因为断网而失效。
 */
public class SunTimes {

    private static final String TAG = "PipDesk";
    private static final String API = "https://api.sunrise-sunset.org/json?lat=%f&lng=%f&formatted=0";

    /** 默认坐标：北京（定位不可用时的兜底，保证「日落模式」至少有个合理值） */
    private static final double DEFAULT_LAT = 39.9042, DEFAULT_LNG = 116.4074;

    private SunTimes() { }

    // ------------------------------------------------------------ 对外

    /**
     * 当前是否处于「夜间」区间。只有「跟随时间」模式才会调用。
     * 日落/日出可用就用它，否则用固定时段（默认 18:30 → 07:00）。
     */
    public static boolean isDarkNow(Context c) {
        return isDarkNow(c, Prefs.useSunTime(c));
    }

    /**
     * 同上，但由调用方决定用不用联网日落（亮度自动调节用的是自己的子开关，
     * 和「外观跟随时间」互不影响）。useSun=false 时直接走固定时段。
     */
    public static boolean isDarkNow(Context c, boolean useSun) {
        int now = nowMinutes();
        int start = Prefs.getNightStart(c);
        int end = Prefs.getNightEnd(c);

        if (useSun && freshCache(c)) {
            int set = Prefs.getSunSetMin(c);
            int rise = Prefs.getSunRiseMin(c);
            if (set >= 0 && rise >= 0) {
                start = set;
                end = rise;
            }
        }
        return inRange(now, start, end);
    }

    /** start == end 视为没有夜间区间；start > end 表示跨午夜 */
    private static boolean inRange(int now, int start, int end) {
        if (start == end) return false;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private static boolean freshCache(Context c) {
        return Prefs.getSunCacheDay(c) == today() && Prefs.getSunSetMin(c) >= 0;
    }

    /** 每天第一次需要时刷新一次；必须在后台线程调用 */
    public static void refreshIfStale(Context c) {
        // 不再限制「跟随时间」模式：亮度自动调节也用这份缓存，多查一次没副作用
        if (freshCache(c)) return;
        try {
            fetch(c);
        } catch (Throwable t) {
            Log.i(TAG, "日落时间获取失败，沿用固定时段：" + t);
        }
    }

    /** 上次尝试失败的时间：断网时别每 15 秒重试一次，10 分钟内不重复 */
    private static volatile long lastTryAt;
    private static volatile boolean refreshing;

    /** 主线程安全的版本：日落数据过期就丢后台拉一次（失败静默沿用固定时段） */
    public static void refreshIfStaleAsync(Context c) {
        if (c == null || freshCache(c) || refreshing) return;
        if (System.currentTimeMillis() - lastTryAt < 10 * 60 * 1000L) return;
        final Context app = c.getApplicationContext();
        refreshing = true;
        lastTryAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                fetch(app);
            } catch (Throwable t) {
                Log.i(TAG, "日落时间获取失败，沿用固定时段：" + t);
            } finally {
                refreshing = false;
            }
        }, "pipdesk-sun").start();
    }

    /**
     * 当前使用的位置：手动坐标 → 系统最后已知定位 → IP 反查（24h 缓存）→ 北京兜底。
     * 日落查询和天气查询共用同一个位置，保证两块数据说的是同一个地方。
     */
    public static double[] location(Context c) {
        if (!Double.isNaN(Prefs.getLatitude(c))) {
            return new double[]{Prefs.getLatitude(c), Prefs.getLongitude(c)};
        }
        double[] loc = lastKnown(c);
        if (loc != null) return loc;
        double ip = Prefs.getIpLat(c);
        if (!Double.isNaN(ip)) return new double[]{ip, Prefs.getIpLng(c)};
        return new double[]{DEFAULT_LAT, DEFAULT_LNG};
    }

    /** 状态文案（设置页用） */
    public static String describe(Context c) {
        if (!Prefs.useSunTime(c)) {
            return fmt(Prefs.getNightStart(c)) + " 到 " + fmt(Prefs.getNightEnd(c));
        }
        if (freshCache(c)) {
            return "日落 " + fmt(Prefs.getSunSetMin(c)) + " → 日出 " + fmt(Prefs.getSunRiseMin(c));
        }
        return "日落时间待联网（暂用 " + fmt(Prefs.getNightStart(c))
                + " 到 " + fmt(Prefs.getNightEnd(c)) + "）";
    }

    public static String locationLabel(Context c) {
        if (!Double.isNaN(Prefs.getLatitude(c))) {
            return String.format(Locale.US, "手动 %.3f, %.3f",
                    Prefs.getLatitude(c), Prefs.getLongitude(c));
        }
        double[] loc = lastKnown(c);
        if (loc != null) {
            return String.format(Locale.US, "系统定位 %.3f, %.3f", loc[0], loc[1]);
        }
        double ip = Prefs.getIpLat(c);
        if (!Double.isNaN(ip)) {
            return String.format(Locale.US, "IP定位 %.3f, %.3f", ip, Prefs.getIpLng(c));
        }
        return String.format(Locale.US, "默认坐标（北京）%.3f, %.3f", DEFAULT_LAT, DEFAULT_LNG);
    }

    public static String fmt(int minutes) {
        if (minutes < 0) return "--:--";
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ------------------------------------------------------------ IP 定位兜底

    private static final long IP_TTL = 24 * 60 * 60 * 1000L;
    private static volatile boolean ipFetching;
    /** 免 key 的 IP 反查服务，逐个试；都返回 latitude/longitude 字段 */
    private static final String[] IP_APIS = {
            "https://ipwho.is/",
            "https://ipapi.co/json/",
            "https://api.ip.sb/geoip"
    };

    /**
     * 没有手动坐标、系统定位也拿不到时，用车机出口 IP 反查经纬度（城市级精度）。
     * 结果缓存 24h。**只能在后台线程调**（Weather/SunTimes 的 fetch 都满足）。
     */
    public static void ensureIpLocation(Context c) {
        if (!Double.isNaN(Prefs.getLatitude(c))) return;     // 手动坐标优先
        if (lastKnown(c) != null) return;                   // 系统定位优先
        if (!Double.isNaN(Prefs.getIpLat(c))
                && System.currentTimeMillis() - Prefs.getIpAt(c) < IP_TTL) return;
        if (ipFetching) return;
        ipFetching = true;
        try {
            for (String api : IP_APIS) {
                try {
                    JSONObject o = getJson(api, 4000);
                    double lat = o.optDouble("latitude", Double.NaN);
                    double lng = o.optDouble("longitude", Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lng)
                            && Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
                        Prefs.setIpLocation(c, lat, lng);
                        Log.i(TAG, "IP定位成功：" + api + " → " + lat + "," + lng);
                        return;
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "IP定位失败：" + api + "（" + t + "）");
                }
            }
        } finally {
            ipFetching = false;
        }
    }

    private static JSONObject getJson(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ 联网

    private static void fetch(Context c) throws Exception {
        ensureIpLocation(c);        // 没有 GPS/手动坐标时先兜底反查一次（后台线程）
        double[] loc = location(c);
        double lat = loc[0], lng = loc[1];

        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(Locale.US, API, lat, lng));
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
            JSONObject res = new JSONObject(sb.toString()).getJSONObject("results");
            int rise = toLocalMinutes(res.getString("sunrise"));
            int set = toLocalMinutes(res.getString("sunset"));
            if (rise < 0 || set < 0) throw new IllegalStateException("时间解析失败");
            Prefs.setSunCache(c, today(), rise, set);
            Log.i(TAG, "日落/日出已更新：" + fmt(rise) + " / " + fmt(set));
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** "2026-11-05T09:12:20+00:00" → 本地时区的分钟数 */
    private static int toLocalMinutes(String iso) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso);
            java.time.ZonedDateTime local = t.atZoneSameInstant(ZoneId.systemDefault());
            return local.getHour() * 60 + local.getMinute();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static double[] lastKnown(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l;
                try {
                    l = lm.getLastKnownLocation(p);
                } catch (SecurityException se) {
                    continue;
                }
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------ 时间

    private static int nowMinutes() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    private static int today() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
    }
}
