package com.cz.richdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行小工具：优先直接执行（uid 1000 已具备大部分系统权限），
 * 失败再走 su。异步命令丢到单线程队列，不阻塞 UI。
 *
 * 【加固记录】旧实现有两个能把整条队列卡死的坑：
 *   1) readLine() 循环排在 waitFor() 之前且无超时 —— 子进程不退出（典型：su 在等
 *      root 授权弹窗）时读循环永久阻塞，后面所有命令全部排死；
 *   2) stderr 从不排空 —— 子进程往 stderr 写满 64KB 管道缓冲后写阻塞、父进程等
 *      stdout 的 EOF，双向死锁。
 * 现在的方案：redirectErrorStream 把 stderr 并进 stdout 统一排空；用「定时器销毁
 * 进程」实现超时 —— 进程一死 readLine 立刻拿到 EOF，读循环必然结束。
 * 另外 run() 的队列改成有界（64）+ 丢最旧：触摸兜底通道高频灌命令时，旧帧没有
 * 重放的必要，宁可丢也不让队列无限膨胀。
 */
public class Shell {

    private static final String TAG = "PipDesk";
    private static final ThreadPoolExecutor EXEC = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64),
            r -> {
                Thread t = new Thread(r, "pipdesk-shell");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
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
        java.util.Timer killer = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);       // stderr 并进 stdout：不排空会写死子进程
            p = pb.start();
            p.getOutputStream().close();        // 我们从不喂 stdin，关掉防子进程等输入
            // 4s 超时 = 到点销毁进程：readLine 立刻拿到 EOF，读循环必然退出
            killer = new java.util.Timer("pipdesk-shell-kill", true);
            final Process fp = p;
            killer.schedule(new java.util.TimerTask() {
                @Override public void run() { fp.destroy(); }
            }, 4000);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            int code = p.waitFor();             // 读到 EOF 时进程必已退出或被销毁
            if (code == 0) return true;
            if ("su".equals(argv[0])) hasSu = (code != 127);
            return false;
        } catch (Throwable t) {
            if ("su".equals(argv[0])) hasSu = Boolean.FALSE;
            return false;
        } finally {
            if (killer != null) killer.cancel();
            if (p != null) p.destroy();
        }
    }
}
