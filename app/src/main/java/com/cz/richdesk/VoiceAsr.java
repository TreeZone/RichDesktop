package com.cz.richdesk;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * 离线中文识别（只在"已经开始听"之后才工作）。
 *
 * 模型：sherpa-onnx-streaming-zipformer-zh-14M（int8 共 24MB）。
 * 选它而不是几十 MB 的通用大模型的理由：我们的命令空间是封闭的
 * （打开某个应用 / 十来个内置指令），识别错一两个字由 {@link VoiceIntent}
 * 的模糊匹配兜住，不需要通用识别的高精度。要换更强的模型，
 * 把 assets/asr/ 下四个文件替换成同名文件即可，代码不用动。
 *
 * 用例是"整句说完 → 端点 → 出最终文本"，所以流式部分只用来做实时字幕。
 */
final class VoiceAsr {

    private static final String TAG = "PipDesk";

    private final Context ctx;
    private OnlineRecognizer rec;
    private OnlineStream stream;
    private String loadError;
    private long loadedAt;

    VoiceAsr(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    boolean isReady() {
        return rec != null;
    }

    String loadError() {
        return loadError;
    }

    long loadedAt() {
        return loadedAt;
    }

    /** 加载模型（1~2 秒，必须在后台线程，且别放在开机首帧路径上） */
    boolean load() {
        if (rec != null) return true;
        if (tryLoad(VoiceAssets.assetPrefix("asr"))) return true;
        String alt = VoiceAssets.extract(ctx, "asr");
        if (alt != null && tryLoad(alt)) {
            FileLog.append("VOICE 识别模型改从 filesDir 加载：" + alt);
            return true;
        }
        loadError = "识别模型加载失败（assets 与 filesDir 两条路都失败）";
        FileLog.append("VOICE " + loadError);
        release();
        return false;
    }

    private boolean tryLoad(String prefix) {
        long t0 = System.currentTimeMillis();
        try {
            OnlineRecognizerConfig c = new OnlineRecognizerConfig();
            FeatureConfig f = c.getFeatConfig();
            f.setSampleRate(VoiceMic.SR);
            f.setFeatureDim(80);
            OnlineModelConfig m = c.getModelConfig();
            m.getTransducer().setEncoder(prefix + "encoder.int8.onnx");
            m.getTransducer().setDecoder(prefix + "decoder.onnx");
            m.getTransducer().setJoiner(prefix + "joiner.int8.onnx");
            m.setTokens(prefix + "tokens.txt");
            // 2 线程：这台机器核多但有画中画在跑，给 2 个核能把一句话的延迟压到 0.5s 内，
            // 再多开始和渲染抢 CPU，反而掉帧。
            m.setNumThreads(2);
            m.setProvider("cpu");
            m.setDebug(false);
            c.setDecodingMethod("greedy_search");
            c.setEnableEndpoint(true);
            // 端点规则用默认值：说完 ~1.2s 静音就断句，单句上限 20s。
            // 车机噪声大时"说完不等"比"等太久"更讨人厌，所以不把静音阈值调长。

            OnlineRecognizer r = new OnlineRecognizer(ctx.getAssets(), c);
            rec = r;
            stream = null;
            loadedAt = System.currentTimeMillis();
            loadError = null;
            String msg = "VOICE 识别模型加载完成 " + (loadedAt - t0) + "ms prefix=" + prefix;
            Log.i(TAG, msg);
            FileLog.append(msg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "asr load 失败 prefix=" + prefix, t);
            FileLog.append("VOICE 识别模型加载失败(" + prefix + ") " + t);
            release();
            return false;
        }
    }

    /** 开始一句话：清空上文的解码状态 */
    void begin() {
        if (rec == null) return;
        try {
            if (stream == null) stream = rec.createStream("");
            else rec.reset(stream);
            recognized = "";
        } catch (Throwable t) {
            Log.e(TAG, "asr begin", t);
        }
    }

    private String recognized = "";

    /**
     * 喂一段 PCM，返回当前已识别出的文本（流式，用于实时字幕）。
     * 结束时看 {@link #isEndpoint()}。
     */
    String accept(short[] pcm, int n) {
        OnlineRecognizer r = rec;
        OnlineStream st = stream;
        if (r == null || st == null) return recognized;
        try {
            float[] f = new float[n];
            for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
            st.acceptWaveform(f, VoiceMic.SR);
            while (r.isReady(st)) r.decode(st);
            OnlineRecognizerResult res = r.getResult(st);
            if (res != null && res.getText() != null) recognized = res.getText().trim();
            return recognized;
        } catch (Throwable t) {
            Log.e(TAG, "asr accept", t);
            FileLog.append("VOICE 识别异常 " + t);
            return recognized;
        }
    }

    /** 是否已经断句（说完静音足够长 / 单句超长） */
    boolean isEndpoint() {
        try {
            return rec != null && stream != null && rec.isEndpoint(stream);
        } catch (Throwable t) {
            return false;
        }
    }

    String text() {
        return recognized;
    }

    void release() {
        try {
            if (stream != null) stream.release();
        } catch (Throwable ignored) {
        }
        try {
            if (rec != null) rec.release();
        } catch (Throwable ignored) {
        }
        stream = null;
        rec = null;
        recognized = "";
    }
}
