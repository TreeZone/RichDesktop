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
        // 熔断：sherpa 的路径错误是 **native 层 exit(-1)**，Java 拦不住、进程当场死
        // （0.21.0 闪屏、0.23.1 点语音图标桌面重启，都是它）。标记在加载前写、
        // 成功后删：一旦 90 秒内发生过加载期崩溃，就先跳过识别模型，保住桌面
        // 不被"点一下语音图标 → 桌面重启"无限循环。改语音设置会清标记重试。
        java.io.File fuse = new java.io.File(ctx.getFilesDir(), "voice/asr.load");
        if (fuse.isFile()
                && System.currentTimeMillis() - fuse.lastModified() < 90_000L) {
            loadError = "识别模型上次加载曾导致进程崩溃，90 秒内先跳过（改语音设置可立即重试）";
            FileLog.append("VOICE " + loadError);
            return false;
        }
        try {
            java.io.File dir = fuse.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            fuse.createNewFile();
        } catch (Throwable ignored) {
        }

        String prefix = VoiceAssets.assetPrefix("asr");
        boolean wantHotwords = Prefs.voiceHotwords(ctx);

        boolean ok = tryLoad(prefix, wantHotwords);
        // 带热词失败就退回无热词。sherpa 对热词校验很严（词里的字不在词表、
        // 或者格式不规范都会让加载整个失败），而语音是整条链路 ——
        // 宁可不偏置，也不能让识别器起不来。
        if (!ok && wantHotwords) {
            FileLog.append("VOICE 带热词加载失败，退回无热词配置（原因见上一条日志）");
            ok = tryLoad(prefix, false);
        }
        if (!ok) {
            String alt = VoiceAssets.extract(ctx, "asr");
            if (alt != null && tryLoad(alt, false)) {
                FileLog.append("VOICE 识别模型改从 filesDir 加载：" + alt);
                ok = true;
            }
        }
        if (ok) {
            try {
                fuse.delete();
            } catch (Throwable ignored) {
            }
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
            /*
             * 【铁律，0.21.0 闪屏、0.23.1 点语音图标桌面重启都是它】
             * sherpa v2.0.1 file-utils.cc 的 ReadFile(AAssetManager*, path)：
             * 绝对路径 + assetManager 非 null → LOGE + exit(-1)，进程当场死，
             * Java 的 try/catch 拦不住。所以路径模式必须二选一：
             *   assets 相对路径 → assetManager 非 null；
             *   绝对路径       → assetManager 传 null（纯文件模式，OnlineRecognizer
             *                    构造器字节码 ifnull → newFromFile，null 合法）。
             * 热词文件固定写在本机 filesDir（绝对路径），所以只要带热词，
             * 模型也一并解到 filesDir，整条链路切纯文件模式。
             *
             * 注意顺序：先把 filesMode / 最终 prefix 定下来，**再**配四个模型路径 ——
             * 上一版就是路径配完才切模式，结果 tokens 还是 'asr/tokens.txt'，
             * newFromFile 按普通文件找不到，整个识别器建不起来。
             */
            boolean filesMode = prefix.startsWith("/");
            String hw = null;
            if (withHotwords) {
                hw = VoiceHotwords.ensure(ctx, hotwordNames);
                if (hw != null && !filesMode) {
                    String abs = VoiceAssets.extract(ctx, "asr");
                    if (abs != null && asrModelsOk(abs)) {
                        prefix = abs;
                        filesMode = true;
                    } else {
                        FileLog.append("VOICE 模型解压不可用，本次不加热词（保持 assets 模式）");
                        hw = null;
                    }
                }
            }

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

            String hwDesc = "hotwords=off";
            if (hw != null) {
                // cjkchar 必须配：它告诉解码器"热词按单字切"，与 tokens.txt 的结构对应
                m.setModelingUnit("cjkchar");
                c.setHotwordsFile(hw);
                c.setHotwordsScore(Prefs.voiceHotwordScore(ctx));
                hwDesc = VoiceHotwords.summary(ctx);
            }

            OnlineRecognizer r = filesMode
                    ? new OnlineRecognizer(null, c)
                    : new OnlineRecognizer(ctx.getAssets(), c);
            rec = r;
            stream = null;
            loadedAt = System.currentTimeMillis();
            loadError = null;
            String msg = "VOICE 识别模型加载完成 " + (loadedAt - t0) + "ms prefix=" + prefix
                    + (filesMode ? "（纯文件模式）" : "（assets 模式）")
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

    /** 纯文件模式前置校验：四个文件都得真在，否则 newFromFile 会整个建不起来 */
    private static boolean asrModelsOk(String prefix) {
        String[] names = {"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"};
        for (String n : names) {
            java.io.File fp = new java.io.File(prefix + n);
            if (!fp.isFile() || fp.length() <= 0) return false;
        }
        return true;
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
