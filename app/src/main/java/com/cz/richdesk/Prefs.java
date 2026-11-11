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

    /** Dock 位置：0 = 屏幕底部（横向一排），1 = 屏幕左侧（竖向一列） */
    public static final int DOCK_SIDE_BOTTOM = 0;
    public static final int DOCK_SIDE_LEFT = 1;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------ 性能模式

    /**
     * 低性能模式（弱 GPU 车机用）：只把玻璃材质降级为纯色、关掉窗口背景模糊，
     * 其余绘制 / 布局 / 触摸 / 虚拟屏逻辑与高性能模式完全一致。
     * **默认 true（低性能）**：车机是主要部署目标，装完即省合成带宽；
     * 想要原来的玻璃外观，在设置页关掉本开关即可（关掉后所有绘制路径与旧版本完全一致）。
     */
    public static boolean perfLow(Context c) {
        return sp(c).getBoolean("perf_low", true);
    }

    public static void setPerfLow(Context c, boolean low) {
        sp(c).edit().putBoolean("perf_low", low).apply();
    }

    // ------------------------------------------------------------ 应用列表名称

    /**
     * 应用名称是否垫一块浅色底板（黑字）。
     *
     * 桌面是透壁纸的，名字默认按「背后那块壁纸」自动定黑白（见 WallText）——
     * 壁纸花、半明半暗或带细高光时仍可能糊。打开本开关后不再看壁纸：
     * 名字底下画一块半透明白色圆角底，字固定黑色，任何壁纸上都读得清。
     * 默认 false = 保持原来的「随壁纸自动变色」。
     */
    public static boolean appNamePlate(Context c) {
        return sp(c).getBoolean("app_name_plate", false);
    }

    public static void setAppNamePlate(Context c, boolean on) {
        sp(c).edit().putBoolean("app_name_plate", on).apply();
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

    /** Dock 位置。非法值一律按「底部」处理，避免脏配置把界面搞没 */
    public static int getDockSide(Context c) {
        return sp(c).getInt("dock_side", DOCK_SIDE_BOTTOM) == DOCK_SIDE_LEFT
                ? DOCK_SIDE_LEFT : DOCK_SIDE_BOTTOM;
    }

    public static boolean dockOnLeft(Context c) {
        return getDockSide(c) == DOCK_SIDE_LEFT;
    }

    public static void setDockSide(Context c, int side) {
        sp(c).edit().putInt("dock_side",
                side == DOCK_SIDE_LEFT ? DOCK_SIDE_LEFT : DOCK_SIDE_BOTTOM).apply();
    }

    /** 「应用列表」按钮是否已自动补进过 Dock（只补一次，之后用户可长按移除/拖动） */
    public static boolean appListButtonAdded(Context c) {
        return sp(c).getBoolean("app_list_btn_added", false);
    }

    public static void setAppListButtonAdded(Context c) {
        sp(c).edit().putBoolean("app_list_btn_added", true).apply();
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
        putList(c, "dock_left", list);
    }

    /** 右侧功能区的快捷图标（只有右侧被配成「快捷功能」时才有意义） */
    public static List<String> getDockRightIcons(Context c) {
        return getList(c, "dock_right_icons");
    }

    public static void setDockRightIcons(Context c, List<String> list) {
        putList(c, "dock_right_icons", list);
    }

    private static List<String> getList(Context c, String key) {
        List<String> r = new ArrayList<>();
        String s = sp(c).getString(key, "");
        if (s != null && !s.isEmpty()) {
            for (String p : s.split("\\|")) if (!p.isEmpty()) r.add(p);
        }
        return r;
    }

    private static void putList(Context c, String key, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(list.get(i));
        }
        sp(c).edit().putString(key, sb.toString()).apply();
    }

    /**
     * 应用列表「快捷区」的图标顺序（9 项：桌面设置 + 8 个内置快捷方式）。
     * 应用列表里长按拖动排序后由 MainActivity 持久化到这里。
     */
    public static List<String> getShortcutOrder(Context c) {
        return getList(c, "shortcut_order");
    }

    public static void setShortcutOrder(Context c, List<String> list) {
        putList(c, "shortcut_order", list);
    }

    /** Dock 右端功能区：时间 + 天气（默认关） */
    public static boolean dockRightEnabled(Context c) {
        return sp(c).getBoolean("dock_right_on", false);
    }

    public static void setDockRightEnabled(Context c, boolean b) {
        sp(c).edit().putBoolean("dock_right_on", b).apply();
    }

    /**
     * 功能区内容类型：快捷功能（图标）或 时间 + 天气。
     * 左右两侧各自独立配置 —— 左边既可以放图标，也可以放时间（有人喜欢时间在左）。
     */
    public static final int PANEL_SHORTCUT = 0;
    public static final int PANEL_CLOCK = 1;

    /**
     * 快捷功能区的硬上限（只是防呆，防止配置文件被写坏）。
     * 真正的限制是「Dock 总格数 − 1」：中间图标区至少留 1 格，其余都给功能区。
     */
    public static final int PANEL_SHORTCUT_MAX = 12;

    public static int getDockLeftType(Context c) {
        return clamp(sp(c).getInt("dock_left_type", PANEL_SHORTCUT), 0, 1);
    }

    public static void setDockLeftType(Context c, int t) {
        sp(c).edit().putInt("dock_left_type", clamp(t, 0, 1)).apply();
    }

    public static int getDockRightType(Context c) {
        return clamp(sp(c).getInt("dock_right_type", PANEL_CLOCK), 0, 1);
    }

    public static void setDockRightType(Context c, int t) {
        sp(c).edit().putInt("dock_right_type", clamp(t, 0, 1)).apply();
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

    /** 子开关：跟随日出日落（关掉就只用白天亮度，不做夜间变暗） */
    public static boolean brightUseSun(Context c) {
        return sp(c).getBoolean("bright_use_sun", true);
    }

    public static void setBrightUseSun(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_use_sun", b).apply();
    }

    /** 子开关：跟随天气（关掉就不联网取天气，也不做阴天变暗） */
    public static boolean brightUseWeather(Context c) {
        return sp(c).getBoolean("bright_use_weather", true);
    }

    public static void setBrightUseWeather(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_use_weather", b).apply();
    }

    // ---- 亮度写入通道：TS10 一类车机只认其中一条，允许单独开关做排除测试 ----

    /** 通道① 系统设置 Settings.System.SCREEN_BRIGHTNESS */
    public static boolean brightChSetting(Context c) {
        return sp(c).getBoolean("bright_ch_setting", true);
    }

    public static void setBrightChSetting(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_ch_setting", b).apply();
    }

    /** 通道② 显示层 SurfaceControl.setDisplayBrightness（HWC） */
    public static boolean brightChSurface(Context c) {
        return sp(c).getBoolean("bright_ch_surface", true);
    }

    public static void setBrightChSurface(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_ch_surface", b).apply();
    }

    /** 通道③ 背光节点 /sys/class/backlight（需要 root） */
    public static boolean brightChSysfs(Context c) {
        return sp(c).getBoolean("bright_ch_sysfs", true);
    }

    public static void setBrightChSysfs(Context c, boolean b) {
        sp(c).edit().putBoolean("bright_ch_sysfs", b).apply();
    }

    /**
     * 最低亮度下限（百分比 0~40）。
     * 鼎威 TS10 等车机会把过低的亮度直接当「关背光」处理——屏幕一下子全黑。
     * 写入任何通道前都先把亮度抬到这个百分比之上，0 = 不设下限。
     */
    public static int getBrightFloorPct(Context c) {
        return clamp(sp(c).getInt("bright_floor_pct", 10), 0, 40);
    }

    public static void setBrightFloorPct(Context c, int v) {
        sp(c).edit().putInt("bright_floor_pct", clamp(v, 0, 40)).apply();
    }

    /**
     * 接管系统亮度前，用户原本的亮度状态（关掉总开关时还原）。
     * mode: -1 未记录 / 0 手动 / 1 自动；value 仅手动模式有意义。
     */
    public static int getBrightSavedMode(Context c) {
        return sp(c).getInt("bright_saved_mode", -1);
    }

    public static int getBrightSavedValue(Context c) {
        return sp(c).getInt("bright_saved_value", -1);
    }

    public static void setBrightSaved(Context c, int mode, int value) {
        sp(c).edit().putInt("bright_saved_mode", mode)
                .putInt("bright_saved_value", value).apply();
    }

    // ------------------------------------------------------------ 息屏（一键关屏）

    /** 息屏方式：0 = 真息屏（goToSleep）；1 = 黑屏遮罩（最安全，任意触摸即恢复） */
    public static int getSleepMode(Context c) {
        // 固定 1（黑屏遮罩）：车机上真息屏有「黑屏唤不醒」风险，入口已隐藏，强制假息屏
        return 1;
    }

    public static void setSleepMode(Context c, int mode) {
        sp(c).edit().putInt("sleep_mode", clamp(mode, 0, 1)).apply();
    }

    /** 遮罩期点亮屏幕需要的点击次数：1 = 单击，2 = 双击，3 = 三击。默认双击（防误触） */
    public static int getWakeTaps(Context c) {
        return clamp(sp(c).getInt("wake_taps", 2), 1, 3);
    }

    public static void setWakeTaps(Context c, int n) {
        sp(c).edit().putInt("wake_taps", clamp(n, 1, 3)).apply();
    }

    /**
     * 真息屏的兜底自动亮屏秒数：到点强制唤醒，避免「黑屏后怎么都唤不醒」。
     * 0 = 关闭兜底（不建议）。
     */
    public static final int SLEEP_GUARD_MAX = 600;

    public static int getSleepGuardSec(Context c) {
        return clamp(sp(c).getInt("sleep_guard_sec", 180), 0, SLEEP_GUARD_MAX);
    }

    public static void setSleepGuardSec(Context c, int sec) {
        sp(c).edit().putInt("sleep_guard_sec", clamp(sec, 0, SLEEP_GUARD_MAX)).apply();
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

    /** 性能悬浮窗（调试工具，开关在测试功能页）：实时 CPU/GPU/内存/虚拟屏 */
    public static boolean perfOverlay(Context c) {
        return sp(c).getBoolean("perf_overlay", false);
    }

    public static void setPerfOverlay(Context c, boolean b) {
        sp(c).edit().putBoolean("perf_overlay", b).apply();
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

    /** IP 反查的经纬度缓存（24h 有效，车机没 GPS 时兜底）；lat 为 NaN 表示没有 */
    public static double getIpLat(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("ip_lat", Double.doubleToLongBits(Double.NaN)));
    }

    public static double getIpLng(Context c) {
        return Double.longBitsToDouble(sp(c).getLong("ip_lng", Double.doubleToLongBits(Double.NaN)));
    }

    public static long getIpAt(Context c) {
        return sp(c).getLong("ip_at", 0);
    }

    public static void setIpLocation(Context c, double lat, double lng) {
        sp(c).edit()
                .putLong("ip_lat", Double.doubleToLongBits(lat))
                .putLong("ip_lng", Double.doubleToLongBits(lng))
                .putLong("ip_at", System.currentTimeMillis())
                .apply();
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

    /** 未指定屏位应用的默认打开方式：0 = 活动画中画（默认），1 = 全屏，2 = 单画中画 */
    public static final int OPEN_ACTIVE = 0, OPEN_FULLSCREEN = 1, OPEN_SINGLE = 2;

    public static int getOpenMode(Context c) {
        return clamp(sp(c).getInt("open_mode", OPEN_ACTIVE), OPEN_ACTIVE, OPEN_SINGLE);
    }

    public static void setOpenMode(Context c, int mode) {
        sp(c).edit().putInt("open_mode", clamp(mode, OPEN_ACTIVE, OPEN_SINGLE)).apply();
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
