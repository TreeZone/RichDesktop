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
