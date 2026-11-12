package com.cz.richdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志落盘（需求：排查车机上的卡死问题）。
 *
 * 实现方式：起一个子进程跑 `logcat -v time`，只抓本应用的标签（PipDesk、AndroidRuntime、
 * 以及注入服务的 CRASH），逐行追加到应用外部专属目录的日志文件里。
 *
 *  - 位置：/sdcard/Android/data/com.cz.richdesk/files/logs/（外部专属目录，无需存储权限，
 *    拔上车/接电脑直接拷走）；外部不可用时退回内部 filesDir；
 *  - 按天一个文件（pipdesk-YYYY-MM-DD.log），单个文件超过 4MB 轮转为 .old（只保留一份），
 *    最多自动清理 7 天前的旧文件，不会撑爆存储；
 *  - 每次 start 追加一行会话分隔符，方便在时间轴上对齐"哪次启动"。
 *
 * 兼容性：logcat / Process 都是 API 1 的能力；读不到日志（个别 ROM 限制）时静默失败，
 * 不影响桌面本身。
 */
public final class FileLog {

    private static final String TAG = "PipDesk";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final long KEEP_DAYS = 7L;

    private static volatile boolean started;
    private static File dir;
    /** logcat 抓取线程 / 看门狗线程 / 主线程都会写文件，必须串行化 */
    private static final Object LOCK = new Object();

    private FileLog() { }

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;

        File ext = ctx.getExternalFilesDir("logs");
        dir = (ext != null) ? ext : new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();

        writeLine("==== session start " + new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  ver=" + BuildInfo.version(ctx) + " ====");

        Thread t = new Thread(() -> {
            cleanOld();
            try {
                // 只抓自己关心的标签；-v time 带时间戳，排查卡死的时间轴刚好用
                Process p = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-v", "time",
                        "PipDesk:V", "AndroidRuntime:E", "CRASH:W", "*:S"});
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                String today = day.format(new Date());
                BufferedWriter w = open(today);
                long written = 0;
                int lines = 0;
                while ((line = r.readLine()) != null) {
                    String d = day.format(new Date());
                    if (!d.equals(today)) {       // 跨天：换文件
                        today = d;
                        closeQuietly(w);
                        w = open(today);
                        written = 0;
                        lines = 0;
                    }
                    if (w != null) {
                        synchronized (LOCK) {       // 与看门狗 dump 串行，避免多行内容交错
                            w.write(line);
                            w.newLine();
                            w.flush();              // 逐行 flush：被杀时也不丢最后的现场
                        }
                        written += line.length() + 1;
                        // 日内轮转：旧实现只在 open() 时判 4MB，当天文件可以无限涨大；
                        // 每 256 行查一次，超限就重开（open 内部会把旧文件轮转成 .old）
                        if (++lines % 256 == 0 && written > MAX_BYTES) {
                            closeQuietly(w);
                            w = open(today);
                            written = 0;
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }, "pipdesk-filelog");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();

        new Handler(Looper.getMainLooper()).post(() ->
                android.util.Log.i(TAG, "日志目录: " + dir.getAbsolutePath()));
    }

    /** 日志目录（设置页展示用） */
    public static String dirPath() {
        return dir == null ? "(未启动)" : dir.getAbsolutePath();
    }

    /** 当前时间戳（看门狗/诊断记录对齐用）。格式化器复用：这个方法在触摸路径上会被调用 */
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public static String nowStamp() {
        synchronized (STAMP) {
            return STAMP.format(new Date());
        }
    }

    private static String today() {
        synchronized (DAY) {
            return DAY.format(new Date());
        }
    }

    /**
     * 单线程日志 IO：append 由调用线程转投这里执行。
     * 旧实现直接在调用线程 open+write+flush —— PipPane.restartApp 这类 UI 回调
     * 触发的调用会在主线程等磁盘。现在统一丢后台，调用方零等待。
     */
    private static final java.util.concurrent.ExecutorService IO =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    // 必须**有界**：原来用 newSingleThreadExecutor → LinkedBlockingQueue 无上限，
                    // 日志产生速度一旦超过磁盘（车机 /sdcard 走 FUSE，每行 flush 更慢），
                    // 队列只进不出，内存随时间线单调上涨 —— 表现就是"跑一段时间越来越卡"。
                    // 满了丢最旧的：日志是排障用的，不能反过来拖垮桌面。
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "pipdesk-filelog-io");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * 追加一段多行文本（看门狗 dump 堆栈用）。与 logcat 抓取线程并行写入，
     * 必须加锁，否则多行内容会互相插进对方中间，堆栈就读不出来了。
     * 任何线程都可调用（内部转后台），不会阻塞调用方。
     */
    public static void append(String text) {
        if (text == null || text.isEmpty() || dir == null) return;
        IO.execute(() -> appendSync(text));
    }

    private static void appendSync(String text) {
        synchronized (LOCK) {
            BufferedWriter w = open(today());
            try {
                if (w != null) {
                    w.write(text);
                    if (!text.endsWith("\n")) w.newLine();
                    w.flush();
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(w);
            }
        }
    }

    /** 直接写一条（不经过 logcat 的事件，例如会话标记、清理结果） */
    private static void writeLine(String s) {
        if (dir == null) return;
        synchronized (LOCK) {
            BufferedWriter w = open(today());
            try {
                if (w != null) {
                    w.write(s);
                    w.newLine();
                    w.flush();
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(w);
            }
        }
    }

    private static BufferedWriter open(String day) {
        try {
            File f = new File(dir, "pipdesk-" + day + ".log");
            if (f.exists() && f.length() > MAX_BYTES) {
                File old = new File(dir, "pipdesk-" + day + ".old.log");
                old.delete();
                f.renameTo(old);
                f = new File(dir, "pipdesk-" + day + ".log");
            }
            return new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void cleanOld() {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
            for (File f : fs) {
                if (f.isFile() && f.lastModified() < cutoff) f.delete();
            }
        } catch (Throwable ignored) { }
    }

    private static void closeQuietly(BufferedWriter w) {
        try { if (w != null) w.close(); } catch (Throwable ignored) { }
    }

    /** 小工具：读版本号，写进会话头，方便区分车机上装的到底是哪个包 */
    private static final class BuildInfo {
        static String version(Context c) {
            try {
                return c.getPackageManager()
                        .getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (Throwable t) {
                return "?";
            }
        }
    }
}
