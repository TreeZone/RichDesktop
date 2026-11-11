package com.cz.richdesk;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语音助手的状态机 + 线程模型。
 *
 * 分工：
 *   - **采集线程**（VoiceMic 自己的线程）只做「喂模型 + 出结果」，所有模型调用都在这里，
 *     单线程使用 sherpa-onnx，不需要额外加锁；
 *   - **控制线程**（本类）负责开关麦克风、加载模型、判超时、执行动作。
 *     麦克风的 start/stop 走 AudioService 的 binder（本项目有 AUDIO-BLOCK 前科），
 *     所以绝不能出现在主线程上 —— 这是本类存在的第一理由。
 *
 * 两条进入路径：
 *   1) 唤醒词：KWS 常驻 → 命中 → 加载/复用 ASR → 聆听 → 断句 → 执行 → 回到 KWS；
 *   2) 点「语音」图标：直接聆听一次，说完自动收工（不需要唤醒词）。
 *
 * 唤醒瞬间的关键细节：ASR 模型 24MB，加载要 1~2 秒，而用户唤醒后往往**立刻**说
 * 「打开QQ音乐」。所以 KWS 阶段一直在维护一个 2.5 秒的环形缓冲，
 * 唤醒后先把缓冲里的音频补喂给 ASR，再切到实时，否则「打开」两个字必丢。
 */
public final class Voice {

    private static final String TAG = "PipDesk";
    /** 唤醒词的参考底噪门限：低于它不喂 KWS，省 CPU（KWS 自己也会判静音） */
    private static final int MIN_RMS = 60;
    /** 唤醒后单句聆听上限 */
    private static final long LISTEN_MAX_MS = 12_000L;
    /**
     * 硬闸：从这一轮开始（含准备/识别/匹配）算起，超过这个时长无条件收工。
     * 5 秒是用户实测定下来的：说话指令一般 2~3 秒内说完，再挂着只会挡视线。
     *
     * 为什么需要它：3 秒「没有新的识别文字」只能覆盖 S_LISTEN 一种状态，而且噪音里
     * ASR 会不停吐出**不同的**乱码字（每次都"新"，计时器一直被续），端点也会把
     * 12 秒上限无限重置。用户看到的就是"识别不到字也不退出、卡片一直挂着"。
     * 这条闸不依赖识别结果，只看时钟，任何状态都逃不掉。
     */
    private static final long HARD_MAX_MS = 5_000L;
    /** 自我回采最多容忍几次：超过就不是回采了，别再无限续命 */
    private static final int MAX_ECHO_HITS = 2;
    /**
     * 收工阈值：**这么久还没识别出文字**就退出，不看音量。
     *
     * 之前按 RMS 判"静音"：车机里环境噪音常年高于底噪门限（空调、胎噪、音乐），
     * RMS 一直算"有声音"，于是永远不超时，只能干等到 12 秒上限 ——
     * 用户感受就是"没说话它也在那儿一直收"。改成以 ASR 有没有吐出字为准：
     * 噪音再大，模型出不了字，3 秒就收。
     * 同理，已经出过字但 3 秒没有新字也收（正常一句话中间不会停这么久，
     * 端点检测 1.2 秒就会先断句，这里只是兜底）。
     */
    private static final long NO_TEXT_TIMEOUT_MS = 2_500L;
    /** 报完候选后再给多久说「第几个」（比普通聆听短一点，别让卡片一直挂着） */
    private static final long PICK_WAIT_MS = 8_000L;
    /** 环形缓冲时长：覆盖 ASR 加载那 1~2 秒 + 说话前的犹豫 */
    private static final int RING_MS = 2500;

    public static final int S_OFF = 0;        // 没开麦克风
    public static final int S_WAKE = 1;       // 常驻等唤醒词
    public static final int S_LOADING = 2;    // 唤醒后正在准备识别
    public static final int S_LISTEN = 3;     // 正在听指令
    public static final int S_THINK = 4;      // 已断句，正在匹配（很短）
    public static final int S_ERROR = 5;

    /** 宿主（MainActivity）要提供的东西 */
    public interface Host {
        /** 当前可启动应用列表（语音只认这里面有的东西） */
        List<AppInfo> apps();

        /** 状态 / 实时字幕推给 UI */
        void onVoiceEvent(int state, String text, String detail);

        /** 要执行的动作：pkg 是真实包名或 pipdesk:// 内置伪包名 */
        void onVoiceAction(String pkg);

        /** 听不准，给候选让用户点；点完会记成别名 */
        void onVoiceCandidates(String phrase, List<AppInfo> cands);

        /** 用嘴选了第几个（说完「第一个」）：把候选列表收掉 */
        default void onVoiceCandidatesPicked(String phrase, AppInfo pick) { }
    }

    private static final Voice INSTANCE = new Voice();
    private final Object lock = new Object();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Context ctx;
    private Host host;
    private VoiceMic mic;
    private VoiceKws kws;
    private VoiceAsr asr;
    private final VoiceTts tts = new VoiceTts();
    private ToneGenerator tone;

    private Thread control;
    private volatile boolean alive;
    private volatile boolean wakeMode;
    private volatile boolean listenReq;
    private volatile int state = S_OFF;
    private volatile String partial = "";
    private volatile int level;
    private volatile long deadline;
    /** "没识别出文字"的计时起点（TTS 的「我在」念完才开始算） */
    private volatile long listenAnchor;
    /** 最后一次字幕有变化的时刻 */
    private volatile long lastTextAt;
    /** 本轮开始聆听的时刻（识别器为 null 时的兜底判定） */
    private volatile long listenSince;
    /** 本轮把识别结果当成「自我回采」丢掉的次数 */
    private int echoHits;
    /** KWS 重建失败后的退避时刻：否则每 80ms 试一次，日志刷屏还费 CPU */
    private volatile long kwsRetryAt;
    /** 最后一次收到 PCM 的时刻：用来发现"麦克风开着但没有数据" */
    private volatile long lastPcmAt;
    private volatile long cooldownUntil;
    private volatile long idleSince;
    private volatile long lastUiPush;
    private volatile String lastError;

    /** 「您要说哪个？」之后继续听下一条：候选列表 + 原说法 + 是否在等序号 */
    private volatile boolean awaitingPick;
    private volatile List<AppInfo> pickCands;
    private volatile String pickPhrase = "";

    private final short[] ring = new short[VoiceMic.SR * RING_MS / 1000];
    private int ringPos;
    private int ringLen;

    private Voice() { }

    public static Voice get() {
        return INSTANCE;
    }

    public int state() {
        return state;
    }

    public int level() {
        return level;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isRecallable() {
        return wakeMode && (state == S_WAKE || state == S_LISTEN);
    }

    // ------------------------------------------------------------ 生命周期

    /** 桌面 onResume 调：接上宿主，按设置决定是否常驻等唤醒 */
    public void attach(Context context, Host h) {
        this.ctx = context.getApplicationContext();
        this.host = h;
        if (ctx == null) return;
        boolean want = Prefs.voiceWake(ctx);
        if (want) startWake();
    }

    public void detach() {
        host = null;
    }

    /** 打开常驻唤醒（设置里勾上时调用） */
    public void startWake() {
        if (ctx == null) return;
        if (alive) return;
        wakeMode = true;
        alive = true;
        state = S_LOADING;
        pushState("开启唤醒词…", null);
        control = new Thread(this::controlLoop, "pipdesk-voice-ctl");
        control.setDaemon(true);
        control.start();
    }

    /**
     * 关掉常驻唤醒 / 彻底停。
     *
     * **不能阻塞调用方**：桌面 onDestroy 会走到这里，而主线程上 join 采集线程 +
     * AudioRecord.release() 正是本项目 AUDIO-BLOCK 的老路。所以清理全丢后台线程。
     */
    public void stop() {
        alive = false;
        listenReq = false;
        final Thread t = control;
        control = null;
        wakeMode = false;
        Thread cleaner = new Thread(() -> {
            if (t != null) {
                try {
                    t.join(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (lock) {
                if (mic != null) {
                    mic.stop();
                    mic = null;
                }
                if (kws != null) {
                    kws.release();
                    kws = null;
                }
                if (asr != null) {
                    asr.release();
                    asr = null;
                }
            }
            if (tone != null) {
                try {
                    tone.release();
                } catch (Throwable ignored) {
                }
                tone = null;
            }
            tts.release();
            state = S_OFF;
            FileLog.append("VOICE 语音已停止");
        }, "pipdesk-voice-stop");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    /**
     * 点「语音」图标 / 又说了一次唤醒词：听一次。
     *
     * 连点两下、或"唤醒词刚命中又点了图标"这种快速连续触发必须处理干净：
     * 先把上一轮（聆听中的字幕、等序号的候选、识别流上下文）整体收掉，再开新一轮，
     * 否则会出现"上一轮的超时回调把新一轮关掉""候选列表挂着但已经在新一轮"这类错位。
     */
    public void listenOnce() {
        if (ctx == null) return;
        supersede();
        listenReq = true;
        if (!alive) {
            // 没开常驻：临时起一条控制线程，说完自己收工
            wakeMode = false;
            alive = true;
            control = new Thread(this::controlLoop, "pipdesk-voice-ctl");
            control.setDaemon(true);
            control.start();
        }
        pushState("准备聆听…", null);
    }

    /** 新一轮开始前把上一轮的状态清干净（不动麦克风、不动 alive） */
    private void supersede() {
        int st = state;
        awaitingPick = false;
        pickCands = null;
        partial = "";
        cooldownUntil = 0;
        ringLen = 0;
        synchronized (lock) {
            if (asr != null) asr.begin();
            if (kws != null) kws.resetStream();
        }
        FileLog.append("VOICE 新一轮触发（上一轮 state=" + st + " 已收起）");
    }

    /** 设置里改阈值 / 开关后重新加载唤醒词 */
    public void reloadWake() {
        // 用户主动改了设置（比如换了唤醒词）：把熔断标记清掉，值得再试一次
        try {
            new File(ctx.getFilesDir(), "voice/kws.load").delete();
        } catch (Throwable ignored) {
        }
        synchronized (lock) {
            if (kws != null) {
                kws.release();
                kws = null;
            }
        }
    }

    // ------------------------------------------------------------ 控制线程

    private void controlLoop() {
        try {
            // 权限先解决：AppOps / pm grant 都不行时，MainActivity 会补一次系统权限框
            if (!VoicePerm.has(ctx)) {
                VoicePerm.tryGrant(ctx);
            }
            if (wakeMode) {
                synchronized (lock) {
                    if (!ensureKws() || !ensureMic()) {
                        state = S_ERROR;
                        pushState("麦克风不可用：" + lastError, null);
                        return;
                    }
                }
                state = S_WAKE;
                idleSince = SystemClock.elapsedRealtime();
                pushState(hint(), null);
            }

            while (alive) {
                try {
                long now = SystemClock.elapsedRealtime();

                // 硬闸放在最前面：不管下面哪个分支卡住/抛异常，这一轮都会在这里落地
                if (hardGuard(now)) {
                    try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
                    continue;
                }

                // 采集断流：麦克风 2.5 秒没有任何回调 = AudioRecord 挂了或被别的
                // 应用抢走了（车机上 HORI 遥控器、单画里的录音类应用都会抢）。
                // 这种时候电平条是死的、识别也永远没字，只能主动收工并提示。
                if ((state == S_LISTEN || state == S_LOADING)
                        && now - lastPcmAt > 2500 && now - listenSince > 2500) {
                    FileLog.append("VOICE 2.5s 没收到任何 PCM（采集断流，mic="
                            + (mic != null) + "）→ 收工");
                    lastError = "麦克风没有数据";
                    cancelListening();
                    ui.post(() -> {
                        Host h = host;
                        if (h != null) h.onVoiceEvent(S_ERROR, "麦克风没有数据，可能被其他应用占用", null);
                    });
                    try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
                    continue;
                }

                // 聆听期间 KWS 被释放了（见 beginListen 注释）：回到待唤醒就重建，
                // 放在控制线程做，不占采集线程，播 0.4 秒也无感。
                if (state == S_WAKE && kws == null && wakeMode && now >= kwsRetryAt) {
                    if (!ensureKws()) {
                        kwsRetryAt = now + 5000;
                        FileLog.append("VOICE 唤醒词重建失败（5 秒后再试）：" + lastError);
                    }
                }

                if (listenReq) {
                    listenReq = false;
                    beginListen();
                }

                if (state == S_LISTEN) {
                    String cur = asr == null ? "" : asr.text();
                    boolean hasText = cur != null && !cur.trim().isEmpty();
                    // 判据是「字幕多久没变过」，不是「有没有声音」——
                    // 环境噪音大时 RMS 一直超门限，按音量判就永远不会超时；
                    // 而只要识别结果不变（停在同一串字上），也说明人已经说完了。
                    long anchor = hasText ? Math.max(listenAnchor, lastTextAt) : listenAnchor;
                    if (now - anchor >= NO_TEXT_TIMEOUT_MS) {
                        FileLog.append("VOICE " + (now - anchor) + "ms 没有新的识别文字"
                                + (hasText ? "（停在「" + cur + "」）" : "（环境噪音？）")
                                + " → 自动收工");
                        finish(cur, true);
                    } else if (now > deadline) {
                        // 到点没等到断句：有字就认，没字就当没听清
                        finish(cur, true);
                    } else if (asr == null && now - listenSince > 1200) {
                        // 识别器根本没准备好（加载失败/被回收）：别在这儿干等满 12 秒
                        FileLog.append("VOICE 识别器不可用 → 收工");
                        state = wakeMode ? S_WAKE : S_OFF;
                        pushState(wakeMode ? hint() : "语音待命", null);
                        idleSince = now;
                    } else if (asr != null && asr.isEndpoint()) {
                        if (hasText) finish(cur, false);
                        else {
                            // 端点是静音触发的，说明还在"等人说话"阶段：重置流继续等
                            asr.begin();
                            deadline = now + LISTEN_MAX_MS;
                            listenAnchor = now + 300;
                            lastTextAt = listenAnchor;
                        }
                    }
                }

                // 非常驻模式：说完 2.5 秒就关麦，别一直占着
                if (!wakeMode && state == S_OFF && mic != null
                        && now - idleSince > 2500) {
                    synchronized (lock) {
                        if (mic != null) {
                            mic.stop();
                            mic = null;
                        }
                    }
                    break;
                }

                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
                } catch (Throwable loopErr) {
                    // 控制线程是语音的唯一调度者：它一死，卡片就永远挂在屏幕上
                    // （发生过的真事）。所以这里**吞掉一切**，记日志，继续转。
                    Log.e(TAG, "语音控制循环异常", loopErr);
                    FileLog.append("VOICE 控制循环异常（已吞掉，继续运行）：" + loopErr);
                    try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                }
            }
        } finally {
            alive = false;
            synchronized (lock) {
                if (mic != null) {
                    mic.stop();
                    mic = null;
                }
            }
            if (state != S_ERROR) state = S_OFF;
            pushState(wakeMode ? "语音已停止" : "语音待命", null);
            Log.i(TAG, "语音控制线程退出");
        }
    }

    /**
     * 硬闸：这一轮无论卡在哪个状态，到点就收。
     * @return true = 刚刚强制收工（调用方跳过本轮剩余逻辑）
     */
    private boolean hardGuard(long now) {
        int st = state;
        if (st != S_LISTEN && st != S_THINK && st != S_LOADING) return false;
        long since = listenSince;
        if (since <= 0) { listenSince = now; return false; }
        // 等「第几个」的窗口比普通聆听长一点（PICK_WAIT_MS 是报完候选才开始算）
        boolean hasText = partial != null && !partial.isEmpty();
        long cap = (st == S_LISTEN && awaitingPick)
                ? PICK_WAIT_MS + 2_000L
                : (hasText ? HARD_MAX_MS + 1_000L : HARD_MAX_MS);
        if (now - since <= cap) return false;
        FileLog.append("VOICE 本轮已 " + (now - since) + "ms 超过上限（state=" + st
                + "，字幕=\"" + (partial == null ? "" : partial) + "\"）→ 强制收工");
        cancelListening();
        return true;
    }

    private boolean ensureKws() {
        if (kws != null && kws.isReady()) return true;

        // 熔断器：sherpa-onnx 读不到模型/关键词文件时是在 **native 层 exit(-1)**，
        // Java 的 try/catch 拦不住，整个桌面进程会被打死、再被系统拉起 ——
        // 用户看到的就是"一直在闪屏"。所以加载前先落一个标记，加载成功才删。
        // 下次启动若发现标记还在，说明上一次正死在这一步：直接跳过 KWS（语音降级为
        // 不可用），先保住桌面，别让它无限重启。90 秒后自动再试一次。
        File guard = new File(ctx.getFilesDir(), "voice/kws.load");
        long now = System.currentTimeMillis();
        if (guard.isFile() && now - guard.lastModified() < 90_000L) {
            FileLog.append("VOICE 上次加载唤醒词时进程被 native 打死（"
                    + (now - guard.lastModified()) + "ms 前）→ 本次跳过 KWS，先保住桌面");
            lastError = "唤醒词引擎上次加载崩溃，已临时跳过";
            return false;
        }
        try {
            File dir = guard.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(guard);
            fo.write(1);
            fo.close();
        } catch (Throwable ignored) {
        }

        kws = new VoiceKws(ctx);
        // 自定义唤醒词：中文 → 拼音音素关键词文件；转不出来就用 assets 自带的默认词
        String word = VoiceWords.wakeWord(ctx);
        String kwFile = VoiceWords.keywordsFile(ctx, word);
        boolean ok = kws.load(Prefs.voiceThreshold(ctx), kwFile);
        try {
            guard.delete();
        } catch (Throwable ignored) {
        }
        if (!ok) {
            lastError = kws.loadError();
            return false;
        }
        return true;
    }

    private boolean ensureMic() {
        if (mic != null && mic.isRunning()) return true;
        mic = new VoiceMic(this::onPcm);
        boolean ok;
        synchronized (lock) {
            ok = mic.start();
        }
        if (!ok) {
            lastError = mic.error();
            return false;
        }
        idleSince = SystemClock.elapsedRealtime();
        return true;
    }

    /** 开始一次聆听：先把模型和麦克风准备好，再切状态（否则会漏掉开头） */
    private void beginListen() {
        // 一进来就计时：准备阶段（加载模型/开麦）也要算在硬闸里，
        // 否则 S_LOADING 用的是上一轮的时间戳，或者干脆没人管
        listenSince = SystemClock.elapsedRealtime();
        echoHits = 0;
        state = S_LOADING;
        pushState("准备聆听…", null);
        partial = "";

        if (!wakeMode) {
            // PTT：先加载模型（1~2s，界面上能看到"准备中"），再开麦
            synchronized (lock) {
                if (asr == null) asr = new VoiceAsr(ctx);
                if (!asr.isReady() && !asr.load()) {
                    state = S_ERROR;
                    lastError = asr.loadError();
                    pushState(lastError, null);
                    return;
                }
                if (!ensureMic()) {
                    state = S_ERROR;
                    pushState("麦克风不可用：" + lastError, null);
                    return;
                }
            }
            asr.begin();
        } else {
            // 常驻唤醒和这次聆听会抢资源：用户实测「开着唤醒词常驻就收不到声音，
            // 关了就正常」。原因是 KWS 和 ASR 两个 sherpa 模型同时在采集线程里解码，
            // 采集跟不上 → ASR 拿到的是延迟音频 → 一个字都识别不出来。
            // 所以聆听期间把 KWS 整个放掉（回到待唤醒时控制循环会重建，约 0.4 秒），
            // 保证同一时刻只有一个模型吃 CPU/内存。
            synchronized (lock) {
                if (kws != null) {
                    kws.release();
                    kws = null;
                    FileLog.append("VOICE 聆听开始：KWS 已释放（避免与 ASR 抢资源）");
                }
                if (asr == null) asr = new VoiceAsr(ctx);
                if (!asr.isReady() && !asr.load()) {
                    state = S_WAKE;      // 唤醒词还能用，只是这次听不了
                    lastError = asr.loadError();
                    pushState("识别模型加载失败", null);
                    return;
                }
                asr.begin();
                // 把 KWS 阶段缓冲的音频补喂进去：唤醒后立刻说的「打开」都在这里
                feedRing();
            }
        }

        // 开始聆听的提示：能用 TTS 就说「我在」，比一声「滴」自然；
        // 语音回复关着或引擎不可用，退回原来的提示音。
        boolean spoken = Prefs.voiceReply(ctx) && !tts.isBroken();
        if (spoken) {
            say("我在");
        } else {
            beep(true);
        }
        state = S_LISTEN;
        long now = SystemClock.elapsedRealtime();
        listenSince = now;
        awaitingPick = false;
        pickCands = null;
        deadline = now + LISTEN_MAX_MS;
        // 「我在」要念 ~1 秒，这段时间麦克风在收自己的声音，不该算进"没说话"。
        // 所以 3 秒无字超时从 TTS 念完之后才开始计（否则用户刚被唤醒就超时了）。
        listenAnchor = now + (spoken ? 1100 : 0);
        lastTextAt = listenAnchor;
        idleSince = deadline;
        pushState("请说指令", null);
    }

    /** 唤醒后补录环形缓冲 */
    private void feedRing() {
        if (asr == null || ringLen <= 0) return;
        short[] tmp = new short[ringLen];
        int start = (ringPos - ringLen + ring.length) % ring.length;
        for (int i = 0; i < ringLen; i++) tmp[i] = ring[(start + i) % ring.length];
        // 一次喂完容易让 zipformer 的 chunk 对齐错位，按 100ms 切片喂
        int chunk = VoiceMic.SR / 10;
        for (int off = 0; off < tmp.length; off += chunk) {
            int n = Math.min(chunk, tmp.length - off);
            short[] part = new short[n];
            System.arraycopy(tmp, off, part, 0, n);
            asr.accept(part, n);
        }
        FileLog.append("VOICE 唤醒补录 " + ringLen + " 样本（" + (ringLen * 1000 / VoiceMic.SR) + "ms）");
        ringLen = 0;
    }

    /** 采集线程回调 */
    private void onPcm(short[] buf, int n, int rms) {
        level = rms;
        lastPcmAt = SystemClock.elapsedRealtime();
        int st = state;
        if (st == S_LISTEN) {
            String t;
            synchronized (lock) {
                if (asr == null) return;
                t = asr.accept(buf, n);
            }
            String prev = partial == null ? "" : partial;
            partial = t == null ? "" : t;
            long now = SystemClock.elapsedRealtime();
            // **只有字幕真的变了**才重置超时计时。
            // 踩过的坑：原来是「只要字幕非空就续命」，于是噪音偶尔吐出一个字之后，
            // 计时器每帧都被刷新，3 秒超时永远不成立，卡片就一直挂着不退出。
            if (!partial.isEmpty() && !partial.equals(prev)) lastTextAt = now;
            if (now - lastUiPush > 260) {          // 字幕别推太密，省 UI 线程
                lastUiPush = now;
                pushState("请说指令", partial);
            }
            return;
        }
        if (st == S_LOADING) {
            // 唤醒后正在加载识别模型（1~2 秒）：这段时间**必须继续往环形缓冲里写**，
            // 否则用户唤醒后马上说的「打开QQ音乐」全丢在这儿。加载完由 feedRing() 补喂。
            appendRing(buf, n);
            return;
        }
        if (st == S_WAKE && kws != null) {
            // 环形缓冲只为人声准备：静音段没必要存（KWS 本身必须连续喂，
            // 跳帧会把流式模型的上下文打断，唤醒词命中率会莫名其妙掉到 0）
            if (rms >= MIN_RMS) appendRing(buf, n);
            String kw;
            synchronized (lock) {
                if (kws == null) return;
                kw = kws.accept(buf, n);
            }
            long now = SystemClock.elapsedRealtime();
            if (kw != null && now >= cooldownUntil) {
                cooldownUntil = now + 800;         // 冷却：防止同一句触发两次
                FileLog.append("VOICE 唤醒命中「" + kw + "」");
                onWake(kw);
            }
            return;
        }
        if (st == S_WAKE) {
            // 静音：不喂 KWS，缓冲也清空，避免把上一句话的尾巴带进下一次
            if (rms < MIN_RMS / 2) ringLen = 0;
        }
    }

    private void onWake(String kw) {
        // 注意：这里跑在**采集线程**上，绝不能在这里加载 ASR（1~2 秒，会让麦克风断流，
        // 用户唤醒后立刻说的「打开」就全丢了）。只置请求，让控制线程去开识别，
        // 期间环形缓冲继续填 —— 补录靠它。
        // 只保留最后 300ms：唤醒词本身没必要送进 ASR，留一点尾巴防止把下一个字切掉。
        int keep = VoiceMic.SR * 300 / 1000;
        if (ringLen > keep) ringLen = keep;
        partial = "";
        pushState("在听…", null);
        listenReq = true;
    }

    private void appendRing(short[] buf, int n) {
        for (int i = 0; i < n; i++) {
            ring[ringPos] = buf[i];
            ringPos = (ringPos + 1) % ring.length;
            if (ringLen < ring.length) ringLen++;
        }
    }

    /**
     * 断句完成：匹配 + 执行。
     * @param hardTimeout 超时收工（没有端点）——这时候字幕可能只有半句，允许但记日志
     */
    private void finish(String text, boolean hardTimeout) {
        String t = text == null ? "" : text.trim();
        FileLog.append("VOICE 识别\"" + t + "\"" + (hardTimeout ? "（超时收工）" : ""));

        // 自我回采：开始聆听时 TTS 说的「我在」可能被麦克风录进去。
        // 它不是指令 —— 重置识别器继续听，别回一句「没找到我在」。
        if (isEcho(t) && echoHits < MAX_ECHO_HITS) {
            echoHits++;
            FileLog.append("VOICE 忽略自我回采「" + t + "」（第 " + echoHits + " 次）");
            state = S_LISTEN;
            synchronized (lock) {
                if (asr != null) asr.begin();
            }
            long now = SystemClock.elapsedRealtime();
            deadline = now + LISTEN_MAX_MS;
            listenAnchor = now + 500;
            lastTextAt = listenAnchor;
            pushState("请说指令", "");
            return;
        }

        // 「您要说哪个？」之后继续听：用户说「第一个 / 第二个 / 选二」就能直接选。
        // 说了别的内容则当作新指令继续往下走（候选作废）。
        if (awaitingPick && pickCands != null && !pickCands.isEmpty()) {
            int idx = VoiceIntent.parsePick(t, pickCands.size());
            if (idx >= 0) {
                AppInfo pick = pickCands.get(idx);
                awaitingPick = false;
                pickCands = null;
                beep(false);
                say("好的，打开" + pick.label);
                pushState("→ " + pick.label, t);
                FileLog.append("VOICE 候选序号选第 " + (idx + 1) + " 个 → " + pick.label);
                ui.post(() -> {
                    Host hh = host;
                    if (hh != null) {
                        hh.onVoiceCandidatesPicked(pickPhrase, pick);
                        hh.onVoiceAction(pick.pkg);
                    }
                });
                endUtterance(700);
                return;
            }
            awaitingPick = false;
            pickCands = null;
        }

        state = S_THINK;

        Host h = host;
        List<AppInfo> apps = h == null ? null : h.apps();
        Map<String, Set<String>> alias = VoiceIntent.builtinAliases(apps);
        Map<String, Set<String>> user = Prefs.parseAlias(Prefs.voiceAliasRaw(ctx));
        for (Map.Entry<String, Set<String>> e : user.entrySet()) {
            Set<String> set = alias.get(e.getKey());
            if (set == null) alias.put(e.getKey(), e.getValue());
            else set.addAll(e.getValue());
        }

        final VoiceIntent.Result r = VoiceIntent.parse(t, apps, alias);
        FileLog.append("VOICE 判定 " + VoiceIntent.describe(r) + "（说法=\"" + r.text + "\"）");

        if (r.kind == VoiceIntent.APP || r.kind == VoiceIntent.CMD) {
            final String pkg = r.pkg;
            final String label = r.label;
            beep(false);
            say(r.kind == VoiceIntent.APP ? "好的，打开" + label : "好的");
            pushState("→ " + label, t);
            ui.post(() -> {
                Host hh = host;
                if (hh != null) hh.onVoiceAction(pkg);
            });
            endUtterance(700);
        } else if (r.kind == VoiceIntent.AMBIGUOUS && !r.candidates.isEmpty()) {
            final List<AppInfo> c = new java.util.ArrayList<>(r.candidates);
            final String phrase = r.text;
            // 报候选的同时**继续听**：说完「您要说哪个？」用户直接接「第一个」就行，
            // 不用伸手去点（车机上伸手点列表本来就危险）。列表同时弹出来，两条路都能走。
            awaitingPick = true;
            pickCands = c;
            pickPhrase = phrase;
            StringBuilder sb = new StringBuilder("您要说哪个？");
            for (int i = 0; i < c.size() && i < 3; i++) {
                sb.append(i == 0 ? "第一个" : (i == 1 ? "，第二个" : "，第三个"))
                        .append(c.get(i).label);
            }
            say(sb.toString());
            pushState("说「第一个」/「第二个」，或直接点", t);
            ui.post(() -> {
                Host hh = host;
                if (hh != null) hh.onVoiceCandidates(phrase, c);
            });
            // 重新进入聆听态（不结束这一轮）
            state = S_LISTEN;
            partial = "";
            synchronized (lock) {
                if (asr != null) asr.begin();
            }
            long now = SystemClock.elapsedRealtime();
            deadline = now + PICK_WAIT_MS;
            // 提示语要念 1~2 秒，这段时间麦克风收的是自己，不计入"没说话"
            listenAnchor = now + 1800;
            lastTextAt = listenAnchor;
            listenSince = now;
        } else {
            say(t.isEmpty() ? "没听清，再说一遍" : "没找到「" + t + "」");
            pushState(t.isEmpty() ? "没听清" : "没找到「" + t + "」", t);
            endUtterance(1200);
        }
    }

    /** 系统引擎回一句话（设置里可关；引擎不存在时静默） */
    private void say(String s) {
        if (!Prefs.voiceReply(ctx)) return;
        tts.ensure(ctx, Prefs.voiceTtsEngine(ctx));
        tts.speak(s);
    }

    /** 设置里换 TTS 引擎 / 点试听后调用：按新引擎重建 */
    public void onTtsEngineChanged() {
        tts.reset();
    }

    /** 设置里的「试听」：用当前引擎说一句，用户能立刻听到音色效果 */
    public void previewReply() {
        if (!Prefs.voiceReply(ctx)) return;
        tts.ensure(ctx, Prefs.voiceTtsEngine(ctx));
        tts.speak("我在，这是当前的回复音色");
    }

    /** 这些短句只可能是我们自己（或对方接话）说的，不当指令处理 */
    private static final String[] ECHO_WORDS = {
            "我在", "在", "我在呢", "我在这里", "我在这儿",
            "嗯", "嗯哼", "哎", "喂", "来了",
    };

    private static boolean isEcho(String t) {
        String n = VoiceIntent.normalize(t);
        for (String w : ECHO_WORDS) {
            if (n.equals(w)) return true;
        }
        return false;
    }

    /** 收尾：回到等唤醒 / 关机 */
    private void endUtterance(long delayMs) {
        awaitingPick = false;
        pickCands = null;
        ui.postDelayed(() -> {
            if (!alive) return;
            if (wakeMode) {
                synchronized (lock) {
                    if (kws != null) kws.resetStream();
                }
                ringLen = 0;
                state = S_WAKE;
                cooldownUntil = SystemClock.elapsedRealtime() + 700;
                pushState(hint(), null);
            } else {
                state = S_OFF;
                idleSince = SystemClock.elapsedRealtime();
                pushState("语音待命", null);
            }
        }, delayMs);
    }

    /** 「说「你好小瑞」」这类提示文案：跟着设置里的唤醒词走 */
    private String hint() {
        String w = ctx == null ? VoiceWords.DEFAULT : VoiceWords.wakeWord(ctx);
        return "说「" + w + "」";
    }

    /**
     * 用户在浮层上点了一下（或界面上别的地方被点）：主动收工，回到待唤醒。
     * 只关这一轮聆听，不关麦克风、不动常驻唤醒。
     */
    public void cancelListening() {
        int st = state;
        if (st != S_LISTEN && st != S_LOADING && st != S_THINK) return;
        awaitingPick = false;
        pickCands = null;
        partial = "";
        synchronized (lock) {
            if (asr != null) asr.begin();
        }
        if (wakeMode) {
            synchronized (lock) {
                if (kws != null) kws.resetStream();
            }
            ringLen = 0;
            state = S_WAKE;
            cooldownUntil = SystemClock.elapsedRealtime() + 600;
            pushState(hint(), null);
        } else {
            state = S_OFF;
            idleSince = SystemClock.elapsedRealtime();
            pushState("语音待命", null);
        }
        FileLog.append("VOICE 用户点了浮层 → 结束本轮聆听");
    }

    // ------------------------------------------------------------ 提示音

    private void beep(boolean up) {
        if (ctx == null || !Prefs.voiceBeep(ctx)) return;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
            tone.startTone(up ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 110);
        } catch (Throwable t) {
            Log.w(TAG, "beep 失败（车机音频通道不可用） " + t);
        }
    }

    private void pushState(String detail, String text) {
        final int st = state;
        final String d = detail;
        Host h = host;
        if (h == null) return;
        ui.post(() -> {
            Host hh = host;
            if (hh != null) hh.onVoiceEvent(st, text == null ? "" : text, d);
        });
    }
}
