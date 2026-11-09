package com.cz.richdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部桌面配置的持久化入口。
 *
 * 键               含义                        默认
 * dock             Dock 绑定包名（"|" 分隔）     空
 * dock_count       Dock 可绑定数量              5
 * pane_count       画中画屏数（1~3）             1
 * dpi              虚拟屏 density                240（120~720）
 * pip_corner       圆角（dp）                    12（0~48）——画中画与 Dock 共用
 * icon_size        应用列表图标尺寸（dp）        56（24~128）
 * list_columns     应用列表每行列数              4（3~8）
 * status_bar       是否显示系统状态栏            true
 * pane_map         应用 → 画中画槽位（1 起）    空（自动分配）
 */
public class Prefs {

    private static final String FILE = "pipdesk";

    public static final int DOCK_MIN = 3;
    public static final int DOCK_MAX = 10;
    public static final int PANE_MAX = 3;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------ Dock 绑定

    public static List<String> getDock(Context c) {
        List<String> r = new ArrayList<>();
        String s = sp(c).getString("dock", "");
        if (s != null && !s.isEmpty()) {
            for (String p : s.split("\\|")) {
                if (!p.isEmpty()) r.add(p);
            }
        }
        return r;
    }

    public static void setDock(Context c, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(list.get(i));
        }
        sp(c).edit().putString("dock", sb.toString()).apply();
    }

    /** Dock 可绑定的应用格数（固定 n 格，满格后「＋」消失） */
    public static int getDockCount(Context c) {
        return clamp(sp(c).getInt("dock_count", 5), DOCK_MIN, DOCK_MAX);
    }

    // ------------------------------------------------------------ 应用列表手动排序

    /**
     * 用户在应用列表里长按拖出来的顺序（普通应用的 pkg 序列，竖线分隔）。
     * 没出现在序列里的应用（新装的）按字母序排在已排好的后面。
     */
    public static List<String> getAppOrder(Context c) {
        List<String> r = new ArrayList<>();
        String s = sp(c).getString("app_order", "");
        if (s != null && !s.isEmpty()) {
            for (String p : s.split("\\|")) {
                if (!p.isEmpty()) r.add(p);
            }
        }
        return r;
    }

    public static void setAppOrder(Context c, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(list.get(i));
        }
        sp(c).edit().putString("app_order", sb.toString()).apply();
    }

    public static void setDockCount(Context c, int n) {
        sp(c).edit().putInt("dock_count", clamp(n, DOCK_MIN, DOCK_MAX)).apply();
    }

    // ------------------------------------------------------------ Dock 左右功能区

    /** Dock 左端功能区：一列自建快捷图标（默认关，打开后没配图标就只显示「＋」） */
    public static boolean dockLeftEnabled(Context c) {
        return sp(c).getBoolean("dock_left_on", false);
    }

    public static void setDockLeftEnabled(Context c, boolean b) {
        sp(c).edit().putBoolean("dock_left_on", b).apply();
    }

    public static List<String> getDockLeft(Context c) {
        List<String> r = new ArrayList<>();
        String s = sp(c).getString("dock_left", "");
        if (s != null && !s.isEmpty()) {
            for (String p : s.split("\\|")) if (!p.isEmpty()) r.add(p);
        }
        return r;
    }

    public static void setDockLeft(Context c, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(list.get(i));
        }
        sp(c).edit().putString("dock_left", sb.toString()).apply();
    }

    /** Dock 右端功能区：时间 + 天气（默认关） */
    public static boolean dockRightEnabled(Context c) {
        return sp(c).getBoolean("dock_right_on", false);
    }

    public static void setDockRightEnabled(Context c, boolean b) {
        sp(c).edit().putBoolean("dock_right_on", b).apply();
    }

    // ------------------------------------------------------------ 亮度（按日出日落 + 天气）

    public static final int BRIGHT_MIN = 10, BRIGHT_MAX = 255;

    /** 是否按「日出日落 + 天气」自动调屏幕亮度 */
    public static boolean brightAuto(Context c) {
        return sp(c).getBoolean("bright_auto", false);
    }

    public static void setBrightAuto(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_auto", b).apply();
    }

    /** 白天（日出后、非阴天）亮度 0~255 */
    public static int getBrightDay(Context c) {
        return clamp(sp(c).getInt("bright_day", 200), BRIGHT_MIN, BRIGHT_MAX);
    }

    public static void setBrightDay(Context c, int v) {
        sp(c).edit().putInt("bright_day", clamp(v, BRIGHT_MIN, BRIGHT_MAX)).apply();
    }

    /** 夜间（日落后）亮度 */
    public static int getBrightNight(Context c) {
        return clamp(sp(c).getInt("bright_night", 60), BRIGHT_MIN, BRIGHT_MAX);
    }

    public static void setBrightNight(Context c, int v) {
        sp(c).edit().putInt("bright_night", clamp(v, BRIGHT_MIN, BRIGHT_MAX)).apply();
    }

    /** 阴天/雨雪（联网查到的天气）亮度 */
    public static int getBrightCloudy(Context c) {
        return clamp(sp(c).getInt("bright_cloudy", 140), BRIGHT_MIN, BRIGHT_MAX);
    }

    public static void setBrightCloudy(Context c, int v) {
        sp(c).edit().putInt("bright_cloudy", clamp(v, BRIGHT_MIN, BRIGHT_MAX)).apply();
    }

    // ------------------------------------------------------------ 天气缓存（跟亮度、Dock 天气共用）

    /** WMO 天气代码；-1 = 还没查到 */
    public static int getWeatherCode(Context c) {
        return sp(c).getInt("weather_code", -1);
    }

    /** 气温（℃ ×10 存成整数） */
    public static int getWeatherTempX10(Context c) {
        return sp(c).getInt("weather_temp_x10", Integer.MIN_VALUE);
    }

    public static long getWeatherAt(Context c) {
        return sp(c).getLong("weather_at", 0L);
    }

    public static void setWeather(Context c, int code, int tempX10, long atMillis) {
        sp(c).edit()
                .putInt("weather_code", code)
                .putInt("weather_temp_x10", tempX10)
                .putLong("weather_at", atMillis)
                .apply();
    }

    // ------------------------------------------------------------ Dock 外观（需求：高度与图标大小可调）

    public static final int DOCK_H_MIN = 56;
    public static final int DOCK_H_MAX = 120;

    /** Dock 栏整体高度（dp），不含与画中画之间的统一间距 */
    public static int getDockHeight(Context c) {
        return clamp(sp(c).getInt("dock_height", 70), DOCK_H_MIN, DOCK_H_MAX);
    }

    public static void setDockHeight(Context c, int dp) {
        sp(c).edit().putInt("dock_height", clamp(dp, DOCK_H_MIN, DOCK_H_MAX)).apply();
    }

    public static final int DOCK_ICON_MIN = 24;
    /** 上限 = 最大 Dock 高度（120dp）；实际绘制时还会被压到 Dock 高度内、格宽 62% 内 */
    public static final int DOCK_ICON_MAX = 120;

    /** Dock 图标大小（dp）；格子太窄时会自动压缩到格宽的 62%，避免互相挤压 */
    public static int getDockIcon(Context c) {
        return clamp(sp(c).getInt("dock_icon", 42), DOCK_ICON_MIN, DOCK_ICON_MAX);
    }

    public static void setDockIcon(Context c, int dp) {
        sp(c).edit().putInt("dock_icon", clamp(dp, DOCK_ICON_MIN, DOCK_ICON_MAX)).apply();
    }

    // ------------------------------------------------------------ 画中画

    /** 画中画屏数：1 单屏 / 2 双屏 / 3 三屏 */
    public static int getPaneCount(Context c) {
        return clamp(sp(c).getInt("pane_count", 1), 1, PANE_MAX);
    }

    public static void setPaneCount(Context c, int n) {
        sp(c).edit().putInt("pane_count", clamp(n, 1, PANE_MAX)).apply();
    }

    public static final int DPI_MIN = 120;
    public static final int DPI_MAX = 720;     // 需求：上限放大（原来只到 480，屏内图标还是小）

    public static int getDpi(Context c) {
        return clamp(sp(c).getInt("dpi", 240), DPI_MIN, DPI_MAX);
    }

    public static void setDpi(Context c, int dpi) {
        sp(c).edit().putInt("dpi", clamp(dpi, DPI_MIN, DPI_MAX)).apply();
    }

    // ------------------------------------------------------------ 分屏比例

    /**
     * 每块画中画的长度权重（1:1 / 1:2 / 2:1 …），0 表示该屏被折叠隐藏。
     * 存成逗号分隔的浮点串，长度不足按 1 补齐（屏数变化后老配置仍然可用）。
     */
    public static float[] getPaneWeights(Context c, int n) {
        int count = Math.max(1, n);
        float[] r = new float[count];
        for (int i = 0; i < count; i++) r[i] = 1f;
        String s = sp(c).getString("pane_weights", "");
        if (s == null || s.isEmpty()) return r;
        String[] parts = s.split(",");
        for (int i = 0; i < count && i < parts.length; i++) {
            try {
                float v = Float.parseFloat(parts[i].trim());
                if (!Float.isNaN(v)) r[i] = Math.max(0f, Math.min(3f, v));
            } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    public static void setPaneWeights(Context c, float[] w) {
        if (w == null || w.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%.3f", Math.max(0f, w[i])));
        }
        sp(c).edit().putString("pane_weights", sb.toString()).apply();
    }

    public static final int CORNER_MAX = 48;

    public static int getPipCorner(Context c) {
        return clamp(sp(c).getInt("pip_corner", 12), 0, CORNER_MAX);
    }

    /** 圆角只有一套：改画中画圆角 = 同时改 Dock 圆角（需求），不再分别配置 */
    public static void setPipCorner(Context c, int dp) {
        int v = clamp(dp, 0, CORNER_MAX);
        sp(c).edit().putInt("pip_corner", v).putInt("dock_corner", v).apply();
    }

    public static int getDockCorner(Context c) {
        return getPipCorner(c);
    }

    public static void setDockCorner(Context c, int dp) {
        setPipCorner(c, dp);
    }

    // ------------------------------------------------------------ 应用列表

    public static final int ICON_MIN = 24;
    public static final int ICON_MAX = 128;    // 需求：图标可调范围再放大

    public static int getIconSize(Context c) {
        return clamp(sp(c).getInt("icon_size", 56), ICON_MIN, ICON_MAX);
    }

    public static void setIconSize(Context c, int dp) {
        sp(c).edit().putInt("icon_size", clamp(dp, ICON_MIN, ICON_MAX)).apply();
    }

    /** 应用列表每行列数（3~8） */
    public static int getListColumns(Context c) {
        return clamp(sp(c).getInt("list_columns", 4), 3, 8);
    }

    public static void setListColumns(Context c, int n) {
        sp(c).edit().putInt("list_columns", clamp(n, 3, 8)).apply();
    }

    // ------------------------------------------------------------ 状态栏

    public static boolean showStatusBar(Context c) {
        return sp(c).getBoolean("status_bar", true);
    }

    public static void setShowStatusBar(Context c, boolean b) {
        sp(c).edit().putBoolean("status_bar", b).apply();
    }

    // ------------------------------------------------------------ 槽位分配

    /** 应用 → 画中画序号（1 起），未记录表示自动分配 */
    public static Map<String, Integer> getPaneMap(Context c) {
        Map<String, Integer> m = new HashMap<>();
        String s = sp(c).getString("pane_map", "");
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("\\|")) {
            int i = kv.indexOf('=');
            if (i <= 0 || i >= kv.length() - 1) continue;
            try {
                int v = Integer.parseInt(kv.substring(i + 1));
                if (v >= 1 && v <= PANE_MAX) m.put(kv.substring(0, i), v);
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    public static void setPaneMap(Context c, Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sp(c).edit().putString("pane_map", sb.toString()).apply();
    }

    /** 该应用指定的槽位（1 起）；0 = 自动 */
    public static int getPaneOf(Context c, String pkg) {
        Integer v = getPaneMap(c).get(pkg);
        return v == null ? 0 : v;
    }

    public static void setPaneOf(Context c, String pkg, int pane) {
        Map<String, Integer> m = getPaneMap(c);
        if (pane <= 0) {
            m.remove(pkg);
        } else {
            m.put(pkg, pane);
        }
        setPaneMap(c, m);
    }

    // ------------------------------------------------------------ 深浅色外观

    /**
     * 外观模式：
     *   AUTO  —— 跟随安卓（系统夜间模式，未开启夜间时按壁纸亮度自适应）
     *   TIME  —— 跟随时间（固定时段，或联网取日落/日出）
     *   DARK  —— 手动深色（由应用列表里的「深浅模式」图标切换写入）
     *   LIGHT —— 手动浅色
     */
    public static final int THEME_AUTO = 0;
    public static final int THEME_TIME = 1;
    public static final int THEME_DARK = 2;
    public static final int THEME_LIGHT = 3;

    public static int getThemeMode(Context c) {
        return clamp(sp(c).getInt("theme_mode", THEME_AUTO), 0, 3);
    }

    public static void setThemeMode(Context c, int mode) {
        sp(c).edit().putInt("theme_mode", clamp(mode, 0, 3)).apply();
    }

    /**
     * 屏内应用渲染分辨率比例（百分比，存 100 / 75 / 50）。
     *
     * 虚拟屏按「面板像素尺寸 × 比例」创建，屏内应用渲染的像素就少了 —— GPU
     * 重的应用（导航、视频）帧率明显更稳；纹理交给 TextureView 放大回去，
     * 画面略糊。默认 100（标准，1:1 渲染最清晰）。
     */
    public static int getRenderScale(Context c) {
        return clamp(sp(c).getInt("render_scale", 100), 50, 100);
    }

    public static void setRenderScale(Context c, int pct) {
        sp(c).edit().putInt("render_scale", clamp(pct, 50, 100)).apply();
    }

    /**
     * 深浅色切换是否把夜间模式同步给系统（屏内应用跟着变深/变浅）。
     *
     * 默认**关**：全局 uiMode 变更会销毁重建屏内应用，实测部分应用（QQ）
     * 直接进程崩溃（AMS "has died: fg TOP"），用户看到的就是
     * 「切个主题，画中画里的应用被切换掉了」。关掉后只切桌面外观，屏内应用不动。
     */
    public static boolean syncAppsNightMode(Context c) {
        return sp(c).getBoolean("sync_apps_night_mode", false);
    }

    public static void setSyncAppsNightMode(Context c, boolean b) {
        sp(c).edit().putBoolean("sync_apps_night_mode", b).apply();
    }

    /** 定时模式：入夜时间（分钟数，0~1439），默认 18:30 */
    public static int getNightStart(Context c) {
        return clamp(sp(c).getInt("night_start", 18 * 60 + 30), 0, 1439);
    }

    public static void setNightStart(Context c, int minutes) {
        sp(c).edit().putInt("night_start", clamp(minutes, 0, 1439)).apply();
    }

    /** 定时模式：天亮时间（分钟数），默认 07:00 */
    public static int getNightEnd(Context c) {
        return clamp(sp(c).getInt("night_end", 7 * 60), 0, 1439);
    }

    public static void setNightEnd(Context c, int minutes) {
        sp(c).edit().putInt("night_end", clamp(minutes, 0, 1439)).apply();
    }

    /** 定时模式：是否用「联网日落/日出」代替固定时段（默认开，联网失败自动退回固定时段） */
    public static boolean useSunTime(Context c) {
        return sp(c).getBoolean("use_sun_time", true);
    }

    public static void setUseSunTime(Context c, boolean b) {
        sp(c).edit().putBoolean("use_sun_time", b).apply();
    }

    /** 日落时间用的经纬度；lat 为 NaN 表示自动取系统定位（默认北京） */
    public static double getLatitude(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("sun_lat", Double.doubleToLongBits(Double.NaN)));
    }

    public static double getLongitude(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("sun_lng", Double.doubleToLongBits(Double.NaN)));
    }

    public static void setLocation(Context c, double lat, double lng) {
        sp(c).edit()
                .putLong("sun_lat", Double.doubleToLongBits(lat))
                .putLong("sun_lng", Double.doubleToLongBits(lng))
                .apply();
    }

    public static void clearLocation(Context c) {
        sp(c).edit().remove("sun_lat").remove("sun_lng").apply();
    }

    /** 日落/日出缓存：分钟数 + 缓存日期（yyyyMMdd），跨天自动失效 */
    public static int getSunSetMin(Context c) {
        return sp(c).getInt("sun_set_min", -1);
    }

    public static int getSunRiseMin(Context c) {
        return sp(c).getInt("sun_rise_min", -1);
    }

    public static int getSunCacheDay(Context c) {
        return sp(c).getInt("sun_cache_day", -1);
    }

    public static void setSunCache(Context c, int day, int riseMin, int setMin) {
        sp(c).edit()
                .putInt("sun_cache_day", day)
                .putInt("sun_rise_min", riseMin)
                .putInt("sun_set_min", setMin)
                .apply();
    }

    // ------------------------------------------------------------ 画中画默认应用

    /**
     * 活动画中画：双屏/三屏时，未指定屏位的应用都在这块屏打开。只允许配置一个。
     * 未配置（0）时默认**最后一屏**（跟随当前屏数，不写死第 3 屏）。
     */
    public static int getActivePane(Context c, int paneCount) {
        int v = sp(c).getInt("active_pane", 0);
        if (v <= 0) return Math.max(1, paneCount);
        return clamp(v, 1, Math.max(1, paneCount));
    }

    public static void setActivePane(Context c, int pane) {
        sp(c).edit().putInt("active_pane", clamp(pane, 1, PANE_MAX)).apply();
    }

    /**
     * 每块画中画的默认打开应用（键 pane_default_1..3，1 起）。
     * 桌面启动时，空闲且有默认应用的屏会自动把它拉起来。
     */
    public static String getDefaultApp(Context c, int pane) {
        if (pane < 1 || pane > PANE_MAX) return null;
        String s = sp(c).getString("pane_default_" + pane, "");
        return (s == null || s.isEmpty()) ? null : s;
    }

    public static void setDefaultApp(Context c, int pane, String pkg) {
        if (pane < 1 || pane > PANE_MAX) return;
        SharedPreferences.Editor e = sp(c).edit();
        if (pkg == null || pkg.isEmpty()) e.remove("pane_default_" + pane);
        else e.putString("pane_default_" + pane, pkg);
        e.apply();
    }
}
