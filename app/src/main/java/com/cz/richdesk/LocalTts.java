package com.cz.richdesk;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内置离线 TTS：matcha-icefall-zh-baker（标贝中文单女声）+ vocos-22khz-univ 声码器。
 *
 * 【为什么是它】这是本 app 第一个内置语音模型。中途试过 melo（音平）、huayan（piper）
 * 都不理想：huayan 在 sherpa v2.0.0+ 里失去 espeak 音素化，只能靠一份拼音 lexicon 硬转，
 * 音素分布和模型训练时对不上，能出声但吐字发糊、听不清说什么。matcha 是 icefall 官方中文
 * 模型，配的是它自己的 lexicon + jieba 词典 + fst 正则，发音标准、吐字清楚，是最稳的一版。
 *
 * 【关键 API】matcha 不走 OfflineTtsVitsModelConfig（那个没有 vocoder 字段），
 * 而是 OfflineTtsModelConfig.setMatcha(OfflineTtsMatchaModelConfig)：
 *   acousticModel = model-steps-3.onnx
 *   vocoder       = vocos-22khz-univ.onnx   ← 没它就只有声学特征、出不了波形（无声）
 *   lexicon       = lexicon.txt
 *   tokens        = tokens.txt
 *   dictDir       = dict/                   ← jieba 分词词典，决定断句/韵律
 * 文本正则交给 ruleFsts：phone.fst, date.fst, number.fst（顺序不能乱，phone 必须在前）。
 *
 * 【路径铁律】sherpa v2.0.1 遇到「绝对路径 + 非 null AssetManager」会直接 exit(-1)
 * （Java 层 catch 不住，进程当场死）。所以先用 VoiceAssets.extract 把 assets/tts 全解到
 * filesDir（含 dict/ 子目录），再用 new OfflineTts(null, cfg) 走纯文件模式。
 *
 * 合成是同步且吃 CPU 的，播放又会阻塞，全部丢到单线程 worker；语音控制线程只做一次入队。
 */
final class LocalTts {

    /** matcha 声学模型（平铺在 assets/tts 下） */
    private static final String MODEL_FILE = "model-steps-3.onnx";
    /** 声码器：把声学模型输出的特征还原成波形，缺了必定无声 */
    private static final String VOCODER_FILE = "vocos-22khz-univ.onnx";
    /** 字形/词 → 音素（中文发音的唯一来源，缺了必定无声） */
    private static final String LEXICON_FILE = "lexicon.txt";
    /** 音素 → id 映射表 */
    private static final String TOKENS_FILE = "tokens.txt";
    /** jieba 词典目录（决定分词与断句） */
    private static final String DICT_DIR = "dict";
    /** 文本正则化：数字/日期/电话转中文读法。phone 必须排第一 */
    private static final String[] RULE_FSTS = {"phone.fst", "date.fst", "number.fst"};

    private final Object lock = new Object();
    /** 每说一句就自增：旧句子的合成/播放看到序号变了就自己退场（等价于 QUEUE_FLUSH） */
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipdesk-local-tts");
        t.setDaemon(true);
        return t;
    });

    private Context app;
    private OfflineTts engine;
    private AudioTrack track;
    private volatile boolean ready;
    private volatile boolean broken;
    private volatile boolean preparing;
    private volatile String pending;
    /** 此刻是否正在出声：语音侧据此屏蔽「自己说的话被麦克风听回去」 */
    private volatile boolean speaking;

    /** 正在出声（含刚起播那一段）：播报期间收到的识别结果必须整条丢弃 */
    boolean isSpeaking() {
        return speaking;
    }

    boolean isReady() {
        return ready;
    }

    boolean isBroken() {
        return broken;
    }

    /** 懒加载：解压 + 建引擎都在后台线程，首次不会卡住语音控制线程 */
    void ensure(Context ctx) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null) return;
        synchronized (lock) {
            if (ready || broken || preparing) return;
            preparing = true;
        }
        worker.execute(this::init);
    }

    private void init() {
        long t0 = System.currentTimeMillis();
        try {
            String p = VoiceAssets.extract(app, "tts");
            if (p == null) throw new IllegalStateException("assets/tts 解压失败");

            File model = new File(p + MODEL_FILE);
            File vocoder = new File(p + VOCODER_FILE);
            File lexicon = new File(p + LEXICON_FILE);
            File tokens = new File(p + TOKENS_FILE);
            File dict = new File(p + DICT_DIR + "/");
            // 这些缺任何一个都是「能加载但不出声」，必须当硬错误拦下来，不能静默降级
            if (!model.isFile() || model.length() == 0) {
                throw new IllegalStateException("缺模型 " + MODEL_FILE);
            }
            if (!vocoder.isFile() || vocoder.length() == 0) {
                throw new IllegalStateException("缺声码器 " + VOCODER_FILE + "（缺了必定无声）");
            }
            if (!lexicon.isFile() || lexicon.length() < 1024) {
                throw new IllegalStateException("缺 " + LEXICON_FILE);
            }
            if (!tokens.isFile() || tokens.length() == 0) {
                throw new IllegalStateException("缺 " + TOKENS_FILE);
            }
            if (!dict.isDirectory()) {
                throw new IllegalStateException("缺 " + DICT_DIR + "/ 词典目录（断句会乱）");
            }

            OfflineTtsMatchaModelConfig m = new OfflineTtsMatchaModelConfig();
            m.setAcousticModel(p + MODEL_FILE);
            m.setVocoder(p + VOCODER_FILE);   // ★ matcha 专属：没 vocoder 只有特征没有波形
            m.setLexicon(p + LEXICON_FILE);
            m.setTokens(p + TOKENS_FILE);
            m.setDictDir(p + DICT_DIR);       // jieba 分词词典
            m.setNoiseScale(0.667f);
            m.setLengthScale(1.0f);

            OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
            mc.setMatcha(m);                  // ★ 必须用 setMatcha，不能 setVits
            mc.setNumThreads(2);              // 与 ASR 一致，别和画中画抢核
            mc.setProvider("cpu");
            mc.setDebug(false);

            StringBuilder fsts = new StringBuilder();
            for (String f : RULE_FSTS) {
                if (fsts.length() > 0) fsts.append(',');
                fsts.append(p).append(f);
            }

            OfflineTtsConfig c = new OfflineTtsConfig();
            c.setModel(mc);
            c.setRuleFsts(fsts.toString());
            c.setRuleFars("");
            c.setMaxNumSentences(1);
            c.setSilenceScale(0.2f);

            OfflineTts t = new OfflineTts(null, c);   // null → newFromFile（纯文件模式）

            // 启动自检：立刻合成一句，把点数/时长写进日志。
            // 静默失败（空音频）在业务上完全看不出来，只能靠这里探一次。
            probe(t, "你好");
            probe(t, "导航已启动，请注意行车安全");

            synchronized (lock) {
                engine = t;
                ready = true;
                preparing = false;
            }
            FileLog.append("VOICE 内置 TTS 就绪 sr=" + t.sampleRate()
                    + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            String s = pending;
            pending = null;
            if (s != null) speak(s);
        } catch (Throwable e) {
            synchronized (lock) {
                broken = true;
                preparing = false;
            }
            FileLog.append("VOICE 内置 TTS 加载失败：" + e);
        }
    }

    /** 自检探针：只合成不播放，把点数/时长写日志（判断配置是否真的生效） */
    private static void probe(OfflineTts t, String text) {
        try {
            GeneratedAudio a = t.generate(text, 0, 1.0f);
            int n = (a == null || a.getSamples() == null) ? 0 : a.getSamples().length;
            int sr = (a == null) ? 0 : a.getSampleRate();
            FileLog.append("VOICE 内置 TTS 自检「" + text + "」→ " + n + " 采样点 / "
                    + (sr > 0 ? (n * 1000 / sr) + "ms" : "sr=0")
                    + (n > 0 ? "" : "（空音频！配置未生效）"));
        } catch (Throwable e) {
            FileLog.append("VOICE 内置 TTS 自检「" + text + "」抛异常：" + e);
        }
    }

    /** 异步合成 + 播放；引擎还没就绪时只记住最后一句，就绪后补说 */
    void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready) {
            if (broken) return;
            pending = text;
            ensure(app);
            return;
        }
        final long my = seq.incrementAndGet();
        worker.execute(() -> synth(text, my));
    }

    private void synth(String text, long my) {
        OfflineTts t;
        synchronized (lock) {
            t = engine;
        }
        if (t == null || my != seq.get()) return;
        try {
            GeneratedAudio a = t.generate(text, 0, 1.0f);   // sid=0：中文单女声
            if (a == null || my != seq.get()) return;
            float[] s = a.getSamples();
            int sr = a.getSampleRate();
            // 空音频是最典型的失败形态：配置没生效就静默返回空数组。
            if (s == null || s.length == 0 || sr <= 0) {
                FileLog.append("VOICE 内置 TTS 合成结果为空（len="
                        + (s == null ? -1 : s.length) + " sr=" + sr
                        + "）text=" + text);
                return;
            }
            play(s, sr, my);
        } catch (Throwable e) {
            FileLog.append("VOICE 内置 TTS 合成失败：" + e);
        }
    }

    private void play(float[] f, int sr, long my) {
        // 峰值归一化 + 轻度预加重：把响度拉满、高频提亮，嘈杂环境更清晰。
        // 部分车机 HAL 对 ENCODING_PCM_FLOAT 支持不好，这里转 16bit PCM。
        float peak = 0f;
        for (float x : f) {
            float a = x < 0 ? -x : x;
            if (a > peak) peak = a;
        }
        float norm = peak > 1e-4f ? (0.85f / peak) : 1f;   // 留余量给预加重，避免削顶
        float prev = 0f;
        short[] pcm = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            float x = f[i] * norm;
            float y = x - 0.92f * prev;     // 预加重：提亮高频
            prev = x;
            if (y > 1f) y = 1f; else if (y < -1f) y = -1f;
            pcm[i] = (short) (y * 32767f);
        }
        AudioTrack at = null;
        speaking = true;          // 从这一刻起麦克风收到的是我们自己的声音
        try {
            int min = AudioTrack.getMinBufferSize(sr,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return;
            at = new AudioTrack(AudioManager.STREAM_MUSIC, sr,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, pcm.length * 2), AudioTrack.MODE_STREAM);
            synchronized (lock) {
                AudioTrack old = track;
                track = at;
                if (old != null && old != at) {
                    try {
                        old.pause();
                        old.flush();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (my != seq.get()) return;
            at.play();
            int off = 0;
            while (off < pcm.length && my == seq.get()) {
                int n = Math.min(4096, pcm.length - off);
                int w = at.write(pcm, off, n);
                if (w <= 0) break;
                off += w;
            }
            // 等尾部播完；能被新的一句打断，也有超时兜底
            long deadline = System.currentTimeMillis() + 8000;
            while (my == seq.get()
                    && at.getPlaybackHeadPosition() < pcm.length
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        } catch (Throwable e) {
            FileLog.append("VOICE 内置 TTS 播放失败：" + e);
        } finally {
            speaking = false;
            if (at != null) {
                try {
                    at.stop();
                } catch (Throwable ignored) {
                }
                try {
                    at.release();
                } catch (Throwable ignored) {
                }
            }
            synchronized (lock) {
                if (track == at) track = null;
            }
        }
    }

    /** 切回系统引擎 / 改设置时停掉当前朗读（模型不释放，来回切换不用重新加载） */
    void cancel() {
        seq.incrementAndGet();
        pending = null;
        synchronized (lock) {
            AudioTrack at = track;
            track = null;
            if (at != null) {
                try {
                    at.pause();
                    at.flush();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 语音整体停止时彻底释放模型内存；文件已解压，下次 ensure 只重建引擎、不再拷贝 */
    void release() {
        cancel();
        synchronized (lock) {
            ready = false;
            broken = false;
            preparing = false;
            OfflineTts t = engine;
            engine = null;
            if (t != null) {
                try {
                    t.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
