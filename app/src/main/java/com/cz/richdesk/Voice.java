package com.cz.richdesk;

import android.content.Context;
import android.content.pm.PackageManager;
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
    /**
     * 连续这么久采样全为死寂（峰值 ≈ 0）就换一条 input 路由重开一次。
     *
     * 1.2 秒的依据：这是"用户喊唤醒词"的时间尺度。死寂越早恢复越好，
     * 而正常说话不可能连续 1.2 秒一个非零采样都没有（真实麦克风有底噪）。
     * 注意：**死寂不等于"被别人占了"** —— 车机 AudioPolicyService allowCapture=1
     * 允许并发录音（百度 MapVoiceProcess + 厂商 gocsdk 同时开麦），
     * 所以这里只做 recycleMic，绝不停采让路。
     */
    private static final long SILENT_RECOVER_MS = 1_200L;
    /**
     * 让路兜底的等待上限。**只在 HAL 真单路独占、且两条路由反复重开都拿不到数据时**才会走到。
     *
     * 常态（并发）根本不进入让路态；真独占时短暂让出 5 秒让对方把一轮对话说完，
     * 到点探测收回。原值 15 秒会让"三个助手都要能用"变成长时间失聪，缩短到 5 秒。
     */
    private static final long YIELD_WATCHDOG_MS = 5_000L;
    /**
     * 连续换路由重开这么多次仍死寂，且 getActiveRecordingConfigurations 明确查到别的录音方，
     * 才判定这台 HAL 是单路独占、进入让路兜底。避免偶发一次死寂就误判成"被抢"。
     */
    private static final int YIELD_AFTER_RECYCLES = 4;
    /** 重开麦克风的退避起点 / 上限：连续失败时翻倍，封顶后一直以这个节奏后台重试 */
    private static final long MIC_RETRY_MIN_MS = 1_200L;
    private static final long MIC_RETRY_MAX_MS = 15_000L;
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
     * 【需求二.3】收工阈值：开始聆听后**这么久还没识别出有用的文字**就判「没听清」直接退出。
     *
     * 之前按 RMS 判"静音"：车机里环境噪音常年高于底噪门限（空调、胎噪、音乐），
     * RMS 一直算"有声音"，于是永远不超时。改成以 ASR 有没有吐出**有用**的字为准：
     * 唤醒词、TTS「我在」的自我回采、空串都不算有用文字。
     * 旧值是 2500ms 且从 TTS 念完（+1100ms）才开始算，实际要 3.6 秒才收，
     * 而且超时走 finish() →「没听清，再说一遍」+ 1.2 秒延迟收尾，不是「直接退出」。
     * 现在：本轮一个有用字都没出现过 → 满 3 秒直接 cancelListening() 退出；
     * 已经出过字但 3 秒没有新字 → 视为说完，走 finish() 提交执行。
     */
    private static final long NO_TEXT_TIMEOUT_MS = 3_000L;
    /** 报完候选后再给多久说「第几个」（比普通聆听短一点，别让卡片一直挂着） */
    private static final long PICK_WAIT_MS = 8_000L;
    /** 候选每页条数：语音序号只到 9，多了靠翻页；列表本身不截断 */
    private static final int PICK_PAGE_SIZE = 9;
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
    /** 上一次建识别器时用到的别名快照；变了就要重建识别器刷新热词表 */
    private String hotAliasStamp;
    private final VoiceTts tts = new VoiceTts();
    private ToneGenerator tone;

    // ---- 音频焦点：唤醒后把正在放的音乐压下去，免得盖住说话和播报 ----
    /** 语音会话期间持有 TRANSIENT 焦点；持有期间别家播放器会被系统要求暂停 */
    private AudioManager am;
    private android.media.AudioFocusRequest focusReq;
    private volatile boolean focusHeld;

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
    /** 最近一次 TTS 播报的文本与时刻：用来识别「自己刚说的话又被麦克风听回去」 */
    private volatile String lastSayText;
    private volatile long lastSayAt;
    /**
     * 上一帧是不是被播报守卫挡掉了。
     *
     * 播报期间我们**不往 ASR 里喂帧**（物理隔离）。那么守卫解除的那一帧必须先把
     * 识别器重置一次：否则播报前的残留音频 + 播报后的第一句会被接成一句，
     * 端点检测还会在中间多切一刀，吐出来的就是半截播报混着指令。
     */
    private volatile boolean speechGuardHeld;
    /** 本轮是否出现过「有用的文字」：3 秒没听清直接退出就看它 */
    private volatile boolean gotUsefulText;
    /** KWS 重建失败后的退避时刻：否则每 80ms 试一次，日志刷屏还费 CPU */
    private volatile long kwsRetryAt;
    /** 最后一次收到 PCM 的时刻：用来发现"麦克风开着但没有数据" */
    private volatile long lastPcmAt;
    /** 下一次允许重开麦克风的时刻（退避用） */
    private long micRetryAt;
    /** 当前重开间隔：成功一次就回到起点，连续失败翻倍 */
    private long micRetryGap = MIC_RETRY_MIN_MS;
    /** 本轮总共重开了几次（每两次换一个 audio source 试） */
    private int micTries;
    /**
     * 让路中：别的应用（典型是小度）正在录音，我们已主动停采，等它结束后收回。
     * 这是与「重开抢回」并行的另一条恢复路 —— 抢回只用于**没有任何录音方**的
     * 死寂（HAL 选路问题），有明确占用者时先礼后兵，免得把小度抢聋。
     */
    private volatile boolean yielded;
    /** 让路开始 / 下一次探测收回的时刻 */
    private volatile long yieldSince;
    private volatile long yieldWatchAt;
    /** 录音回调通知「占用方已停止」：由控制循环收回麦克风 */
    private volatile boolean reclaimReq;
    private boolean recCbRegistered;
    private volatile long cooldownUntil;
    private volatile long idleSince;
    private volatile long lastUiPush;
    private volatile String lastError;

    /** 「您要说哪个？」之后继续听下一条：候选列表 + 原说法 + 是否在等序号 */
    private volatile boolean awaitingPick;
    /** 候选当前页（0 起）：完整列表不截断，序号是「本页序号 + 页偏移」 */
    private volatile int pickPage;
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
        // 把当前唤醒词交给匹配层：否则「你好小瑞，打开微信」里那半句唤醒词
        // 会被当成应用名的一部分去匹配，永远命中不了（PTT 模式也走这里）
        VoiceIntent.setWakeWord(VoiceWords.wakeWord(ctx));
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
        // 语音整体停掉：立刻把音频焦点还回去，别让背景音乐一直停在那儿
        ui.removeCallbacks(focusRelease);
        abandonAudioFocus();
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
        gotUsefulText = false;
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
            new File(ctx.getFilesDir(), "voice/asr.load").delete();
        } catch (Throwable ignored) {
        }
        // 唤醒词可能刚被改过，同步给匹配层（否则新词在识别结果里剥不掉）
        if (ctx != null) VoiceIntent.setWakeWord(VoiceWords.wakeWord(ctx));
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
            // 每次起控制线程都从"没重试过"开始，别把上一轮的退避带过来
            micTries = 0;
            micRetryGap = MIC_RETRY_MIN_MS;
            micRetryAt = 0;
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

                // 占用方（小度）停止了录音：录音回调会置这个标志。
                // 立刻收回麦克风 —— 回调可能只来一次，错过就得等 watchdog，所以要快。
                if (reclaimReq) {
                    reclaimReq = false;
                    if (yielded) {
                        yielded = false;
                        boolean ok;
                        synchronized (lock) { ok = ensureMic(); }
                        if (ok) {
                            FileLog.append("VOICE 占用方已停止录音 → 收回麦克风，恢复待唤醒");
                            pushState(hint(), null);
                        } else {
                            // 开麦失败（权限/初始化）：退回让路态，走既有退避重试
                            yielded = true;
                            yieldWatchAt = SystemClock.elapsedRealtime() + micRetryGap;
                            FileLog.append("VOICE 收回麦克风失败：" + lastError
                                    + "，" + micRetryGap + "ms 后再试");
                        }
                    }
                }

                // 拿到死寂数据的处理（2.5 秒完全没回调由上面的断流分支处理）。
                // 【并发模型】车机日志 `LSPosed_20261112_080306/full.log` 证明
                // AudioPolicyService 逐次 `+source = 1, allowCapture=1` 放行并发录音：
                // 百度 MapVoiceProcess 与厂商 gocsdk 能同时开麦，小度和小德可同时被唤醒。
                // 所以「别的 app 正在录音」是常态，**绝不能据此停采** —— 那会让我们自己的
                // 唤醒词长时间失效。默认动作只有换 input 路由重开，麦克风全程不关。
                // 仅当「两条路由反复重开都拿不到数据 + 确实查到别的录音方」才判定这台
                // HAL 是单路独占，短暂让路兜底。
                if (yielded && now >= yieldWatchAt) {
                    // 让路态的 watchdog 必须独立在这里：旧版写在 dead>SILENT_RECOVER_MS
                    // 分支里，但 mic==null 时 dead 恒为 0，那段永远进不去，兜底形同虚设。
                    FileLog.append("VOICE 让路已 " + (now - yieldSince) / 1000
                            + "s → 探测性收回麦克风");
                    yielded = false;      // 交给下面的「无麦重试」实际开麦
                    yieldWatchAt = now + YIELD_WATCHDOG_MS;
                }
                // 没麦、也没在让路：watchdog 收回失败 / reclaim 失败 / 别的路径把 mic 丢了，
                // 都要后台退避重试，否则常驻会永久失聪（旧版这里是个死角）。
                if (mic == null && !yielded && now >= micRetryAt
                        && (state == S_WAKE || state == S_LISTEN || state == S_LOADING)) {
                    boolean ok;
                    synchronized (lock) { ok = ensureMic(); }
                    if (!ok) {
                        micRetryAt = now + micRetryGap;
                        micRetryGap = Math.min(micRetryGap * 2, MIC_RETRY_MAX_MS);
                        FileLog.append("VOICE 无麦重试失败：" + lastError
                                + "，" + micRetryGap + "ms 后再试");
                    }
                }
                if ((mic != null || yielded) && now >= micRetryAt
                        && (state == S_WAKE || state == S_LISTEN || state == S_LOADING)) {
                    long dead = mic == null ? 0 : mic.silentMs();
                    if (dead > SILENT_RECOVER_MS) {
                        if (!yielded) {
                            if (micTries >= YIELD_AFTER_RECYCLES) {
                                java.util.List<android.media.AudioRecordingConfiguration> others =
                                        otherRecorders();
                                if (!others.isEmpty()) {
                                    // 两条路由反复重开仍死寂 + 明确有占用者 → 判定真单路独占
                                    yieldMic(describeRecorders(others));
                                    micTries = 0;
                                } else {
                                    recycleMic("连续 " + dead + "ms 死寂，换 input 路由重开");
                                }
                            } else {
                                recycleMic("连续 " + dead + "ms 死寂 → 换 input 路由重开（不让麦，并发常驻）");
                            }
                        }
                        try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
                        continue;
                    } else if (mic != null && (micTries != 0 || yielded)) {
                        micTries = 0;                    // 已经重新听到声音，计数清零
                        micRetryGap = MIC_RETRY_MIN_MS;
                        if (yielded) {
                            yielded = false;             // 让路期间收到了真声音（比如对方只是瞬断）
                            FileLog.append("VOICE 让路中收到信号 → 取消让路，恢复待唤醒");
                        }
                    }
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

                // 播报期间不判超时 / 不收工：我们自己正占着麦说话，这时候收到的文字必然
                // 是自己的回音。让它照常走超时逻辑，会形成「收工 → 又被唤醒 → 再收工」的空转。
                if (state == S_LISTEN && !withinSpeechGuard()) {
                    String cur = asr == null ? "" : asr.text();
                    boolean hasText = cur != null && !cur.trim().isEmpty();
                    // 判据是「字幕多久没变过」，不是「有没有声音」——
                    // 环境噪音大时 RMS 一直超门限，按音量判就永远不会超时；
                    // 而只要识别结果不变（停在同一串字上），也说明人已经说完了。
                    long anchor = hasText ? Math.max(listenAnchor, lastTextAt) : listenAnchor;
                    // 本轮一个有用字都没出现过 → 从 listenSince 起算满 3 秒就退出，
                    // 不受 endpoint 重置 anchor / 噪音乱码续命的影响。
                    boolean noUsefulYet = !gotUsefulText;
                    boolean timedOut = noUsefulYet
                            ? (now - listenSince >= NO_TEXT_TIMEOUT_MS)
                            : (now - anchor >= NO_TEXT_TIMEOUT_MS);
                    if (timedOut) {
                        FileLog.append("VOICE " + (noUsefulYet
                                ? (now - listenSince) + "ms 没有任何有用文字 → 没听清，直接退出"
                                : (now - anchor) + "ms 没有新的有用文字 → 收工")
                                + (hasText ? "（停在「" + cur + "」）" : "（环境噪音？）"));
                        if (noUsefulYet) {
                            // 【需求二.3】没听清就立刻退出这一轮，不再走 finish() 的
                            // 「没听清，再说一遍」+ endUtterance(1200) 延迟收尾链
                            cancelListening();
                            say("没听清");
                            pushState("没听清", null);
                        } else {
                            finish(cur, true);
                        }
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
                ? PICK_WAIT_MS + 4_000L      // 候选多时 TTS 报读更久，别把列表提前收掉
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

    /**
     * 建识别器，并把"本机能启动的应用名"灌进去当热词。
     *
     * 必须在 load() 之前设好 —— sherpa 是在构造 OnlineRecognizer 时读热词表的，
     * 加载完再改不会生效（要 release 重建）。
     */
    private VoiceAsr ensureAsr() {
        if (asr == null) {
            asr = new VoiceAsr(ctx);
            asr.setHotwordNames(appLabels());
        }
        return asr;
    }

    /** 语音能认的全部名字 —— 热词表的数据源（和识别后匹配用的名单是同一份） */
    private List<String> appLabels() {
        Host h = host;
        if (h == null) return null;
        List<AppInfo> as;
        try {
            as = h.apps();
        } catch (Throwable t) {
            FileLog.append("VOICE 取应用列表失败（热词跳过）：" + t);
            return null;
        }
        if (as == null || as.isEmpty()) return null;
        List<String> out = new java.util.ArrayList<>(as.size());
        for (AppInfo a : as) {
            if (a != null && a.label != null && !a.label.isEmpty()) out.add(a.label);
        }
        // 把「用户点选过的说法」也灌进热词表。这些串是 ASR 自己吐过的汉字，
        // 必然都在 tokens.txt 里；作为 cjkchar 热词做解码偏置，等于告诉识别器
        // 「这段发音优先走这条汉字路径」。中文模型听英文每次吐的字都不同，
        // 热词把它往学过的几种写法上拉，输出才会逐渐收敛 ——
        // 这是别名在英文场景下真正起作用的途径，而不是靠精确字符串命中。
        java.util.Map<String, java.util.Set<String>> learned =
                Prefs.parseAlias(Prefs.voiceAliasRaw(ctx));
        for (java.util.Set<String> set : learned.values()) {
            for (String x : set) {
                if (x != null && x.length() >= 2) out.add(x);
            }
        }
        return out;
    }

    /**
     * 别名变了 → 重建识别器，让新的热词表生效。
     *
     * sherpa 是在构造 OnlineRecognizer 时读 hotwords 文件的，加载完再改不生效。
     * 所以这里 release + 置 null，下面的 ensureAsr() 会带着新的 appLabels()
     * 重新 load（beginListen 里本来就有 isReady/load 分支，不用另写加载）。
     */
    private void syncHotwordAliases() {
        String now;
        try {
            now = Prefs.voiceAliasRaw(ctx);
        } catch (Throwable t) {
            return;
        }
        if (now.equals(hotAliasStamp)) return;
        boolean rebuild = hotAliasStamp != null && !hotAliasStamp.isEmpty();
        hotAliasStamp = now;
        if (!rebuild) return;               // 首次启动：正常 load 就已经带上了
        synchronized (lock) {
            VoiceHotwords.invalidate();
            if (asr != null) asr.release();
            asr = null;
        }
        FileLog.append("VOICE 语音别名有更新 → 重建识别器，把新说法写进热词表");
    }

    /**
     * 让路【兜底，常态不走】：只在「两条 input 路由反复重开都拿不到数据，且明确查到
     * 别的录音方」时才调用，说明这台 HAL 真是单路独占。主动停掉我们的采集，
     * 把 input 完整留给对方。注册了录音回调，对方一停 reclaimReq 就会置位，
     * 控制循环在 ~80ms 内收回麦克风；没等到也有 watchdog 探测收回。
     *
     * 车机常态是**并发录音**（百度 MapVoiceProcess / 厂商 gocsdk / 高德可同时开麦），
     * 那时绝不能停采 —— 停了就等于我们自己的唤醒词长时间失效。
     * KWS / ASR 模型都保留着，只是没有 PCM 可喂，收回后立即可用。
     */
    private void yieldMic(String who) {
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (mic != null) {
                try {
                    mic.stop();
                } catch (Throwable ignored) {
                }
                mic = null;
            }
        }
        yielded = true;
        yieldSince = now;
        yieldWatchAt = now + YIELD_WATCHDOG_MS;
        lastPcmAt = now;      // 麦克风没了，别让"2.5s 没 PCM"断流分支乱报
        registerRecCb();
        FileLog.append("VOICE 麦克风让路【真独占兜底】：" + who
                + " 正在录音且两条路由都取不到数据 → 短暂停采，对方结束后自动收回"
                + "（" + YIELD_WATCHDOG_MS / 1000 + "s 后仍未结束则探测收回）");
        pushState("麦克风被 " + who + " 独占，短暂让路后自动恢复", null);
    }

    /**
     * 当前有哪些**别的**应用在录音。
     *
     * 排除自己用的不是 uid —— getClientUid() 是隐藏 API（编译不过）—— 而是
     * AudioRecord 的 session id：我们自己的采集配置里 session 一定对得上，
     * 其余的都算别人。没开麦（让路中）时 -1 对不上任何记录，全部算别人，正合适。
     */
    private java.util.List<android.media.AudioRecordingConfiguration> otherRecorders() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return java.util.Collections.emptyList();
            java.util.List<android.media.AudioRecordingConfiguration> all =
                    am.getActiveRecordingConfigurations();
            if (all == null || all.isEmpty()) return java.util.Collections.emptyList();
            int mySession;
            synchronized (lock) {
                mySession = mic == null ? -1 : mic.audioSessionId();
            }
            java.util.List<android.media.AudioRecordingConfiguration> out = new java.util.ArrayList<>();
            for (android.media.AudioRecordingConfiguration c : all) {
                try {
                    if (c.getClientAudioSessionId() != mySession) out.add(c);
                } catch (Throwable ignored) {
                }
            }
            return out;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /** 把录音方列表翻成人话：「百度地图」/「百度地图 等 2 个」/「其他应用」 */
    private String describeRecorders(java.util.List<android.media.AudioRecordingConfiguration> list) {
        if (list == null || list.isEmpty()) return "其他应用";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            for (android.media.AudioRecordingConfiguration c : list) {
                String pkg = packageNameOf(c);
                if (pkg == null) continue;
                String label = null;
                try {
                    label = (String) pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (Throwable ignored) {
                }
                names.add(label != null ? label : pkg);
            }
        } catch (Throwable ignored) {
        }
        if (names.isEmpty()) return "其他应用";
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String n : names) {
            if (i++ >= 2) {
                b.append(" 等 ").append(names.size()).append(" 个");
                break;
            }
            if (i > 1) b.append('、');
            b.append(n);
        }
        return b.toString();
    }

    /**
     * 录音配置里拿包名。getClientPackageName() 是隐藏 API，公开表里只有 session id ——
     * 用反射试一下（部分 ROM 没挂拦截就能拿到真名），拿不到就返回 null，
     * 让上层显示"其他应用"。反射失败无副作用，纯展示用途。
     */
    private static String packageNameOf(android.media.AudioRecordingConfiguration c) {
        try {
            Object pkg = c.getClass().getMethod("getClientPackageName").invoke(c);
            return pkg instanceof String ? (String) pkg : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 注册录音状态回调：占用方停止录音的瞬间置 reclaimReq，让控制循环收回麦克风。
     * 回调只在让路状态下有作用；注册一次、进程生命周期有效。
     */
    private void registerRecCb() {
        if (recCbRegistered) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(
                        java.util.List<android.media.AudioRecordingConfiguration> configs) {
                    // 不直接信参数（里面混着我们自己的记录），让控制循环重新查一遍
                    if (yielded) reclaimReq = true;
                }
            }, new Handler(Looper.getMainLooper()));
            recCbRegistered = true;
            FileLog.append("VOICE 已注册录音状态回调（占用方结束时可立即收回麦克风）");
        } catch (Throwable t) {
            // 没有回调也能活：控制循环的 watchdog 15 秒兜底
            FileLog.append("VOICE 注册录音回调失败（退化为 15s watchdog）：" + t);
        }
    }

    /**
     * 重开麦克风。车机上「小度小度」唤醒后，百度地图的语音占着 input，我们这边
     * read 照常返回但内容全是死寂 —— KWS 永不命中、ASR 一个字也出不来，
     * 用户看到的就是「你好小瑞再也唤不醒、点语音图标也收不到声音」。
     *
     * 这种状态**等不回来**：必须 release 再重新 open，让 AudioFlinger 重新选路、
     * 重新分配 input。这是那条路里唯一能自己恢复的动作。
     *
     * 换 source 重试：MIC 与 VOICE_RECOGNITION 在 audio policy 里是两条不同的
     * input 路由，一条拿不到数据时另一条常常还能开。默认音源已是 MIC，
     * 所以每次重开交替切到另一条，保证每轮都真的换了路由。
     *
     * @return 是否成功重新开起来
     */
    private boolean recycleMic(String why) {
        micTries++;
        int src = ((micTries % 2) == 1)
                ? android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
                : android.media.MediaRecorder.AudioSource.MIC;
        boolean ok;
        synchronized (lock) {
            if (mic != null) {
                try {
                    mic.stop();
                } catch (Throwable ignored) {
                }
                mic = null;
            }
            VoiceMic nm = new VoiceMic(this::onPcm);
            nm.setSource(src);
            ok = nm.start();
            if (ok) {
                mic = nm;
                long now = SystemClock.elapsedRealtime();
                lastPcmAt = now;      // 别让"2.5 秒没 PCM"把这次重开误判成断流
                idleSince = now;
                micRetryGap = MIC_RETRY_MIN_MS;
            } else {
                lastError = nm.error();
                micRetryGap = Math.min(micRetryGap * 2, MIC_RETRY_MAX_MS);
            }
        }
        micRetryAt = SystemClock.elapsedRealtime() + micRetryGap;
        if (ok) {
            FileLog.append("VOICE 重开麦克风成功（" + why + "）→ "
                    + VoiceMic.sourceName(src) + "，第 " + micTries + " 次");
        } else {
            FileLog.append("VOICE 重开麦克风失败（" + why + "）→ " + lastError
                    + "，下次 " + micRetryGap + "ms 后再试");
        }
        return ok;
    }

    /** 开始一次聆听：先把模型和麦克风准备好，再切状态（否则会漏掉开头） */
    private void beginListen() {
        // 一进来就压住背景音乐：用户马上要说话，音乐还在响会同时干扰识别和播报。
        // 拿到短暂独占焦点后，系统会让正在放歌的 app 暂停；收工时再放回去。
        requestAudioFocus();
        // 一进来就计时：准备阶段（加载模型/开麦）也要算在硬闸里，
        // 否则 S_LOADING 用的是上一轮的时间戳，或者干脆没人管
        listenSince = SystemClock.elapsedRealtime();
        echoHits = 0;
        gotUsefulText = false;
        state = S_LOADING;
        pushState("准备聆听…", null);
        partial = "";

        // 上一轮收工时麦克风可能正被别人占着（读到的是死寂数据）。不先处理，
        // 这一轮从头到尾都是白听 —— 用户看到的就是"点了语音图标却没反应"。
        // 用户主动要点语音：结束让路态，全力以赴开麦。
        // 【P0 修复】yieldMic() 会把 mic.stop() 并置 null；而下面的 wakeMode 分支
        // 原本没有 ensureMic()（只有非常驻 PTT 分支有），旧版这里只清 yielded 标志、
        // 不重新开麦 → 常驻下让过一次路之后，唤醒/点语音助手整轮零 PCM，
        // 用户看到的就是「常驻唤醒词不让权限、一直听不到声音」。
        yielded = false;
        // 上一轮点候选学到的说法要在这轮开始前写进热词表（会重建识别器）
        syncHotwordAliases();
        synchronized (lock) {
            if (!ensureMic()) {
                state = wakeMode ? S_WAKE : S_OFF;
                pushState("麦克风不可用：" + lastError, null);
                return;
            }
        }
        if (mic != null && mic.silentMs() > 600) {
            // 别的应用在录音是车机常态（AudioPolicyService allowCapture=1 允许并发），
            // **不能因为查到别人就放弃本轮聆听** —— 旧版会显示「麦克风被 X 占用，等它
            // 结束后再试」然后 return，等于常驻唤醒被别人一句话挡在门外。
            // 只处理「我们自己拿不到数据」：换一条 input 路由重开，然后照常往下走。
            // 这里刻意不再用 silentMs() 二次判定：新 AudioRecord 启动时 silentSince 初值
            // 就是「当前静音」，车内安静时 300ms 后 silentMs 必然 >250，会把正常聆听误杀。
            // 真没数据由控制循环的「2.5 秒无 PCM」断流分支兜底。
            recycleMic("聆听前麦克风已 " + mic.silentMs() + "ms 无信号 → 换 input 路由重开");
        }

        if (!wakeMode) {
            // PTT：先加载模型（1~2s，界面上能看到"准备中"），再开麦
            synchronized (lock) {
                ensureAsr();
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
                ensureAsr();
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
        // 用 canSpeakNow 而不是 !isBroken：内置引擎首次要解压 129MB 模型，
        // 没就绪时别挂一句延迟的「我在」，直接退回提示音更干净
        boolean spoken = Prefs.voiceReply(ctx) && tts.canSpeakNow();
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
        // 【需求二.3】3 秒从「开始聆听」起算，不再等 TTS「我在」念完才计时。
        // 回采的「我在」由 usefulText() 判为无用字，既不会算成指令，也不会把超时续掉。
        listenAnchor = now;
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
            // 【播报期物理隔离】只要我们自己还在出声（或刚说完），这一帧就直接不送 ASR。
            //
            // 以前是"先让 ASR 听、识别完再按文本比对过滤"——这条路必然漏：
            //   · ASR 把播报识别成别的字 → 相似度不够，绕过去；
            //   · 时间窗过期了才吐出字 → 守卫已经失效；
            //   · 端点在播报中间切一刀 → 前半段被当指令，后半段又当一次指令。
            // 漏一次就回一句"没找到…"，这句再被听回去 → 用户看到的自激循环。
            // 不喂它，ASR 里就**根本不存在**这段音频，不需要任何事后判断。
            if (withinSpeechGuard()) {
                if (!speechGuardHeld) {
                    speechGuardHeld = true;
                    // 每轮播报只打一条：现场看日志就能确认"屏蔽真的生效了"，
                    // 不用靠耳朵判断，也不会把音频线程刷爆。
                    FileLog.append("VOICE 播报期屏蔽麦克风（本帧起不喂 ASR/KWS）");
                }
                partial = "";
                return;
            }
            if (speechGuardHeld) {
                // 守卫刚解除：丢掉播报前残留的流式上下文，从干净状态开始听
                speechGuardHeld = false;
                partial = "";
                synchronized (lock) {
                    if (asr != null) asr.begin();
                }
            }
            String t;
            synchronized (lock) {
                if (asr == null) return;
                t = asr.accept(buf, n);
            }
            String prev = partial == null ? "" : partial;
            partial = t == null ? "" : t;
            // 用户实测：喊「你好小瑞」唤醒后，唤醒词的尾巴（小瑞，或 ASR 听成的小雷）
            // 会被识别进字幕显示出来。虽然 parse() 里剥得掉、执行没错，但看着脏，
            // 而且这几个字会被当成「有用文字」把 3 秒超时一直续掉。
            // 显示和判据都用清洗后的文本，识别原文仍留在 partial 里供排障。
            String shown = VoiceIntent.cleanWake(partial);
            String prevShown = VoiceIntent.cleanWake(prev);
            long now = SystemClock.elapsedRealtime();
            // **只有字幕真的变了、且是有用的文字**才重置超时计时。
            // 踩过的坑：原来是「只要字幕非空就续命」，噪音偶尔吐一个字就把计时器
            // 每帧刷新，3 秒超时永远不成立；TTS「我在」的自我回采也会续命。
            if (!shown.isEmpty() && !shown.equals(prevShown) && usefulText(shown)) {
                lastTextAt = now;
                gotUsefulText = true;
            }
            if (now - lastUiPush > 260) {          // 字幕别推太密，省 UI 线程
                lastUiPush = now;
                pushState("请说指令", shown);
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
            // 播报期同样不喂唤醒词识别。
            // 这是最隐蔽的一条自激回路：TTS 说「我在 / 要选第几个」→ 被 KWS 听成唤醒词
            // → 又唤醒 → 又说「我在」→ 再被听回去……用户看到的就是"自己跟自己说话停不下来"。
            // 唤醒词模型对短句尤其敏感，光靠 ASR 那层过滤根本挡不住。
            if (withinSpeechGuard()) return;
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
        // 只保留最后一段：唤醒词本身没必要送进 ASR，但要留够"下一个字的开头"，
        // 否则用户唤醒后立刻说「打开音乐」，「打」字会被切掉 → 识别成「开音乐」→ 匹配不上。
        // 300ms 实测不够（车机上「打」被吃掉是高频错法），放到 500ms；
        // 多带出来的唤醒词尾巴由 VoiceIntent.cleanWake() 剥掉。
        int keep = VoiceMic.SR * 500 / 1000;
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
        // 剥掉唤醒词尾巴（含同音错字）后的文本，专供显示 / 序号选择 / 回采判定。
        // 匹配仍传原文 t —— VoiceIntent.parse() 内部也走 cleanWake，且日志能保留原始识别串。
        final String shown = VoiceIntent.cleanWake(t);
        FileLog.append("VOICE 识别\"" + t + "\""
                + (shown.equals(t) ? "" : " 清洗后=\"" + shown + "\"")
                + (hardTimeout ? "（超时收工）" : ""));

        // 【播报期硬抑制】只要我们自己正在出声（或刚说完），麦克风收到的内容一律不算指令。
        //
        // 为什么不能只靠「文本相似度」：ASR 会把播报识别成别的字（「要选第几个」→「要学第几个」），
        // 比对失效 → 当成指令 → 匹配不到 → 回一句 → 这句又被听回去 → 无限循环停不下来。
        // 所以这里不看文本，只看「我们是不是在发声」，这是唯一可靠的判据。
        if (withinSpeechGuard()) {
            FileLog.append("VOICE 播报期抑制「" + t + "」（我们在出声，不算指令）");
            state = S_LISTEN;
            synchronized (lock) {
                if (asr != null) asr.begin();
            }
            long nowG = SystemClock.elapsedRealtime();
            deadline = nowG + LISTEN_MAX_MS;
            listenAnchor = nowG + 300;
            lastTextAt = listenAnchor;
            pushState("请说指令", "");
            return;
        }

        // 兜底：引擎没报状态、但确实是刚播报过的残片（ASR 延迟吐出）
        if (isSayEcho(shown)) {
            FileLog.append("VOICE 忽略播报残片「" + t + "」");
            state = S_LISTEN;
            synchronized (lock) {
                if (asr != null) asr.begin();
            }
            long nowSay = SystemClock.elapsedRealtime();
            deadline = nowSay + LISTEN_MAX_MS;
            listenAnchor = nowSay + 300;
            lastTextAt = listenAnchor;
            pushState("请说指令", "");
            return;
        }

        // 自我回采：开始聆听时 TTS 说的「我在」可能被麦克风录进去；
        // 唤醒词尾巴（小瑞 / 小雷）同理。都不是指令 —— 重置识别器继续听。
        // 注意别把「一个字都没识别到」也当回采：那种情况应走下面的「没听清」。
        if ((isEcho(t) || (!t.isEmpty() && shown.isEmpty())) && echoHits < MAX_ECHO_HITS) {
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
            // 候选里没有想要的：说一句「都不是 / 不对 / 取消」就能收掉，
            // 不必等 3 秒超时，也不用手去点「都不是」。
            if (VoiceIntent.isPickCancel(shown)) {
                awaitingPick = false;
                pickCands = null;
                beep(false);
                say("好的，取消");
                pushState("已取消", shown);
                ui.post(() -> {
                    Host hh = host;
                    if (hh != null) hh.onVoiceCandidates(pickPhrase, null);
                });
                endUtterance(600);
                return;
            }
            // 候选可能几十个：说「下一页 / 上一页」翻页，列表和序号一起换一批
            int page = VoiceIntent.parsePage(shown);
            if (page != 0) {
                flipPickPage(page);
                return;                       // announcePickPage 已重置计时并继续听
            }

            int pageCount = Math.min(PICK_PAGE_SIZE,
                    pickCands.size() - pickPage * PICK_PAGE_SIZE);
            int idx = VoiceIntent.parsePick(shown, pageCount);
            if (idx >= 0) {
                int abs = pickPage * PICK_PAGE_SIZE + idx;
                if (abs >= 0 && abs < pickCands.size()) {
                    AppInfo pick = pickCands.get(abs);
                    FileLog.append("VOICE 候选序号选第 " + (abs + 1) + " 个 → " + pick.label);
                    commitPick(pick, shown);
                    return;
                }
            } else {
                // 不报序号、直接把名字说出来：在**完整候选**里再匹配一次，
                // 这样排在后面的 app 不用一直翻页也能选中。
                int byName = VoiceIntent.pickByText(shown, pickCands, aliasMap());
                if (byName >= 0) {
                    AppInfo pick = pickCands.get(byName);
                    FileLog.append("VOICE 候选说名字选中 → " + pick.label);
                    commitPick(pick, shown);
                    return;
                }
            }
            awaitingPick = false;
            pickCands = null;
        }

        state = S_THINK;

        Host h = host;
        List<AppInfo> apps = h == null ? null : h.apps();
        final VoiceIntent.Result r = VoiceIntent.parse(t, apps, aliasMap());
        FileLog.append("VOICE 判定 " + VoiceIntent.describe(r) + "（说法=\"" + r.text + "\"）");

        if (r.kind == VoiceIntent.APP || r.kind == VoiceIntent.CMD) {
            final String pkg = r.pkg;
            final String label = r.label;
            beep(false);
            say(r.kind == VoiceIntent.APP ? "好的，打开" + label : "好的");
            pushState("→ " + label, shown);
            ui.post(() -> {
                Host hh = host;
                if (hh != null) hh.onVoiceAction(pkg);
            });
            endUtterance(700);
        } else if (r.kind == VoiceIntent.AMBIGUOUS && r.candidates.size() == 1
                && r.score >= 60) {
            // 只有一个候选、分数也够：没有"第几个"可选，问了也是白问（用户明确要求
            // 「有多个备选时」才提示选第几个）。直接打开，别让用户为一个没有分支的
            // 选择再操作一次。分数低于 60 的仍走候选，让用户确认，避免误开。
            AppInfo only = r.candidates.get(0);
            beep(false);
            say("好的，打开" + only.label);
            pushState("→ " + only.label, shown);
            final String op = only.pkg;
            ui.post(() -> {
                Host hh = host;
                if (hh != null) hh.onVoiceAction(op);
            });
            endUtterance(700);
        } else if (r.kind == VoiceIntent.AMBIGUOUS && !r.candidates.isEmpty()) {
            // 报候选的同时**继续听**：说序号、直接说名字、或点列表都行。
            // 完整列表留在 pickCands；announcePickPage 只把当前页（9 条）交给 UI 和 TTS。
            awaitingPick = true;
            pickCands = new java.util.ArrayList<>(r.candidates);
            pickPhrase = r.text;
            pickPage = 0;
            announcePickPage();
        } else {
            // 找不到就**直说找不到**，不弹候选、不列全部应用（用户明确要求）。
            // 旧行为：匹配不到时把所有英文名应用塞进候选页，用户看到的是一屏
            // 跟自己说法毫无关系的应用，还得逐页翻 —— 比一句"找不到"更差。
            String miss = shown.isEmpty() ? "没听清，再说一遍" : "找不到" + shown + "这个应用";
            say(miss);
            pushState(shown.isEmpty() ? "没听清" : "找不到「" + shown + "」", shown);
            endUtterance(1200);
        }
    }

    /** 内置别名 + 用户点选学到的别名，合并成一份给匹配层用 */
    private Map<String, Set<String>> aliasMap() {
        Host h = host;
        List<AppInfo> apps = h == null ? null : h.apps();
        Map<String, Set<String>> alias = VoiceIntent.builtinAliases(apps);
        Map<String, Set<String>> user = Prefs.parseAlias(Prefs.voiceAliasRaw(ctx));
        for (Map.Entry<String, Set<String>> e : user.entrySet()) {
            Set<String> set = alias.get(e.getKey());
            if (set == null) alias.put(e.getKey(), e.getValue());
            else set.addAll(e.getValue());
        }
        return alias;
    }

    /** 候选最终选中：收尾、记别名、打开应用（序号选 / 说名字选 / 点击都走这里） */
    private void commitPick(AppInfo pick, String shownText) {
        awaitingPick = false;
        pickCands = null;
        beep(false);
        say("好的，打开" + pick.label);
        pushState("→ " + pick.label, shownText);
        ui.post(() -> {
            Host hh = host;
            if (hh != null) {
                hh.onVoiceCandidatesPicked(pickPhrase, pick);
                hh.onVoiceAction(pick.pkg);
            }
        });
        endUtterance(700);
    }

    /**
     * 报读当前页候选 + 弹出当前页菜单 + 重置这一轮的聆听计时。
     *
     * 完整列表留在 pickCands（可能有几十个英文名应用），UI 和语音只看当前页 9 条；
     * 说「下一页」翻页，或直接说出名字在完整列表里搜 —— 数量不再有上限。
     */
    private void announcePickPage() {
        if (pickCands == null || pickCands.isEmpty()) return;
        int total = pickCands.size();
        int pages = (total + PICK_PAGE_SIZE - 1) / PICK_PAGE_SIZE;
        if (pickPage < 0) pickPage = 0;
        if (pickPage >= pages) pickPage = pages - 1;
        int from = pickPage * PICK_PAGE_SIZE;
        int to = Math.min(total, from + PICK_PAGE_SIZE);
        final List<AppInfo> page = new java.util.ArrayList<>(pickCands.subList(from, to));
        final String phrase = pickPhrase;

        // 只问序号，**不逐个念应用名**：念 5 个名字要十几秒，用户得干等着，
        // 还容易被硬闸收掉；名字在屏幕上的候选菜单里看得见，念出来是纯浪费。
        // 也**不报总数/页数**（用户明确要求）：一句短问就够，页数写在屏幕提示里。
        say("要选第几个？");
        pushState(pages > 1
                ? "第 " + (pickPage + 1) + "/" + pages + " 页 · 说序号 / 说名字，或直接点"
                : "说序号 / 说名字，或直接点", "");
        ui.post(() -> {
            Host hh = host;
            if (hh != null) hh.onVoiceCandidates(phrase, page);
        });
        // 重新进入聆听态（不结束这一轮）
        state = S_LISTEN;
        partial = "";
        synchronized (lock) {
            if (asr != null) asr.begin();
        }
        long now = SystemClock.elapsedRealtime();
        deadline = now + PICK_WAIT_MS;
        // 提示语要念几秒，这段时间收的是自己的 TTS，不算进「3 秒没听清」
        // 播报文本固定是「要选第几个？」(6 字)，估算≈3000ms；播报期本身也已物理隔离。
        listenAnchor = now + 3000L;
        lastTextAt = listenAnchor;
        listenSince = now;
    }

    /** 翻页：语音说「下一页」，或点菜单里的「下一页」 */
    void flipPickPage(int delta) {
        if (!awaitingPick || pickCands == null || pickCands.isEmpty()) return;
        int pages = (pickCands.size() + PICK_PAGE_SIZE - 1) / PICK_PAGE_SIZE;
        if (pages <= 1) {
            say("没有更多了");
            return;
        }
        int next = pickPage + delta;
        if (next < 0 || next >= pages) {
            say(delta > 0 ? "已经是最后一页了" : "已经是第一页了");
            return;
        }
        pickPage = next;
        FileLog.append("VOICE 候选翻到第 " + (next + 1) + "/" + pages + " 页");
        announcePickPage();      // 会重建菜单（Host 侧先 dismiss 旧的那张）
    }

    /** 系统引擎回一句话（设置里可关；引擎不存在时静默） */
    private void say(String s) {
        if (!Prefs.voiceReply(ctx)) return;
        // 记下这一句：ASR 把它录回来时要靠它认出「这是我自己说的」
        lastSayText = s;
        lastSayAt = SystemClock.elapsedRealtime();
        // 播报期间压住背景音乐，并把「释放焦点」重排到这句念完之后
        requestAudioFocus();
        releaseFocusWhenSpoken();
        tts.ensure(ctx, Prefs.voiceTtsMode(ctx), Prefs.voiceTtsEngine(ctx));
        tts.speak(s);
    }

    /** 设置里换 TTS 引擎 / 点试听后调用：按新引擎重建 */
    public void onTtsEngineChanged() {
        tts.reset();
    }

    /** 设置页预热内置离线引擎：后台提前加载模型，点试播 / 首次说话不再等加载 */
    public void preheatLocalTts(Context ctx) {
        tts.ensure(ctx, Prefs.TTS_LOCAL, "");
    }

    /** 设置里的「试听」：用当前引擎说一句，用户能立刻听到音色效果 */
    public void previewReply() {
        if (!Prefs.voiceReply(ctx)) return;
        tts.ensure(ctx, Prefs.voiceTtsMode(ctx), Prefs.voiceTtsEngine(ctx));
        tts.speak("您好，我是你的语音助理。");
    }

    // ------------------------------------------------------------ 音频焦点（压住背景音乐）

    /**
     * 申请一次短暂独占焦点：正在放歌的 app 会被系统要求暂停。
     *
     * 为什么要它：唤醒后用户要说话、我们要播报，音乐还在响就全糊在一起。
     * 用「短暂独占」而不是自己去发暂停键：焦点是系统契约，abandon 之后播放器
     * 会自己接着放，不会误伤别的 app，也不用管对方是什么播放器。
     *
     * 失败或对方不响应都不影响语音主流程，只记一行日志。
     */
    private void requestAudioFocus() {
        if (ctx == null) return;
        try {
            if (focusHeld) return;
            if (am == null) am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (focusReq == null) {
                android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                focusReq = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        // 明确要"暂停"而不是"把音量压低一点"
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(
                                new AudioManager.OnAudioFocusChangeListener() {
                                    @Override
                                    public void onAudioFocusChange(int change) {
                                        // 我们不抢回来：语音是短会话，让系统按自己的规则调度
                                    }
                                })
                        .build();
            }
            int r = am.requestAudioFocus(focusReq);
            focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            FileLog.append("VOICE 音频焦点" + (focusHeld
                    ? "已获得 → 背景音乐暂停"
                    : "被拒（音乐不会暂停，可能是车机不响应焦点）"));
        } catch (Throwable t) {
            FileLog.append("VOICE 请求音频焦点失败（不影响语音）：" + t);
        }
    }

    /** 放掉焦点：背景音乐自己恢复。多次调用无害 */
    private void abandonAudioFocus() {
        try {
            if (am == null || focusReq == null || !focusHeld) return;
            am.abandonAudioFocusRequest(focusReq);
            focusHeld = false;
            FileLog.append("VOICE 释放音频焦点 → 背景音乐恢复");
        } catch (Throwable t) {
            focusHeld = false;
        }
    }

    /**
     * 等「这一句播报」念完再放焦点。
     *
     * 不这么做就会出现：卡片关了、焦点立刻放开、音乐起来，把我们正在念的最后半句盖掉。
     * 用 removeCallbacks 保证同一时刻只有一个待执行的释放任务，不会重复排。
     */
    private void releaseFocusWhenSpoken() {
        if (!focusHeld) return;
        try {
            long now = SystemClock.elapsedRealtime();
            String said = lastSayText;
            long endAt = lastSayAt + 900L + 260L * (said == null ? 0 : said.length());
            long delay = Math.max(0L, endAt - now) + 250L;   // 留一点尾音余量
            if (delay > 8000L) delay = 8000L;                // 兜底：绝不永远不放
            ui.removeCallbacks(focusRelease);
            ui.postDelayed(focusRelease, delay);
        } catch (Throwable t) {
            abandonAudioFocus();                             // 算不出来就立刻放，别赖着
        }
    }

    /** 释放任务的句柄，供 removeCallbacks 去重 */
    private final Runnable focusRelease = new Runnable() {
        @Override
        public void run() {
            abandonAudioFocus();
        }
    };

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

    /**
     * 是否处于「我们自己正在出声」的屏蔽期 —— 播报期收到的识别结果一律丢弃。
     *
     * 判据两条：
     *  1) 引擎报告正在出声（内置引擎看 AudioTrack 生命周期，系统引擎看
     *     UtteranceProgressListener），且距最后一次 say 没超太久 ——
     *     加超时是防止回调丢失把状态卡成"永久屏蔽"，那样用户说什么都没反应；
     *  2) 刚说完不久（1.2 秒内）：盖住 TTS 尾音和 ASR 的延迟吐字。
     */
    private boolean withinSpeechGuard() {
        if (lastSayAt == 0L) return false;   // 本轮还没说过话（也避免开机早期把 elapsed 误算成「刚说完」）
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastSayAt;
        String said = lastSayText;
        long est = 900L + 260L * (said == null ? 0 : said.length());
        // 引擎自报"正在出声"最准，但它可能不回调（车机系统 TTS / 内置引擎的播放结束
        // 回调都有丢失的现场）。所以**不能只信它**：估算时长本身就是硬下限 ——
        // 以前这里回落到固定 1200ms，句子念 3 秒时后面 1.8 秒的播报照样被录进 ASR，
        // 这就是"改了还是自说自听"的直接原因。
        try {
            if (tts.isSpeaking() && elapsed < est + 2500L) return true;
        } catch (Throwable ignored) { }
        return elapsed < Math.max(1200L, est);
    }

    /**
     * 自己刚播报出去的那句话，被麦克风录回来了吗？
     *
     * 场景：播报「要选第几个？」→ 这句被自己的麦克风收进去 → ASR 识别成「要选第几个」
     * → 以前会被当成用户指令，去匹配应用名，最后回一句「没找到「要选第几个」」。
     *
     * 两道门槛防止把真实指令一起吞掉：
     *  · 长度门槛：识别结果比播报文本短太多就不算（「打开音乐」不会被
     *    「好的，打开波点音乐」判成回采）；
     *  · 覆盖率门槛：识别结果的汉字要有 75% 出现在播报文本里
     *    （用户答「第一个」对「要选第几个」只覆盖 2/3 = 66%，不会被误吞）。
     */
    private boolean isSayEcho(String t) {
        String said = lastSayText;
        if (said == null || said.isEmpty() || t == null || t.isEmpty()) return false;
        long dt = SystemClock.elapsedRealtime() - lastSayAt;
        // 播报估算时长 + 600ms 缓冲：超过这个时间就不是「刚说完」了
        if (dt > 1200L + 260L * said.length() + 600L) return false;

        String a = VoiceIntent.normalize(t);
        String b = VoiceIntent.normalize(said);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.length() * 10 < b.length() * 6) return false;      // 长度差太多，不是同一句
        if (b.contains(a) || a.contains(b)) return true;

        int hit = 0;
        for (int i = 0; i < a.length(); i++) {
            if (b.indexOf(a.charAt(i)) >= 0) hit++;
        }
        return hit * 100 >= a.length() * 75;
    }

    /**
     * 「有用的文字」：非空、不是语气词、不是 TTS「我在」这类自我回采。
     * 3 秒没听清的判据用它，避免噪音乱码 / 自己的提示音把超时一直续掉。
     */
    private static boolean usefulText(String t) {
        if (t == null) return false;
        // 整句只有唤醒词尾巴（小瑞 / 小雷 / 我在）不算有用文字：
        // 否则它会把「3 秒没听清直接退出」一直续掉，卡片挂着不收。
        String n = VoiceIntent.cleanWake(t);
        return !n.isEmpty() && !isEcho(t);
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
            // 这一轮彻底收工：等最后一句播报念完再把音频焦点放回去，音乐才会接着放。
            // 放在 state 切换之后，保证「播报完毕 + 卡片/状态收掉」两个条件都满足才恢复。
            releaseFocusWhenSpoken();
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
        // 用户收工 = 立刻闭嘴：别把「要选第几个？」念完，更别继续念后面的
        tts.stop();
        // 播报已停，按「最后一句的估算时长」放焦点（若随后还要说「没听清」，say 会重排）
        releaseFocusWhenSpoken();
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
