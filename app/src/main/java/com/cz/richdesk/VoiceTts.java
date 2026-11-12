package com.cz.richdesk;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * TTS 回复。三种来源，设置页里可选（行名后显示当前值）：
 *  · {@link Prefs#TTS_SYS}    系统默认引擎（用户装了新引擎可在系统设置里换）
 *  · {@link Prefs#TTS_LOCAL}  内置离线女声（{@link LocalTts}，不依赖系统有没有 TTS）
 *  · {@link Prefs#TTS_CUSTOM} 指定第三方引擎包名（原来的「替换 TTS 引擎」功能，保留）
 *
 * 【自动兜底】选了系统 / 指定引擎但 init 失败（ROM 没引擎、设置入口被裁掉、
 * 指定包被卸载）时，speak() 会自动改用内置离线女声 —— 用户不会遇到「一点声音都没有」。
 *
 * 系统 TTS 的 init 是异步的，就绪前只挂 pending；内置引擎的加载也是异步的，
 * 两条链路都不会阻塞语音控制线程。
 */
public final class VoiceTts {

    private static final String TAG = "PipDesk";

    private final LocalTts local = new LocalTts();

    private TextToSpeech tts;
    /** 持有 appContext：init 失败回退默认引擎时重建 / 内置引擎解压都要用 */
    private Context appCtx;
    private volatile boolean ready;
    /** 当前系统引擎 init 失败过：同样的引擎不再重试，换引擎后重置 */
    private volatile boolean broken;
    /** 当前请求的引擎包名，"" = 系统默认 */
    private volatile String engineUsed = "";
    /** 当前模式（Prefs.TTS_*） */
    private volatile int mode = Prefs.TTS_SYS;

    /** 系统引擎就绪前要补说的那句（init 期间用户指令可能已经执行完） */
    private volatile String pending;

    /** @param mode Prefs.TTS_SYS / TTS_LOCAL / TTS_CUSTOM */
    public void ensure(Context ctx, int mode, String engine) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        this.mode = mode;
        if (mode == Prefs.TTS_LOCAL) {
            local.ensure(ctx);
            return;
        }
        local.cancel();                 // 从内置切走：停掉还没念完的那句
        ensureSystem(ctx, engine == null ? "" : engine);
    }

    /** 懒初始化系统 TextToSpeech：第一次真的要说话时才创建。engine 传 "" 走系统默认 */
    private void ensureSystem(Context ctx, final String e) {
        if (ctx == null) return;
        synchronized (this) {
            if (ready && e.equals(engineUsed)) return;
            if (broken && e.equals(engineUsed)) return;
            if (tts != null) {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Throwable ignored) {
                }
                tts = null;
            }
            ready = false;
            pending = null;
            engineUsed = e;
            broken = false;          // 换了引擎，重新给一次机会
            appCtx = ctx.getApplicationContext();
            try {
                tts = e.isEmpty()
                        ? new TextToSpeech(appCtx, this::onInit)
                        : new TextToSpeech(appCtx, this::onInit, e);
            } catch (Throwable t) {
                // 指定的引擎包不存在等：直接回退系统默认再试一次
                if (!e.isEmpty()) {
                    FileLog.append("VOICE 指定 TTS 引擎创建失败（" + e + "），回退系统默认");
                    engineUsed = "";
                    try {
                        tts = new TextToSpeech(appCtx, this::onInit);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                broken = true;
                Log.w(TAG, "tts create", t);
            }
        }
    }

    private void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // 中文优先；引擎不支持中文时也照用（总比没有强）
            try {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            } catch (Throwable ignored) {
            }
            ready = true;
            FileLog.append("VOICE TTS 就绪（引擎=" +
                    (engineUsed.isEmpty() ? "系统默认" : engineUsed) + "）");
            String p = pending;
            pending = null;
            if (p != null) speak(p);
        } else {
            if (!engineUsed.isEmpty()) {
                // 选的引擎起不来：回退系统默认，别让语音回复直接哑掉
                FileLog.append("VOICE 指定 TTS 引擎 init 失败（" + engineUsed + "），回退系统默认");
                engineUsed = "";
                broken = false;
                try {
                    tts.shutdown();
                } catch (Throwable ignored) {
                }
                if (appCtx != null) tts = new TextToSpeech(appCtx, this::onInit);
                return;
            }
            broken = true;
            FileLog.append("VOICE 系统 TTS 引擎不可用 → 语音回复自动改用内置离线女声");
            Log.w(TAG, "tts init failed: " + status);
        }
    }

    /**
     * 当前是否已确认「连内置也用不了」。调用方据此退回提示音。
     * 系统引擎坏了但内置还在（或尚未确认坏），不算全哑。
     */
    public boolean isBroken() {
        if (mode == Prefs.TTS_LOCAL) return local.isBroken();
        return broken && local.isBroken();
    }

    /** 此刻能不能立刻发声（决定唤醒后要不要说「我在」；未就绪时退回提示音，不挂延迟语音） */
    public boolean canSpeakNow() {
        if (mode == Prefs.TTS_LOCAL) return local.isReady();
        if (ready) return true;
        return broken && local.isReady();
    }

    /** 说一句话。没就绪 / 没引擎就先挂着，绝不阻塞调用方。 */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        // 显式选内置，或系统引擎已经确认不可用 → 自动兜底内置
        if (mode == Prefs.TTS_LOCAL || broken) {
            local.ensure(appCtx);
            local.speak(text);
            return;
        }
        if (!ready) {
            pending = text;
            return;
        }
        try {
            // QUEUE_FLUSH：连续指令时新的顶掉旧的，别排队念
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pipdesk-reply");
        } catch (Throwable t) {
            Log.w(TAG, "tts speak", t);
        }
    }

    /**
     * 只停「当前正在念的这一句」，不销毁引擎、不释放模型。
     * 用户点遮罩收掉收音框时用：立刻闭嘴，但下次说话还是秒出声。
     */
    public void stop() {
        pending = null;
        local.cancel();
        synchronized (this) {
            if (tts != null) {
                try {
                    tts.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 换模式 / 换引擎后调用：丢掉旧系统实例，停掉内置朗读（不释放模型，切换更轻快） */
    public void reset() {
        local.cancel();
        synchronized (this) {
            ready = false;
            broken = false;
            pending = null;
            if (tts != null) {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Throwable ignored) {
                }
                tts = null;
            }
        }
    }

    /** 语音整体停止：连内置模型一起释放，别让它占着内存 */
    public void release() {
        reset();
        local.release();
    }
}
