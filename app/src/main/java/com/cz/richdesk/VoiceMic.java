package com.cz.richdesk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 16k 单声道采集。
 *
 * 三条硬规矩，都是被这台车机教出来的：
 *  1) **创建 / start / stop / release 一律不碰主线程**。AudioRecord 的这几个调用都要
 *     过 AudioService 的 binder（无超时），本项目已经有 AUDIO-BLOCK 前科（见 AudioProbe）：
 *     蓝牙路由切换时主线程一卡，三块画中画会一起冻住。所以整个生命周期都关在自己的线程里。
 *  2) 音源用 VOICE_RECOGNITION 而不是 MIC：车机 HAL 通常会给它一路做过降噪/回声抑制的
 *     流，放歌时唤醒词才听得见；MIC 是原始流，音乐会把唤醒词淹掉。
 *  3) startRecording 成功不等于能拿到音。部分车机 HAL 只允许一路采集，后来者拿到的是
 *     全 0 静音 —— 所以 {@link #probe} 会把 RMS/峰值/静音比例一起写进日志，
 *     用数据判断"到底有没有声音"，而不是靠"启动了"。
 */
public final class VoiceMic {

    private static final String TAG = "PipDesk";
    public static final int SR = 16000;
    /** 每次读 100ms：KWS 的 chunk 是 16 帧（约 320ms），100ms 喂一次足够实时，又不至于太碎 */
    private static final int CHUNK = SR / 10;

    /**
     * 判定"麦克风被独占"的采样门限：**连续采样里峰值不超过它**。
     *
     * 为什么用峰值而不是 RMS/音量：安静环境和"被独占"在音量上是同一件事，
     * 分不开。但被独占时 HAL 给的是**精确的 0**（或只差一个 LSB），而真实麦克风
     * 即使在深夜车库也有量化底噪（几十个 LSB 起）。用"峰值 ≤ 1"当判据，
     * 才不会把"用户没说话"当成"麦克风被占"而反复重开。
     */
    private static final int SILENCE_PEAK = 1;

    public interface Sink {
        /** @param buf  16bit PCM，直接就是 -32768..32767 的原始值
         *  @param n    有效样本数
         *  @param rms  这一段的均方根（0..32767），UI 电平条和静音判断都用它 */
        void onPcm(short[] buf, int n, int rms);
    }

    private final Sink sink;
    private AudioRecord rec;
    private Thread th;
    private volatile boolean running;
    private volatile String error;
    private volatile int sampleRate = SR;
    /**
     * 实际采样率与模型要求(16k)不一致 → 需要在喂 ASR 前重采样。
     *
     * 为什么必须有这道：AudioRecord.Builder 只是"请求" 16k，ROM 完全可以给你
     * 48000（很多车机 HAL 固定 48k）。而识别模型只认 16k —— 把 48k 的样本按 16k
     * 喂进去，声音就是 3 倍速的"花栗鼠"，音调全错，识别结果必然一塌糊涂。
     * 这个字段以前根本没有：sampleRate() 有定义、却没有任何调用者，
     * 也就是说真出这种情况时，代码是"静默地按错的采样率送进去了"。
     */
    private volatile boolean needResample;
    private volatile int realSr = SR;
    /** 系统级降噪 / 自动增益（可用才启用，车机远场拾音全靠它俩） */
    private android.media.audiofx.NoiseSuppressor ns;
    private android.media.audiofx.AutomaticGainControl agc;
    /** 本次采集的 audio session id：录音配置列表里用它把自己排除掉 */
    private volatile int session;
    /**
     * 连续"没有信号"的起点（0 = 当前有信号）。
     *
     * 这条线是为「被别人占着麦克风」准备的：那种情况下 read 照常返回、lastPcmAt 照常刷新，
     * 只是内容全是 0 —— 用"有没有数据"判断永远发现不了，只能用"数据是不是死寂"判断。
     * 详见 {@link #silentMs()}。
     */
    private volatile long silentSince;
    /**
     * 采集音源。**默认 MIC（source=1）**，与这台车机上的原生语音 app 保持一致：
     * 车机日志 `LSPosed_20261112_080306/full.log` 里百度地图 `MapVoiceProcess` 用
     * `AudioRecord: set(): inputSource 1` 常驻监听，同时厂商 `/system_tw/bin/gocsdk`
     * 也在录音，AudioPolicyService 逐次 `+source = 1, allowCapture=1` 放行并发。
     * 旧默认值 VOICE_RECOGNITION 是另一条 input 路由，在这台车机上常读到全 0 死寂，
     * 正是「常驻唤醒词听着听着就聋了」的高发原因。
     * 拿不到数据时由 Voice.recycleMic() 换到另一条路由试，仍保留 setSource() 便于回调。
     */
    private volatile int source = MediaRecorder.AudioSource.MIC;

    public VoiceMic(Sink sink) {
        this.sink = sink;
    }

    public String error() {
        return error;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** 当前 AudioRecord 的 session id（未开麦时 -1）。Voice 用它从录音配置里认出"自己" */
    public int audioSessionId() {
        return session;
    }

    /** 已经连续多久没有信号（ms）。0 = 当前在正常收到声音 */
    public long silentMs() {
        long s = silentSince;
        return s == 0 ? 0 : android.os.SystemClock.elapsedRealtime() - s;
    }

    public void setSource(int src) {
        source = src;
    }

    public int source() {
        return source;
    }

    public static String sourceName(int src) {
        switch (src) {
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            default: return "src" + src;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 打开麦克风并开始出流。<b>必须在后台线程调用</b>。
     * 返回 false 时 error() 里有原因（权限 / HAL 拒绝 / 初始化失败）。
     */
    public boolean start() {
        if (running) return true;
        error = null;
        if (!hasPermission()) {
            error = "没有 RECORD_AUDIO 权限";
            FileLog.append("VOICE 麦克风启动失败：" + error);
            return false;
        }
        try {
            int minBuf = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = CHUNK * 2 * 4;
            int bufBytes = Math.max(minBuf, CHUNK * 2 * 2);

            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                error = "AudioRecord 初始化失败 state=" + r.getState();
                r.release();
                FileLog.append("VOICE 麦克风启动失败：" + error);
                return false;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                error = "startRecording 未进入录音态=" + r.getRecordingState();
                r.release();
                FileLog.append("VOICE 麦克风启动失败：" + error);
                return false;
            }
            sampleRate = r.getSampleRate();          // ROM 可能改写采样率，记真实值
            realSr = sampleRate;
            needResample = sampleRate != SR;
            session = r.getAudioSessionId();
            rec = r;
            enableEffects(session);
            // 保守起见先当"没有信号"：第一个真正有声的 chunk 会把它清掉。
            // 这样 HAL 开了却一直不回数据（read 阻塞）也能被 silentMs() 发现。
            silentSince = android.os.SystemClock.elapsedRealtime();
            running = true;
            th = new Thread(this::loop, "pipdesk-voice-mic");
            th.setDaemon(true);
            th.start();
            FileLog.append("VOICE 麦克风已开 source=" + sourceName(source)
                    + " 请求=" + SR + " 实际=" + sampleRate + "Hz"
                    + (needResample ? "（与模型不一致 → 已启用重采样到 " + SR + "）" : "")
                    + " 降噪=" + (ns != null && ns.getEnabled() ? "开" : "无")
                    + " 自动增益=" + (agc != null && agc.getEnabled() ? "开" : "无"));
            return true;
        } catch (Throwable t) {
            error = "打开麦克风异常 " + t;
            FileLog.append("VOICE 麦克风启动失败：" + error);
            Log.e(TAG, "voice mic start", t);
            return false;
        }
    }

    /**
     * 打开系统级降噪与自动增益。
     *
     * 车机是**远场拾音**（麦在顶棚/仪表台，人离 0.5~1m，还有空调与路噪），
     * 不给 AGC 时说话声往往只有几百 LSB，模型的输入动态范围远低于训练分布；
     * 不给 NS 时风噪/路噪会直接把字糊掉。这两个 effect 由系统提供、零体积成本，
     * 个别 ROM 不支持就跳过（isAvailable 为 false 或 create 返回 null），不报错。
     */
    private void enableEffects(int sessionId) {
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                ns = android.media.audiofx.NoiseSuppressor.create(sessionId);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable t) {
            ns = null;
        }
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                agc = android.media.audiofx.AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Throwable t) {
            agc = null;
        }
    }

    private void releaseEffects() {
        try {
            if (ns != null) ns.release();
        } catch (Throwable ignored) {
        }
        try {
            if (agc != null) agc.release();
        } catch (Throwable ignored) {
        }
        ns = null;
        agc = null;
    }

    private static final short[] EMPTY = new short[0];

    /**
     * 线性插值把实际采样率的 PCM 转到 16k。
     * 48k→16k 是整除关系，等价于 ideal 3:1 抽取；块间最多丢不到 1 个样本，
     * 对识别没有影响（不要在块边界处做相位累积，那个更容易写错）。
     */
    private short[] resample(short[] in, int n) {
        double step = (double) realSr / SR;
        int outN = (int) (n / step);
        if (outN <= 0) return EMPTY;
        short[] out = new short[outN];
        for (int i = 0; i < outN; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            if (i0 >= n) i0 = n - 1;
            int i1 = i0 + 1 < n ? i0 + 1 : i0;
            double f = pos - i0;
            out[i] = (short) (in[i0] * (1 - f) + in[i1] * f + 0.5);
        }
        return out;
    }

    private void loop() {
        // 采集线程比普通线程高一点（-12，低于 AUDIO 的 -16）：太低会被画中画渲染饿死、
        // 出现丢帧式断音；踩到 AUDIO 又会反过来抢渲染的 CPU，这台机器两者都要让。
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO + 4);
        short[] buf = new short[CHUNK];
        boolean wasSilent = true;
        while (running) {
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Throwable t) {
                error = "read 异常 " + t;
                break;
            }
            if (n <= 0) {
                if (n < 0) {
                    error = "read 返回 " + n;
                    break;
                }
                continue;
            }
            long sum = 0;
            int peak = 0;
            for (int i = 0; i < n; i++) {
                short v = buf[i];
                sum += (long) v * v;
                int a = v < 0 ? -v : v;
                if (a > peak) peak = a;
            }
            int rms = (int) Math.sqrt((double) sum / n);
            // 只记"死寂段从什么时候开始"，恢复时清掉。判断独占看的是这个时长，
            // 不看有没有回调（被独占时回调全是 0，照样有回调）。
            if (peak <= SILENCE_PEAK) {
                if (silentSince == 0) silentSince = android.os.SystemClock.elapsedRealtime();
                wasSilent = true;
            } else {
                if (silentSince != 0) {
                    if (wasSilent) {
                        FileLog.append("VOICE 麦克风恢复信号（静音 "
                                + (android.os.SystemClock.elapsedRealtime() - silentSince) + "ms 后）");
                    }
                    silentSince = 0;
                }
                wasSilent = false;
            }
            // 采样率守卫：ROM 给了非 16k（车机常见 48k）时必须转成模型要求的样子，
            // 否则送进去的是 3 倍速音频，音调全错、识别必然崩。电平用原始数据算，
            // 不受重采样影响。
            short[] frame = buf;
            int fn = n;
            if (needResample) {
                frame = resample(buf, n);
                fn = frame.length;
                if (fn <= 0) continue;
            }
            try {
                sink.onPcm(frame, fn, rms);
            } catch (Throwable t) {
                Log.e(TAG, "voice sink", t);
            }
        }
        running = false;
        releaseEffects();
        // 停也在本线程：主线程调 stop() 只置标志 + 等它收尾
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (Throwable ignored) {
        }
        rec = null;
        FileLog.append("VOICE 麦克风已关" + (error == null ? "" : "：" + error));
    }

    /** 停采。<b>必须在后台线程调用</b>（内部 join 采集线程）。 */
    public void stop() {
        running = false;
        silentSince = 0;
        session = -1;
        Thread t = th;
        th = null;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (rec != null) {
            // join 超时（卡在 read 上）时的兜底：直接释放，采集线程会从 read 里抛错退出
            try {
                rec.stop();
                rec.release();
            } catch (Throwable ignored) {
            }
            rec = null;
        }
    }

    private static boolean hasPermission() {
        Context c = AppState.app;
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ 自检

    /** 自检结果：给设置页直接显示，同时整段写进 FileLog */
    public static final class Probe {
        public String summary;
        public File wav;
        public int peak;
        public int rms;
        public int silentPermille;
    }

    /**
     * 录一段存 wav 并统计能量。<b>阻塞，必须后台线程调用。</b>
     *
     * 这是"麦克风到底有没有声音"的唯一可靠判据：
     *  - silentPermille ≈ 1000 且 peak = 0 → HAL 给了静音（被别的应用独占 / ROM 不放行）
     *  - SILENT 但 peak 很小（几十）→ 采到了，只是没说话或被压得很低
     */
    public static Probe probe(Context ctx, int seconds, String header) {
        Probe p = new Probe();
        p.summary = "未执行";
        int minBuf = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = CHUNK * 2 * 4;
        AudioRecord r = null;
        File out = null;
        try {
            r = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, CHUNK * 2 * 4))
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                p.summary = "AudioRecord 初始化失败 state=" + r.getState();
                return p;
            }
            r.startRecording();
            File dir = ctx.getExternalFilesDir("logs");
            if (dir == null) dir = new File(ctx.getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            out = new File(dir, "mic-" + (header == null ? "probe" : header) + ".wav");

            int total = SR * seconds;
            short[] buf = new short[CHUNK];
            long sumAll = 0, count = 0, silent = 0;
            int peak = 0;
            BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            writeWavHeader(os, total * 2);
            while (count < total) {
                int n = r.read(buf, 0, Math.min(CHUNK, (int) (total - count)));
                if (n <= 0) break;
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    short v = buf[i];
                    bytes[i * 2] = (byte) (v & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                    int a = Math.abs(v);
                    if (a > peak) peak = a;
                    sumAll += (long) v * v;
                    if (a < 60) silent++;
                }
                os.write(bytes);
                count += n;
            }
            os.flush();
            os.close();
            p.peak = peak;
            p.rms = count > 0 ? (int) Math.sqrt((double) sumAll / count) : 0;
            p.silentPermille = count > 0 ? (int) (silent * 1000 / count) : 1000;
            p.wav = out;
            p.summary = header + "：" + seconds + "s 实际=" + r.getSampleRate() + "Hz"
                    + " peak=" + p.peak + " rms=" + p.rms
                    + " 静音占比=" + (p.silentPermille / 10f) + "%"
                    + " → " + out.getName();
            String verdict;
            if (p.peak == 0) verdict = "采到的是全 0 静音：麦克风被别的应用独占，或 ROM 不给第三方录音";
            else if (p.peak < 200) verdict = "能采到但极低：检查车机麦克风增益 / 是否被静音开关关掉";
            else verdict = "采音正常";
            p.summary = p.summary + "\n" + verdict;
            FileLog.append("VOICE 自检 " + p.summary.replace('\n', ' '));
            Log.i(TAG, "voice probe " + p.summary.replace('\n', ' '));
        } catch (Throwable t) {
            p.summary = "自检异常：" + t;
            FileLog.append("VOICE 自检异常 " + t);
        } finally {
            if (r != null) {
                try {
                    r.stop();
                    r.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return p;
    }

    private static void writeWavHeader(BufferedOutputStream os, int dataLen) throws IOException {
        int fileLen = dataLen + 36;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putInt(h, 4, fileLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putInt(h, 16, 16);
        putShort(h, 20, 1);
        putShort(h, 22, 1);
        putInt(h, 24, SR);
        putInt(h, 28, SR * 2);
        putShort(h, 32, 2);
        putShort(h, 34, 16);
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putInt(h, 40, dataLen);
        os.write(h);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
