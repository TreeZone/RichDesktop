package com.cz.richdesk;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 模型资源定位。
 *
 * 默认直接读 APK 里的 assets（sherpa-onnx 原生层用 AAssetManager 读，不用先拷贝，
 * 首帧不会被几十兆的解压拖住）。但个别 ROM / 个别 aapt 配置下"压缩资源 + 原生读取"
 * 会失败，表现就是模型加载抛异常。
 *
 * 所以这里准备了一条退路：加载失败时把 assets 解到 filesDir，再用**绝对路径**重建一次。
 * sherpa 的路径处理是"以 / 开头就当真实文件"，所以换前缀就够了，代码其余部分不用动。
 * 代价是首次失败后多一次几十兆的拷贝 + 一份存储，比"语音功能直接不可用"划算。
 *
 * 【防崩溃关键】解压目录放一个 .modeltag 指纹文件。换了模型（标贝→melo 等）必须改
 * TTS_MODEL_TAG。否则 extract 会因"文件已存在"跳过拷贝，用旧模型配新配置去加载，
 * sherpa 原生层直接 abort，Java 的 try/catch 抓不住，表现就是"选中就闪退"。
 */
final class VoiceAssets {

    private static final String TAG = "PipDesk";

    /** 当前内置 TTS 模型指纹：换了模型（标贝→melo→huayan→标贝）必须同步改这个字符串 */
    private static final String TTS_MODEL_TAG = "matcha-baker-zh-v1";

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
            File tagFile = new File(outDir, ".modeltag");
            // 指纹不符（首次 / 换模型 / 残留旧模型）→ 清掉整个目录再解压，避免新旧模型混用
            if (!tagMatches(tagFile, TTS_MODEL_TAG)) {
                if (outDir.exists()) {
                    FileLog.append("VOICE 模型指纹不符，清理旧目录 " + outDir);
                    deleteRecursively(outDir);
                }
                if (!outDir.mkdirs()) {
                    FileLog.append("VOICE 模型解压失败：建目录 " + outDir + " 不成功");
                    return null;
                }
            } else if (!outDir.exists() && !outDir.mkdirs()) {
                FileLog.append("VOICE 模型解压失败：建目录 " + outDir + " 不成功");
                return null;
            }
            AssetManager am = c.getAssets();
            long t0 = System.currentTimeMillis();
            int n = copyAssetsRecursively(am, dir, outDir);
            if (n <= 0) {
                FileLog.append("VOICE 模型解压失败：assets/" + dir + " 是空的");
                return null;
            }
            writeTag(tagFile, TTS_MODEL_TAG);
            String prefix = outDir.getAbsolutePath() + "/";
            FileLog.append("VOICE 模型已解压到 " + prefix
                    + "（" + (System.currentTimeMillis() - t0) + "ms，" + n + " 个文件）");
            return prefix;
        } catch (Throwable t) {
            Log.e(TAG, "extract " + dir, t);
            FileLog.append("VOICE 模型解压异常：" + t);
            return null;
        }
    }

    private static boolean tagMatches(File tagFile, String expected) {
        if (!tagFile.isFile()) return false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(tagFile)))) {
            String line = r.readLine();
            return expected.equals(line);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeTag(File tagFile, String tag) {
        try (OutputStream os = new FileOutputStream(tagFile)) {
            os.write((tag + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    /** 递归删除目录（清旧模型残留用） */
    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File ch : children) {
                if (ch.isDirectory()) deleteRecursively(ch);
                else ch.delete();
            }
        }
        dir.delete();
    }

    /** 递归拷贝 assets 子树（melo 等模型带 dict/ 子目录必须跟着解出来） */
    private static int copyAssetsRecursively(AssetManager am, String assetDir, File outDir)
            throws IOException {
        int count = 0;
        String[] items = am.list(assetDir);
        if (items == null) return count;
        if (!outDir.exists() && !outDir.mkdirs()) return count;
        for (String name : items) {
            String assetPath = assetDir + "/" + name;
            String[] children = am.list(assetPath);
            if (children != null && children.length > 0) {
                // 目录：递归
                count += copyAssetsRecursively(am, assetPath, new File(outDir, name));
                continue;
            }
            File f = new File(outDir, name);
            if (f.exists() && f.length() > 0) { count++; continue; }   // 已解过，跳过
            try (InputStream in = am.open(assetPath);
                 OutputStream os = new FileOutputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                os.flush();
            }
            count++;
        }
        return count;
    }
}
