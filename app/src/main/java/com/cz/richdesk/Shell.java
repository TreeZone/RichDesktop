package com.cz.richdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 命令执行小工具：优先直接执行（uid 1000 已具备大部分系统权限），
 * 失败再走 su。全部丢到单线程队列，不阻塞 UI。
 */
public class Shell {

    private static final String TAG = "PipDesk";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static Boolean hasSu;

    public static void run(final String cmd) {
        EXEC.execute(() -> {
            if (exec(new String[]{"sh", "-c", cmd})) return;
            if (Boolean.FALSE.equals(hasSu)) return;
            if (exec(new String[]{"su", "-c", cmd})) return;
            Log.w(TAG, "命令执行失败: " + cmd);
        });
    }

    /** 同步执行（调用方自己保证在后台线程） */
    public static boolean runSync(String cmd) {
        if (exec(new String[]{"sh", "-c", cmd})) return true;
        return exec(new String[]{"su", "-c", cmd});
    }

    private static boolean exec(String[] argv) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(argv);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            int code = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
            if (code == 0) return true;
            if ("su".equals(argv[0])) hasSu = (code != 127);
            return false;
        } catch (Throwable t) {
            if ("su".equals(argv[0])) hasSu = Boolean.FALSE;
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
