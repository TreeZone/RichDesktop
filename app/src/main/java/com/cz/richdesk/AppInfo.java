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

    /** 所有伪包名共用的前缀：凭它区分"桌面内置快捷方式"和真实应用 */
    public static final String SCHEME = "pipdesk://";

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
