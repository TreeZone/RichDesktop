package com.cz.richdesk;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * RECORD_AUDIO 是 dangerous 级权限：即使 uid 1000 + priv-app，targetSdk 30 下
 * 也不一定自动给（privapp 白名单只管 privileged 级权限）。
 *
 * 而语音是常驻链路，弹一次系统权限框的体验在车机上很糟（而且桌面是 HOME，
 * 框弹出来会把桌面盖住）。所以按"越权能力从强到弱"试三条路：
 *   1) AppOps 自己改自己的录音 op（system uid 有 MANAGE_APP_OPS_MODES）；
 *   2) `pm grant`（uid 1000 调 cmd package 通常能过）；
 *   3) 都不行才让桌面去弹系统权限框（见 MainActivity.requestVoicePermission）。
 *
 * 这三步的任何一步失败都只记日志，不影响桌面本身。
 */
final class VoicePerm {

    private static final String TAG = "PipDesk";
    private static final String PERM = Manifest.permission.RECORD_AUDIO;
    /** AppOpsManager.OPSTR_RECORD_AUDIO 对应的 op 号（AOSP 长期稳定为 27） */
    private static final int OP_RECORD_AUDIO = 27;

    private VoicePerm() { }

    static boolean has(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 尝试在后台把权限拿到（不弹框）。<b>可能耗时（会 fork pm），别在主线程调。</b>
     * @return 最终是否可用
     */
    static boolean tryGrant(Context c) {
        if (c == null) return false;
        if (has(c)) return true;
        String pkg = c.getPackageName();

        // ① AppOps：最直接，不需要外部进程
        try {
            AppOpsManager am = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (am != null) {
                Method m = AppOpsManager.class.getMethod("setMode",
                        int.class, int.class, String.class, int.class);
                m.invoke(am, OP_RECORD_AUDIO, Process.myUid(), pkg, AppOpsManager.MODE_ALLOWED);
                FileLog.append("VOICE 已通过 AppOps 放开录音 op");
            }
        } catch (Throwable t) {
            FileLog.append("VOICE AppOps 放开录音失败：" + t);
        }
        if (has(c)) return true;

        // ② pm grant 兜底
        try {
            String out = Shell.capture("pm grant " + pkg + " " + PERM);
            FileLog.append("VOICE pm grant 结果：" + (out == null || out.isEmpty() ? "(无输出)" : out.trim()));
        } catch (Throwable t) {
            FileLog.append("VOICE pm grant 异常：" + t);
        }
        boolean ok = has(c);
        if (!ok) {
            Log.w(TAG, "录音权限仍未拿到，需要用户手动授权");
            FileLog.append("VOICE 录音权限未授予：需要在桌面弹一次系统权限框");
        }
        return ok;
    }
}
