package com.cz.richdesk;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 模型资源定位。
 *
 * 默认直接读 APK 里的 assets（sherpa-onnx 原生层用 AAssetManager 读，不用先拷贝，
 * 首帧不会被 24MB 的解压拖住）。但个别 ROM / 个别 aapt 配置下"压缩资源 + 原生读取"
 * 会失败，表现就是模型加载抛异常。
 *
 * 所以这里准备了一条退路：加载失败时把 assets 解到 filesDir，再用**绝对路径**重建一次。
 * sherpa 的路径处理是"以 / 开头就当真实文件"，所以换前缀就够了，代码其余部分不用动。
 * 代价是首次失败后多一次几十兆的拷贝 + 一份存储，比"语音功能直接不可用"划算。
 */
final class VoiceAssets {

    private static final String TAG = "PipDesk";

    private VoiceAssets() { }

    /** assets 前缀（sherpa 认的相对路径） */
    static String assetPrefix(String dir) {
        return dir + "/";
    }

    /**
     * 把 assets/<dir> 整个解到 filesDir/voice/<dir>，返回带结尾斜杠的绝对路径前缀。
     * 失败返回 null。
     */
    static String extract(Context c, String dir) {
        try {
            File outDir = new File(c.getFilesDir(), "voice/" + dir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                FileLog.append("VOICE 模型解压失败：建目录 " + outDir + " 不成功");
                return null;
            }
            AssetManager am = c.getAssets();
            String[] files = am.list(dir);
            if (files == null || files.length == 0) {
                FileLog.append("VOICE 模型解压失败：assets/" + dir + " 是空的");
                return null;
            }
            long t0 = System.currentTimeMillis();
            for (String name : files) {
                File f = new File(outDir, name);
                if (f.exists() && f.length() > 0) continue;      // 已经解过，跳过
                InputStream in = null;
                OutputStream os = null;
                try {
                    in = am.open(dir + "/" + name);
                    os = new FileOutputStream(f);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) { }
                    if (os != null) try { os.close(); } catch (Throwable ignored) { }
                }
            }
            String prefix = outDir.getAbsolutePath() + "/";
            FileLog.append("VOICE 模型已解压到 " + prefix
                    + "（" + (System.currentTimeMillis() - t0) + "ms，"
                    + files.length + " 个文件）");
            return prefix;
        } catch (Throwable t) {
            Log.e(TAG, "extract " + dir, t);
            FileLog.append("VOICE 模型解压异常：" + t);
            return null;
        }
    }
}
