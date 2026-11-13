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
 * 离线识别（只在"已经开始听"之后才工作）。
 *
 * <h3>模型（feature/bilingual-asr）：streaming-paraformer-bilingual-zh-en-int8</h3>
 * 中英双语，**能直接吐出拉丁字母** —— 这正是"打开 QQ"念不清的根因：
 * 纯中文模型的词表里一个字母都没有，QQ 只能被听成"扣扣/秋秋"。
 *
 * 同一批真实音频上的实测对比（本地 sherpa 2.0.1，与 APK 内 so 同版本）：
 *
 * <pre>
 *   wav        zipformer-small 双语(48MB)          paraformer 双语(227MB)
 *   0.wav   昨天是 MONDAY TODAY IS THE AFTER…      昨天是 monday today is… the day after tomorrow 是星期
 *   2.wav   这是频繁                                 这个是频繁的啊不认识接下来 frequently 频繁
 *   4.wav   嗯 TIME是及时                            嗯 on time 标准是 in time 是及时叫他总是准时交他的作业…
 *   46.wav  你好石刀                                 你好石头把厨房扫拖下
 * </pre>
 * 差距主要在中英混说的长句：小号几乎丢句子，paraformer 能整段接住。
 *
 * <h3>代价（明确接受）</h3>
 *   · 体积：encoder.int8 158MB + decoder.int8 69MB，APK 从 179MB 涨到约 380MB；
 *   · CPU：本地 RTF 0.13~0.17（小号 0.06），车机弱得多，实时性是这版最大的风险点；
 *   · 仍然**不能用热词**：ContextGraph 只存在于 transducer，paraformer 走自己的解码路径。
 *     专有名词的容错继续靠匹配层（同音整名 96 分直通 / 声母骨架 / 候选确认）。
 *
 * <h3>解码方法：必须 greedy_search</h3>
 * 这一族模型换解码方法踩过两次 native exit(-1)（CTC+beam 直接杀进程，Java 拦不住）。
 * paraformer 本地已用 greedy 跑通，不再尝试 modified_beam_search。
 */
final class VoiceAsr {

    private static final String TAG = "PipDesk";
    /** paraformer 双文件模型（encoder 出编码、decoder 出 CTC 式预测） */
    private static final String ENCODER_FILE = "encoder.int8.onnx";
    private static final String DECODER_FILE = "decoder.int8.onnx";
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
            // 双语 paraformer：encoder + decoder 两个文件（没有 joiner）。
            // 传错成 CTC / transducer 的槽位会直接建不起来（对应路径全空）。
            com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig pf = m.getParaformer();
            if (pf == null) {                      // Kotlin data class 默认值不保证非 null，判一下更稳
                pf = new com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig(
                        prefix + ENCODER_FILE, prefix + DECODER_FILE);
                m.setParaformer(pf);
            } else {
                pf.setEncoder(prefix + ENCODER_FILE);
                pf.setDecoder(prefix + DECODER_FILE);
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
            /*
             * 【端点调快，0.23.47】sherpa 默认 rule2 = 1.2s 静音 + ≥0.6s 语音，
             * 也就是用户说完最后两个字，还要**干等 1.2 秒**才断句执行。
             * 中文指令就 2~4 个字，这个 1.2 秒就是体感上"反应慢"的全部来源。
             *
             * 现在：rule2 收到 0.8s，同时把「至少 0.6s 语音」降到 0.3s ——
             * 不降的话像「暂停」「静音」这种半秒级的短词根本走不到 rule2，
             * 只能等 rule1 的 2.4 秒，短指令反而最慢。
             * rule1 也从 2.4s 收到 1.6s（它是"必须有语音"的那条，兜住短词）。
             *
             * rule3 是**单句上限**（语音满 20s 强切），保持默认不动：
             * 长指令（"打开网易云音乐然后播放每日推荐"）需要它。
             *
             * 风险：说话中途停顿超过 0.8s（想词）会被切成两句。中文短指令可接受，
             * 真在车机上觉得太急，把这两个数调回 1.2 / 2.4 即可。
             */
            com.k2fsa.sherpa.onnx.EndpointConfig ep = c.getEndpointConfig();
            if (ep != null) {
                com.k2fsa.sherpa.onnx.EndpointRule r1 = ep.getRule1();
                if (r1 != null) r1.setMinTrailingSilence(1.6f);
                com.k2fsa.sherpa.onnx.EndpointRule r2 = ep.getRule2();
                if (r2 != null) {
                    r2.setMinTrailingSilence(0.8f);
                    r2.setMinUtteranceLength(0.3f);
                }
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
                    + " greedy"                       // paraformer 只验过 greedy，别试 beam
                    + " model=paraformer-bi-zh-en hotwords=unsupported";
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
        String[] names = {ENCODER_FILE, DECODER_FILE, TOKENS_FILE};
        for (String n : names) {
            java.io.File fp = new java.io.File(prefix + n);
            if (!fp.isFile() || fp.length() <= 0) return false;
        }
        return true;
    }

    /** 开始一句话：清空上文的解码状态 */
    /** 本轮喂进去的样本数 / 解码耗的纳秒：只为了算 RTF（车机上能不能实时，见 rtfText） */
    private long fedSamples;
    private long decodeNanos;

    void begin() {
        if (rec == null) return;
        try {
            if (stream == null) stream = rec.createStream("");
            else rec.reset(stream);
            recognized = "";
            fedSamples = 0;
            decodeNanos = 0;
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
            long t0 = System.nanoTime();
            st.acceptWaveform(f, VoiceMic.SR);
            while (r.isReady(st)) r.decode(st);
            decodeNanos += System.nanoTime() - t0;
            fedSamples += n;
            OnlineRecognizerResult res = r.getResult(st);
            if (res != null && res.getText() != null) recognized = res.getText().trim();
            return recognized;
        } catch (Throwable t) {
            Log.e(TAG, "asr accept", t);
            FileLog.append("VOICE 识别异常 " + t);
            return recognized;
        }
    }

    /**
     * 本轮「解码耗时 / 音频时长」：RTF &lt; 1 才可能实时。
     *
     * 车机的这颗 SoC 比开发机慢得多（本地 x86 两线程 RTF≈0.24），所以这条数字必须在
     * 真机上看得见 —— 拿到车机日志就能直接判断「双语大模型能不能留」，
     * 不用再靠猜。没有样本（这一轮没喂到音频）时返回 null。
     */
    String rtfText() {
        if (fedSamples <= 0 || decodeNanos <= 0) return null;
        double audioSec = fedSamples / (double) VoiceMic.SR;
        double decodeSec = decodeNanos / 1e9;
        return String.format(java.util.Locale.US, "音频%.1fs 解码%.2fs RTF=%.2f",
                audioSec, decodeSec, decodeSec / audioSec);
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
