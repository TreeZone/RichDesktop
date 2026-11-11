package com.cz.richdesk;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * TTS 回复。支持两种引擎来源：
 *  1) 跟随系统默认（用户在系统设置里选的那个，多数国产 ROM 内置讯飞/自家引擎）；
 *  2) 指定引擎包名（设置里可选）——用户装个第三方 TTS（讯飞语记、Google TTS…），
 *     不需要去系统设置换默认引擎，在这里选一下就生效，音色随引擎走。
 *
 * init 是异步的，speak() 在就绪前只挂 pending（补说一下比丢了强）；
 * 引擎不存在 / init 失败时静默退化为无语音回复，绝不阻塞执行链路。
 */
public final class VoiceTts {

    private static final String TAG = "PipDesk";

    private TextToSpeech tts;
    /** 持有 appContext，init 失败回退默认引擎时重建用 */
    private Context appCtx;
    private volatile boolean ready;
    /** 当前引擎 init 失败过：同样的引擎不再重试，换引擎后重置 */
    private volatile boolean broken;
    /** 当前请求的引擎包名，"" = 系统默认 */
    private volatile String engineUsed = "";

    /** 引擎就绪后要补说的那句（init 期间用户指令可能已经执行完） */
    private volatile String pending;

    /** 懒初始化：第一次真的要说话时才创建。engine 传 "" 走系统默认 */
    public void ensure(Context ctx, String engine) {
        final String e = engine == null ? "" : engine;
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
            final Context c = ctx.getApplicationContext();
            appCtx = c;
            try {
                tts = e.isEmpty()
                        ? new TextToSpeech(c, this::onInit)
                        : new TextToSpeech(c, this::onInit, e);
            } catch (Throwable t) {
                // 指定的引擎包不存在等：直接回退系统默认再试一次
                if (!e.isEmpty()) {
                    FileLog.append("VOICE 指定 TTS 引擎创建失败（" + e + "），回退系统默认");
                    engineUsed = "";
                    try {
                        tts = new TextToSpeech(c, this::onInit);
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
            FileLog.append("VOICE TTS 引擎不可用（本机没有文字转语音服务），回复改用提示音");
            Log.w(TAG, "tts init failed: " + status);
        }
    }

    /** 当前引擎是否已确认不可用（init 失败 / 创建失败）：调用方据此退回提示音 */
    public boolean isBroken() {
        return broken;
    }

    /** 说一句话。没就绪 / 没引擎就先挂着，绝不阻塞调用方。 */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
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

    /** 换引擎后调用：丢掉旧实例，下次 ensure 按新引擎重建 */
    public void reset() {
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

    public void release() {
        reset();
    }
}
