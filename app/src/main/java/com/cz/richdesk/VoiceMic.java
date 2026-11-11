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

    public VoiceMic(Sink sink) {
        this.sink = sink;
    }

    public String error() {
        return error;
    }

    public int sampleRate() {
        return sampleRate;
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
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
            rec = r;
            running = true;
            th = new Thread(this::loop, "pipdesk-voice-mic");
            th.setDaemon(true);
            th.start();
            FileLog.append("VOICE 麦克风已开 source=VOICE_RECOGNITION"
                    + " 请求=" + SR + " 实际=" + sampleRate + "Hz");
            return true;
        } catch (Throwable t) {
            error = "打开麦克风异常 " + t;
            FileLog.append("VOICE 麦克风启动失败：" + error);
            Log.e(TAG, "voice mic start", t);
            return false;
        }
    }

    private void loop() {
        // 采集线程比普通线程高一点（-12，低于 AUDIO 的 -16）：太低会被画中画渲染饿死、
        // 出现丢帧式断音；踩到 AUDIO 又会反过来抢渲染的 CPU，这台机器两者都要让。
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO + 4);
        short[] buf = new short[CHUNK];
        long silentChunks = 0;
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
            for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
            int rms = (int) Math.sqrt((double) sum / n);
            if (rms < 60) silentChunks++;
            else if (silentChunks > 0) silentChunks = 0;
            try {
                sink.onPcm(buf, n, rms);
            } catch (Throwable t) {
                Log.e(TAG, "voice sink", t);
            }
        }
        running = false;
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
