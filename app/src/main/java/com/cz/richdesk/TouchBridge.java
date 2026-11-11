package com.cz.richdesk;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 把画中画面板上的触摸转发进虚拟屏。设计对齐 scrcpy 的注入模式，并按工程规范加固：
 *
 *  1) 时间轴：每帧 eventTime = 注入时刻，downTime = 手势 DOWN 的注入时刻。
 *     宿主事件时间戳不采信（经过 View 分发 + 队列，到达注入点往往已"过期"，
 *     InputDispatcher 会直接丢弃 eventTime 落后的帧）。
 *  2) deviceId 固定 0（scrcpy DEFAULT_DEVICE_ID），source = TOUCHSCREEN。
 *  3) 注入一律 ASYNC；关键帧失败立即同步重试一次。虚拟屏上 WAIT_FOR_RESULT
 *     会等不到完成反而丢帧，禁用。
 *  4) 手势状态机（单线程）：按指针集合跟踪；POINTER_DOWN/POINTER_UP 正确进出集合。
 *     新 DOWN 到来时集合非空 → 先补一帧 CANCEL（多指针）再开新手势。
 *     MOVE/UP 没有 DOWN 在先 → 丢弃（无源事件会被拒收并污染后续手势）。
 *  5) **通道策略（最高优先级）**：手势在 DOWN 时选定一条通道，整段手势只用它；
 *     中途失败 → 补发 CANCEL、终止手势、丢弃剩余帧，**禁止降级到 cmd**。
 *  6) 历史采样点全量转发（管道协议携带），快速滑动轨迹不跳格。
 *  7) 管道协议带包头（魔数 + 包长），根治粘包/半包。
 *  8) injector 用有界队列（128），过载丢最旧帧（状态机保证丢帧不破坏手势一致性）。
 */
public class TouchBridge {

    private static final String TAG = "PipDesk";
    private static final String SERVER_CLASS = "com.cz.richdesk.InputServer";
    private static final int DEVICE_ID = 0;           // scrcpy DEFAULT_DEVICE_ID
    /** 管道包头魔数 "PDPK" */
    static final int PIPE_MAGIC = 0x5044504B;
    private static final int MAX_POINTERS = 10;
    private static final int MAX_HISTORY = 10;
    private static final int QUEUE_CAPACITY = 128;

    private static TouchBridge sInstance;

    /**
     * 把【VD → View】方向的矩阵（例如 textureView.getTransform(null)）
     * 转成 send() 需要的【View → VD】方向：内部做 invert。
     */
    public static android.graphics.Matrix inverted(android.graphics.Matrix vdToView) {
        android.graphics.Matrix m = new android.graphics.Matrix(vdToView);
        m.invert(m);
        return m;
    }

    public static synchronized TouchBridge get(Context c) {
        if (sInstance == null) sInstance = new TouchBridge(c.getApplicationContext());
        return sInstance;
    }

    /** 手势选定的注入通道 */
    private enum Channel { INPROC, PIPE, CMD }

    private final Context app;
    /** 注入专用线程：有界队列，过载丢最旧帧；单线程串行保证 DOWN/MOVE/UP 顺序 */
    private final ThreadPoolExecutor injector = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "pipdesk-touch");
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            },
            new KeepCriticalPolicy());
    private final Executor bg = new Executor();

    // 通道 1：进程内反射注入
    private static Method mSetDisplayId, mInject;
    private static Object inputManager;
    private Boolean inProcOk;                 // null = 未探测

    // 通道 2：常驻注入服务
    private volatile Process server;
    private volatile DataOutputStream pipe;
    private volatile boolean starting;
    private long lastStartTry;
    /**
     * 管道写入开始的时刻（0 = 空闲）。
     *
     * 为什么要有它：`pipe.flush()` 是阻塞写。一旦对端进程被系统冻住 / 管道写满，
     * 注入线程会**永久**卡在 write 上 —— 而注入线程是所有画中画共用的，
     * 结果就是三块屏的触摸一起"没反应"（用户看到的就是应用卡死）。
     * 看门狗发现写超过 3 秒没回来，就把管道关掉：写入方会收到 IOException，
     * 手势按失败收尾，下一次按下重新建通道。
     */
    private volatile long pipeWriteAt;

    // ---- 手势状态机（只在 injector 线程读写）----
    /** 本段手势仍按着的指针：id → 最近坐标 {x, y}（补 CANCEL 用） */
    private final Map<Integer, float[]> activePointers = new LinkedHashMap<>();
    private int gestureDisplay = -1;
    private long gestureDown;                 // 本段手势 downTime（注入时刻锚点）
    private Channel gestureChannel;
    private boolean gestureInHistory;         // 本手势是否带历史采样（DOWN 时记录）

    // ---- 点击稳定化（tap-hold）----
    // 背景：触摸屏对快速点按普遍报出上百 px 的"漂移轨迹"（实测 DOWN→UP 位移
    // 100~470px，多次贴着显示边界），应用把这种手势判定成滚动/滑动——表现就是
    // "有按压效果（波纹）但点击没反应"。策略：DOWN 立即注入（保住按压反馈），
    // MOVE 先扣在本地；位移超过阈值或扣住超时，判定为拖动并整批放行；UP 时
    // 还没判定为拖动 → 按纯点击补发（UP 落在 DOWN 坐标，丢弃漂移轨迹）。
    private static final float HOLD_SLOP_DP = 30f;  // 位移超过它 = 拖动意图
    private static final long HOLD_MS = 400;        // 扣住超过此时长按拖动处理
    /**
     * 焦点稳定等待（DOWN 注入前）。
     *
     * 实测（12:01 连续 8 次点击，8/8 可复现）：手指按下宿主桌面后 9~21ms，
     * MIUI 会把焦点从虚拟屏应用抢到桌面窗口，应用侧收到 onWindowFocusChanged(false)
     * 并**取消当前触摸流** —— 表现就是"波纹出现了但点击没反应"（UP 变成孤儿帧）。
     * 抖动总是发生在我们注入 DOWN 之后 20ms 内，所以 DOWN 推迟到抖动之后再注入
     * 就能整段避开。只在新手势开头等，连续手势（拖动/长按）不受影响。
     */
    private static final long FOCUS_SETTLE_MS = 45;
    private static final long NEW_GESTURE_GAP_MS = 250;
    private long lastGestureEnd;
    private final java.util.ArrayList<Snap> held = new java.util.ArrayList<>();
    private float holdSlopPx;
    private float downX, downY;               // DOWN 的注入坐标
    private float maxDrift;                   // 本手势已见最大位移
    private int heldMoves;
    private boolean streaming;                // true = 已判定拖动，MOVE 实时透传
    /** cmd 兜底通道 MOVE 限频锚点（只在注入线程读写） */
    private long lastCmdMoveAt;

    private TouchBridge(Context c) {
        app = c;
        holdSlopPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, HOLD_SLOP_DP,
                c.getResources().getDisplayMetrics());
        startPipeWatch();
    }

    /** 注入队列里还压着多少帧（0 = 跟得上；长期 >0 说明注入通道在拖） */
    public static int pendingFrames() {
        TouchBridge b = sInstance;
        if (b == null) return 0;
        try {
            return b.injector.getQueue().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 管道写卡死看门狗：见 {@link #pipeWriteAt} 的说明 */
    private void startPipeWatch() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                long at = pipeWriteAt;
                if (at == 0) continue;
                long waited = SystemClock.uptimeMillis() - at;
                if (waited < 3000L) continue;
                Log.w(TAG, "注入管道写入卡住 " + waited + "ms，强制断开重建（触摸会自愈）");
                closeServer();      // 关流 → 阻塞的 write 抛 IOException → 手势失败收尾
            }
        }, "pipdesk-pipe-watch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** 预热注入服务，避免第一次点击时还要等它启动 */
    public void prime() {
        bg.run(this::ensureServer);
    }

    public boolean alive() {
        return pipe != null || Boolean.TRUE.equals(inProcOk);
    }

    /**
     * 中断当前手势：给屏内应用补一帧 CANCEL，让它放弃这次按压。
     *
     * 用在「长按画中画弹宿主菜单」的场景：用户要的是长按只弹菜单，
     * 不要把这一下当成点击/长按传给屏里的应用。DOWN 早就发出去了（为了让点击够快），
     * 所以这里补一个 CANCEL 把那条手势线掐断 —— 应用侧不会走到 click。
     *
     * 注意：调用方必须**同时停止后续帧的转发**，否则应用会收到孤立的 MOVE/UP。
     */
    public void cancelCurrent() {
        try {
            injector.execute(new Frame(this::endGestureWithCancel, true));
        } catch (Throwable t) {
            Log.w(TAG, "中断手势失败", t);
        }
    }

    // ------------------------------------------------------------ 入口

    /**
     * 简单轴向缩放（sx/sy 相当于一个对角仿射矩阵）。
     * **只适合面板铺满虚拟屏、无黑边、无旋转的场景**；居中黑边 / 旋转等复杂
     * 画面请用 {@link #send(MotionEvent, int, Matrix, int, int)} 传矩阵。
     */
    public void send(MotionEvent ev, int displayId, float sx, float sy) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(sx, sy);
        send(ev, displayId, m, 0, 0);
    }

    /**
     * 通用仿射映射入口：view 本地坐标 → 虚拟屏像素坐标，经矩阵 mapPoints 变换。
     *
     * **上层对接契约（务必遵守）**：
     *  1. matrix 必须是【View 本地坐标 → VD 逻辑坐标】的变换。
     *     如果手里是反方向的矩阵（如 textureView.getTransform()，它是 VD→View），
     *     必须先调 {@link #inverted(Matrix)} 反转再传入，否则坐标完全颠倒。
     *  2. clampW/clampH 填 **VD 的逻辑宽高**（带 rotation 之后的 Display 宽高），
     *     不是 SurfaceTexture buffer 的原始分辨率。
     *  3. cmd 兜底只支持单点；多点手势落到 cmd 会异常。策略上 cmd 只允许在
     *     DOWN 开局被选中，手势中途不会切 cmd，风险可控。
     *  4. {@link #send(MotionEvent, int, float, float)} 简易缩放只适合
     *     「铺满、无黑边、无旋转」场景；居中黑边、旋转等复杂画面必须用本入口传矩阵。
     *
     * 事件到达 pane 的 OnTouchListener 时，框架已把屏幕坐标沿视图树做过一次
     * 逆变换（screen → view 本地），所以这里只需要【本地 → VD】的正向 mapPoints；
     * invert 只在把别的方向的矩阵转过来时才需要。
     *
     * @param clampW/clampH 越界钳制边界（>0 时生效）：旋转/resize 瞬间 view 与
     *                      display 尺寸不一致，不钳制会点出屏外被 Dispatcher 丢弃
     */
    public void send(MotionEvent ev, int displayId, android.graphics.Matrix m,
                     int clampW, int clampH) {
        if (displayId < 0) return;
        final Snap s = Snap.from(ev, displayId, m, clampW, clampH);
        if (s == null) return;
        boolean critical = (s.action & MotionEvent.ACTION_MASK) != MotionEvent.ACTION_MOVE;
        try {
            injector.execute(new Frame(() -> dispatch(s), critical));
        } catch (Throwable t) {
            Log.w(TAG, "注入任务提交失败", t);
        }
    }

    /** 注入任务包装：critical = DOWN/UP/CANCEL/POINTER_* 等关键帧，队列里不许丢 */
    private static final class Frame implements Runnable {
        final Runnable body;
        final boolean critical;

        Frame(Runnable body, boolean critical) {
            this.body = body;
            this.critical = critical;
        }

        @Override public void run() { body.run(); }
    }

    /**
     * 队列满时的取舍：MOVE 过载丢新帧（轨迹少几帧不影响形状）；关键帧不能丢，
     * 先把队列里排队的 MOVE 挤掉再塞进去，实在挤不动才丢本帧（状态机会兜底）。
     */
    private static final class KeepCriticalPolicy
            implements java.util.concurrent.RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor ex) {
            if (r instanceof Frame && !((Frame) r).critical) return;   // MOVE 过载：丢
            for (Object o : ex.getQueue().toArray()) {
                if (o instanceof Frame && !((Frame) o).critical) ex.getQueue().remove(o);
            }
            try {
                ex.execute(r);
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ 快照

    /** 从系统事件里把需要的字段全拷出来（系统会在 onTouchEvent 返回后回收原事件） */
    private static final class Snap {
        int displayId, action, metaState, buttonState;
        int[] ids, toolTypes;
        float[] xs, ys, ps, sizes;
        // 历史采样（只有 MOVE 有）：每样本 n 个指针 + 1 个时间
        int histCount;
        long[] histDt;                 // 相对当前帧的时间差（ms，>=0）
        float[] histX, histY, histP, histS;   // histCount * n，按样本排列

        static Snap from(MotionEvent ev, int displayId, android.graphics.Matrix m,
                         int clampW, int clampH) {
            int n = ev.getPointerCount();
            if (n <= 0 || n > MAX_POINTERS) return null;
            Snap s = new Snap();
            s.displayId = displayId;
            s.action = ev.getAction();
            s.metaState = ev.getMetaState();
            s.buttonState = ev.getButtonState();
            s.ids = new int[n];
            s.toolTypes = new int[n];
            s.xs = new float[n];
            s.ys = new float[n];
            s.ps = new float[n];
            s.sizes = new float[n];
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            float[] pt = new float[2];
            for (int i = 0; i < n; i++) {
                ev.getPointerProperties(i, pp);
                ev.getPointerCoords(i, pc);
                s.ids[i] = pp.id;
                s.toolTypes[i] = pp.toolType;
                pt[0] = pc.x;
                pt[1] = pc.y;
                if (m != null) m.mapPoints(pt);     // 仿射映射（含旋转/斜切等一般情形）
                clamp(pt, clampW, clampH);
                s.xs[i] = pt[0];
                s.ys[i] = pt[1];
                s.ps[i] = pc.pressure;
                s.sizes[i] = pc.size;
            }
            if ((s.action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE) {
                int hist = ev.getHistorySize();
                if (hist > 0) {
                    // 只保留最近 MAX_HISTORY 个样本（旧的丢了也不影响轨迹形状）
                    int from = Math.max(0, hist - MAX_HISTORY);
                    s.histCount = hist - from;
                    long evT = ev.getEventTime();
                    s.histDt = new long[s.histCount];
                    s.histX = new float[s.histCount * n];
                    s.histY = new float[s.histCount * n];
                    s.histP = new float[s.histCount * n];
                    s.histS = new float[s.histCount * n];
                    for (int h = 0; h < s.histCount; h++) {
                        int hi = from + h;
                        s.histDt[h] = Math.max(0, evT - ev.getHistoricalEventTime(hi));
                        for (int i = 0; i < n; i++) {
                            int k = h * n + i;
                            pt[0] = ev.getHistoricalX(i, hi);
                            pt[1] = ev.getHistoricalY(i, hi);
                            if (m != null) m.mapPoints(pt);
                            clamp(pt, clampW, clampH);
                            s.histX[k] = pt[0];
                            s.histY[k] = pt[1];
                            s.histP[k] = ev.getHistoricalPressure(i, hi);
                            s.histS[k] = ev.getHistoricalSize(i, hi);
                        }
                    }
                }
            }
            return s;
        }

        private static void clamp(float[] pt, int w, int h) {
            if (w > 0 && pt[0] < 0) pt[0] = 0;
            if (w > 0 && pt[0] > w - 1) pt[0] = w - 1;
            if (h > 0 && pt[1] < 0) pt[1] = 0;
            if (h > 0 && pt[1] > h - 1) pt[1] = h - 1;
        }

        /** 调试注入用：单指、无历史 */
        static Snap raw(int displayId, int action, float x, float y) {
            Snap s = new Snap();
            s.displayId = displayId;
            s.action = action;
            s.ids = new int[]{0};
            s.toolTypes = new int[]{MotionEvent.TOOL_TYPE_FINGER};
            s.xs = new float[]{x};
            s.ys = new float[]{y};
            s.ps = new float[]{1f};
            s.sizes = new float[]{1f};
            return s;
        }
    }

    // ------------------------------------------------------------ 手势状态机（injector 线程）

    private void dispatch(Snap s) {
        int masked = s.action & MotionEvent.ACTION_MASK;
        long now = SystemClock.uptimeMillis();

        switch (masked) {
            case MotionEvent.ACTION_DOWN:
                if (!activePointers.isEmpty()) {
                    // 上一段手势的 UP 丢了：应用里指针还按着，新 DOWN 会被当重复指针丢弃
                    PipDebug.log("上一段手势未收尾（" + activePointers.size() + " 指），补发 CANCEL");
                    endGestureWithCancel();
                }
                if (now - lastGestureEnd > NEW_GESTURE_GAP_MS) {
                    // 新手势：等宿主触摸引起的焦点抖动过去（见 FOCUS_SETTLE_MS 说明），
                    // 否则 DOWN 之后 9~21ms 焦点被抢走，应用会把整段手势 CANCEL 掉
                    SystemClock.sleep(FOCUS_SETTLE_MS);
                    now = SystemClock.uptimeMillis();
                }
                activePointers.put(s.ids[0], new float[]{s.xs[0], s.ys[0]});
                gestureDisplay = s.displayId;
                gestureDown = now;
                // 通道只在 DOWN 时选定；cmd 只允许作为开局兜底，手势中途绝不降级
                gestureChannel = selectChannel();
                // 点击稳定化：新手势从"扣住 MOVE"开始
                held.clear();
                heldMoves = 0;
                streaming = false;
                maxDrift = 0;
                downX = s.xs[0];
                downY = s.ys[0];
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (!sameGesture(s)) return;
                if (!streaming) {           // 多指 = 必是拖/捏合，停止扣帧
                    streaming = true;
                    flushHeld();
                }
                for (int i = 0; i < s.ids.length; i++) {
                    activePointers.put(s.ids[i], new float[]{s.xs[i], s.ys[i]});
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (!sameGesture(s)) return;
                for (int i = 0; i < s.ids.length; i++) {
                    int id = s.ids[i];
                    // pointer index 位指向的才是抬起的那个指；只移除它
                    int pi = (s.action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                            >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                    if (i == pi) activePointers.remove(id);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!sameGesture(s)) return;
                if (!streaming) {
                    float d = (float) Math.hypot(s.xs[0] - downX, s.ys[0] - downY);
                    if (d > maxDrift) maxDrift = d;
                    // 位移超阈值（明确的拖动）或扣太久（长按/慢拖）→ 放行实时透传
                    if (d > holdSlopPx || now - gestureDown > HOLD_MS) {
                        streaming = true;
                        flushHeld();
                    } else {
                        held.add(s);        // 还像一次点击：先扣住不发
                        heldMoves++;
                        return;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!sameGesture(s)) return;
                if (!streaming && masked == MotionEvent.ACTION_UP
                        && heldMoves > 0 && now - gestureDown < HOLD_MS
                        && activePointers.size() == 1) {
                    // 点击稳定化：短促、位移小的手势按"点击"收尾 —— UP 直接落在
                    // DOWN 坐标上，扣住的漂移 MOVE 全部丢弃。应用看到的就是一次
                    // 干净利落的点按，不会被漂移轨迹误判成滚动。
                    PipDebug.log("tap 稳定化生效：drift=" + (int) maxDrift
                            + "px held=" + heldMoves + " dur=" + (now - gestureDown) + "ms");
                    held.clear();
                    s.xs[0] = downX;
                    s.ys[0] = downY;
                } else {
                    flushHeld();
                }
                break;

            default:
                return;   // ACTION_SCROLL 等不处理
        }

        boolean ok = deliver(s, now);
        if (!ok && (masked == MotionEvent.ACTION_DOWN
                || masked == MotionEvent.ACTION_UP
                || masked == MotionEvent.ACTION_CANCEL
                || masked == MotionEvent.ACTION_POINTER_DOWN
                || masked == MotionEvent.ACTION_POINTER_UP)) {
            // 关键帧失败立即同步重试一次（仍是同一条通道）
            ok = deliver(s, SystemClock.uptimeMillis());
        }
        if (!ok) {
            // 通道挂了：CANCEL 收尾 + 终止手势 + 丢弃剩余帧（不降级）
            Log.w(TAG, "通道 " + gestureChannel + " 注入失败，终止手势 d=" + s.displayId);
            endGestureWithCancel();
            return;
        }

        if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP) {
            PipDebug.log("touch " + (masked == MotionEvent.ACTION_DOWN ? "DOWN" : "UP")
                    + " d=" + s.displayId + " @" + (int) s.xs[0] + "," + (int) s.ys[0]
                    + " ch=" + gestureChannel + " ptr=" + activePointers.size() + " ok=" + ok);
        }

        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            activePointers.clear();
            lastGestureEnd = now;      // 手势结束时间（下一个 DOWN 据此判断是否新手势）
        } else if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_MOVE) {
            // 记录最新坐标（补 CANCEL 用）
            for (int i = 0; i < s.ids.length; i++) {
                float[] c = activePointers.get(s.ids[i]);
                if (c != null) {
                    c[0] = s.xs[i];
                    c[1] = s.ys[i];
                }
            }
        }
    }

    /**
     * 「点一下这块屏」——只为了让这块虚拟屏拿到**输入焦点**。
     *
     * 为什么返回键需要它：这台 ROM（HyperOS，mPerDisplayFocusEnabled=false 的行为）
     * 只有「顶层焦点屏」上的窗口是可聚焦的，其余屏的窗口一律 inputConfig=NOT_FOCUSABLE。
     * 用户点 Dock 上那个返回按钮时，手指已经把焦点抢回了主屏，于是注入到画中画屏的
     * BACK 找不到可投递的窗口，被 InputDispatcher 直接丢弃 —— 表现就是"返回键没反应"。
     *
     * 实测：向目标屏注入一次触摸 DOWN，WMS 立刻把该屏设为顶层焦点屏
     * （dumpsys input 里 FocusedWindows 从 display 0 变成该屏，FocusRequests result=OK），
     * 之后的按键就能投递进去了。这里用 DOWN + CANCEL：应用收到 ACTION_CANCEL，
     * 不会真的触发一次点击。
     *
     * **同步**执行（调用方本来就在后台线程）：返回时焦点已经落位。
     */
    public void pokeFocus(int displayId) {
        if (displayId < 0) return;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            injector.execute(() -> {
                try {
                    dispatch(Snap.raw(displayId, MotionEvent.ACTION_DOWN, 2f, 2f));
                    SystemClock.sleep(24);
                    dispatch(Snap.raw(displayId, MotionEvent.ACTION_CANCEL, 2f, 2f));
                } catch (Throwable t) {
                    Log.w(TAG, "焦点探针失败 d=" + displayId, t);
                } finally {
                    done.countDown();
                }
            });
            done.await(600, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    /** 事件是否属于当前进行中的手势（有 DOWN 在先、同一块屏） */
    private boolean sameGesture(Snap s) {
        if (activePointers.isEmpty() || s.displayId != gestureDisplay) {
            if ((s.action & MotionEvent.ACTION_MASK) != MotionEvent.ACTION_MOVE) {
                PipDebug.warn("丢弃无源事件 action=" + (s.action & MotionEvent.ACTION_MASK)
                        + " d=" + s.displayId);
            }
            return false;
        }
        return true;
    }

    /** 整批放行扣住的 MOVE（判定为拖动/超时/多指时调用） */
    private void flushHeld() {
        if (held.isEmpty()) return;
        java.util.ArrayList<Snap> list = new java.util.ArrayList<>(held);
        held.clear();
        for (Snap hs : list) {
            deliver(hs, SystemClock.uptimeMillis());
        }
    }

    /** 用当前指针集合补一帧 CANCEL（多指针），然后清空手势状态 */
    private void endGestureWithCancel() {
        if (activePointers.isEmpty() || gestureDisplay < 0) {
            activePointers.clear();
            return;
        }
        long now = SystemClock.uptimeMillis();
        int n = activePointers.size();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        int i = 0;
        for (Map.Entry<Integer, float[]> e : activePointers.entrySet()) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = e.getKey();
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = e.getValue()[0];
            coords[i].y = e.getValue()[1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
            i++;
        }
        MotionEvent ev = MotionEvent.obtain(gestureDown > 0 ? gestureDown : now, now,
                MotionEvent.ACTION_CANCEL, n, props, coords,
                0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            switch (gestureChannel) {
                case INPROC:
                    injectInProcess(ev, gestureDisplay);
                    break;
                case PIPE:
                    try {
                        writeFrame(ev, gestureDisplay, now);
                        pipe.flush();
                    } catch (Throwable t) {
                        Log.w(TAG, "CANCEL 管道补发失败", t);
                        closeServer();
                        bg.run(this::ensureServer);
                    }
                    break;
                default:
                    break;   // cmd 无法补 CANCEL
            }
        } finally {
            ev.recycle();
            activePointers.clear();
            lastGestureEnd = now;
        }
    }

    // ------------------------------------------------------------ 通道选择与交付

    private Channel selectChannel() {
        if (Boolean.FALSE.equals(inProcOk)) {
            return pipeReady() ? Channel.PIPE : Channel.CMD;
        }
        return Channel.INPROC;   // inProcess 失败时内部会自动转探 pipe/cmd
    }

    private boolean pipeReady() {
        return pipe != null && server != null && server.isAlive();
    }

    /**
     * 一帧 = 按 DOWN 时选定的通道交付。
     * 返回 false 表示通道失败（调用方负责 CANCEL 收尾，绝不降级）。
     */
    private boolean deliver(Snap s, long now) {
        switch (gestureChannel) {
            case INPROC:
                if (inProcess(s, gestureDown, now)) return true;
                // 进程内通道在 DOWN 时失败 → 探测一次降级机会只发生在 DOWN；
                // 手势中途失败一律终止手势（由调用方处理）。这里只处理 DOWN：
                if ((s.action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                    if (pipe(s, gestureDown, now)) {
                        gestureChannel = Channel.PIPE;
                        return true;
                    }
                    gestureChannel = Channel.CMD;
                    cmd(s);
                    return true;
                }
                return false;
            case PIPE:
                if (pipe(s, gestureDown, now)) return true;
                return false;      // 手势中途管道断：终止手势，不换通道
            case CMD:
            default:
                cmd(s);
                return true;       // 命令兜底无法回报结果，按已处理算
        }
    }

    // ------------------------------------------------------------ 通道 1：进程内反射

    private boolean inProcess(Snap s, long dn, long now) {
        if (Boolean.FALSE.equals(inProcOk)) return false;
        MotionEvent copy = null;
        try {
            ensureReflect();
            copy = buildEvent(s, dn, now);
            mSetDisplayId.invoke(copy, s.displayId);
            Object ret = mInject.invoke(inputManager, copy, 0);   // ASYNC（scrcpy 同款）
            if (!Boolean.TRUE.equals(ret)) {
                ret = mInject.invoke(inputManager, copy, 0);      // Dispatcher 忙时偶发拒绝
            }
            return Boolean.TRUE.equals(ret);
        } catch (Throwable t) {
            Log.i(TAG, "进程内注入不可用（hidden API 被限），改用注入服务：" + t.getClass().getSimpleName());
            inProcOk = Boolean.FALSE;
            mInject = null;
            mSetDisplayId = null;
            return false;
        } finally {
            if (copy != null) copy.recycle();
        }
    }

    /** 按快照构建 MotionEvent：当前点 + 历史采样（addBatch） */
    private static MotionEvent buildEvent(Snap s, long downTime, long now) {
        int n = s.ids.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        fillPointer(s, -1, props, coords);
        MotionEvent ev = MotionEvent.obtain(downTime, now, s.action, n, props, coords,
                s.metaState, s.buttonState, 1f, 1f, DEVICE_ID, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        for (int h = 0; h < s.histCount; h++) {
            MotionEvent.PointerCoords[] hc = new MotionEvent.PointerCoords[n];
            for (int i = 0; i < n; i++) {
                hc[i] = new MotionEvent.PointerCoords();
                hc[i].x = s.histX[h * n + i];
                hc[i].y = s.histY[h * n + i];
                hc[i].pressure = s.histP[h * n + i];
                hc[i].size = s.histS[h * n + i];
            }
            ev.addBatch(now - s.histDt[h], hc, 0);
        }
        return ev;
    }

    private static void fillPointer(Snap s, int histIdx,
                                    MotionEvent.PointerProperties[] props,
                                    MotionEvent.PointerCoords[] coords) {
        int n = s.ids.length;
        for (int i = 0; i < n; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = s.ids[i];
            props[i].toolType = s.toolTypes[i];
            coords[i] = new MotionEvent.PointerCoords();
            if (histIdx >= 0) {
                coords[i].x = s.histX[histIdx * n + i];
                coords[i].y = s.histY[histIdx * n + i];
                coords[i].pressure = s.histP[histIdx * n + i];
                coords[i].size = s.histS[histIdx * n + i];
            } else {
                coords[i].x = s.xs[i];
                coords[i].y = s.ys[i];
                coords[i].pressure = s.ps[i];
                coords[i].size = s.sizes[i];
            }
        }
    }

    private void ensureReflect() throws Exception {
        if (mInject != null) return;
        inputManager = app.getSystemService(Context.INPUT_SERVICE);
        mSetDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
        mSetDisplayId.setAccessible(true);
        mInject = InputManager.class.getDeclaredMethod(
                "injectInputEvent", InputEvent.class, int.class);
        mInject.setAccessible(true);
    }

    /** CANCEL 专用：通道 1 直接注入，不探测、不降级 */
    private boolean injectInProcess(MotionEvent ev, int displayId) {
        try {
            ensureReflect();
            mSetDisplayId.invoke(ev, displayId);
            Object ret = mInject.invoke(inputManager, ev, 0);
            if (!Boolean.TRUE.equals(ret)) {
                ret = mInject.invoke(inputManager, ev, 0);
            }
            return Boolean.TRUE.equals(ret);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 通道 2：常驻注入服务

    private synchronized boolean pipe(Snap s, long dn, long now) {
        if (!pipeReady()) {
            bg.run(this::ensureServer);
            return false;
        }
        MotionEvent ev = null;
        try {
            ev = buildEvent(s, dn, now);
            pipeWriteAt = SystemClock.uptimeMillis();   // 卡死检测起点（见字段说明）
            writeFrame(ev, s.displayId, now);
            pipe.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "注入服务写入失败，重启", t);
            closeServer();
            bg.run(this::ensureServer);
            return false;
        } finally {
            pipeWriteAt = 0;
            // 这里必须回收：每帧一个 MotionEvent（带 native 资源），
            // 走管道通道时漏掉回收会持续制造垃圾，长时间使用后 GC 压力明显
            if (ev != null) ev.recycle();
        }
    }

    /**
     * 写一帧（含历史采样）。协议 v2：MAGIC + 包长 + 载荷。
     * 时间统一重锚到注入时刻 now：downTime=gestureDown，历史点 = now - 相对差。
     */
    private void writeFrame(MotionEvent ev, int displayId, long now) throws Exception {
        int n = ev.getPointerCount();
        int hist = ev.getActionMasked() == MotionEvent.ACTION_MOVE ? ev.getHistorySize() : 0;
        DataOutputStream o = pipe;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(64 + hist * n * 16);
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(displayId);
        d.writeInt(ev.getAction());
        d.writeInt(n);
        d.writeInt(hist);
        d.writeLong(ev.getDownTime());
        d.writeLong(ev.getEventTime());
        d.writeInt(ev.getMetaState());
        d.writeInt(ev.getButtonState());
        for (int i = 0; i < n; i++) {
            d.writeInt(ev.getPointerId(i));
            d.writeFloat(ev.getX(i));
            d.writeFloat(ev.getY(i));
            d.writeFloat(ev.getPressure(i));
            d.writeFloat(ev.getSize(i));
        }
        for (int h = 0; h < hist; h++) {
            d.writeLong(now - Math.max(0, ev.getEventTime() - ev.getHistoricalEventTime(h)));
            for (int i = 0; i < n; i++) {
                d.writeFloat(ev.getHistoricalX(i, h));
                d.writeFloat(ev.getHistoricalY(i, h));
                d.writeFloat(ev.getHistoricalPressure(i, h));
                d.writeFloat(ev.getHistoricalSize(i, h));
            }
        }
        d.flush();
        byte[] payload = buf.toByteArray();
        o.writeInt(PIPE_MAGIC);
        o.writeInt(payload.length);
        o.write(payload);
    }

    private synchronized void ensureServer() {
        if (pipeReady()) return;
        if (starting) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastStartTry < 3000) return;   // 别疯狂重启
        lastStartTry = now;
        starting = true;
        try {
            String apk = app.getPackageCodePath();
            String run = "CLASSPATH=" + apk + " app_process /system/bin " + SERVER_CLASS + " 2>/dev/null";

            Process p = tryStart(new String[]{"sh", "-c", run});
            if (p == null) {
                Log.i(TAG, "直接起注入服务失败，尝试 su");
                p = tryStart(new String[]{"su", "-c", run});
            }
            if (p == null) {
                Log.w(TAG, "注入服务未能启动，触摸将走命令兜底（会迟滞）");
                return;
            }
            server = p;
            pipe = new DataOutputStream(new BufferedOutputStream(p.getOutputStream(), 1024));
            Log.i(TAG, "注入服务已连接");
        } catch (Throwable t) {
            Log.w(TAG, "注入服务启动异常", t);
        } finally {
            starting = false;
        }
    }

    /**
     * 启动注入服务并等握手：循环读 stdout 直到出现 READY 标记（或流结束/异常），
     * 不假设 READY 一定出现在第一行（app_process 可能先打版本/警告行）。
     */
    private Process tryStart(String[] argv) {
        try {
            Process p = Runtime.getRuntime().exec(argv);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                line = r.readLine();
                if (line == null) break;                  // 流关闭：进程退出
                if (line.contains("READY")) {
                    if (line.contains("READY 1")) {
                        drain(r);                         // 继续读走 stdout，避免远端写满阻塞
                        return p;
                    }
                    Log.i(TAG, "注入服务 READY=0：" + line);
                    break;
                }
                // 其他行（app_process 的警告/版本信息）忽略，继续等 READY
            }
            try { p.destroy(); } catch (Throwable ignored) { }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void drain(final BufferedReader r) {
        bg.run(() -> {
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("ERR") || line.startsWith("DBG")) {
                        Log.w(TAG, "[注入服务] " + line);   // 注入失败要能看见，不能吞
                    }
                }
            } catch (Throwable ignored) { }
        });
    }

    /**
     * 关通道。**不加锁**：卡死看门狗要在写入线程正卡在 flush() 的时候调用它，
     * 一加锁就跟着一起卡住了 —— 关流正是让阻塞写抛异常、把人救出来的手段。
     */
    private void closeServer() {
        DataOutputStream p = pipe;
        pipe = null;
        try {
            if (p != null) p.close();
        } catch (Throwable ignored) { }
        Process s = server;
        server = null;
        if (s != null) {
            try { s.destroy(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ 通道 3：input 命令兜底（仅限手势开局）

    private void cmd(Snap s) {
        int x = (int) s.xs[0], y = (int) s.ys[0];
        int masked = s.action & MotionEvent.ACTION_MASK;
        if (Build.VERSION.SDK_INT < 29) {
            if (masked == MotionEvent.ACTION_UP) {
                Shell.run("input tap " + x + " " + y);
            }
            return;
        }
        // MOVE 限频 30Hz：每条命令都是一次 fork/exec（50~150ms），不限频的话
        // 一次拖拽会灌进几十上百条命令，队列只增不减，手指抬起了还在补发
        if (masked == MotionEvent.ACTION_MOVE) {
            long now = SystemClock.uptimeMillis();
            if (now - lastCmdMoveAt < 33) return;
            lastCmdMoveAt = now;
        }
        switch (masked) {
            case MotionEvent.ACTION_DOWN:
                Shell.run("input -d " + s.displayId + " motionevent DOWN " + x + " " + y);
                break;
            case MotionEvent.ACTION_MOVE:
                Shell.run("input -d " + s.displayId + " motionevent MOVE " + x + " " + y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                Shell.run("input -d " + s.displayId + " motionevent UP " + x + " " + y);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ 调试

    /**
     * 调试用：直接在指定屏上点一下（或滑一下）。
     * 通过广播调用，用来在没有手指的情况下验证「注入链路」是否通畅。
     */
    public void debugTap(final int displayId, final int x, final int y,
                         final int x2, final int y2, final int steps) {
        debugTap(displayId, x, y, x2, y2, steps, 0);
    }

    /**
     * hold 毫秒：DOWN 后先按住再开始移动 —— 用来触发长按（拖动排序验证用）。
     * 注入线程是专用的，sleep 不影响主线程。
     */
    public void debugTap(final int displayId, final int x, final int y,
                         final int x2, final int y2, final int steps, final long hold) {
        injector.execute(new Frame(() -> {
            long now = SystemClock.uptimeMillis();
            activePointers.put(0, new float[]{x, y});
            gestureDisplay = displayId;
            gestureDown = now;
            gestureChannel = selectChannel();
            deliver(Snap.raw(displayId, MotionEvent.ACTION_DOWN, x, y), now);
            if (hold > 0) {
                try { Thread.sleep(hold); } catch (InterruptedException ignored) { }
            }
            for (int s = 1; s <= steps; s++) {
                int cx = x + (x2 - x) * s / steps;
                int cy = y + (y2 - y) * s / steps;
                deliver(Snap.raw(displayId, MotionEvent.ACTION_MOVE, cx, cy),
                        SystemClock.uptimeMillis());
            }
            deliver(Snap.raw(displayId, MotionEvent.ACTION_UP, x2, y2), SystemClock.uptimeMillis());
            activePointers.clear();
        }, true));
    }

    /** 极简后台执行器（串行） */
    private static final class Executor {
        private final ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(32),
                r -> {
                    Thread t = new Thread(r, "pipdesk-bg");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());

        void run(Runnable r) {
            try {
                ex.execute(r);
            } catch (Throwable ignored) { }
        }
    }
}
