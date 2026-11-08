package com.cz.richdesk;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 数据目录标签自愈。
 *
 * uid 1000 的后装应用，PMS 建出来的目录标签是 system_data_file，
 * 而进程实际跑在 system_app 域里写不进去 → SharedPreferences 存不住，配置每次重启就丢。
 * 每次安装/升级后标签都会被重置，所以启动时探测一次，写不进去就 chcon 纠正。
 *
 * 注意：不能拿 getFilesDir() 探活 —— 目录建不出来时它返回 null，会直接把探测线程 NPE 掉，
 * 所以这里一律用 getDataDir() 拼路径，并且整段都包在 try 里。
 */
public class DataDirFix {

    private static final String TAG = "PipDesk";
    private static Boolean healthy;

    /** 数据目录是否可写（配置能不能存住） */
    public static boolean ok() {
        return Boolean.TRUE.equals(healthy);
    }

    /** 健康时只是一次 createNewFile，可以直接在主线程调；不健康才会去 chcon（较慢）。 */
    public static void ensure(Context ctx) {
        if (Boolean.TRUE.equals(healthy)) return;
        try {
            File dataDir = ctx.getDataDir();
            File files = new File(dataDir, "files");
            if (probe(files)) {
                healthy = Boolean.TRUE;
                return;
            }

            Log.w(TAG, "数据目录不可写，尝试修正 SELinux 标签: " + dataDir);
            String cmd = "chcon -R u:object_r:system_app_data_file:s0 "
                    + dataDir.getAbsolutePath()
                    + " /data/user_de/0/" + ctx.getPackageName();
            boolean ok = Shell.runSync(cmd);
            Log.i(TAG, "chcon 结果=" + ok + " 重试可写=" + probe(files));
            if (probe(files)) healthy = Boolean.TRUE;
        } catch (Throwable t) {
            Log.w(TAG, "数据目录检查异常", t);
        }
    }

    /** 真写一次文件，能写才算健康 */
    private static boolean probe(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File p = new File(dir, ".rw_probe");
            boolean ok = p.createNewFile() || p.exists();
            //noinspection ResultOfMethodCallIgnored
            p.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }
}
