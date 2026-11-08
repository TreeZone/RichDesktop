package com.cz.richdesk;

/**
 * 极简的进程内状态：桌面（MainActivity）当前是不是前台。
 *
 * 用途：虚拟屏被释放时，屏内应用的 task 会被系统搬到主屏并顶到最前面
 * （表现就是"画中画没了，应用变成全屏，桌面被盖住"）。这种情况下要主动把桌面拉回前台。
 * 但如果用户此刻在设置页，就绝不能抢焦点，所以要先判断桌面是否真的在前台。
 */
public class AppState {

    /** MainActivity 是否 resumed */
    public static volatile boolean mainResumed = false;

    private AppState() { }
}
