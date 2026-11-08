package com.cz.richdesk;

/**
 * 高频调试日志的总开关。
 *
 * 为什么要有它：桌面上每次触摸（DOWN / UP / 每个手势）原本都要拼 3~5 条日志，
 * 而 FileLog 把每条日志都**立即 flush 落盘**。长期开着用，日志 I/O 是实打实的
 * 负担（而且 Watchdog.touch 每条都要 new 一个 SimpleDateFormat）。这些日志只在
 * 排查触摸链路时有用，正常使用完全不需要。
 *
 * 打开方式：在 /sdcard 建一个空文件 pipdesk_verbose，重启桌面即生效
 * （设置页里也会显示当前状态，排查完删掉文件即可）。
 */
public final class PipDebug {

    private static final String TRIGGER = "/sdcard/pipdesk_verbose";

    /** 是否输出高频日志（触摸轨迹、手势判定、注入细节） */
    public static final boolean VERBOSE = detect();

    private PipDebug() { }

    private static boolean detect() {
        try {
            return new java.io.File(TRIGGER).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 高频日志：只有 VERBOSE 才真的写 logcat / 落盘 */
    public static void log(String msg) {
        if (VERBOSE) android.util.Log.i("PipDesk", msg);
    }

    /** 高频告警同样收敛（默认不写，避免刷盘） */
    public static void warn(String msg) {
        if (VERBOSE) android.util.Log.w("PipDesk", msg);
    }
}
