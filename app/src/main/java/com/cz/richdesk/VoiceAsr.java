package com.cz.richdesk;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.util.List;

/**
 * 离线中文识别（只在"已经开始听"之后才工作）。
 *
 * 模型：sherpa-onnx-streaming-zipformer-zh-14M（int8 共 24MB）。
 * 它的词表是 5539 个 token、其中 5508 个**单汉字** —— 一个英文字母都没有，
 * 所以它先天性念不了「YouTube」「Spotify」这种名字（这个限制改不了，
 * 只能靠 VoiceAliases 的谐音别名在匹配层兜住）。
 *
 * 针对"识别率差"做了三件事：
 *  1) 解码从 greedy_search 换成 modified_beam_search（beam=4）：噪声里 greedy 会跳字，
 *     「打开酷狗」掉成「打开酷」这种错，匹配层再宽容也救不回来。
 *  2) 把本机应用名做成热词表做上下文偏置（VoiceHotwords），专有名词识别率提升最明显。
 *  3) 热词加载失败自动退回无热词配置 —— 热词校验很严，不能因为一个生僻字
 *     把整条语音链路带死。
 *
 * 要换更强的模型（比如中英双语的 stream-zipformer-bilingual-zh-en），
 * 把 assets/asr/ 下四个文件替换成同名文件即可，代码不用动。
 */
final class VoiceAsr {

    private static final String TAG = "PipDesk";
    /** 束搜索宽度：4 是 sherpa 示例里精度/延迟的常用折中 */
    private static final int BEAM_SIZE = 4;

    private final Context ctx;
    private OnlineRecognizer rec;
    private OnlineStream stream;
    private String loadError;
    private long loadedAt;
    /** 热词候选（本机应用名），由 Voice 在加载前灌进来；null = 不加偏置 */
    private List<String> hotwordNames;

    VoiceAsr(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 热词候选。必须在 load() 之前调用，加载后再改不会生效（要重建识别器） */
    void setHotwordNames(List<String> names) {
        this.hotwordNames = names;
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
        String prefix = VoiceAssets.assetPrefix("asr");
        boolean wantHotwords = Prefs.voiceHotwords(ctx);

        if (tryLoad(prefix, wantHotwords)) return true;
        // 带热词失败就退回无热词。sherpa 对热词校验很严（词里的字不在词表、
        // 或者格式不规范都会让加载整个失败），而语音是整条链路 ——
        // 宁可不偏置，也不能让识别器起不来。
        if (wantHotwords) {
            FileLog.append("VOICE 带热词加载失败，退回无热词配置（原因见上一条日志）");
            if (tryLoad(prefix, false)) return true;
        }
        String alt = VoiceAssets.extract(ctx, "asr");
        if (alt != null && tryLoad(alt, false)) {
            FileLog.append("VOICE 识别模型改从 filesDir 加载：" + alt);
            return true;
        }
        loadError = "识别模型加载失败（assets 与 filesDir 两条路都失败）";
        FileLog.append("VOICE " + loadError);
        release();
        return false;
    }

    private boolean tryLoad(String prefix, boolean withHotwords) {
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

            // 解码方式：beam 抗跳字，代价约 2 倍 CPU（14M 模型 2 线程仍然实时）
            if (Prefs.voiceBeam(ctx)) {
                c.setDecodingMethod("modified_beam_search");
                c.setMaxActivePaths(BEAM_SIZE);
            } else {
                c.setDecodingMethod("greedy_search");
            }

            c.setEnableEndpoint(true);
            // 端点规则用默认值：说完 ~1.2s 静音就断句，单句上限 20s。
            // 车机噪声大时"说完不等"比"等太久"更讨人厌，所以不把静音阈值调长。

            // 上下文偏置（应用名热词）
            String hwDesc = "hotwords=off";
            if (withHotwords) {
                String hw = VoiceHotwords.ensure(ctx, hotwordNames);
                if (hw != null) {
                    // cjkchar 必须配：它告诉解码器"热词按单字切"，与 tokens.txt 的结构对应
                    m.setModelingUnit("cjkchar");
                    c.setHotwordsFile(hw);
                    c.setHotwordsScore(Prefs.voiceHotwordScore(ctx));
                    hwDesc = VoiceHotwords.summary(ctx);
                } else {
                    hwDesc = "hotwords=(无可用词)";
                }
            }

            OnlineRecognizer r = new OnlineRecognizer(ctx.getAssets(), c);
            rec = r;
            stream = null;
            loadedAt = System.currentTimeMillis();
            loadError = null;
            String msg = "VOICE 识别模型加载完成 " + (loadedAt - t0) + "ms prefix=" + prefix
                    + (Prefs.voiceBeam(ctx) ? " beam=" + BEAM_SIZE : " greedy")
                    + " " + hwDesc;
            Log.i(TAG, msg);
            FileLog.append(msg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "asr load 失败 prefix=" + prefix + " hotwords=" + withHotwords, t);
            FileLog.append("VOICE 识别模型加载失败(" + prefix + " hotwords=" + withHotwords
                    + ") " + t);
            release();
            loadError = "识别模型加载失败：" + t;
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
