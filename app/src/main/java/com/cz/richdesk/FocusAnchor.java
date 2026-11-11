package com.cz.richdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 每块虚拟屏的「焦点锚」—— 0.22.8 用来验证「卡死 = 焦点真空」这个假设的工具类。
 *
 * <h3>已验证的卡死链路（21:35 / 22:06 两次全量日志对照）</h3>
 * <pre>
 *   手指点画中画的按钮（触摸经宿主注入进虚拟屏）
 *   → 屏内系统蓝牙拿到窗口焦点，紧接着开始播放
 *   → com.tw.service requestAudioFocus 成功
 *   → 【致命】WindowManager: setFocusedApp mFocusedApp = null
 *        （蓝牙自己在播放时切"播放中"主题 / relaunch，那个 activity 记录一换，
 *          系统就把 focusedApp 清空）
 *   → 各虚拟屏 Input focus 依次变 null，整机输入焦点塌回 display 0
 *   → 主屏 home 变 resumed，ROM 私有 TaskDisplayArea.pauseBackTasks 把各屏
 *     resumed 的 activity 一起 pause → makeInvisible 并 stop
 *   → 虚拟屏内谁也不再提交新帧，TextureView 停在最后一帧 = 卡死
 * </pre>
 * 音频数据面全程正常（歌还在放），坏掉的只有"画面提交"。
 *
 * <h3>为什么锚能挡住、又为什么不能常开</h3>
 * 在这块屏上常驻一个可聚焦窗口，focusedApp 就不会被清空，链路第一环断掉 ——
 * 实测 always 模式 3/3 不卡死。但 overlay 窗口层级永远压过应用窗口，
 * 蓝牙一旦不是"有焦点的窗口"就自己把 A2DP 静音
 * （日志：`requestSource: windowFocusChangedBT = false` → `ON_MUSIC_MUTE` → `set_a2dpmute 1`），
 * 于是变成"画面不卡、但没有声音"。**这是应用层无解的一对矛盾**：
 * 要声音就必须让蓝牙保持焦点，要保持焦点就会触发上面那条链路。
 *
 * 所以本类默认**不启用**（mode=off，行为与 0.22.7 一致），只作为现场开关与对照实验：
 *   /data/local/tmp/pipdesk_anchor = always → 不卡死、但屏内蓝牙/音乐没声音
 *                                    pulse  → 仅按下瞬间顶 900ms 焦点（实测挡不住）
 *                                    wall   → 壁纸层锚（本机 WMS 直接 BadTokenException）
 * 真正的根治只能在 framework 侧（别在 focusedApp 变化时清焦点 / 别跨屏 pauseBackTasks），
 * 或者换架构：系统蓝牙/音乐不用虚拟屏 task，改用主屏 freeform 窗口按面板矩形摆放。
 */
final class FocusAnchor {

    private static final String TAG = "PipDesk";

    /**
     * 现场开关 /data/local/tmp/pipdesk_anchor：
     *   不存在 / off / 其它 → **默认不加锚**（行为与 0.22.7 一致：有声音，卡死后自愈）
     *   always              → 常驻可聚焦：实测不卡死，但屏内系统蓝牙/音乐会自己静音
     *                         （`requestSource: windowFocusChangedBT = false` +
     *                          `ON_MUSIC_MUTE` / `set_a2dpmute 1`）→ 没声音
     *   pulse               → 手指按下瞬间顶 900ms 焦点（实验）
     *   wall                → 壁纸层锚（实验，本机被 WMS 以 BadTokenException 拒绝）
     */
    private static final String SWITCH = "/data/local/tmp/pipdesk_anchor";

    private static final int MODE_PULSE = 0;
    private static final int MODE_OFF = 1;
    private static final int MODE_ALWAYS = 2;
    /** 壁纸层：窗口类型 TYPE_WALLPAPER，层级在应用窗口**下面**（对照实验） */
    private static final int MODE_WALL = 3;

    /** 脉冲默认顶多久：够覆盖蓝牙 relaunch（实测 200~800ms）又不至于把声音拖太久 */
    static final long PULSE_MS = 900L;

    /**
     * 只给**会触发这套卡死**的应用挂锚。实测需要更多包名时，
     * 往 /data/local/tmp/pipdesk_anchor_pkgs 里一行一个追加。
     */
    private static final String[] ANCHOR_PKGS = {"com.tw.bt", "com.tw.music"};
    private static final String PKG_SWITCH = "/data/local/tmp/pipdesk_anchor_pkgs";
    private static volatile long pkgCheckedAt;
    private static volatile String[] extraPkgs = new String[0];

    private static volatile long modeCheckedAt;
    private static volatile int mode = MODE_OFF;

    private static final class Holder {
        int displayId;
        int paneIndex;
        View view;
        WindowManager wm;
        boolean armed;      // true = 可聚焦（正在顶焦点）
        int failures;
        long addedAt;
        long lastTryAt;
        long gen;           // 脉冲代次：后到的脉冲/撤防覆盖先到的
    }

    private static final Map<Integer, Holder> MAP = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FocusAnchor() { }

    // ------------------------------------------------------------ 开关与包名

    private static int readMode() {
        long now = android.os.SystemClock.uptimeMillis();
        if (modeCheckedAt != 0 && now - modeCheckedAt < 1500) return mode;
        modeCheckedAt = now;
        int m = MODE_OFF;
        try {
            java.io.File f = new java.io.File(SWITCH);
            if (f.exists()) {
                String s = readSmall(f).toLowerCase(java.util.Locale.US);
                if (s.startsWith("off")) m = MODE_OFF;
                else if (s.startsWith("always")) m = MODE_ALWAYS;
                else if (s.startsWith("wall")) m = MODE_WALL;
            }
        } catch (Throwable ignored) { }
        mode = m;
        return m;
    }

    private static String readSmall(java.io.File f) {
        try {
            byte[] b = new byte[64];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    static void logMode() {
        int m = readMode();
        Log.i(TAG, "焦点锚模式=" + (m == MODE_ALWAYS ? "always(常驻可聚焦)" : m == MODE_PULSE
                ? "pulse(脉冲)" : m == MODE_WALL ? "wall(壁纸层)" : "off(不加锚,默认)"));
    }

    static boolean needsAnchor(String pkg) {
        if (pkg == null) return false;
        for (String p : ANCHOR_PKGS) if (p.equals(pkg)) return true;
        readExtraPkgs();
        for (String p : extraPkgs) if (p.equals(pkg)) return true;
        return false;
    }

    private static void readExtraPkgs() {
        long now = android.os.SystemClock.uptimeMillis();
        if (pkgCheckedAt != 0 && now - pkgCheckedAt < 5000) return;
        pkgCheckedAt = now;
        try {
            java.io.File f = new java.io.File(PKG_SWITCH);
            if (!f.exists()) { extraPkgs = new String[0]; return; }
            java.util.List<String> out = new java.util.ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
            r.close();
            extraPkgs = out.toArray(new String[0]);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ 状态查询

    static synchronized boolean alive(int displayId) {
        Holder h = MAP.get(displayId);
        return h != null && h.view != null && h.view.isAttachedToWindow();
    }

    static synchronized int count() {
        int n = 0;
        for (Holder h : MAP.values()) if (h.view != null && h.view.isAttachedToWindow()) n++;
        return n;
    }

    /** 心跳日志用：a<displayId>=A(顶焦点中)/d(休眠)/X(掉了) */
    static synchronized String summary() {
        StringBuilder b = new StringBuilder();
        for (Holder h : MAP.values()) {
            if (h.view == null) continue;
            b.append(" a").append(h.displayId).append('=')
                    .append(!h.view.isAttachedToWindow() ? "X" : (h.armed ? "A" : "d"));
        }
        return b.toString();
    }

    // ------------------------------------------------------------ 窗口生命周期

    /**
     * 这块屏要不要有锚窗口（dormant）。
     * false → 立刻摘掉（换成了不需要锚的应用 / 屏空了）。
     */
    static void setWanted(Context app, int displayId, int paneIndex, boolean want) {
        if (displayId < 0) return;
        if (want) ensure(app, displayId, paneIndex);
        else release(displayId);
    }

    static void ensure(final Context app, final int displayId, final int paneIndex) {
        if (app == null || displayId < 0) return;
        if (readMode() == MODE_OFF) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> ensure(app, displayId, paneIndex));
            return;
        }
        final Holder h;
        synchronized (FocusAnchor.class) {
            Holder e = MAP.get(displayId);
            if (e != null && e.view != null && e.view.isAttachedToWindow()) {
                e.paneIndex = paneIndex;
                return;
            }
            if (e == null) {
                e = new Holder();
                e.displayId = displayId;
                MAP.put(displayId, e);
            }
            e.paneIndex = paneIndex;
            if (readMode() == MODE_ALWAYS || readMode() == MODE_WALL) e.armed = true;
            h = e;
        }
        apply(app, h, true);
    }

    /**
     * 打一发脉冲：把锚切成可聚焦，hold 期后自动放开。
     * 重复调用只会延长当前这一发（代次更大的赢）。
     */
    static void pulse(final Context app, final int displayId, final int paneIndex, final long holdMs) {
        if (app == null || displayId < 0) return;
        if (readMode() == MODE_OFF) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> pulse(app, displayId, paneIndex, holdMs));
            return;
        }
        final Holder h;
        final long gen;
        synchronized (FocusAnchor.class) {
            Holder e = MAP.get(displayId);
            if (e == null) {
                e = new Holder();
                e.displayId = displayId;
                MAP.put(displayId, e);
            }
            e.paneIndex = paneIndex;
            e.armed = true;
            e.gen++;
            gen = e.gen;
            h = e;
        }
        apply(app, h, true);
        if (readMode() == MODE_ALWAYS) return;      // 常驻模式不撤防
        MAIN.postDelayed(() -> {
            synchronized (FocusAnchor.class) {
                if (h.gen != gen) return;           // 期间又打了一发，交给新的那发收尾
                h.armed = false;
            }
            apply(app, h, false);
            Log.i(TAG, "焦点锚撤防 display=" + displayId + "（焦点交还屏内应用）");
        }, holdMs);
    }

    /** 建窗口 / 改窗口参数（armed 变化） */
    private static void apply(Context app, Holder h, boolean mayAdd) {
        try {
            boolean armed;
            synchronized (FocusAnchor.class) {
                armed = h.armed;
            }
            if (h.view == null) {
                if (!mayAdd) return;
                add(app, h, false);
                return;
            }
            WindowManager.LayoutParams lp = params(h.displayId, armed, false);
            h.wm.updateViewLayout(h.view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "焦点锚更新失败 d=" + h.displayId + "：" + t.getClass().getSimpleName());
        }
    }

    private static void add(Context app, Holder h, boolean oldType) {
        Display display;
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            display = dm == null ? null : dm.getDisplay(h.displayId);
        } catch (Throwable t) {
            display = null;
        }
        if (display == null) {
            Log.w(TAG, "焦点锚：display " + h.displayId + " 取不到，跳过");
            return;
        }
        try {
            Context dc = app.createDisplayContext(display);
            WindowManager wm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            View v = new View(dc);
            boolean armed;
            synchronized (FocusAnchor.class) {
                armed = h.armed;
            }
            WindowManager.LayoutParams lp = params(h.displayId, armed, oldType);
            wm.addView(v, lp);
            synchronized (FocusAnchor.class) {
                h.view = v;
                h.wm = wm;
                h.addedAt = android.os.SystemClock.uptimeMillis();
            }
            Log.i(TAG, "焦点锚已建（dormant）：display=" + h.displayId + " pane=" + h.paneIndex
                    + " type=" + lp.type + " armed=" + armed);
            FileLog.append("ANCHOR-ADD display=" + h.displayId + " pane=" + h.paneIndex
                    + " type=" + lp.type + " armed=" + armed + " @ " + FileLog.nowStamp());
        } catch (Throwable t) {
            h.failures++;
            Log.w(TAG, "焦点锚建不上 display=" + h.displayId + " oldType=" + oldType
                    + " 第" + h.failures + "次：" + t.getClass().getSimpleName() + " " + t.getMessage());
            if (!oldType) add(app, h, true);    // 退到 TYPE_SYSTEM_ALERT 再试一次
            else FileLog.append("ANCHOR-FAIL display=" + h.displayId + " "
                    + t.getClass().getSimpleName() + " " + t.getMessage() + " @ " + FileLog.nowStamp());
        }
    }

    private static WindowManager.LayoutParams params(int displayId, boolean focusable, boolean oldType) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        int type;
        if (readMode() == MODE_WALL) {
            type = WindowManager.LayoutParams.TYPE_WALLPAPER;
        } else {
            type = oldType
                    ? WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        lp.alpha = 0f;              // 全透明：不参与任何视觉
        lp.dimAmount = 0f;
        lp.setTitle("PipDeskAnchor-" + displayId);
        try {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } catch (Throwable ignored) { }
        return lp;
    }

    /** 虚拟屏真销毁时把锚一起摘掉 */
    static void release(final int displayId) {
        final Holder h;
        synchronized (FocusAnchor.class) {
            h = MAP.remove(displayId);
            if (h != null) h.armed = false;
        }
        if (h == null || h.view == null) return;
        Runnable r = () -> {
            try {
                h.wm.removeViewImmediate(h.view);
            } catch (Throwable ignored) { }
            Log.i(TAG, "焦点锚摘下 display=" + displayId);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    static void releaseAll() {
        java.util.ArrayList<Integer> ids;
        synchronized (FocusAnchor.class) {
            ids = new java.util.ArrayList<>(MAP.keySet());
        }
        for (int id : ids) release(id);
    }

    /**
     * 巡检用：锚不该消失（没有触摸、没有动画、没人能关掉它），
     * 但系统清理窗口等极端情况下可能掉，掉了就补回来。
     */
    static void keepAlive(final Context app) {
        if (app == null || readMode() == MODE_OFF) return;
        final long now = android.os.SystemClock.uptimeMillis();
        final java.util.ArrayList<int[]> todo = new java.util.ArrayList<>();
        synchronized (FocusAnchor.class) {
            for (Holder h : MAP.values()) {
                if (h.view != null && h.view.isAttachedToWindow()) continue;
                // 刚挂上还没走完一次窗口布局时 isAttachedToWindow() 也是 false，
                // 不设宽限期会每加一次都自己判"丢了"再挂一次，白折腾。
                if (h.addedAt != 0 && now - h.addedAt < 5_000L) continue;
                if (h.failures > 3 && now - h.lastTryAt < 10_000L) continue;   // 挂不上的别死循环
                h.lastTryAt = now;
                todo.add(new int[]{h.displayId, h.paneIndex});
            }
        }
        for (int[] it : todo) {
            Log.w(TAG, "焦点锚丢了 display=" + it[0] + "，重挂");
            ensure(app, it[0], it[1]);
        }
    }
}
