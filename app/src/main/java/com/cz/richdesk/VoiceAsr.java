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
 * <h3>模型（0.23.25 起）：streaming-zipformer-small-ctc-zh-int8-2025-04-01</h3>
 * 单文件 CTC（model.int8.onnx 25MB + tokens.txt），体积与旧的 14M 三件套（24MB）
 * **基本持平**，但准确率上一个档。同一批真实音频上的实测对比：
 *
 * <pre>
 *   1.wav   旧 14M：全球金融「动量」  ← 错        新 CTC：全球金融「动荡」  ← 对
 *   8k.wav  旧 14M：「身处的飞机分析」← 完全错     新 CTC：「深入的分析」   ← 对
 * </pre>
 * 而且新 CTC 的识别结果与 154MB 的 zipformer-large（multi_zh-hans）**完全一致**，
 * 也就是说这个 25MB 模型已经把大模型的效果拿到了。
 *
 * <h3>为什么它不像上次那样缺字</h3>
 * 上次的 multi_zh-hans 系列（154MB / 60MB 那个 transducer）用的是**完整汉字 BPE 词表**，
 * 只有 1687~1741 个汉字 token，「酷、抖、哔、哩、驶」都没有 → 「酷狗音乐」「抖音」
 * 直接念不出来。这个 small-ctc 的词表是**字节级 BPE**（token 形如 `▁ƌŕş`，
 * 一个 token 是一串 UTF-8 字节），理论上能拼出任何汉字，**不存在缺字**。
 *
 * <h3>代价：不能用热词（明确接受）</h3>
 * sherpa 的热词靠 ContextGraph 实现，而 ContextGraph **只存在于 transducer 解码器**
 * （见 online-recognizer-transducer-impl.h；online-recognizer.cc 里 hotwords-file
 * 也只允许配 modified_beam_search）。CTC 走的是另一条解码路径，完全不读热词。
 *
 * 所以这个版本**不再给识别器灌应用名热词** —— 留着只会白写文件、白重建识别器。
 * 补偿手段在匹配层：同音整名给 96 分直通（pinyin:exact）、声母骨架、覆盖率门槛、
 * 猜不中弹候选。换言之，专有名词的容错从"解码前偏置"挪到"解码后纠错"。
 *
 * <h3>还可能拖后腿的地方（已单独修，见 VoiceMic）</h3>
 * 录音侧采样率对齐到 16k（ROM 可能给 48k，以前会变成 3 倍速花栗鼠音）+
 * 系统降噪/自动增益。
 */
final class VoiceAsr {

    private static final String TAG = "PipDesk";
    /** CTC 单文件模型（zipformer2-ctc） */
    private static final String MODEL_FILE = "model.int8.onnx";
    private static final String TOKENS_FILE = "tokens.txt";

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
        // 熔断：sherpa 的一些配置错误是 **native 层 exit(-1)**，Java 拦不住、进程当场死
        // （0.21.0 闪屏、0.23.1 点语音图标桌面重启、0.23.25 的 CTC+beam，都是它）。
        // 标记在加载前写、成功后删：一旦 90 秒内发生过加载期崩溃，就先跳过识别模型，
        // 保住桌面不被"点一下语音图标 → 桌面重启"无限循环。
        //
        // 标记里要带**模型指纹**：否则换了模型（尤其修完崩溃换个能跑的）之后，
        // 旧模型留下的崩溃记录还会把新模型挡在外面 —— 用户装完新版立刻测，
        // 会发现"语音还是不能用"，其实是熔断在挡。
        final String curTag = VoiceAssets.asrTag();
        java.io.File fuse = new java.io.File(ctx.getFilesDir(), "voice/asr.load");
        if (fuse.isFile()) {
            String prevTag = readTiny(fuse);
            boolean sameModel = prevTag != null && prevTag.equals(curTag);
            boolean recent = System.currentTimeMillis() - fuse.lastModified() < 90_000L;
            if (!sameModel) {
                // 旧模型（或旧版本没写内容）的崩溃记录，对新模型无效
                try {
                    fuse.delete();
                } catch (Throwable ignored) {
                }
                FileLog.append("VOICE 检测到旧模型的崩溃标记（" + prevTag + "），已作废");
            } else if (recent) {
                loadError = "识别模型上次加载曾导致进程崩溃，90 秒内先跳过（改语音设置可立即重试）";
                FileLog.append("VOICE " + loadError);
                return false;
            }
        }
        try {
            java.io.File dir = fuse.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(fuse);
            fo.write(curTag.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }

        String prefix = VoiceAssets.assetPrefix("asr");

        /*
         * 不再加热词（CTC 解码器根本没有热词通道，见类注释）。
         * 原来这里会传 wantHotwords 并在失败时退回无热词 —— 那套是为 transducer 写的，
         * 对 CTC 是空转：VoiceHotwords.ensure() 会白写一份热词文件，sherpa 却一个都不读。
         */
        boolean ok = tryLoad(prefix);
        if (!ok) {
            String alt = VoiceAssets.extract(ctx, "asr");
            if (alt != null && asrModelsOk(alt) && tryLoad(alt)) {
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

    private boolean tryLoad(String prefix) {
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
             * CTC 版没有热词文件（热词是 transducer 专属），所以正常情况下一直走
             * assets 模式，不需要为了"热词文件是绝对路径"把模型解到 filesDir。
             * filesDir 只在 assets 读取失败时作为兜底（见 load()）。
             */
            boolean filesMode = prefix.startsWith("/");

            OnlineRecognizerConfig c = new OnlineRecognizerConfig();
            FeatureConfig f = c.getFeatConfig();
            f.setSampleRate(VoiceMic.SR);
            f.setFeatureDim(80);
            OnlineModelConfig m = c.getModelConfig();
            // 单文件 CTC（zipformer2-ctc）：只有 model + tokens，没有 encoder/decoder/joiner。
            // 换成旧的 transducer 写法会直接建不起来（三个路径全空）。
            com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig z = m.getZipformer2Ctc();
            if (z == null) {                       // Kotlin data class 默认值不保证非 null，判一下更稳
                z = new com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(prefix + MODEL_FILE);
                m.setZipformer2Ctc(z);
            } else {
                z.setModel(prefix + MODEL_FILE);
            }
            m.setTokens(prefix + TOKENS_FILE);
            // 2 线程：这台机器核多但有画中画在跑，给 2 个核能把一句话的延迟压到 0.5s 内，
            // 再多开始和渲染抢 CPU，反而掉帧。
            // （0.23.24 试过 zipformer-large，那个才需要 4 线程；大模型因词表缺字已否决，
            //   见类注释，这里跟着回到 2。）
            m.setNumThreads(2);
            m.setProvider("cpu");
            m.setDebug(false);

            /*
             * 【必须 greedy_search —— 这里出过一次严重事故，别改】
             *
             * streaming CTC 只支持 greedy_search。传 modified_beam_search 时 sherpa 在
             * online-recognizer-ctc-impl.h 的 InitDecoder 里直接
             *     SHERPA_ONNX_LOGE("Unsupported decoding method: modified_beam_search
             *                      for streaming CTC models") + exit(-1)
             * 也就是 **native 直接杀进程**，Java 的 try/catch 一点用都没有。
             * 现象就是「第一次点语音图标 → 桌面闪退重启」。
             *
             * 0.23.25 第一次发布正是踩了这个：沿用 Prefs.voiceBeam（默认 true）走了
             * beam，一点就崩。CTC 也没有自回归，beam 收益本来就小，greedy 足够。
             */
            c.setDecodingMethod("greedy_search");

            c.setEnableEndpoint(true);
            // 端点规则用默认值：说完 ~1.2s 静音就断句，单句上限 20s。
            // 车机噪声大时"说完不等"比"等太久"更讨人厌，所以不把静音阈值调长。

            OnlineRecognizer r = filesMode
                    ? new OnlineRecognizer(null, c)
                    : new OnlineRecognizer(ctx.getAssets(), c);
            rec = r;
            stream = null;
            loadedAt = System.currentTimeMillis();
            loadError = null;
            String msg = "VOICE 识别模型加载完成 " + (loadedAt - t0) + "ms prefix=" + prefix
                    + (filesMode ? "（纯文件模式）" : "（assets 模式）")
                    + " greedy"                       // CTC 只支持 greedy，别按 voiceBeam 走
                    + " model=zipformer2-ctc hotwords=unsupported(CTC)";
            Log.i(TAG, msg);
            FileLog.append(msg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "asr load 失败 prefix=" + prefix, t);
            FileLog.append("VOICE 识别模型加载失败(" + prefix + ") " + t);
            release();
            loadError = "识别模型加载失败：" + t;
            return false;
        }
    }

    /**
     * 读一个很小的文本文件（熔断标记，内容就是模型指纹）。
     * 读不到 / 空文件 → null：老版本用 createNewFile() 写的是空文件，
     * 那种一律当成"和当前模型不匹配"，正好把历史遗留标记作废。
     */
    private static String readTiny(java.io.File f) {
        try {
            if (!f.isFile() || f.length() <= 0 || f.length() > 512) return null;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            String s = new String(buf, 0, off, "UTF-8").trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 纯文件模式前置校验：两个文件都得真在，否则 newFromFile 会整个建不起来。
     * （CTC 单文件模型只有 model + tokens；旧 transducer 的 encoder/decoder/joiner
     *   三件套已经不用了，别再按那份清单校验，否则永远是 false。）
     */
    private static boolean asrModelsOk(String prefix) {
        String[] names = {MODEL_FILE, TOKENS_FILE};
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
