package com.cz.richdesk;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.io.File;

/**
 * 唤醒词（KWS）—— 常驻跑的那一层。
 *
 * 模型：sherpa-onnx-kws-zipformer-wenetspeech-3.3M（int8 共 4.7MB）。
 * 它的解码端被约束成"只允许 keywords.txt 里那几串拼音"，所以：
 *   - 不需要 ASR 就能判断"刚才有没有说唤醒词"，常驻开销约 1% 单核；
 *   - 唤醒词是拼音 token 拼出来的（n ǐ h ǎo x iǎo zh uō @你好小桌），
 *     改词只改 assets/kws/keywords.txt，不用重新训练模型。
 *
 * 调用约定：所有方法都跑在采集线程上，单线程使用，不做并发保护。
 */
final class VoiceKws {

    private static final String TAG = "PipDesk";

    private final Context ctx;
    private KeywordSpotter spotter;
    private OnlineStream stream;
    private String loadError;
    private int hits;

    VoiceKws(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    String loadError() {
        return loadError;
    }

    boolean isReady() {
        return spotter != null;
    }

    /**
     * 加载模型（秒级，必须在后台线程）。
     *
     * @param keywordsFile 绝对路径的自定义关键词文件；null = 用 assets 里自带的默认词。
     *                     自定义词由 {@link VoiceWords} 现场生成（汉字→拼音音素）。
     */
    boolean load(int thresholdPercent, String keywordsFile) {
        // 自定义唤醒词必须走「纯文件模式」：sherpa 的 ReadFile 是这么分流的 ——
        // assetManager 非 null 时它就拿 AAssetManager_open 去开这个路径，
        // 绝对路径必然开不出来，然后 SHERPA_ONNX_LOGE + exit(-1) **直接杀掉整个进程**。
        // 表现就是桌面每秒重启一次、屏幕一直闪（0.21.0 的真实事故）。
        // 所以只要用绝对路径，constructor 的第一参就必须是 null，模型也只能给绝对路径。
        if (keywordsFile != null && keywordsOk(keywordsFile)) {
            String dir = VoiceAssets.extract(ctx, "kws");
            if (dir != null && modelsOk(dir) && tryLoad(dir, thresholdPercent, keywordsFile, true)) {
                return true;
            }
            FileLog.append("VOICE 自定义唤醒词不可用，退回默认词（不崩）");
        } else if (keywordsFile != null) {
            FileLog.append("VOICE 自定义词文件校验不过（空/损坏），退回默认词：" + keywordsFile);
        }

        if (tryLoad(VoiceAssets.assetPrefix("kws"), thresholdPercent, null, false)) return true;
        // assets 直读失败：解压到 filesDir 再用绝对路径重试一次（见 VoiceAssets 注释）
        String alt = VoiceAssets.extract(ctx, "kws");
        if (alt != null && modelsOk(alt) && tryLoad(alt, thresholdPercent, null, true)) {
            FileLog.append("VOICE 唤醒词模型改从 filesDir 加载：" + alt);
            return true;
        }
        loadError = "唤醒词模型加载失败（assets 与 filesDir 两条路都失败）";
        FileLog.append("VOICE " + loadError);
        release();
        return false;
    }

    /** 关键词文件预检：native 层读不到会 exit，所以宁可在这里判死也不交给它 */
    private static boolean keywordsOk(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() <= 0 || f.length() > 64 * 1024) return false;
            // 合法内容形如「n ǐ h ǎo @你好小瑞」：至少要有空格和 @
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line = r.readLine();
                return line != null && line.indexOf(' ') > 0 && line.lastIndexOf('@') > 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 四个模型文件都得在，否则 newFromFile 一样会 exit */
    private static boolean modelsOk(String prefix) {
        String[] names = {"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"};
        for (String n : names) {
            File f = new File(prefix + n);
            if (!f.isFile() || f.length() <= 0) return false;
        }
        return true;
    }

    private boolean tryLoad(String prefix, int thresholdPercent, String keywordsFile, boolean fileOnly) {
        long t0 = System.currentTimeMillis();
        try {
            KeywordSpotterConfig c = new KeywordSpotterConfig();
            FeatureConfig f = c.getFeatConfig();
            f.setSampleRate(VoiceMic.SR);
            f.setFeatureDim(80);
            OnlineModelConfig m = c.getModelConfig();
            m.getTransducer().setEncoder(prefix + "encoder.int8.onnx");
            m.getTransducer().setDecoder(prefix + "decoder.onnx");
            m.getTransducer().setJoiner(prefix + "joiner.int8.onnx");
            m.setTokens(prefix + "tokens.txt");
            m.setNumThreads(1);            // 单线程足够：3.3M 参数、每 100ms 喂一次
            m.setProvider("cpu");
            m.setDebug(false);
            c.setKeywordsFile(keywordsFile != null ? keywordsFile : prefix + "keywords.txt");
            c.setKeywordsScore(1.5f);
            // 百分数 → 模型阈值：10% 对应 0.10（最松），90% 对应 0.90（最严）
            c.setKeywordsThreshold(Math.max(0.05f, Math.min(0.95f, thresholdPercent / 100f)));
            c.setNumTrailingBlanks(2);
            c.setMaxActivePaths(4);

            KeywordSpotter s = new KeywordSpotter(fileOnly ? null : ctx.getAssets(), c);
            OnlineStream st = s.createStream("");
            spotter = s;
            stream = st;
            loadError = null;
            String msg = "VOICE 唤醒词模型加载完成 " + (System.currentTimeMillis() - t0) + "ms"
                    + " 阈值=" + c.getKeywordsThreshold() + " prefix=" + prefix
                    + (fileOnly ? "（纯文件模式）" : "");
            Log.i(TAG, msg);
            FileLog.append(msg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "kws load 失败 prefix=" + prefix, t);
            FileLog.append("VOICE 唤醒词加载失败(" + prefix + ") " + t);
            release();
            return false;
        }
    }

    /**
     * 喂一段 PCM。
     * @return 命中的唤醒词（如「你好小桌」），没命中返回 null
     */
    String accept(short[] pcm, int n) {
        KeywordSpotter s = spotter;
        OnlineStream st = stream;
        if (s == null || st == null) return null;
        try {
            float[] f = new float[n];
            for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
            st.acceptWaveform(f, VoiceMic.SR);
            while (s.isReady(st)) s.decode(st);
            KeywordSpotterResult r = s.getResult(st);
            String kw = r == null ? null : r.getKeyword();
            if (kw != null && !kw.isEmpty()) {
                s.reset(st);
                hits++;
                return kw.trim();
            }
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "kws accept", t);
            FileLog.append("VOICE 唤醒词解码异常 " + t);
            // 模型状态可能已经坏了：丢掉重建，下一次采集重新开始
            release();
            return null;
        }
    }

    /** 从音频里检测完之后调用：清掉当前流的上下文，避免同一个词连触发 */
    void resetStream() {
        if (spotter != null && stream != null) {
            try {
                spotter.reset(stream);
            } catch (Throwable ignored) {
            }
        }
    }

    int hits() {
        return hits;
    }

    void release() {
        try {
            if (stream != null) stream.release();
        } catch (Throwable ignored) {
        }
        try {
            if (spotter != null) spotter.release();
        } catch (Throwable ignored) {
        }
        stream = null;
        spotter = null;
    }
}
