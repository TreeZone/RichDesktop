package com.cz.richdesk;

import android.graphics.drawable.Drawable;

/** 一个可启动应用的描述 */
public class AppInfo {

    /**
     * 伪包名：Dock 里的「深浅模式」图标。
     * 它不是真应用，只在 Dock 里占一格，点了切换深/浅色；
     * 用带 scheme 的串是为了绝不会和真实包名撞车。
     */
    public static final String THEME_PKG = "pipdesk://theme-toggle";
    /** 伪包名：桌面设置（绑到 Dock 时用） */
    public static final String SETTINGS_PKG = "pipdesk://desktop-settings";
    /** 伪包名：重启桌面 */
    public static final String RESTART_PKG = "pipdesk://restart-desktop";
    /** 伪包名：返回（安卓返回键） */
    public static final String BACK_PKG = "pipdesk://nav-back";
    /** 伪包名：主页（安卓主页键） */
    public static final String HOME_PKG = "pipdesk://nav-home";
    /** 伪包名：应用列表（翻到应用页；已在应用页则翻回主页） */
    public static final String APP_LIST_PKG = "pipdesk://app-list";
    /** 伪包名：息屏（PowerManager.goToSleep，双击屏幕可唤醒） */
    public static final String SLEEP_PKG = "pipdesk://sys-sleep";
    /** 伪包名：最近任务（KEYCODE_APP_SWITCH） */
    public static final String RECENTS_PKG = "pipdesk://sys-recents";
    /** 伪包名：音量加 */
    public static final String VOL_UP_PKG = "pipdesk://vol-up";
    /** 伪包名：音量减 */
    public static final String VOL_DOWN_PKG = "pipdesk://vol-down";
    /*
     * 下面五个是**只给语音用**的媒体控制伪包名（应用列表 / Dock 里不出现）：
     * 播报式操作放按钮上没有意义，但「说一句就能控制」很实用。
     * 走的是和其它内置快捷方式同一条 runBuiltin() 分发，见 Media.java。
     */
    public static final String MEDIA_PLAY_PKG = "pipdesk://media-play";
    public static final String MEDIA_PAUSE_PKG = "pipdesk://media-pause";
    public static final String MEDIA_NEXT_PKG = "pipdesk://media-next";
    public static final String MEDIA_PREV_PKG = "pipdesk://media-prev";
    /** 静音切换（说「静音」静音，再说一次恢复） */
    public static final String MEDIA_MUTE_PKG = "pipdesk://media-mute";
    /**
     * 伪包名：语音助手（点一下开始听，说完自动收工；常驻唤醒词在设置里开）。
     * 它单独占一格快捷方式，是因为语音最需要「一眼能看到、一下能按到」，
     * 而不是藏进设置页。
     */
    public static final String VOICE_PKG = "pipdesk://voice";

    /** 所有伪包名共用的前缀：凭它区分"桌面内置快捷方式"和真实应用 */
    public static final String SCHEME = "pipdesk://";

    /**
     * 暂时隐藏的内置快捷方式（不再出现在任何可选列表 / 快捷区 / 应用列表里）。
     *
     * 音量 ±：车机上方向盘走的是 Vehicle HAL / 功放 DSP 的独立通道，
     * 我们只能调 Android 的 STREAM_MUSIC，两边数值根本不联动（见项目日志 11-11），
     * 与其留两个按了没反应的按钮，先藏掉。代码路径保留，想恢复只需从这里删掉。
     */
    private static final java.util.Set<String> HIDDEN_PKGS =
            new java.util.HashSet<>(java.util.Arrays.asList(VOL_UP_PKG, VOL_DOWN_PKG));

    /** 这个内置快捷方式是否已隐藏 */
    public static boolean isHidden(String pkg) {
        return pkg != null && HIDDEN_PKGS.contains(pkg);
    }

    public final String label;
    public final String pkg;
    public final Drawable icon;

    public AppInfo(String label, String pkg, Drawable icon) {
        this.label = label;
        this.pkg = pkg;
        this.icon = icon;
    }

    public boolean isThemeToggle() {
        return THEME_PKG.equals(pkg);
    }

    /** 是不是桌面内置的快捷方式（深浅模式 / 重启桌面 / 返回 / 主页） */
    public boolean isBuiltin() {
        return pkg != null && pkg.startsWith(SCHEME);
    }

    public static boolean isBuiltin(String pkg) {
        return pkg != null && pkg.startsWith(SCHEME);
    }
}
