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

    private static float clampF(float v, float lo, float hi) {
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

    // ------------------------------------------------------------ 语音「静音」

    /**
     * 静音前的 STREAM_MUSIC 音量（0 = 没记过）。
     *
     * 不能靠 `ADJUST_UNMUTE` 恢复：有的 ROM 在音量为 0 时 unmute 还是 0，
     * 用户会以为"静音之后再也放不出声"。这里自己记一份，取消静音时写回去。
     */
    public static int mediaVolumeBackup(Context c) {
        return sp(c).getInt("media_vol_backup", 0);
    }

    public static void setMediaVolumeBackup(Context c, int v) {
        sp(c).edit().putInt("media_vol_backup", v).apply();
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

    /**
     * 画中画圆角用「四角壁纸遮罩」还是「整块裁剪」。
     *
     * 遮罩只在四个角重画壁纸（约 1.4 万像素），裁剪要把整块 199 万像素先离屏再贴回。
     * 默认遮罩；裁剪作为回退（遮罩在某些壁纸上可能看出接缝，或拿不到壁纸位图）。
     */
    /**
     * 桌面窗口是否改成不透明（自绘壁纸）。
     *
     * 透明窗口时，系统把「壁纸图层」和「桌面窗口」交给硬件合成器各叠一次；
     * 不透明后壁纸由我们画进窗口 buffer，合成器只处理一层，但画中画每出帧
     * 都会连带把壁纸重画一遍 —— 哪边更省要看 GPU，做成开关让用户自己对比。
     *
     * 代价：动态壁纸 / 壁纸视差会消失（只剩静态快照）。
     */
    public static boolean opaqueWindow(Context c) {
        return sp(c).getBoolean("opaque_window", true);
    }

    public static void setOpaqueWindow(Context c, boolean b) {
        sp(c).edit().putBoolean("opaque_window", b).apply();
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

    // ------------------------------------------------------------ 语音助手

    /**
     * 常驻唤醒开关（默认关）。
     *
     * 关着的时候麦克风完全不打开 —— 点 Dock 上的「语音」图标才录一次，属于按住说话的变体。
     * 打开后常驻跑 KWS（3.3M 模型，约 1% 单核），说「你好小桌」进入聆听。
     * 默认关的理由：车机上麦克风可能和百度地图的「小度小度」抢，先让用户主动开。
     */
    public static boolean voiceWake(Context c) {
        return sp(c).getBoolean("voice_wake", false);
    }

    public static void setVoiceWake(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_wake", on).apply();
    }

    /**
     * 唤醒词（中文，默认「你好小瑞」）。
     *
     * 模型认的是拼音音素，所以设置里改的这句中文会在启动 KWS 前被
     * {@link VoiceWords} 转成 keywords 文件（汉字 → 带声调拼音 → 音素 token）；
     * 转不出来的字自动退回默认词，不会让语音整个起不来。
     */
    public static String voiceWakeWord(Context c) {
        String s = sp(c).getString("voice_wake_word", "");
        return (s == null || s.trim().isEmpty()) ? VoiceWords.DEFAULT : s.trim();
    }

    public static void setVoiceWakeWord(Context c, String w) {
        String s = w == null ? "" : w.trim();
        sp(c).edit().putString("voice_wake_word",
                s.isEmpty() ? VoiceWords.DEFAULT : s).apply();
    }

    /** 唤醒/开始聆听时是否给一声提示音（默认开）。车机没有振动马达，只能靠声音确认。 */
    public static boolean voiceBeep(Context c) {
        return sp(c).getBoolean("voice_beep", true);
    }

    public static void setVoiceBeep(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_beep", on).apply();
    }

    /**
     * 唤醒词命中阈值（百分数，默认 25 = 0.25）。
     * 调低更容易唤醒也更爱误唤醒，车机放歌时噪声大，用户可以自己往上推。
     */
    public static int voiceThreshold(Context c) {
        return clamp(sp(c).getInt("voice_thr", 25), 10, 90);
    }

    public static void setVoiceThreshold(Context c, int v) {
        sp(c).edit().putInt("voice_thr", clamp(v, 10, 90)).apply();
    }

    /** 唤醒/识别时是否顺便把桌面拉到前台（默认关：车机上抢前台可能把导航挤掉） */
    public static boolean voiceBringFront(Context c) {
        return sp(c).getBoolean("voice_front", false);
    }

    public static void setVoiceBringFront(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_front", on).apply();
    }

    /** 执行完指令后用系统 TTS 回一句「好的 / 没听清」（默认开；没 TTS 引擎自动静默） */
    public static boolean voiceReply(Context c) {
        return sp(c).getBoolean("voice_reply", true);
    }

    public static void setVoiceReply(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_reply", on).apply();
    }

    /** 语音引擎的三种来源（数据统一存在 voice_tts_sel 这一个键里） */
    public static final int TTS_SYS = 0;       // 系统默认（不可用时自动降级内置）
    public static final int TTS_LOCAL = 1;     // 内置离线女声（LocalTts）
    public static final int TTS_CUSTOM = 2;    // 第三方 TTS 引擎包名

    /** voice_tts_sel 的取值：local=内置离线女声 / sys=跟随系统 / 其余为第三方引擎包名 */
    public static final String TTS_SEL_LOCAL = "local";
    public static final String TTS_SEL_SYS = "sys";

    /**
     * 当前语音引擎选择（单一真相，合并「回复音色」与「指定 TTS 引擎」两行为一行）。
     * 首次从旧的 voice_tts_mode / voice_tts_engine 迁移并写回新键；之后只认新键。
     * 默认「跟随系统」，系统引擎不可用时 VoiceTts 会自动改用内置，不会哑掉。
     */
    public static String voiceTtsSel(Context c) {
        String s = sp(c).getString("voice_tts_sel", null);
        if (s != null) return s;
        int m = sp(c).getInt("voice_tts_mode", TTS_SYS);
        String eng = sp(c).getString("voice_tts_engine", "");
        String sel = (m == TTS_LOCAL) ? TTS_SEL_LOCAL
                : (eng != null && !eng.isEmpty()) ? eng : TTS_SEL_SYS;
        setVoiceTtsSel(c, sel);
        return sel;
    }

    public static void setVoiceTtsSel(Context c, String sel) {
        sp(c).edit().putString("voice_tts_sel",
                (sel == null || sel.isEmpty()) ? TTS_SEL_SYS : sel).apply();
    }

    /** 解析出 VoiceTts 需要的 mode（数据统一来自 voiceTtsSel） */
    public static int voiceTtsMode(Context c) {
        String sel = voiceTtsSel(c);
        if (TTS_SEL_LOCAL.equals(sel)) return TTS_LOCAL;
        if (TTS_SEL_SYS.equals(sel)) return TTS_SYS;
        return TTS_CUSTOM;       // 其余都是第三方引擎包名
    }

    /** 解析出第三方引擎包名（local / sys 返回空串） */
    public static String voiceTtsEngine(Context c) {
        String sel = voiceTtsSel(c);
        return (TTS_SEL_LOCAL.equals(sel) || TTS_SEL_SYS.equals(sel)) ? "" : sel;
    }

    /**
     * 识别解码用束搜索。
     *
     * <p><b>当前模型（0.23.25 起的 streaming CTC）绝对不能用这个开关</b>：
     * CTC 只支持 greedy_search，传 modified_beam_search 会让 sherpa 在 native 层
     * 直接 exit(-1)（见 VoiceAsr 里那段注释），表现为「点语音图标 → 桌面闪退重启」。
     * VoiceAsr 已经写死 greedy，不再读这个值。
     *
     * <p>保留它只为将来换回 transducer 模型时的历史兼容（当年 14M 上
     * modified_beam_search 确实更抗跳字）。换回 transducer 时记得同步改 VoiceAsr。
     */
    public static boolean voiceBeam(Context c) {
        return sp(c).getBoolean("voice_beam", true);
    }

    public static void setVoiceBeam(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_beam", on).apply();
    }

    /**
     * 给识别加「应用名热词」（默认开）。
     *
     * 把本机装的应用名做成热词表喂给识别器，模型解码时会偏向这些词 ——
     * 「酷狗音乐」「哔哩哔哩」这类专有名词的识别率提升最明显。
     * 加载失败会自动退回不带热词的配置，不会让语音整个不可用。
     */
    public static boolean voiceHotwords(Context c) {
        return sp(c).getBoolean("voice_hw", true);
    }

    public static void setVoiceHotwords(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_hw", on).apply();
    }

    /** 热词偏置强度。太高会把普通句子往应用名上带，1.5 是 sherpa 官方示例的量级 */
    public static float voiceHotwordScore(Context c) {
        return clampF(sp(c).getFloat("voice_hw_score", 1.5f), 0.1f, 5f);
    }

    public static void setVoiceHotwordScore(Context c, float v) {
        sp(c).edit().putFloat("voice_hw_score", clampF(v, 0.1f, 5f)).apply();
    }

    /**
     * 用户教给语音的别名：「包名=别名1|别名2;包名=别名3」。
     * 识别结果匹配不到应用时会弹候选卡片，用户点一下就把当时的说法记进来。
     *
     * 注意：英文应用名的 ASR 汉字输出**每次都不一样**，所以这些别名不再被当作
     * 「下次精确命中」的钥匙，而是两种用途：①转声母骨架做候选排序；
     * ②写进识别热词表，让解码逐渐收敛到学过的写法（见 Voice.syncHotwordAliases）。
     */
    public static String voiceAliasRaw(Context c) {
        String s = sp(c).getString("voice_alias", "");
        return s == null ? "" : s;
    }

    /** 追加一条「说法 → 包名」的记忆，同名说法的旧记录会被顶掉 */
    public static void addVoiceAlias(Context c, String pkg, String phrase) {
        if (pkg == null || pkg.isEmpty()) return;
        if (phrase == null || phrase.trim().isEmpty()) return;
        String p = phrase.trim();
        java.util.Map<String, java.util.Set<String>> map = parseAlias(voiceAliasRaw(c));
        for (java.util.Iterator<java.util.Map.Entry<String, java.util.Set<String>>> it =
             map.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, java.util.Set<String>> e = it.next();
            e.getValue().remove(p);
            if (e.getValue().isEmpty()) it.remove();
        }
        java.util.Set<String> set = map.get(pkg);
        if (set == null) {
            set = new java.util.LinkedHashSet<>();
            map.put(pkg, set);
        }
        set.add(p);                 // 同一个 app 累积多条，不是覆盖 —— 每次发音吐的字不一样
        writeVoiceAlias(c, map);
    }

    /** 删掉某一条已学说法（设置页别名管理用） */
    public static void removeVoiceAlias(Context c, String pkg, String phrase) {
        java.util.Map<String, java.util.Set<String>> map = parseAlias(voiceAliasRaw(c));
        java.util.Set<String> set = map.get(pkg);
        if (set == null) return;
        set.remove(phrase);
        if (set.isEmpty()) map.remove(pkg);
        writeVoiceAlias(c, map);
    }

    public static void clearVoiceAlias(Context c) {
        sp(c).edit().putString("voice_alias", "").apply();
    }

    private static void writeVoiceAlias(Context c,
                                        java.util.Map<String, java.util.Set<String>> map) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.Set<String>> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=');
            boolean first = true;
            for (String a : e.getValue()) {
                if (!first) sb.append('|');
                sb.append(a);
                first = false;
            }
        }
        sp(c).edit().putString("voice_alias", sb.toString()).apply();
    }

    /** 包名 → 别名集合 */
    public static java.util.Map<String, java.util.Set<String>> parseAlias(String raw) {
        java.util.Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String one : raw.split(";")) {
            int eq = one.indexOf('=');
            if (eq <= 0) continue;
            String pkg = one.substring(0, eq).trim();
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (String a : one.substring(eq + 1).split("\\|")) {
                if (!a.trim().isEmpty()) set.add(a.trim());
            }
            if (!pkg.isEmpty() && !set.isEmpty()) out.put(pkg, set);
        }
        return out;
    }
}
