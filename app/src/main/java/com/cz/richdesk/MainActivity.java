package com.cz.richdesk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面主界面。
 * 第 0 页 = 主页：上方 1~3 块画中画，下方仿 macOS Dock；
 * 第 1 页 = 应用页：全部应用（前 5 项是「桌面设置」「深浅模式」「重启桌面」「返回」「主页」快捷图标）。
 * 翻页规则：主页只能在 Dock 上左滑进应用页；应用页整页右滑回主页。
 */
public class MainActivity extends AppCompatActivity
        implements DockAdapter.Host, AppListAdapter.FullHost, Voice.Host {

    private static final String TAG = "PipDesk";

    /** onCreate → 首帧的耗时（写进落盘日志）：车机不连 adb 也能看到开机到底慢在哪 */
    private long bootT0;
    /** 录音权限只主动申请一次，用户拒绝后不再反复弹框 */
    private boolean voicePermAsked;
    /** 首帧后的重活只跑一次；recreate() 时重置 */
    private boolean bootHeavyDone;
    /** onCreate 代号：recreate() 后旧的首帧回调必须作废，别提前把重活跑了 */
    private int bootGeneration;

    private SimplePager pager;
    private LinearLayout pageHome;
    private PaneBox paneBox;
    private FrameLayout dockWrap;
    private RecyclerView rvDock, rvApps;
    /** Dock 一行：[左功能区][竖线][Dock 图标区][竖线][右功能区]（玻璃铺在 dockRow 上） */
    private LinearLayout dockRow, dockLeftBox, dockRightBox;
    /** 两段玻璃竖线：只在对应功能区显示时才出现 */
    private View dockLeftDiv, dockRightDiv;
    /** 功能区里的时间/天气（左右各一套：任一侧都可能被配成「时间 + 天气」） */
    private TextView dockClock, dockWeather, dockClockL, dockWeatherL;
    private TextView dockGap, dockGapL;
    /** 时间面板可用文本宽度（px）：字号自适应的基准，天气文案变了要重算 */
    private int dockPanelAvailW, dockPanelAvailWL;
    /** 日期行（年月日）：与时间行一起刷新，秒级跳 */
    private TextView dockDate, dockDateL;
    private final android.os.Handler dockHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 15s 一跳：刷时间/天气，顺带跑一次亮度自动调节（目标没变不会重复写系统设置） */
    /** 秒针每秒一跳（文本没变不会重绘）；天气/亮度按 15 跳一次 */
    private int dockTickCount;

    private final Runnable dockTick = new Runnable() {
        @Override public void run() {
            updateDockClock();
            if (dockTickCount++ % 15 == 0) updateDockRealtime();
            dockHandler.postDelayed(this, 1000);
        }
    };

    private final List<String> dockPkgs = new ArrayList<>();
    private List<AppInfo> appList;
    /**
     * 全部已安装应用（包名 → AppInfo），**与列表分区无关**。
     *
     * appList 只含「应用区」里的应用 —— 用户把某个 app 拖进快捷区后它就不在 appList 里了。
     * Dock 找图标如果只看 appList，那个 app 的 Dock 图标就会退化成系统默认图标
     * （用户反馈：改过位置后重启桌面 / 切深浅色，Dock 图标全变成默认图标）。
     */
    private final java.util.Map<String, AppInfo> allApps = new java.util.HashMap<>();
    private DockAdapter dockAdapter;
    private AppListAdapter appListAdapter;
    private android.app.Dialog pickerDialog;
    private WallText appListTitle;
    private PipManager pip;
    /**
     * 返回键的目标画中画 = 用户最近触碰过（或最近在其中打开过应用）的那块屏。
     *
     * 背景：Dock 的返回按钮按下时，手指已经把窗口焦点抢给了桌面 ——
     * 全局注入的 BACK 只会落到桌面身上（等于没按）。修法是把键定向注入到
     * 这块屏的虚拟屏（Keys.backOnDisplay），所以必须记住"用户想返回哪块屏"。
     * 存面板对象引用而不是 index：调换顺序/重建后引用仍然指向同一块屏。
     */
    private PipPane lastActivePane;

    // ---------------------------------------------------------------- 语音卡片兜底
    /**
     * 卡片自己的心跳。
     *
     * 语音那套状态机跑在 Voice 的控制线程里，它一旦卡死/抛异常，卡片就会永远挂在
     * 屏幕上 —— 用户已经连着踩了三次「识别不到字也不退出」。所以在这里再放一道
     * 纯 UI 侧的保险：
     *   1) 每 150ms 刷一次电平条（不再依赖 Voice 的 pushState，采集那边有声音就能动）；
     *   2) 卡片挂超过 6.5 秒，无条件收掉（Voice 那边有 5 秒硬闸，这里是它也没跑时的兜底）。
     */
    private final android.os.Handler voiceUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private long voiceRoundAt;
    private boolean voiceTicking;

    private final Runnable voiceTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) { voiceTicking = false; return; }
            int st = Voice.get().state();
            long age = SystemClock.elapsedRealtime() - voiceRoundAt;
            if (st == Voice.S_LOADING || st == Voice.S_LISTEN || st == Voice.S_THINK) {
                if (age > 6_500L) {
                    FileLog.append("UI 兜底：语音卡片已挂 " + age + "ms（state=" + st + "）→ 强制收工");
                    Voice.get().cancelListening();
                    VoiceCard.hide(MainActivity.this);
                    voiceTicking = false;
                    return;
                }
                if (st == Voice.S_LISTEN) VoiceCard.updateLevel(Voice.get().level());
                voiceUi.postDelayed(this, 150);
            } else {
                voiceTicking = false;
            }
        }
    };

    private void kickVoiceTick() {
        voiceRoundAt = SystemClock.elapsedRealtime();
        if (!voiceTicking) {
            voiceTicking = true;
            voiceUi.post(voiceTick);
        }
    }
    /** 语音候选列表的弹窗（说「第一个」时要能收掉它） */
    private android.app.Dialog voiceCandMenu;
    /** 单画模式下连续点返回的次数：app 自己拦截 BACK 时的兜底（见 forceExitSingleIfStuck） */
    private volatile int singleBackCount;
    private volatile String singleBackForPkg;
    /** 单画退出监视的在途标记（pkg@displayId），防止重复起线程做重复 dumpsys */
    private String singleExitKey;
    private volatile long singleExitAt;

    // 上次应用的配置，用于从设置页返回时做增量刷新
    private int appliedPaneCount = -1, appliedDpi = -1, appliedPipCorner = -1;
    private float appliedRenderScale = -1f;
    private int appliedDockCorner = -1, appliedDockCount = -1, appliedIconSize = -1;
    private int appliedDockIcon = -1;
    /** Dock 当前是否竖排在左侧（false = 底部横排）。布局、格宽、翻页判定都看它 */
    private boolean dockVertical;
    /** 两端面板是按哪个方向建的；换向时 LayoutParams 和可用宽高都得重算 */
    private Boolean panelsBuiltVertical;
    private int appliedListColumns = -1;
    /** 当前已应用的性能模式：从设置页切回来只在这一项变化时重刷全部外观（默认低性能） */
    private boolean appliedPerfLow = true;
    /**
     * 当前已应用的深浅色：设置页改「外观模式」时只改 Ui.dark 并重建设置页自己，
     * 桌面这边返回时走 applySettings() 的增量刷新 —— 它按各配置项比对，没有一项跟
     * Ui.dark 挂钩，于是 Dock 玻璃、画中画（虚拟桌面）玻璃底都停在旧配色。
     * 这里存一份已应用的深浅色，变了就整块重刷。
     */
    private boolean appliedDark = true;
    /** 应用列表「应用名称背景」开关的上次值：变了要重绑列表，否则名字底板不出现/不消失 */
    private boolean appliedNamePlate = false;
    /** 当前已应用的方向（旋转后要重建画中画，但切深浅色不能重建） */
    private int appliedOrientation = Configuration.ORIENTATION_UNDEFINED;
    private boolean appliedStatusBar = true;
    private int appliedSlotCount = -1, appliedSlotWidth = -1, appliedSlotCells = -1, appliedSidePad = -1;
    private GridLayoutManager appsGlm;
    private int listSpan = 4;

    /** 调试入口用（DebugTapReceiver 直接驱动界面自测；无手指/无 adb input 时靠它） */
    static MainActivity instance;

    /**
     * 当前活着的桌面实例。正常只有一个；但车机上系统会再起一个（见 reapDuplicateInstances），
     * 所以这里必须用列表而不是单个静态引用 —— 只看 instance 的话，被顶掉的旧实例
     * 就彻底失联，它的虚拟屏和自愈线程会一直留在后台抢屏内应用。
     */
    private static final java.util.ArrayList<java.lang.ref.WeakReference<MainActivity>> liveInstances =
            new java.util.ArrayList<>();

    private static synchronized void trackInstance(MainActivity a) {
        for (int i = liveInstances.size() - 1; i >= 0; i--) {
            MainActivity o = liveInstances.get(i).get();
            if (o == null || o.isDestroyed()) liveInstances.remove(i);
        }
        liveInstances.add(new java.lang.ref.WeakReference<>(a));
    }

    private static synchronized void untrackInstance(MainActivity a) {
        for (int i = liveInstances.size() - 1; i >= 0; i--) {
            MainActivity o = liveInstances.get(i).get();
            if (o == null || o == a) liveInstances.remove(i);
        }
    }

    /**
     * 回收重复的桌面实例（**这就是"蓝牙一放歌，两块画中画一起卡死"的元凶**）。
     *
     * 车机现场抓到的状态：system_server 里同时存在两个 com.cz.richdesk/.MainActivity
     * （task 1800 与 task 1806），各自建了 3 块虚拟屏（PipDesk-0/1/2 各两代，
     * display 58/59/60 + 61/62/63 六块并存）。于是同一个屏内应用有两个"主人"：
     * 旧实例的 pane0 隔几秒 launchOn(58)、新实例的 pane0 隔几秒 launchOn(61)，
     * 谁后动手谁把 activity 搬过去 —— 每次搬家都是一次 pause/stop。
     * 结果 task 在 58 ↔ 61 之间来回跳，两边虚拟屏都只剩最后一帧 = 用户说的"卡死"，
     * 而且自愈越勤、抖得越厉害。
     *
     * 处理：只保留最后起来的那个实例，其余立刻 finish —— onDestroy 里的 pip.release()
     * 会把它们的虚拟屏真正释放掉，屏内应用的 task 归到一层屏上，画面才能动起来。
     */
    private static void reapDuplicateInstances(final MainActivity keep) {
        final java.util.ArrayList<MainActivity> dup = new java.util.ArrayList<>();
        synchronized (MainActivity.class) {
            for (int i = liveInstances.size() - 1; i >= 0; i--) {
                MainActivity o = liveInstances.get(i).get();
                if (o == null || o == keep || o.isDestroyed()) continue;
                dup.add(o);
            }
        }
        for (final MainActivity o : dup) {
            Log.w("PipDesk", "发现第二个桌面实例，回收旧实例（它的虚拟屏会一并释放）");
            FileLog.append("DUP-INSTANCE 回收旧桌面实例 task=" + (o.getTaskId())
                    + " 保留=" + keep.getTaskId() + " @ " + FileLog.nowStamp());
            o.runOnUiThread(() -> {
                try { o.finish(); } catch (Throwable ignored) { }
            });
        }
    }

    /**
     * 供 PipPane 调用：屏内应用的 task 落在**另一块虚拟屏**上（既不是本屏也不是主屏），
     * 说明进程里还有第二套画中画在抢它 —— 立刻回收重复实例，别在这儿死搬 task。
     */
    static void reclaimDuplicateInstances() {
        MainActivity cur = instance;
        if (cur != null && !cur.isDestroyed()) reapDuplicateInstances(cur);
    }

    void debugPage(int page) {
        if (pager != null) pager.setCurrentPageSmooth(page);
    }

    boolean debugSwap(int pos, int dir) {
        boolean ok = pip != null && pip.swap(pos, dir);
        toast(ok ? ("画中画 " + (pos + 1) + (dir < 0 ? " 前移" : " 后移")) : "调换失败");
        return ok;
    }

    void debugClose(int pos) {
        if (pip != null) pip.close(pos);
    }

    void debugFullScreen(String pkg) {
        if (pip != null) pip.launchFullScreen(pkg);
    }

    void debugShowPicker() {
        showPicker(-1, 0);
    }

    /** 调试用：直接弹出某个画中画的长按菜单（无手指时验证菜单尺寸） */
    void debugShowMenu(int pos) {
        if (pip != null) showPaneMenu(pip.pane(pos));
    }

    /** 调试用：把应用开到指定画中画（多虚拟屏触摸测试用） */
    void debugOpen(String pkg, int paneIndex) {
        openPip(pkg, paneIndex);
    }

    /**
     * 调试用：导出取样用的壁纸缩略图，并把当前可见标签的「取样矩形 + 测到的亮度 + 结论」打出来。
     * 用来核对字色判定是否准：日志里 lum 与实际背景一对比就知道阈值偏了没有。
     */
    void debugWallTint() {
        android.util.Log.i("PipDesk", "WPDUMP " + WallTint.dumpThumb(this));
        dumpWallText(rvApps);
    }

    private void dumpWallText(View v) {
        if (v == null) return;
        if (v instanceof WallText) {
            WallText w = (WallText) v;
            float lum = w.sampleNow();
            android.util.Log.i("PipDesk", String.format("WPTEXT '%s' box=%s lum=%.3f -> %s",
                    w.getText(), w.boxText(), lum,
                    lum < 0 ? "n/a" : (WallTint.isBrightLum(lum) ? "黑字" : "白字")));
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpWallText(g.getChildAt(i));
        }
    }

    /** 调试用：重启某个画中画上的应用（验证卡死急救通道） */
    void debugRestart(int pos) {
        PipPane p = pip == null ? null : pip.pane(pos);
        if (p != null) p.restartApp();
    }

    /** 调试用：直接切分屏比例（0 等宽 / 1 = 1:2 / 2 = 2:1 / 3 恢复全部），无手指时验证布局与动画 */
    void debugPreset(int mode) {
        if (pip == null) return;
        if (mode == 3) {
            pip.resetRatio();
            return;
        }
        pip.applyPreset(0, mode);
    }

    /** 调试用：模拟单击某块空白画中画（弹应用列表并记默认应用） */
    void debugTapEmpty(int pos) {
        PipPane p = pip == null ? null : pip.pane(pos);
        if (p != null) openPickerForPane(p);
    }

    /** 调试用：打印当前分屏比例 */
    void debugRatios() {
        if (pip == null) return;
        android.util.Log.i(TAG, "RATIOS " + java.util.Arrays.toString(pip.weights())
                + " visible=" + pip.visibleCount() + "/" + pip.count());
    }

    /** 调试用：直接改画中画屏数（无手指时验证多分屏布局） */
    void debugPaneCount(int n) {
        Prefs.setPaneCount(this, n);
        applySettings();
        toast("画中画屏数 = " + Prefs.getPaneCount(this));
    }

    /** 调试用：模拟拖动第 pair 条玻璃分隔条（横屏左右、竖屏上下） */
    void debugDrag(int pair, int delta) {
        if (pip != null) pip.debugDrag(pair, delta);
    }

    /** 调试用：模拟轻点第 pair 条玻璃分隔条（交换两侧画中画） */
    void debugTapHandle(int pair) {
        if (pip != null) pip.debugTapHandle(pair);
    }

    /** 调试用：执行一个内置快捷方式（验证 重启桌面 / 返回 / 主页） */
    void debugBuiltin(String pkg) {
        if (pkg != null) runBuiltin(pkg);
    }

    /** 调试用：直接触发 Dock 第 pos 格的长按菜单（无手指时验证菜单项） */
    void debugDockLongClick(int pos) {
        if (pos >= 0 && pos < dockPkgs.size()) onDockLongClick(pos, dockPkgs.get(pos));
    }

    /** 调试用：注入按键（本机 adb shell input 被禁，唤醒屏幕/回主页都靠它） */
    void debugKey(int keyCode) {
        Keys.send(this, keyCode);
    }

    /**
     * 桌面窗口要不要"放弃输入焦点"。默认**保持可聚焦**（0.22.8 实测：放弃焦点并不能
     * 阻止那次全局 pause，反而让桌面自己的按键/输入法链路更绕）。
     * 现场对照用：/data/local/tmp/pipdesk_desk_nofocus 存在 → 桌面窗口设 FLAG_NOT_FOCUSABLE。
     */
    private static boolean deskGivesUpFocus() {
        try {
            return new java.io.File("/data/local/tmp/pipdesk_desk_nofocus").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 开机 / 重启桌面都重新计时：车机不连 adb，靠落盘日志看「首帧到底等多久」
        bootT0 = android.os.SystemClock.uptimeMillis();
        bootHeavyDone = false;
        bootGeneration++;
        // 启动底色只服务于「系统的 starting window」：它在桌面进程起来之前就画好，
        // 负责盖住开机时的最近任务界面。真实窗口一创建就**一次性**换回透明，
        // 之后绝不再动窗口背景。0.17.12 把这一步放在首帧回调里，赶上 Activity
        // 反复重建就变成「深色 ↔ 透明」来回切 —— 用户看到的持续闪烁。
        // 不透明模式（性能开关）：窗口 format 必须在 setContentView 之前定，
        // 否则 ViewRootImpl 已经按 TRANSLUCENT 建好 surface，改背景只会变成
        // "不透明色打底 + 依旧 alpha 混合"，白忙一场。
        if (Prefs.opaqueWindow(this)) {
            getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    0xFF0B0F14));
        } else {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
        }
        // 桌面窗口要不要放弃输入焦点。默认不放弃 —— 0.22.8 实测过：放弃焦点并不能
        // 阻止那次全局 pause（放手后照样冻），所以只留作现场对照开关。
        if (deskGivesUpFocus()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        // 全局绘制开关必须早于任何 Ui.glass / Ui.clipRounded 调用
        Ui.syncPerfMode(this);
        instance = this;
        trackInstance(this);
        reapDuplicateInstances(this);   // 系统偶尔会把桌面再起一个实例（见方法注释）
        AppState.app = getApplicationContext();   // 语音链路要一个不依赖 Activity 的上下文
        FileLog.start(this);       // 日志落盘（排查车机问题用），越早启动越完整
        Watchdog.start();          // 主线程卡死自动 dump 堆栈，车机上不用连 adb 也能取到现场
        // 数据目录自愈（可能要跑递归 chcon，秒级）：丢后台线程，别挡启动首帧
        new Thread(() -> DataDirFix.ensure(MainActivity.this), "pipdesk-datafix").start();
        // 铺满全屏：否则隐藏状态栏后窗口也不会往上长，画中画顶部永远差一条状态栏的高度
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        appliedStatusBar = Prefs.showStatusBar(this);
        applyStatusBar(appliedStatusBar);

        pager = new SimplePager(this);
        pager.addView(buildHomePage());
        pager.addView(buildAppListPage());
        pager.setCurrentPage(0);
        // 翻页闸门（需求）：主页只能在 Dock 上左滑去应用页；应用页整页右滑回主页
        pager.setSwipeGate(new SimplePager.SwipeGate() {
            @Override public boolean allowSwipe(int fromPage, boolean toNext, float x, float y) {
                if (fromPage == 0) {
                    if (!toNext) return false;   // 主页不能右滑（右边没有页）
                    // 只允许从 Dock 区域起手：底部模式看 y，左侧模式看 x
                    return inDock(x, y);
                }
                return !toNext;                  // 应用页：整页右滑回主页，左滑不翻
            }

            @Override public float strictness(float x, float y) {
                // Dock 区：手指本来就贴在那儿点图标，稍微一碰就翻页太容易误触。
                // 2.2 倍位移门槛 + 2.2 倍速度门槛，正常「甩一下」照样能翻。
                return inDock(x, y) ? 2.2f : 1f;
            }

            private boolean inDock(float x, float y) {
                if (dockWrap == null || dockWrap.getWidth() <= 0) return false;
                // 左侧竖排：Dock 贴在左边缘，起手点 x 落在它的宽度内即可；
                // 底部横排：Dock 顶沿 = pager 高 - dockWrap 高。
                return dockVertical ? x <= dockWrap.getWidth()
                        : y >= pager.getHeight() - dockWrap.getHeight();
            }
        });        FrameLayout root = new FrameLayout(this);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 铺满全屏后所有系统栏留白都得自己算：横屏时导航栏在侧边，竖屏时在底部
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(WindowInsets.Type.systemBars());
                l = sb.left;
                t = sb.top;
                r = sb.right;
                b = sb.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            // 四个间距统一 = Ui.GAP_DP：屏↔上沿 / 屏↔Dock / 屏↔屏 / Dock↔下沿
            int g = gapPx();
            v.setPadding(l + g, (appliedStatusBar ? t : 0) + g, r + g, b + g);
            return insets;
        });
        setContentView(root);
        // 不透明模式下系统不再垫壁纸，得自己画。异步解码：壁纸动辄几 MB，
        // 在 onCreate 里同步取会把首帧推迟几百毫秒。取到之前先用兜底底色。
        if (Prefs.opaqueWindow(this)) {
            final android.view.View bgRoot = root;
            new Thread(() -> {
                final android.graphics.drawable.Drawable w = Ui.wallpaperCover(getApplicationContext());
                if (w == null) {
                    Log.w("PipDesk", "不透明窗口：拿不到壁纸，保留兜底底色");
                    return;
                }
                bgRoot.post(() -> bgRoot.setBackground(w));
            }, "wallpaper-bg").start();
        }
        root.requestApplyInsets();

        // 调试钩子：以 uid 1000 执行一条系统命令（调分辨率/密度用）
        //   --es debug_wm "wm size reset; wm density reset"
        android.content.Intent wmIntent = getIntent();
        if (wmIntent != null && wmIntent.hasExtra("debug_wm")) {
            final String wmCmd = wmIntent.getStringExtra("debug_wm");
            wmIntent.removeExtra("debug_wm");
            new Thread(() -> {
                StringBuilder out = new StringBuilder();
                boolean ok = false;
                try {
                    Process p = new ProcessBuilder("sh", "-c", wmCmd)
                            .redirectErrorStream(true).start();
                    p.getOutputStream().close();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append(" / ");
                    ok = p.waitFor() == 0;
                } catch (Throwable t) {
                    out.append("EX ").append(t);
                }
                android.util.Log.i("PipDesk", "debug_wm [" + wmCmd + "] ok=" + ok + " out=" + out);
                final boolean fok = ok;
                runOnUiThread(() -> Toast.makeText(this,
                        "wm " + (fok ? "成功" : "失败") + "：" + wmCmd, Toast.LENGTH_LONG).show());
            }, "debug-wm").start();
        }

        // 调试钩子：没有手指（自动化截图核对）时也能把长按浮层菜单弹出来看效果
        //   --es debug_menu dock    → Dock 第一个图标的菜单
        //   --es debug_menu panel   → 左端功能区第一个图标的菜单
        android.content.Intent dbgIntent = getIntent();
        if (dbgIntent != null && dbgIntent.hasExtra("debug_menu")) {
            final String what = dbgIntent.getStringExtra("debug_menu");
            dbgIntent.removeExtra("debug_menu");
            root.postDelayed(() -> {
                if ("dock".equals(what) && !dockPkgs.isEmpty()) {
                    OverlayMenu.setAnchor(rvDock != null && rvDock.getChildCount() > 0
                            ? rvDock.getChildAt(0) : null);
                    onDockLongClick(0, dockPkgs.get(0));
                } else if ("panel".equals(what) && dockLeftBox != null
                        && dockLeftBox.getChildCount() > 0) {
                    List<String> dl = Prefs.getDockLeft(this);
                    if (!dl.isEmpty()) {
                        showPanelIconMenu(true, dl.get(0), dockLeftBox.getChildAt(0));
                    }
                }
            }, 1500);
        }

        // 调试钩子：没有手指（自动化截图核对）时也能翻页
        //   --ei debug_page 1 → 直接翻到应用列表页
        if (dbgIntent != null && dbgIntent.hasExtra("debug_page")) {
            final int which = dbgIntent.getIntExtra("debug_page", 0);
            dbgIntent.removeExtra("debug_page");
            root.postDelayed(() -> pager.setCurrentPage(which), 900);
        }

        // 调试钩子：无手指时直接在画中画里开一个应用，验证虚拟屏链路
        //   --es debug_voice 1 [--ei debug_voice_at 毫秒] → 触发一次聆听；
        //   延时给足（如 6000）可让应用列表先就绪，验证热词表加载路径
        if (dbgIntent != null && dbgIntent.hasExtra("debug_voice")) {
            dbgIntent.removeExtra("debug_voice");
            final long vDelay = dbgIntent.getIntExtra("debug_voice_at", 900);
            dbgIntent.removeExtra("debug_voice_at");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> Voice.get().listenOnce(), vDelay);
        }
        //   --ei debug_mode 0/1/2 → 临时设置「打开方式」（0 活动画中画 1 全屏 2 单画），
        //   配合 debug_open 自动化验证单画链路
        if (dbgIntent != null && dbgIntent.hasExtra("debug_mode")) {
            final int mode = dbgIntent.getIntExtra("debug_mode", 0);
            dbgIntent.removeExtra("debug_mode");
            Prefs.setOpenMode(this, mode);
            Log.i("PipDesk", "debug_mode 打开方式=" + mode);
        }
        //   --es debug_open <包名> [--ei debug_open_pane 0]
        if (dbgIntent != null && dbgIntent.hasExtra("debug_open")) {
            final String pkg = dbgIntent.getStringExtra("debug_open");
            final int pane = dbgIntent.getIntExtra("debug_open_pane", 0);
            dbgIntent.removeExtra("debug_open");
            dbgIntent.removeExtra("debug_open_pane");
            root.postDelayed(() -> {
                Log.i("PipDesk", "debug_open " + pkg + " pane=" + pane);
                openPip(pkg, pane);
            }, 1600);
        }

        // 调试钩子：先开一个应用，等一下再模拟点一次「返回」（验证单画收尾）
        //   --es debug_open <包名> [--ei debug_open_pane 0] --ei debug_back_at 4000
        if (dbgIntent != null && dbgIntent.getIntExtra("debug_back_at", 0) > 0) {
            final int at = dbgIntent.getIntExtra("debug_back_at", 0);
            dbgIntent.removeExtra("debug_back_at");
            root.postDelayed(() -> {
                Log.i("PipDesk", "debug_back");
                sendBack();
            }, at);
        }

        // 调试钩子：把系统任务表 dump 进日志（判断"应用退出/到后台"要靠它，先确认本机拿得到什么）
        //   --es debug_task <包名>
        if (dbgIntent != null && dbgIntent.hasExtra("debug_task")) {
            final String pkg = dbgIntent.getStringExtra("debug_task");
            dbgIntent.removeExtra("debug_task");
            new Thread(() -> {
                String dump = PipPane.debugDumpTasks(MainActivity.this, pkg);
                Log.i("PipDesk", "DBG " + dump);
                FileLog.append("DBG " + dump);
            }, "pipdesk-dbgtask").start();
        }

        if (!DataDirFix.ok()) {
            Toast.makeText(this, "配置暂时存不住：请安装权限模块后重启手机",
                    Toast.LENGTH_LONG).show();
        }

        appliedPaneCount = Prefs.getPaneCount(this);
        appliedOrientation = getResources().getConfiguration().orientation;
        appliedDpi = Prefs.getDpi(this);
        // 「屏内渲染分辨率」重新开放：比例现在记在虚拟屏槽位上，比例一变就换屏重建，
        // 不会再出现"画面缩到左上角"（根因是 resize 不会让屏内应用重新 measure）。
        appliedRenderScale = Prefs.getRenderScale(this) / 100f;
        appliedPipCorner = Prefs.getPipCorner(this);
        appliedDockCorner = Prefs.getDockCorner(this);
        appliedDockCount = Prefs.getDockCount(this);
        appliedDockIcon = Prefs.getDockIcon(this);
        appliedIconSize = Prefs.getIconSize(this);
        appliedListColumns = Prefs.getListColumns(this);
        appliedPerfLow = Prefs.perfLow(this);
        appliedNamePlate = Prefs.appNamePlate(this);

        pip = new PipManager(this, paneBox);
        pip.setListener(new PipManager.Listener() {
            @Override public void onOpened(int index, String pkg) { }
            @Override public void onClosed(int index) { }
            /**
             * 运行时新建的面板（目前只有单画专用屏）必须在这里补绑手势。
             * 漏掉的话那块屏就点不动 —— 详见 bindPaneGesture 的注释。
             */
            @Override public void onPaneCreated(PipPane pane) {
                bindPaneGesture(pane);
            }
        });
        pip.build(appliedPaneCount);   // 首帧要看到画中画玻璃框，这一步必须同步
        bindPaneGestures();
        pip.setPaneLongClickListener(this::showPaneMenu);
        // 卡死时把各画中画的 display/尺寸/包名一起写进日志：能立刻看出是
        // "某块屏没建出来" 还是 "三块屏都正常但渲染卡住了"
        Watchdog.setProbe(() -> {
            if (pip == null) return "(pip 未初始化)";
            StringBuilder b = new StringBuilder();
            b.append("paneCount=").append(pip.count()).append(' ');
            for (int i = 0; i < pip.count(); i++) {
                b.append("[p").append(i).append(" ")
                        .append(pip.pkgAt(i) == null ? "idle" : pip.pkgAt(i))
                        .append(' ').append(pip.displaySize(i)).append("] ");
            }
            b.append("page=").append(pager.getCurrentPage());
            return b.toString();
        });
        dockPkgs.addAll(Prefs.getDock(this));   // 首帧 Dock 就要有图标
        // 新版「应用列表」按钮：升级后自动补一个到 Dock 末尾，装上就能看到。
        // 只补一次（之后长按移除/拖动都不会被强行加回来）；Dock 满格时先把格数 +1，
        // 绝不挤掉用户已有图标；格数已到上限才放弃，用户可在设置里腾位后手动加。
        if (!Prefs.appListButtonAdded(this)
                && !dockPkgs.contains(AppInfo.APP_LIST_PKG)) {
            int count = Prefs.getDockCount(this);
            boolean room = dockPkgs.size() < count;
            if (!room && count < Prefs.DOCK_MAX) {
                count++;
                Prefs.setDockCount(this, count);
                appliedDockCount = count;
                room = true;
            }
            if (room) {
                dockPkgs.add(AppInfo.APP_LIST_PKG);
                Prefs.setDock(this, dockPkgs);
                Prefs.setAppListButtonAdded(this);
                if (dockAdapter != null) dockAdapter.notifyDataSetChanged();
                if (rvDock != null) rvDock.post(this::applyDockSlots);
                Log.i(TAG, "已自动补上「应用列表」按钮（Dock 格数=" + count + "）");
            } else {
                Log.i(TAG, "Dock 已满格，未自动补「应用列表」按钮");
            }
        }

        // 深浅色外观：先按手动/系统模式立即生效，壁纸亮度在后台精确算完后刷新
        Ui.updateAppearance(this);
        appliedDark = Ui.dark;
        // 壁纸亮度后台重算完成 → 主线程回这里比对并刷新（不再在主线程等解码）
        Ui.onWallpaperRechecked = this::refreshAppearanceIfChanged;

        // 其余全部推迟到首帧之后：调度放在 onResume（真正确认到前台之后），
        // 见 runBootWork()。onCreate 越短，开机时桌面出现得越早。
    }

    /**
     * 连等两帧再干重活。
     *
     * frameCallback 只可能发生在 onCreate / onResume 返回、主线程回到消息循环之后；
     * 再等一帧，确保首帧 traversal 走完、画面已经提交，这时才启动常驻监控、
     * 才去建虚拟屏拉默认应用。窗口底色不在这里动（见 onCreate）。
     */
    private void scheduleBootWork() {
        final android.view.Choreographer ch = android.view.Choreographer.getInstance();
        final int gen = bootGeneration;
        ch.postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                ch.postFrameCallback(t -> {
                    if (gen == bootGeneration) runBootWork();
                });
            }
        });
    }

    /** 首帧之后才做的事：这些活以前全塞在 onCreate 里，直接推迟了桌面出现的时间 */
    private void runBootWork() {
        // 不在前台就作废这次（不标记完成），等下次 onResume 重新调度。
        // 0.17.12 没做这个检查，Activity 已 onStop 仍继续建虚拟屏，
        // 每重建一次就多留两块虚拟屏 → 显示服务反复调刷新率 → 屏幕一直闪。
        if (isFinishing() || isDestroyed() || !AppState.mainResumed) return;
        if (bootHeavyDone) return;
        bootHeavyDone = true;
        final int gen = bootGeneration;

        long ms = android.os.SystemClock.uptimeMillis() - bootT0;
        Log.i(TAG, "BOOT onCreate→首帧 " + ms + "ms");
        FileLog.append("BOOT onCreate→首帧 " + ms + "ms");

        // 触摸注入服务预热会 fork app_process，跟首帧抢 CPU，挪到首帧后
        if (pip != null) pip.primeTouch();

        // 常驻监控 / 测试悬浮窗
        PaneGuard.start(this, pip);
        AudioProbe.start(this, pip);
        PerfMon.start(this, pip);
        PerfOverlay.apply(this, pip);       // 性能悬浮窗：按设置开关恢复显示
        if (PerfOverlay.isShown()) UiJank.start();   // 桌面重建后重挂主线程抖动监测

        loadApps();                        // PackageManager 全量扫描 + 图标解码（内部走后台线程）

        // 开机不再 syncSystemNightMode：改 uiMode 会触发全局配置变化，
        // 把刚拉起的屏内应用销毁重建，还会连带重建桌面本身（闪烁放大器）。
        // 用户在设置里改外观时本来就会同步，开机这一下纯属添乱。
        new Thread(() -> {
            SunTimes.refreshIfStale(getApplicationContext());   // 「跟随时间」需要日落/日出
            Ui.updateAppearance(getApplicationContext());
            runOnUiThread(this::refreshAppearance);
        }, "pipdesk-appearance").start();
        // 应用名的黑白，按「壁纸那一块的实际颜色」定；壁纸缩略图后台取一次
        WallTint.setOnReady(this::refreshAppearance);
        WallTint.load(this);
        registerWallpaperWatcher();
        registerThemeTicker();

        // 默认应用是启动最重的一步：建虚拟屏 + 跨进程拉起应用。
        // 必须确认桌面还在前台、且这次启动没被重建取代，否则宁可不拉 ——
        // 后台建出来的虚拟屏没人回收，是 displays=5/面板=2 的来源。
        dockHandler.postDelayed(() -> {
            if (!isDestroyed() && gen == bootGeneration && AppState.mainResumed) {
                openDefaults();
            }
        }, 400);
    }

    /**
     * 「跟随时间」靠系统每分钟一次的 TIME_TICK 推进：
     * 到点只比较 Ui.dark 有没有变，变了才刷界面（不动其他状态）。
     * 跨天时顺带把日落/日出重新拉一次。
     */
    private void registerThemeTicker() {
        themeTicker = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                checkThemeTick();
            }
        };
        registerReceiver(themeTicker,
                new android.content.IntentFilter(Intent.ACTION_TIME_TICK));
        // 跨天刷新日落时间：每分钟检查一次日期，变了就后台重拉
        sunDay = currentDay();
    }

    private void checkThemeTick() {
        if (Prefs.getThemeMode(this) != Prefs.THEME_TIME) return;
        int day = currentDay();
        if (day != sunDay) {
            sunDay = day;
            new Thread(() -> {
                SunTimes.refreshIfStale(getApplicationContext());
                runOnUiThread(this::refreshAppearanceIfChanged);
            }, "pipdesk-sun").start();
            return;
        }
        refreshAppearanceIfChanged();
    }

    private void refreshAppearanceIfChanged() {
        boolean was = Ui.dark;
        Ui.updateAppearance(this);
        if (was != Ui.dark) {
            // 定时/跟随安卓换色时，系统夜间模式一起同步：屏内应用才会跟着变深/变浅
            syncSystemNightMode(Ui.dark);
            refreshAppearance();
        }
    }

    /**
     * 把深浅色同步给系统（UiModeManager）。
     *
     * 受「屏内应用跟随深浅色」开关控制（默认关，见 Ui.applySystemNightMode）：
     * 全局 uiMode 变更会销毁重建屏内应用，实测 QQ 直接崩溃，用户看到的就是
     * 「切个深浅色，画中画里的应用被切走了」。开关打开时才同步，且同步完
     * 延迟 2s 逐屏校验：被挪走的搬回、没了的重新拉起（PipPane.reensureTask）。
     */
    private void syncSystemNightMode(boolean dark) {
        if (!Prefs.syncAppsNightMode(this)) return;
        Ui.applySystemNightMode(this, dark);
        if (pip == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(pip::reensureAll, 2000);
    }

    private static int currentDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000
                + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    private android.content.BroadcastReceiver themeTicker;
    private int sunDay;

    /** 壁纸变化 → 重新测亮度 → 外观跟着变（浅色壁纸自动换成浅玻璃 + 深色字） */
    private void registerWallpaperWatcher() {
        wallpaperReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                Ui.invalidateWallpaper();
                WallTint.invalidate(getApplicationContext());
                new Thread(() -> {
                    Ui.updateAppearance(getApplicationContext());
                    runOnUiThread(MainActivity.this::refreshAppearance);
                }, "pipdesk-appearance").start();
            }
        };
        registerReceiver(wallpaperReceiver,
                new android.content.IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
    }

    private android.content.BroadcastReceiver wallpaperReceiver;

    // ------------------------------------------------------------ 首页布局

    private View buildHomePage() {
        pageHome = new LinearLayout(this);
        pageHome.setOrientation(LinearLayout.VERTICAL);
        // 边距全部由 root 的 insets 统一给（最小边距 Ui.GAP_DP），这里不再叠自己的 padding

        paneBox = new PaneBox(this);
        paneBox.setHorizontal(isLandscape());
        pageHome.addView(paneBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dockWrap = new FrameLayout(this);
        // Dock ↔ 画中画的间距 = 画中画之间的缝（Ui.PANE_GAP_DP），需求：两处永远一致
        dockWrap.setPadding(0, paneGapPx(), 0, 0);

        // Dock 一行 = [左功能区][图标区][右功能区]；玻璃铺在整行上，三段共用一条连续的玻璃
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        int dp8 = Ui.dp(this, 8);
        dockRow.setPadding(dp8, dp8, dp8, dp8);
        dockRow.setBackground(Ui.glass(this, appliedDockCorner < 0 ? Prefs.getDockCorner(this) : appliedDockCorner));

        // 左端功能区：默认放自建快捷图标（返回/主页/息屏/音量…），也可改成时间+天气
        dockLeftBox = new LinearLayout(this);
        dockLeftBox.setOrientation(LinearLayout.HORIZONTAL);
        dockLeftBox.setGravity(Gravity.CENTER_VERTICAL);
        dockLeftBox.setVisibility(View.GONE);
        // 右端功能区：默认时间 + 天气，也可改成自建快捷图标
        dockRightBox = new LinearLayout(this);
        dockRightBox.setOrientation(LinearLayout.HORIZONTAL);
        dockRightBox.setGravity(Gravity.CENTER_VERTICAL);
        dockRightBox.setVisibility(View.GONE);

        // 玻璃竖线：两块功能区和中间图标区之间的分隔。
        // 用竖向渐变（上下淡出）画一条 2dp 的细线，看着像玻璃切面，而不是生硬的实线。
        dockLeftDiv = new View(this);
        dockLeftDiv.setBackground(glassDivider());
        dockLeftDiv.setVisibility(View.GONE);
        dockRightDiv = new View(this);
        dockRightDiv.setBackground(glassDivider());
        dockRightDiv.setVisibility(View.GONE);

        rvDock = new RecyclerView(this);
        rvDock.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvDock.setHasFixedSize(true);       // 格子数变化走 adapter 通知，省整表 requestLayout
        rvDock.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rvDock.setClipToPadding(false);
        rvDock.setBackground(null);          // 玻璃在 dockRow 上（三段共用）
        // 添加入口：长按 Dock 应用区的**空白处**（有没有图标都生效 ——
        // 触摸先分给图标格子，格子不消费才落到 RecyclerView，所以和图标长按天然并存）。
        // 注意：空 RecyclerView 的触摸走它自己的手势处理，不会触发 view 级长按 ——
        // 所以这里必须用 GestureDetector 识别长按，setOnLongClickListener 永远不响。
        final android.view.GestureDetector dockGesture = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public void onLongPress(MotionEvent e) {
                        onDockAdd();
                    }
                });
        rvDock.setOnTouchListener((v, e) -> {
            // 只有在应用栏真的「放不下」、必须自己滚动时才拦截翻页手势。
            // 判断用「可滚距离 > 12dp」而不是 canScrollXXX：后者常常因为
            // 一两个像素的取整余量就返回 true，结果在应用栏上横滑永远翻不到应用列表页。
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int range = dockVertical ? rvDock.computeVerticalScrollRange()
                        : rvDock.computeHorizontalScrollRange();
                int extent = dockVertical ? rvDock.computeVerticalScrollExtent()
                        : rvDock.computeHorizontalScrollExtent();
                if (range - extent > Ui.dp(this, 12)) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            dockGesture.onTouchEvent(e);
            return false;
        });
        // Dock 用配置高度：RecyclerView 在 wrap_content 下会被量成 0，
        // 内容就会溢出到画中画区域上（之前"格子跑到画中画底部"就是这个原因）
        // 需求 2：Dock 宽度与画中画等宽 —— 直接 MATCH_PARENT，格子由 padding 居中
        dockRow.addView(dockLeftBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 竖线只在功能区显示时占位：跟着功能区 GONE 一起消失，不留空白
        int divW = Math.max(1, Ui.dp(this, 2));
        int divInset = Ui.dp(this, 6);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                divW, ViewGroup.LayoutParams.MATCH_PARENT);
        divLp.topMargin = divInset;
        divLp.bottomMargin = divInset;
        dockRow.addView(dockLeftDiv, divLp);
        dockRow.addView(rvDock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                divW, ViewGroup.LayoutParams.MATCH_PARENT);
        divLp2.topMargin = divInset;
        divLp2.bottomMargin = divInset;
        dockRow.addView(dockRightDiv, divLp2);
        dockRow.addView(dockRightBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dockWrap.addView(dockRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageHome.addView(dockWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, effectiveDockHeight()) + paneGapPx()));

        dockAdapter = new DockAdapter(this, Prefs.getDockCorner(this));
        dockAdapter.setIconDp(Prefs.getDockIcon(this));
        rvDock.setAdapter(dockAdapter);
        dockAdapter.attachDragHelper(rvDock);   // 长按拖动排序（原地松手仍是菜单）
        // 按配置切「底部横排 / 左侧竖排」：必须在 refreshDockPanels 之前，
        // 功能区子 View 的 LayoutParams 要按 dockVertical 来建。
        applyDockSide(Prefs.dockOnLeft(this));
        refreshDockPanels();                     // 左右功能区按配置显示
        rvDock.post(this::applyDockSlots);
        dockWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyDockSlots());
        // rvDock 量完才拿到真实可用长度：首帧它还是旧方向的尺寸，而 dockWrap
        // 自身尺寸没变、不会再回调，必须单独听 rvDock 的排列轴变化（仅竖排）。
        rvDock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!dockVertical) return;
            if (b - t != ob - ot) applyDockSlots();
        });
        return pageHome;
    }

    /**
     * Dock 位置切换：底部横排 ↔ 左侧竖排。
     *
     * 只改 orientation / LayoutParams / LayoutManager，不重建 View —— 重建会销毁
     * PaneBox 里的 TextureView，虚拟屏跟着整屏重启（画中画会闪，屏内应用还会被
     * 搬回主屏）。重排时只 detach/attach dockWrap，paneBox 始终保持挂着。
     */
    private void applyDockSide(boolean left) {
        dockVertical = left;
        if (pageHome == null || paneBox == null || dockWrap == null || dockRow == null
                || rvDock == null || dockLeftBox == null || dockRightBox == null) return;

        // 主页：底部模式 = 上画中画 / 下 Dock；左侧模式 = 左 Dock / 右画中画。
        // HORIZONTAL 时子 View 按添加顺序从左到右，所以左侧模式要把 dockWrap 挪到第一位。
        pageHome.setOrientation(left ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int idx = pageHome.indexOfChild(dockWrap);
        if (left && idx > 0) {
            pageHome.removeView(dockWrap);
            pageHome.addView(dockWrap, 0);
        } else if (!left && idx == 0) {
            pageHome.removeView(dockWrap);
            pageHome.addView(dockWrap);
        }
        paneBox.setLayoutParams(new LinearLayout.LayoutParams(
                left ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                left ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1f));
        int thickness = Ui.dp(this, effectiveDockHeight()) + paneGapPx();
        dockWrap.setLayoutParams(new LinearLayout.LayoutParams(
                left ? thickness : ViewGroup.LayoutParams.MATCH_PARENT,
                left ? ViewGroup.LayoutParams.MATCH_PARENT : thickness));
        // 间距永远留在「朝向画中画」的那一侧：底部模式在上沿，左侧模式在右沿
        dockWrap.setPadding(0, left ? 0 : paneGapPx(), left ? paneGapPx() : 0, 0);

        dockRow.setOrientation(left ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockRow.setGravity(left ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);

        dockLeftBox.setOrientation(left ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockRightBox.setOrientation(left ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockLeftBox.setGravity(left ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        dockRightBox.setGravity(left ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        dockLeftBox.setLayoutParams(dockSideChildParams(left));
        dockRightBox.setLayoutParams(dockSideChildParams(left));

        int div = Math.max(1, Ui.dp(this, 2));
        int inset = Ui.dp(this, 6);
        dockLeftDiv.setLayoutParams(dividerParams(left, div, inset));
        dockRightDiv.setLayoutParams(dividerParams(left, div, inset));

        rvDock.setLayoutParams(new LinearLayout.LayoutParams(
                left ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                left ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        rvDock.setLayoutManager(new LinearLayoutManager(this,
                left ? LinearLayoutManager.VERTICAL : LinearLayoutManager.HORIZONTAL, false));
        if (dockAdapter != null) dockAdapter.setVertical(left);
        // 换向之后格宽是按另一条轴算的，数值可能与换向前完全相同。
        // 清掉 applyDockSlots 的去重缓存，否则它会 early-return，
        // rvDock 的留白仍留在旧方向（左右 padding 而不是上下）。
        appliedPitch = -1;
        appliedSlotCount = -1;
        appliedSlotWidth = -1;
        appliedSlotCells = -1;
        appliedSidePad = -1;
        applyDockHeight();
        // 面板子 View 的 LayoutParams 是按方向建的（竖排 col 宽撑满、高 = 格数×格宽）。
        // 换向不重建的话它仍带着旧方向的尺寸，字号也会按旧宽度算 → 文字被裁。
        if (panelsBuiltVertical == null || panelsBuiltVertical.booleanValue() != left) {
            buildDockPanel(dockLeftBox, true);
            buildDockPanel(dockRightBox, false);
            updateDockRealtime();
        }
        rvDock.post(this::applyDockSlots);
    }

    /** 两端功能区在 Dock 里的占位：横排占满高度、宽度自适应；竖排反之 */
    private LinearLayout.LayoutParams dockSideChildParams(boolean left) {
        return new LinearLayout.LayoutParams(
                left ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                left ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** 分隔线：横排是竖线（2dp 宽 + 上下留白），竖排是横线（2dp 高 + 左右留白） */
    private LinearLayout.LayoutParams dividerParams(boolean left, int div, int inset) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                left ? ViewGroup.LayoutParams.MATCH_PARENT : div,
                left ? div : ViewGroup.LayoutParams.MATCH_PARENT);
        if (left) { lp.leftMargin = inset; lp.rightMargin = inset; }
        else { lp.topMargin = inset; lp.bottomMargin = inset; }
        return lp;
    }

    private View buildAppListPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        WallText title = new WallText(this, true);
        title.setDim(true);
        appListTitle = title;
        title.setText("全部应用　·　点按＝画中画　长按＝更多");
        title.setTextSize(12);
        title.setGravity(Gravity.START);
        int padH = Ui.dp(this, 18);
        title.setPadding(padH, Ui.dp(this, 14), padH, Ui.dp(this, 8));
        page.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rvApps = new RecyclerView(this);
        listSpan = effectiveListColumns();
        appsGlm = new GridLayoutManager(this, listSpan);
        rvApps.setLayoutManager(appsGlm);
        rvApps.setHasFixedSize(true);
        rvApps.setClipToPadding(false);
        rvApps.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int p = Ui.dp(this, 8);
        rvApps.setPadding(p, 0, p, Ui.dp(this, 24));
        // 首次布局拿到真实宽度后，列数还要再收敛一次（构造时只能按屏幕宽度估）。
        // 不收敛的话：图标固定 dp + 列数固定 → 格子窄于图标 → 图标被列表边缘裁掉
        rvApps.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) applyListSpan();
        });
        page.addView(rvApps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        appListAdapter = new AppListAdapter(null, this, true, Prefs.getIconSize(this));
        rvApps.setAdapter(appListAdapter);
        // 名字底板是配置项，Adapter 本身不读配置：建好后立刻按当前值同步一次
        appListAdapter.setNamePlate(Prefs.appNamePlate(this));
        appListAdapter.setShortcutOrder(Prefs.getShortcutOrder(this));
        appListAdapter.attachDragHelper(rvApps);   // 长按拖动排序（原地松手仍是菜单）
        // 以前这里还挂了一个滚动监听，每 120ms 把每个可见名字重新按壁纸取色。
        // 字色现在只跟外观模式走（与位置无关），滚动重取样纯属白烧 CPU —— 一并去掉。
        return page;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * 实际列数：竖屏按配置；横屏按宽度自动加密（每格约 120dp）。
     * 横屏还用竖屏的 4 列会让图标大到离谱，一屏只能放两行。
     */
    /**
     * 实际列数：以用户在设置里选的为准，**但必须装得下图标**。
     *
     * 图标是硬性 dp、列数也是用户选的，两者在小屏/高列数组合下会打架：
     * 格子内宽窄于图标时，图标溢出格子 —— 第一列/最后一列被 RecyclerView
     * 边界裁掉一半，中间的又被邻格图标盖住（"图标显示不完整"）。
     * 所以列数以「格子最小宽 = 图标 + 内边距」封顶；真放不下时（fitIconDp）
     * 才允许缩图标，优先保住用户选的列数所对应的布局密度。
     */
    private int effectiveListColumns() {
        int iconDp = Prefs.getIconSize(this);
        return AppListAdapter.fitColumns(this, Prefs.getListColumns(this), iconDp, appListAvailPx());
    }

    /** 应用列表可用宽度（px）：布局后用真实值，之前用屏幕宽估（去掉列表左右 padding） */
    private int appListAvailPx() {
        int w = rvApps != null && rvApps.getWidth() > 0
                ? rvApps.getWidth() : getResources().getDisplayMetrics().widthPixels;
        return w - 2 * Ui.dp(this, 8);
    }

    /** 按当前配置重算列数（图标大小/方向/宽度变化后都要调） */
    private void applyListSpan() {
        int cols = effectiveListColumns();
        if (cols == listSpan) return;
        listSpan = cols;
        if (appsGlm != null) appsGlm.setSpanCount(cols);
        if (appListAdapter != null) appListAdapter.notifyDataSetChanged();
    }

    /**
     * Dock 高度**只由图标的 dp 决定**（图标 + 上下各 12dp），不再单独设置。
     *
     * 为什么砍掉「Dock 高度」这一项：两个独立的高度/尺寸设置互相打架 ——
     * 高度设小了就把图标削一刀，用户把两边设成同一个 dp 也看着不一样。
     * 现在只有「Dock 图标大小」一个真源，高度跟着它走，永远装得下。
     */
    private int effectiveDockHeight() {
        return Math.max(48, Prefs.getDockIcon(this) + 24);
    }

    private void applyDockHeight() {
        int h = effectiveDockHeight();
        if (dockWrap != null) {
            ViewGroup.LayoutParams lp = dockWrap.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                int thickness = Ui.dp(this, h) + paneGapPx();
                // 竖排时「Dock 高度」这个配置值其实是 Dock 的宽度；
                // 两个维度都写全，切换方向后不会留下另一个方向的旧尺寸。
                if (dockVertical) {
                    lp.width = thickness;
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                } else {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.height = thickness;
                }
                dockWrap.setLayoutParams(lp);
            }
        }
    }

    /** 全局统一的最小边距（px）：虚拟屏四周、屏间、Dock 上下都用它 */
    private int gapPx() {
        return Ui.dp(this, Ui.GAP_DP);
    }

    /**
     * 画中画之间的缝（px）。需求：Dock ↔ 画中画的间距必须等于它，
     * 所以 Dock 的上间距和容器高度都用这个值，别再单独取 GAP_DP。
     */
    private int paneGapPx() {
        return Ui.dp(this, Ui.PANE_GAP_DP);
    }

    // ------------------------------------------------------------ 状态栏

    /**
     * 遮罩息屏期间连状态栏一起藏掉（否则黑屏上还挂着时间/电量，既不像"熄屏"也刺眼）。
     * 恢复时按用户设置还原。
     */
    void sleepBars(boolean hide) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (hide) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                applyStatusBar(Prefs.showStatusBar(this));
                c.show(WindowInsets.Type.navigationBars());
            }
        } else {
            View d = getWindow().getDecorView();
            if (hide) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                applyStatusBar(Prefs.showStatusBar(this));
            }
        }
    }

    private void applyStatusBar(boolean show) {        androidx.appcompat.app.ActionBar bar = getSupportActionBar();
        if (bar != null) bar.hide();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (show) c.show(WindowInsets.Type.statusBars());
                else c.hide(WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View d = getWindow().getDecorView();
            if (show) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        }
        // 状态栏显隐变化后重新派发 insets，画中画的上沿才会跟着贴到状态栏下 / 顶上
        if (pageHome != null) pageHome.getRootView().requestApplyInsets();
    }

    // ------------------------------------------------------------ Dock 左右功能区

    /**
     * 按配置重建 Dock 左右两端的功能区（设置里改开关/内容后调用）。
     * 每一侧的内容都可以是「自建快捷图标」或「时间 + 天气」（见 Prefs.PANEL_*）。
     * 竖线跟着功能区一起显隐：不显示某侧功能区，那一侧也不留分隔线。
     */
    private void refreshDockPanels() {
        if (dockRow == null || dockLeftBox == null || dockRightBox == null) return;
        boolean left = Prefs.dockLeftEnabled(this);
        boolean right = Prefs.dockRightEnabled(this);
        dockLeftBox.setVisibility(left ? View.VISIBLE : View.GONE);
        dockRightBox.setVisibility(right ? View.VISIBLE : View.GONE);
        if (dockLeftDiv != null) dockLeftDiv.setVisibility(left ? View.VISIBLE : View.GONE);
        if (dockRightDiv != null) dockRightDiv.setVisibility(right ? View.VISIBLE : View.GONE);
        buildDockPanel(dockLeftBox, true);
        buildDockPanel(dockRightBox, false);
        updateDockRealtime();
        startDockTicker();
        applyDockSlots();
    }

    /** 建一侧功能区的内容（快捷图标 / 时间天气），两侧共用一套逻辑 */
    private void buildDockPanel(LinearLayout box, boolean left) {
        if (box == null) return;
        box.removeAllViews();
        panelsBuiltVertical = dockVertical;   // 面板按当前方向建，换向时由 applyDockSide 重建
        if (left) { dockClockL = null; dockWeatherL = null; dockGapL = null;
                    dockDateL = null; dockPanelAvailWL = 0; }
        else { dockClock = null; dockWeather = null; dockGap = null;
               dockDate = null; dockPanelAvailW = 0; }
        if (!(left ? Prefs.dockLeftEnabled(this) : Prefs.dockRightEnabled(this))) return;
        int type = left ? Prefs.getDockLeftType(this) : Prefs.getDockRightType(this);
        if (type == Prefs.PANEL_CLOCK) buildClockPanel(box, left);
        else buildShortcutPanel(box, left);
    }

    /**
     * 快捷功能区：最多 5 个自建图标（返回/主页/息屏/音量…）。
     *
     * · 格宽与中间图标区完全一致（同一个 dockPitch），图标都在各自格子里居中，
     *   两处图标的间距天然相同。
     * · 不留「＋」也不留空白格（需求：不要占一个空位）。添加入口挪到图标的
     *   长按菜单里（第一项「添加图标」）；只有在图标数为 0 时才留一格空白，
     *   否则面板宽度为 0 就没地方长按了。
     */
    private void buildShortcutPanel(LinearLayout box, boolean left) {
        final int pitch = dockPitchPx();
        final int ic = Ui.dp(this, Prefs.getDockIcon(this));
        List<String> pkgs = panelIcons(left);
        // 按「实际能占的格数」截断：多出来的图标先不显示（加大 Dock 格数就回来了）
        int cap = panelCellCount(left);
        if (cap <= 0) { box.setPadding(0, 0, 0, 0); return; }
        if (pkgs.size() > cap) {
            pkgs = new ArrayList<>(pkgs.subList(0, cap));
        }
        int padH = Ui.dp(this, 4);
        box.setPadding(padH, 0, padH, 0);
        // 面板级长按：长按**空白/间隙**弹「添加图标」选择框（和图标的长按菜单并存 ——
        // 触摸先分给图标格子，格子自己消费了长按，只有空白处才会落到面板上）
        box.setOnLongClickListener(v -> {
            pickPanelApp(left);
            return true;
        });
        for (final String pkg : pkgs) box.addView(shortcutIcon(pkg, left, ic, pitch));
        if (pkgs.isEmpty()) {
            // 一个图标都没有：留一格透明的长按区当作「添加」入口
            View blank = new View(this);
            blank.setLayoutParams(dockVertical
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pitch)
                    : new LinearLayout.LayoutParams(pitch, ViewGroup.LayoutParams.MATCH_PARENT));
            blank.setOnLongClickListener(v -> {
                pickPanelApp(left);
                return true;
            });
            box.addView(blank);
        }
    }

    /**
     * 一侧功能区「想要」占几格（不受总格数限制）：
     *   · 快捷功能：按图标数算（不留空白格），一个都没有时算 1 格；
     *   · 时间 + 天气：2 格（日期/时间/天气要够宽）；
     *   · 没启用：0 格。
     */
    private int rawPanelCells(boolean left) {
        boolean on = left ? Prefs.dockLeftEnabled(this) : Prefs.dockRightEnabled(this);
        if (!on) return 0;
        int type = left ? Prefs.getDockLeftType(this) : Prefs.getDockRightType(this);
        if (type == Prefs.PANEL_CLOCK) return 2;   // 时间天气最少占 2 格
        int n = panelIcons(left).size();
        return Math.max(1, Math.min(n, Prefs.PANEL_SHORTCUT_MAX));
    }

    /**
     * 一侧功能区在 Dock 里占几个格。
     *
     * 不再有「最多 5 个」的限制（需求）：图标有几个就占几格，格宽由 dockPitchPx
     * 统一分摊 —— 面板多了格宽变窄，中间图标区按格宽剩下多少算多少，但
     * dockPitchPx 的分母里始终包含 Dock 全部槽位数，所以中间那块至少还有 1 格的宽度。
     */
    private int panelCellCount(boolean left) {
        return rawPanelCells(left);
    }

    /** 一个快捷图标：固定格宽、图标居中（图标之间的间距由格宽决定，与中间图标区一致） */
    private View shortcutIcon(final String pkg, final boolean left, int ic, int pitch) {
        FrameLayout box = new FrameLayout(this);
        box.setLayoutParams(dockVertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pitch)
                : new LinearLayout.LayoutParams(pitch, ViewGroup.LayoutParams.MATCH_PARENT));
        // 全局关闭震感：必须在构建时就关（放到长按监听里第一下震动已经出去了）
        box.setHapticFeedbackEnabled(false);
        android.widget.ImageView iv = new MaskedIconView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        AppInfo ai = findApp(pkg);
        iv.setImageDrawable(ai != null ? ai.icon : getDrawable(android.R.drawable.sym_def_app_icon));
        box.addView(iv, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
        box.setOnClickListener(v -> {
            if (AppInfo.isBuiltin(pkg)) runBuiltin(pkg);
            else openPip(pkg, 0);
        });
        box.setOnLongClickListener(v -> {
            beginPanelDrag(left, pkg, box);
            return true;
        });
        box.setOnTouchListener((v, e) -> onPanelDragTouch(v, e, left));
        return box;
    }

    /** 功能区图标的长按菜单（长按即弹；拖动会把它关掉；添加入口也在这里） */
    private void showPanelIconMenu(final boolean left, final String pkg, View anchor) {
        OverlayMenu.show(this, anchor, labelOf(pkg),
                new CharSequence[]{"添加图标", "移到 Dock 图标区", "从功能区移除"},
                which -> {
                    if (which == 0) {
                        pickPanelApp(left);
                    } else if (which == 1) {
                        if (dockPkgs.size() >= Prefs.getDockCount(this)) {
                            toast("Dock 已满，可在设置里加大格数");
                            return;
                        }
                        if (!dockPkgs.contains(pkg)) dockPkgs.add(pkg);
                        Prefs.setDock(this, dockPkgs);
                        dockAdapter.notifyDataSetChanged();
                        applyDockSlots();
                        toast("已移到 Dock");
                    } else {
                        List<String> l = panelIcons(left);
                        l.remove(pkg);
                        setPanelIcons(left, l);
                        buildDockPanel(left ? dockLeftBox : dockRightBox, left);
                        applyDockSlots();
                        toast("已移除");
                    }
                });
    }

    /**
     * 长按：**立刻**把菜单弹出来（浮层，不抢手势），同时进入拖动待命。
     *
     * 手指接着拖动 → onPanelDragTouch 一检测到位移就把菜单收掉，继续换位；
     * 原地松手 → 菜单留着，用户直接点里面的项。这是用户要的交互顺序，
     * 之前那个「先等 280ms 再弹」的版本反了（他嫌逻辑不对）。
     */
    private void beginPanelDrag(boolean left, String pkg, View cell) {
        LinearLayout box = left ? dockLeftBox : dockRightBox;
        List<String> l = panelIcons(left);
        int idx = l.indexOf(pkg);
        if (idx < 0 || box == null) return;
        panelDragCells.clear();
        for (int i = 0; i < box.getChildCount(); i++) panelDragCells.add(box.getChildAt(i));
        panelDragList = l;
        panelDragOrigin = new ArrayList<>(l);   // 原始顺序：算位移用，避免多跨几格后累计错位
        panelDragLeft = left;
        panelDragView = cell;
        panelDragIndex = panelDragCur = idx;
        panelDragMoved = false;
        panelDragStartRawX = -1;   // 起手位置在第一次 MOVE 时才记（长按时 DOWN 已过去）
        // 长按拖动期间不许翻页：否则按住最右边那个图标往左拖，主屏会被带着滑到应用列表页
        if (cell.getParent() != null) cell.getParent().requestDisallowInterceptTouchEvent(true);
        cell.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120).start();
        cell.setElevation(Ui.dp(this, 10));
        OverlayMenu.dismissCurrent();
        showPanelIconMenu(left, pkg, cell);
    }

    /**
     * 功能区拖动换位。
     *
     * 位移不再「交换一次就累加一次」—— 那样跨两三格后每个格子的 translation 会互相
     * 叠加，图标被推到面板外面看不见（用户反馈「挤得看不到」）。现在每次位置变化都
     * 按「当前顺序 vs 原始顺序」整排重算：位移 =（现在的下标 − 原始下标）× 格宽，
     * 任何时刻都是确定的，也永远不会超出面板范围。
     */
    private boolean onPanelDragTouch(View v, android.view.MotionEvent e, boolean left) {
        if (panelDragIndex < 0) return false;   // 非拖动态：交给点击/长按
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                // 竖排 Dock 沿 Y 轴拖，横排沿 X 轴；这个字段存「沿排列方向的起点」
                panelDragStartRawX = dragAxis(e);
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (panelDragStartRawX < 0) panelDragStartRawX = dragAxis(e);
                float dx = dragAxis(e) - panelDragStartRawX;
                // 8dp：手指按着不动时的轻微抖动不算「拖动」
                boolean movedNow = Math.abs(dx) > Ui.dp(this, 8);
                if (movedNow) {
                    // 检测到拖动 → 把刚弹出来的菜单关掉，继续换位（用户要的就是这个顺序）
                    OverlayMenu.dismissCurrent();
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                panelDragMoved |= movedNow;
                int pitch = Math.max(1, dockPitchPx());
                int n = panelDragList.size();
                // 被拖的图标不能移出面板：把跟手位移夹在首尾格之间
                float shown = Math.max((0 - panelDragIndex) * (float) pitch,
                        Math.min((n - 1 - panelDragIndex) * (float) pitch, dx));
                if (dockVertical) v.setTranslationY(shown);
                else v.setTranslationX(shown);
                int target = Math.max(0, Math.min(n - 1,
                        panelDragIndex + Math.round(dx / (float) pitch)));
                if (target != panelDragCur) {
                    String moving = panelDragList.remove(panelDragCur);
                    panelDragList.add(target, moving);
                    panelDragCur = target;
                    layoutPanelCells(pitch);
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                finishPanelDrag(left);
                return true;
        }
        return true;
    }

    /** 拖动沿排列方向的坐标：竖排 Dock 取 Y，横排取 X */
    private float dragAxis(android.view.MotionEvent e) {
        return dockVertical ? e.getRawY() : e.getRawX();
    }

    /** 按当前顺序重算每个格子的位移：位移 =（现在的位置 − 原始位置）× 格宽 */
    private void layoutPanelCells(int pitch) {
        if (panelDragOrigin == null || panelDragList == null) return;
        for (int i = 0; i < panelDragCells.size() && i < panelDragOrigin.size(); i++) {
            View c = panelDragCells.get(i);
            if (c == panelDragView) continue;   // 被拖的那个跟着手指走
            int now = panelDragList.indexOf(panelDragOrigin.get(i));
            if (now < 0) continue;
            float delta = (now - i) * (float) pitch;
            // 竖排沿 Y 让位，横排沿 X；另一轴清 0，换向拖动后不留残影
            if (dockVertical) { c.setTranslationX(0); c.setTranslationY(delta); }
            else { c.setTranslationY(0); c.setTranslationX(delta); }
        }
    }

    /**
     * 拖动结束：换过位置就落盘，没换就复位。
     * 菜单不在这里弹 —— 长按那一刻已经弹出来过了（见 beginPanelDrag），
     * 这里再弹就是用户反馈的「松手又蹦一个弹框」。
     */
    private void finishPanelDrag(boolean left) {
        if (panelDragIndex < 0) return;
        View cell = panelDragView;
        boolean dragged = panelDragMoved;
        List<String> l = panelDragList;
        boolean reordered = panelDragOrigin != null && !panelDragOrigin.equals(l);
        if (cell != null) {
            cell.setTranslationX(0);
            cell.setTranslationY(0);
            cell.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            cell.setElevation(0);
            if (cell.getParent() != null) cell.getParent().requestDisallowInterceptTouchEvent(false);
        }
        for (View c : panelDragCells) { c.setTranslationX(0); c.setTranslationY(0); }
        panelDragIndex = panelDragCur = -1;
        panelDragView = null;
        panelDragCells.clear();
        panelDragList = null;
        panelDragOrigin = null;
        if (dragged) {
            if (reordered) setPanelIcons(left, l);
            buildDockPanel(left ? dockLeftBox : dockRightBox, left);   // 清掉残留位移
            applyDockSlots();
        }
    }

    /** 某一侧的快捷图标列表（左右各自独立存） */
    private List<String> panelIcons(boolean left) {
        return new ArrayList<>(left ? Prefs.getDockLeft(this) : Prefs.getDockRightIcons(this));
    }

    private void setPanelIcons(boolean left, List<String> l) {
        if (left) Prefs.setDockLeft(this, l);
        else Prefs.setDockRightIcons(this, l);
    }

    /** 往功能区加图标：只列内置快捷方式（那才是「自己建的应用图标」） */
    private void pickPanelApp(final boolean left) {
        if (pickerDialog != null && pickerDialog.isShowing()) return;
        // 不再限制 5 个（需求）：只留一个防呆上限，图标多了格宽自己变窄
        final int cap = Prefs.PANEL_SHORTCUT_MAX;
        if (panelIcons(left).size() >= cap) {
            toast("操作区最多 " + cap + " 个，先长按移走一个");
            return;
        }
        pickerDialog = new AppPickerSheet(this,
                (left ? "左" : "右") + (dockVertical ? (left ? "（上）" : "（下）") : "")
                        + "端功能区 · 图标（已放 "
                        + panelIcons(left).size() + " 个）",
                AppListAdapter.shortcuts(), Prefs.getListColumns(this), Prefs.getIconSize(this),
                ai -> {
                    List<String> l = panelIcons(left);
                    if (l.size() >= Prefs.PANEL_SHORTCUT_MAX) {
                        toast("操作区最多 " + Prefs.PANEL_SHORTCUT_MAX + " 个");
                        return;
                    }
                    if (!l.contains(ai.pkg)) l.add(ai.pkg);
                    setPanelIcons(left, l);
                    buildDockPanel(left ? dockLeftBox : dockRightBox, left);
                    applyDockSlots();
                });
        pickerDialog.show();
    }

    /**
     * 时间 + 天气（左右两侧可以各有一套）。
     *
     * 横排 Dock：两行 ——「2026-11-10 · 晴 12°」/「11:07:18」。
     * 竖排 Dock：两行 ——「小时:分」/「天气」。窄条里年月日横着放不下，
     *   三行又把每行的高度配额压得太小，所以竖排直接省掉日期（用户定的简化版），
     *   秒也去掉（窄 Dock 宽度不够；每分钟一跳足够）。
     * 建的时候就把文字填上 —— 面板可能被 applyDockSlots 重建，空 TextView 会塌成一条。
     */
    private void buildClockPanel(LinearLayout box, boolean left) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClipChildren(false);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER);
        top.setClipChildren(false);

        TextView date = new TextView(this);
        date.setTextSize(13);
        date.setTypeface(date.getTypeface(), android.graphics.Typeface.BOLD);
        date.setTextColor(Ui.tintDim());
        date.setGravity(Gravity.CENTER);
        date.setSingleLine(true);

        TextView gap = new TextView(this);
        gap.setText(" · ");
        gap.setTextSize(13);
        gap.setTypeface(gap.getTypeface(), android.graphics.Typeface.BOLD);
        gap.setTextColor(Ui.tintDim());
        gap.setGravity(Gravity.CENTER);
        gap.setSingleLine(true);

        TextView weather = new TextView(this);
        weather.setTextSize(13);
        weather.setTypeface(weather.getTypeface(), android.graphics.Typeface.BOLD);
        weather.setTextColor(Ui.tintDim());
        weather.setGravity(Gravity.CENTER);
        weather.setSingleLine(true);

        TextView clock = new TextView(this);
        clock.setTextSize(24);
        clock.setTextColor(Ui.tint());
        clock.setGravity(Gravity.CENTER);
        clock.setSingleLine(true);
        clock.setTypeface(clock.getTypeface(), android.graphics.Typeface.BOLD);
        // 等宽数字：秒在跳的时候整行不会左右抖
        clock.setFontFeatureSettings("tnum");

        java.util.Calendar cal = java.util.Calendar.getInstance();
        date.setText(dateLabel(cal));
        weather.setText(Weather.dockLabel(this));
        clock.setText(dockVertical ? timeLabelShort(cal) : timeLabel(cal));

        if (dockVertical) {
            // 时间 / 天气两行。date 仍然建出来（updateDockClock 会写它），
            // 但不挂进竖排面板 —— 窄条放不下年月日，挂进去只会把另外两行挤裁。
            col.addView(clock);
            col.addView(weather);
        } else {
            top.addView(date);
            top.addView(gap);
            top.addView(weather);
            col.addView(top);
            col.addView(clock);
        }
        // 横排：面板宽度 = 格数 × 格宽（时间天气最少 2 格），高度撑满 Dock。
        // 竖排：宽度撑满 Dock 厚度；高度不再按「格数 × 格宽」给 —— 两行字占两格
        //   会在下面空出一大截（用户报的「时间占的位置太高」），改成按两行文字
        //   行高算的紧凑固定值，跟 pitch 解耦（pitch 会随布局/屏数变化而飘）。
        int w = panelCellCount(left) * dockPitchPx();
        col.setLayoutParams(dockVertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        clockColumnHeightPx())
                : new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        int padH = Ui.dp(this, dockVertical ? 2 : 4);
        col.setPadding(padH, 0, padH, 0);
        // 布局尺寸变了就重算字号：竖排时约束轴是 Dock 厚度（宽度），横排时是
        // Dock 高度，两个方向都监听才不会在换向 / 改图标大小后留着旧字号。
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (r - l != or2 - ol || b - t != ob - ot) refitClockRow(left);
        });
        // 首帧 build 时 col 还没量过，宽高都是 0；等这一轮布局走完再用实测值算一次
        col.post(() -> refitClockRow(left));
        box.addView(col);
        // 字号自适应：横排按「面板占的宽」收敛；竖排时面板是上下占 w、左右撑满
        // Dock 宽度，这里只是布局前的兜底值，真正计算以 refitClockColumn 里的
        // col 实测宽高为准（竖排 = 宽度换到 Dock 厚度、高度换成紧凑固定值）。
        int avail = dockVertical
                ? Math.max(Ui.dp(this, 40), Ui.dp(this, effectiveDockHeight()) - Ui.dp(this, 16))
                : w - 2 * padH;
        if (left) { dockDateL = date; dockClockL = clock; dockWeatherL = weather;
                    dockGapL = gap; dockPanelAvailWL = avail; }
        else { dockDate = date; dockClock = clock; dockWeather = weather;
               dockGap = gap; dockPanelAvailW = avail; }
        refitClockRow(left);
    }

    /**
     * Dock 时间面板字号自适应：宽度、高度**双向**取「能显示完整」的最大字号。
     * 横排：第一行 = 日期 · 天气（13sp 起），第二行 = 时分秒（24sp 起）。
     * 竖排：换成 col 的实测宽（Dock 厚度）× 实测高（格数×格宽）重新收敛两行，
     *   见 refitClockColumn —— 横排那套「按面板宽度 + box 高度」在竖排下轴是反的。
     */
    private void refitClockRow(boolean left) {
        int avail = left ? dockPanelAvailWL : dockPanelAvailW;
        TextView d = left ? dockDateL : dockDate;
        TextView g = left ? dockGapL : dockGap;
        TextView w = left ? dockWeatherL : dockWeather;
        TextView c = left ? dockClockL : dockClock;
        if (avail <= 0 || w == null || c == null) return;
        if (dockVertical) {
            refitClockColumn(d, c, w, left, avail);
            return;
        }
        if (d == null) return;
        float sd = getResources().getDisplayMetrics().scaledDensity;

        // 高度预算：面板实际高度（已布局用真实值；没布局按「图标 dp + Dock 上下 padding」估算）
        LinearLayout box = left ? dockLeftBox : dockRightBox;
        int availH = (box != null && box.getHeight() > 0)
                ? box.getHeight()
                : Ui.dp(this, Prefs.getDockIcon(this)) + Ui.dp(this, 16);
        int hBudget = availH - Ui.dp(this, 4);   // 留一点呼吸缝

        // 第一行：三段按同一个字号缩放（间距才能保持对齐）
        android.text.TextPaint p = new android.text.TextPaint(d.getPaint());
        p.setTextSize(13 * sd);
        String join = g != null ? g.getText().toString() : " · ";
        float rowW = (p.measureText(d.getText().toString())
                + p.measureText(join) + p.measureText(w.getText().toString())) * 1.06f;
        float topSp = rowW > avail ? Math.max(8f, 13f * avail / rowW) : 13f;

        // 第二行：时分秒 8 个字符宽度固定（tnum），竖屏缩、横屏最多放到 30sp
        android.text.TextPaint cp = new android.text.TextPaint(c.getPaint());
        cp.setTextSize(24 * sd);
        float clockW = cp.measureText("00:00:00") * 1.06f;
        float clockSp = Math.max(9f, Math.min(30f, 24f * avail / clockW));

        // 高度上限：两行行高（≈1.4×字号）之和超出面板高 → 两行等比例压缩
        //（保证两行都完整显示，宁可字小一点也不裁字）
        float hTop = topSp * sd * 1.4f;
        float hClock = clockSp * sd * 1.4f;
        if (hBudget > 0 && hTop + hClock > hBudget) {
            float scale = hBudget / (hTop + hClock);
            topSp = Math.max(6f, topSp * scale);
            clockSp = Math.max(7f, clockSp * scale);
        }

        d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, topSp);
        if (g != null) g.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, topSp);
        w.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, topSp);
        c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, clockSp);
    }

    /**
     * 竖排 Dock 时间区的紧凑高度：两行 15sp 文字的行高 + 上下呼吸缝。
     * 不用格宽算 —— 格宽随布局/屏数变化，会让时间区忽高忽低。
     */
    private int clockColumnHeightPx() {
        float sd = getResources().getDisplayMetrics().scaledDensity;
        int line = (int) (15f * sd * 1.4f);
        return 2 * line + Ui.dp(this, 8);
    }

    /**
     * 竖排 Dock 时间区字号自适应：只有「时间 / 天气」两行。
     *
     * 宽度、高度要跟横排**对调**：竖排下面板是「宽 = Dock 厚度、高 = 格数×格宽」，
     * 所以宽度预算取 col 的实测宽（横排取的是面板占的宽 w），高度预算取 col 的实测高
     * （横排取的是 box 的高度）。两行各分一半高度，字号同时受「宽度放得下」和
     * 「行高配额放得下」约束取更小值；不再留 8sp/9sp 的硬下限 —— 窄条里宁可
     * 字小一点，也保证整行完整显示，不会被裁掉。
     */
    private void refitClockColumn(TextView d, TextView c, TextView w,
                                  boolean left, int availFallback) {
        if (c == null || w == null) return;
        float sd = getResources().getDisplayMetrics().scaledDensity;
        android.view.ViewParent parent = c.getParent();
        android.view.View col = parent instanceof android.view.View
                ? (android.view.View) parent : null;

        int availW = col != null
                ? col.getWidth() - col.getPaddingLeft() - col.getPaddingRight() : 0;
        if (availW <= 0) availW = availFallback;                 // 首帧还没量过
        int availH = col != null
                ? col.getHeight() - col.getPaddingTop() - col.getPaddingBottom() : 0;
        if (availH <= 0) availH = clockColumnHeightPx();
        availW = Math.max(Ui.dp(this, 32), availW);
        availH = Math.max(Ui.dp(this, 48), availH);

        // 每行高度配额（留 6% 行间呼吸缝），换算成字号上限
        float maxSpByHeight = (availH / 2f * 0.94f) / (1.4f * sd);
        // 时间为主视觉：先按可用宽尽量放大，天气再取「比时间略小」的值
        //（原来各行独立按宽度收敛，"00:00" 比天气长，反而被压得比天气小）。
        float clockSp = Math.min(maxSpByHeight,
                fitSp(c.getPaint(), "00:00", 26f, availW, sd));
        float weatherSp = Math.min(clockSp * 0.85f,
                fitSp(w.getPaint(), w.getText().toString(), 15f, availW, sd));

        c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(4f, clockSp));
        w.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(4f, weatherSp));
        // 日期不显示，但对象还在（updateDockClock 会写它），保持字号不失控
        if (d != null) d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                Math.max(4f, weatherSp));
        Log.i(TAG, "clock 竖排 宽=" + availW + " 高=" + availH
                + " 时间=" + String.format(java.util.Locale.US, "%.1f", clockSp)
                + "sp 天气=" + String.format(java.util.Locale.US, "%.1f", weatherSp) + "sp");
    }

    /** 按可用宽度算出「整行能完整显示」的最大字号，含 12% 粗体/字距/取整余量 */
    private float fitSp(android.text.TextPaint src, String text, float baseSp,
                        int availW, float sd) {
        if (text == null || text.length() == 0 || availW <= 0) return baseSp;
        android.text.TextPaint p = new android.text.TextPaint(src);
        p.setTextSize(baseSp * sd);
        float need = p.measureText(text) * 1.12f;
        return need > availW ? baseSp * availW / need : baseSp;
    }

    private static String dateLabel(java.util.Calendar c) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static String timeLabel(java.util.Calendar c) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /** 竖排 Dock：只显示小时:分（窄条里放不下秒，也省得每秒重绘一次） */
    private static String timeLabelShort(java.util.Calendar c) {
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }

    /** 玻璃竖线：竖向渐变（上下淡出），比一条实线更像玻璃切面 */
    private android.graphics.drawable.Drawable glassDivider() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                Ui.dark ? new int[]{0x00FFFFFF, 0x66FFFFFF, 0x66FFFFFF, 0x00FFFFFF}
                        : new int[]{0x00000000, 0x4D000000, 0x4D000000, 0x00000000});
        g.setCornerRadius(Ui.dp(this, 1));
        return g;
    }

    /**
     * 时钟每秒一跳（要显示秒），天气/亮度这类重活仍按 15 秒一次 ——
     * 每帧都去看天气缓存、写系统亮度只会白烧 CPU。
     */
    private void updateDockClock() {
        if (dockClock == null && dockClockL == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String date = dateLabel(cal);
        String time = dockVertical ? timeLabelShort(cal) : timeLabel(cal);
        if (dockDate != null) setTextIfChanged(dockDate, date);
        if (dockClock != null) setTextIfChanged(dockClock, time);
        if (dockDateL != null) setTextIfChanged(dockDateL, date);
        if (dockClockL != null) setTextIfChanged(dockClockL, time);
    }

    /** 文本没变就不 setText：避免每秒触发一次重排/重绘 */
    private static boolean setTextIfChanged(TextView v, String s) {
        if (s != null && !s.contentEquals(v.getText())) { v.setText(s); return true; }
        return false;
    }

    /** 天气/亮度/网络：15s 一跳（左右两侧只要有一侧是时间天气就要刷） */
    private void updateDockRealtime() {
        updateDockClock();
        if (dockWeather != null && dockRightBox != null
                && dockRightBox.getVisibility() == View.VISIBLE) {
            if (setTextIfChanged(dockWeather, Weather.dockLabel(this))) refitClockRow(false);
        }
        if (dockWeatherL != null && dockLeftBox != null
                && dockLeftBox.getVisibility() == View.VISIBLE) {
            if (setTextIfChanged(dockWeatherL, Weather.dockLabel(this))) refitClockRow(true);
        }
        // 天气缓存过期就后台拉一次（亮度判断「阴天」也靠它）
        Weather.refreshIfStale(this);
        SunTimes.refreshIfStaleAsync(this);   // 当天日落/日出，一天只查一次
        // 亮度自动调节：目标值没变时内部直接返回，不会反复写系统设置
        Brightness.applyIfAuto(this);
    }

    private void startDockTicker() {
        dockHandler.removeCallbacks(dockTick);
        dockHandler.post(dockTick);
    }

    private void stopDockTicker() {
        dockHandler.removeCallbacks(dockTick);
    }

    // ------------------------------------------------------------ Dock 格位

    /** 上一次算出的统一格宽：变了就要重建功能区（两边图标间距必须一致） */
    private int appliedPitch = -1;

    // 功能区图标拖动排序状态（同一时刻只拖一个；交互与中间图标区一致：
    // 长按待命 → 拖走换位 / 原地松手弹菜单）
    private final List<View> panelDragCells = new ArrayList<>();
    private List<String> panelDragList;
    /** 拖动开始那一刻的顺序：算每个格子的位移都拿它当基准，不会累计错位 */
    private List<String> panelDragOrigin;
    private View panelDragView;
    private boolean panelDragLeft;
    private int panelDragIndex = -1, panelDragCur = -1;
    private float panelDragStartRawX = -1;
    private boolean panelDragMoved;

    /**
     * 整条 Dock 的统一格宽：中间图标区和两侧功能区共用同一个值 ——
     * 图标都居中在自己的格子里，间距天然一致。
     *
     * 分配规则：可用宽 ÷ (中间格数 + 两侧功能区格数)，夹在 46~78dp 之间。
     * 夹到上限还不够时，中间图标区就比自己的份额窄 → RecyclerView 横向滑动；
     * 两侧功能区永远按格宽占位，不会被压窄（时间/天气和操作按钮必须保持可点）。
     */
    private int dockPitchPx() {
        // 横排按 Dock 的宽度分格，竖排（Dock 在左侧）按 Dock 的高度分格
        int dockSpan;
        if (dockVertical) {
            dockSpan = dockWrap != null && dockWrap.getHeight() > 0
                    ? dockWrap.getHeight() : getResources().getDisplayMetrics().heightPixels;
        } else {
            dockSpan = dockWrap != null && dockWrap.getWidth() > 0
                    ? dockWrap.getWidth() : getResources().getDisplayMetrics().widthPixels;
        }
        int slots = Math.max(1, Prefs.getDockCount(this));
        int panels = panelCellCount(true) + panelCellCount(false);
        int divW = 2 * Math.max(1, Ui.dp(this, 2));   // 两条玻璃竖线也要从可用宽里扣掉
        // 功能区 box 自己还有左右各 4dp padding（有面板时才有）
        int panelPad = 0;
        if (panelCellCount(true) > 0) panelPad += 8;
        if (panelCellCount(false) > 0) panelPad += 8;
        int lo = Ui.dp(this, 46), hi = Ui.dp(this, 96);
        // 分母用「Dock 槽位数」而不是实际图标数：格宽不能因为中间应用少就被放大，
        // 否则功能区（≤5 格）会跟着撑得很宽，把中间图标区的地盘挤掉。
        int fixed = 2 * Ui.dp(this, 8) + divW + Ui.dp(this, panelPad) + Ui.dp(this, 6);  // 6dp 取整余量
        int pitch = (dockSpan - fixed) / Math.max(1, slots + panels);
        return Math.max(lo, Math.min(hi, pitch));
    }

    /**
     * 中间图标区按统一格宽排；放不下（图标 × 格宽 > 可用宽）就退化成横向滑动。
     * 注意「值没变就直接返回」的保护 —— 否则 onLayoutChange → notifyDataSetChanged
     * → 重新布局 → onLayoutChange 会形成无限布局循环（界面一直在重排，点击还会被抖掉）。
     */
    private void applyDockSlots() {
        if (rvDock == null || dockAdapter == null || dockWrap == null) return;
        int slots = Prefs.getDockCount(this);
        int pitch = dockPitchPx();
        if (pitch != appliedPitch) {
            boolean first = appliedPitch <= 0;
            appliedPitch = pitch;
            if (!first) {
                // 格宽变了（格数/屏宽/功能区内容变了）：功能区重建，间距才对得齐
                buildDockPanel(dockLeftBox, true);
                buildDockPanel(dockRightBox, false);
            }
        }
        // 图标区可用长度 = rvDock 沿排列方向的长度（两端功能区 + 外框内边距已扣掉）；
        // 首帧还是 0 时按「整条 Dock − 功能区格数」估，随后的 onLayoutChange 会纠正
        int availW = dockVertical ? rvDock.getHeight() : rvDock.getWidth();
        if (availW <= 0) {
            int dockW = dockVertical
                    ? (dockWrap.getHeight() > 0 ? dockWrap.getHeight()
                            : getResources().getDisplayMetrics().heightPixels)
                    : (dockWrap.getWidth() > 0 ? dockWrap.getWidth()
                            : getResources().getDisplayMetrics().widthPixels);
            availW = dockW - 2 * Ui.dp(this, 8)
                    - (panelCellCount(true) + panelCellCount(false)) * pitch;
        }
        int avail = Math.max(Ui.dp(this, 46), availW);
        int cells = Math.min(slots, Math.max(1, dockPkgs.size()));
        int slotW = pitch;
        // 能放下就绝不滚动：极端情况下（格宽被夹到 46dp 下限）内容仍可能超出，
        // 这时才把格宽压到刚好放下 —— 只有压到 40dp 还不下才真的交给 RecyclerView 滑
        if (cells > 0 && cells * slotW > avail) {
            int fit = avail / cells;
            if (fit >= Ui.dp(this, 40)) slotW = fit;
        }
        if (dockVertical && cells > 0) {
            // 竖排「两端对齐」：操作区固定在顶端，应用区的图标首尾贴边、
            // 沿剩余高度均分铺满，不再上下各留一大块居中空白。
            slotW = Math.max(slotW, avail / cells);
        }
        int content = cells * slotW;
        // 两侧留白只能是「真正剩余宽度的一半」。
        // 以前这里有个 8dp 下限：剩余不足 16dp 时，内容会比可用宽多出十几像素，
        // RecyclerView 于是有了可滚距离 —— 在应用栏上横滑就变成「列表自己滚几像素」，
        // 手不够长就翻不到应用列表页（用户报的正是这个）。
        // 竖排时上面已把格子高度均分铺满，留白恒为 0（两端对齐）。
        int side = dockVertical ? 0 : Math.max(0, (avail - content) / 2);
        if (slots == appliedSlotCount && slotW == appliedSlotWidth
                && cells == appliedSlotCells && side == appliedSidePad) return;
        appliedSlotCount = slots;
        appliedSlotWidth = slotW;
        appliedSlotCells = cells;
        appliedSidePad = side;

        dockAdapter.setSlotWidth(slotW);
        // 两侧留白：横排加在左右，竖排加在上下；另一轴由 dockRow 的 padding 统一给
        if (dockVertical) rvDock.setPadding(0, side, 0, side);
        else rvDock.setPadding(side, 0, side, 0);
        dockAdapter.notifyDataSetChanged();
        // 排查「应用栏明明放得下却还能滑」用：range - extent 必须 ≤ 0，
        // 大于 0 就是内容比视口长，rvDock 会把手势吃掉、翻不到应用列表页。
        final int fAvail = avail, fContent = content, fSide = side;
        rvDock.post(() -> {
            int range = dockVertical ? rvDock.computeVerticalScrollRange()
                    : rvDock.computeHorizontalScrollRange();
            int extent = dockVertical ? rvDock.computeVerticalScrollExtent()
                    : rvDock.computeHorizontalScrollExtent();
            Log.i("PipDesk", "dock 格宽=" + appliedSlotWidth + " 可用=" + fAvail
                    + " 内容=" + fContent + " 留白=" + fSide
                    + " 可滚距离=" + (range - extent)
                    + (dockVertical ? " 竖排" : " 横排"));
        });
    }

    @Override
    public int dockSlots() {
        return appliedSlotCount > 0 ? appliedSlotCount : Prefs.getDockCount(this);
    }

    // ------------------------------------------------------------ 触摸 / 长按

    private void bindPaneGestures() {
        for (int i = 0; i < pip.count(); i++) bindPaneGesture(pip.pane(i));
    }

    /**
     * 给一块面板绑手势。
     *
     * 单独抽出来是因为**单画专用屏是运行时才创建的**（PipManager.ensureSinglePane），
     * 它不在 panes 里 —— 只靠 bindPaneGestures() 的遍历永远轮不到它，那块屏就成了
     * "只看不摸"的死画面：点按没反应、滑动被宿主当成翻页/拖拽。
     * 现在由 PipManager.Listener.onPaneCreated 回调进来补绑。
     */
    /** 屏内 app 的系统长按约 500ms；画中画菜单要等「超长按」才出，先把它让出来 */
    private static final long PANE_SUPER_LONG_MS = 1200L;
    private static final float PANE_SUPER_LONG_SLOP_DP = 14f;

    private void bindPaneGesture(final PipPane pane) {
        if (pane == null || pane.root == null) return;
        // 超长按已接管：这次手势剩下的 MOVE/UP 一律不转发（否则松手会在屏内应用里点一下）
        final boolean[] consumed = new boolean[1];
        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    /** 空白的画中画：单击直接弹应用列表（需求 2） */
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (pane.pkg() != null) return false;
                        consumed[0] = true;
                        openPickerForPane(pane);
                        return true;
                    }
                });
        // 【需求一.4】画中画内长按优先显示 app 自己的长按效果，超长按才弹画中画菜单。
        // 计时期间所有事件照常 pip.dispatch 给屏内 app —— 旧版 GestureDetector.onLongPress
        // 500ms 就 cancelTouch 掐断它，方向正好相反。单画专用屏走的是同一套（onPaneCreated）。
        final Runnable[] superLong = new Runnable[1];
        final Runnable cancelSuperLong = () -> {
            if (superLong[0] != null) {
                pane.root.removeCallbacks(superLong[0]);
                superLong[0] = null;
            }
        };
        final float superSlopPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, PANE_SUPER_LONG_SLOP_DP,
                getResources().getDisplayMetrics());
        final float[] gst = new float[5];   // 0,1=起手坐标 2=最大位移 3=帧数 4=按下时刻
        pane.root.setOnTouchListener((v, e) -> {
            if (!pane.isInteractive()) {
                return false;   // 被折叠隐藏的屏：不吃触摸，免得挡住旁边那块屏
            }
            final boolean empty = pane.pkg() == null;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                consumed[0] = false;      // 新手势：重新开始判定
                // 有应用的屏被按下的瞬间它就是"返回键的目标"：
                // 之后用户点 Dock 返回，键要回到这块屏（哪怕焦点已被桌面抢走）
                if (!empty && pane.isInteractive()) lastActivePane = pane;
                gst[0] = e.getX();
                gst[1] = e.getY();
                gst[2] = 0;
                gst[3] = 0;
                gst[4] = android.os.SystemClock.uptimeMillis();
                cancelSuperLong.run();          // 幂等：新手势开始前先清掉上一轮计时
                Runnable fire = () -> {
                    superLong[0] = null;
                    consumed[0] = true;
                    pip.cancelTouch();          // 到点才掐断屏内 app 的按压
                    FileLog.append("画中画 " + pane.index + " 超长按 → 弹画中画菜单");
                    showPaneMenu(pane);
                };
                superLong[0] = fire;
                pane.root.postDelayed(fire, PANE_SUPER_LONG_MS);
                // 轨迹日志只在排查模式（/sdcard/pipdesk_verbose）下输出：
                // 每次按下都要拼长字符串 + getLocationOnScreen，再加上
                // Watchdog.touch 里的时间戳格式化，正常使用纯属白烧 CPU/IO。
                if (PipDebug.VERBOSE) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    PipDebug.log("pane " + pane.index + " down local="
                            + (int) e.getX() + "," + (int) e.getY()
                            + " raw=" + (int) e.getRawX() + "," + (int) e.getRawY()
                            + " at=" + loc[0] + "," + loc[1]
                            + " size=" + v.getWidth() + "x" + v.getHeight()
                            + " empty=" + empty
                            + " dispPixel=" + pip.displaySize(pane.index));
                    Watchdog.touch("pane " + pane.index + " DOWN local="
                            + (int) e.getX() + "," + (int) e.getY()
                            + " size=" + v.getWidth() + "x" + v.getHeight());
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float d = (float) Math.hypot(e.getX() - gst[0], e.getY() - gst[1]);
                if (d > gst[2]) gst[2] = d;
                gst[3]++;
                // 明显在拖动/滑动：不是超长按，取消计时，事件继续照常给屏内 app
                if (d > superSlopPx) cancelSuperLong.run();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancelSuperLong.run();     // 抬手即取消：只有一直按住才会弹画中画菜单
                // 宿主手指的真实轨迹：与注入侧日志对照，能区分是手指漂移还是转发丢失
                if (PipDebug.VERBOSE) {
                    PipDebug.log("pane " + pane.index
                            + (e.getActionMasked() == MotionEvent.ACTION_UP ? " up" : " cancel")
                            + " local=" + (int) e.getX() + "," + (int) e.getY()
                            + " moves=" + (int) gst[3]
                            + " maxDrift=" + (int) gst[2] + "px"
                            + " dur=" + (android.os.SystemClock.uptimeMillis() - (long) gst[4]) + "ms");
                    Watchdog.touch("pane " + pane.index
                            + (e.getActionMasked() == MotionEvent.ACTION_UP ? " UP" : " CANCEL")
                            + " local=" + (int) e.getX() + "," + (int) e.getY()
                            + " moves=" + (int) gst[3]
                            + " drift=" + (int) gst[2]
                            + " dur=" + (android.os.SystemClock.uptimeMillis() - (long) gst[4]) + "ms");
                }
            }
            gd.onTouchEvent(e);
            if (empty) {
                // 空白屏后面没有虚拟屏可转发：手势全归宿主（单击＝选应用，长按＝菜单）。
                // 翻页不受影响 —— 主页的翻页闸门只放行从 Dock 区域起手的左滑。
                if (consumed[0] && (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                    consumed[0] = false;
                }
                return true;
            }
            if (consumed[0]) {
                // 长按已经弹了宿主菜单：这一下算宿主的，别再往屏内应用送，
                // 否则松手会被当成一次点击（用户反馈"长按后应用里也点了一下"）
                int am = e.getActionMasked();
                if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) {
                    consumed[0] = false;
                }
                return true;
            }
            // 按面板对象转发（不按位置）：调换顺序后监听器跟着 view 走，不用重绑
            long tDispatch = android.os.SystemClock.uptimeMillis();
            pip.dispatch(pane, e);
            long cost = android.os.SystemClock.uptimeMillis() - tDispatch;
            if (cost > 40) {
                // 卡死前几天往往先出现"触摸转发越来越慢"，留下证据
                android.util.Log.w("PipDesk", "触摸转发耗时 " + cost + "ms pane="
                        + pane.index + " action=" + e.getActionMasked());
            }
            return true;
        });
    }

    /** 空白画中画单击：直接弹应用列表，选中的应用会记为这块屏的默认应用 */
    private void openPickerForPane(PipPane pane) {
        if (pane == null) return;
        if (appList == null) {
            toast("应用列表还在加载中…");
            return;
        }
        showPicker(-1, pip.positionOf(pane) + 1, true);
    }

    private void showPaneMenu(final PipPane pane) {
        if (pane == null) return;
        final int index = pip.positionOf(pane);
        // 单画专用屏不在 panes 里，positionOf 返回 -1：左右移/按位置关闭这类
        // "兄弟之间"的操作对它没有意义，直接收起来；关闭走退出单画那条路。
        final boolean single = (pane == pip.singlePane());
        final String pkg = pane.pkg();
        boolean horizontal = paneBox != null && paneBox.isHorizontal();
        String movePrev = horizontal ? "左移" : "上移";
        String moveNext = horizontal ? "右移" : "下移";
        List<String> items = new ArrayList<>();
        if (pkg != null) {
            items.add("全屏打开");
            items.add("重启这个应用");
            items.add("关闭这个画中画");
        }
        if (!single && pip.count() > 1) {
            items.add(movePrev);
            items.add(moveNext);
        }
        // 有画中画被比例拖动折叠掉时，给一条恢复入口（正常都是拖边缘的玻璃条回来）
        if (pip.count() > pip.visibleCount()) items.add("显示全部画中画");
        items.add("在这里打开其他应用");
        // 「打开应用列表」「指定打开位置」先按需求隐藏（入口仍在，只是不在长按菜单里露出）
        // items.add("打开应用列表");
        // if (pip.count() > 1) items.add("指定打开位置");

        GlassMenu.show(this,
                pkg == null ? ("画中画 " + (pane.index + 1) + "（空闲）") : labelOf(pkg),
                items, which -> {
                    String sel = items.get(which);
                    if (sel.equals("全屏打开")) {
                        // 只把应用搬到主屏全屏显示，画中画保留；
                        // 回到桌面时 onResume 会把应用搬回这块屏继续显示
                        pane.launchFullScreen(pkg);
                    } else if (sel.equals("重启这个应用")) {
                        // 屏内应用卡死（画面冻住、点了没反应）时的一键急救：
                        // 杀进程 → 在同一个虚拟屏上重新拉起，1~2 秒恢复
                        pane.restartApp();
                    } else if (sel.equals("关闭这个画中画")) {
                        if (single) pip.closeSingle();   // 单画屏：收掉应用并退回原布局
                        else pip.close(index);
                    } else if (sel.equals(movePrev)) {
                        pip.swap(index, -1);   // 到边界循环到另一端
                    } else if (sel.equals(moveNext)) {
                        pip.swap(index, +1);
                    } else if (sel.equals("显示全部画中画")) {
                        pip.resetRatio();
                    } else if (sel.equals("在这里打开其他应用")) {
                        showPicker(-1, pip.positionOf(pane) + 1);
                    } else if (sel.equals("打开应用列表")) {
                        pager.setCurrentPageSmooth(1);
                    } else {
                        showPaneAssignDialog(pkg);
                    }
                });
    }

    // ------------------------------------------------------------ 应用数据

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            List<AppInfo> res = new ArrayList<>();
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris;
            try {
                ris = pm.queryIntentActivities(it, 0);
            } catch (Throwable t) {
                ris = new ArrayList<>();
            }
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                res.add(new AppInfo(label, ri.activityInfo.packageName, ri.loadIcon(pm)));
            }
            Collections.sort(res, (a, b) -> a.label.compareToIgnoreCase(b.label));
            // 用户拖动过的顺序优先：按记录重排；记录里的内置功能图标（跨区互换后
            // 会落在应用区）也按原位插回；新装的应用（没记录）垫在后面。
            List<String> order = Prefs.getAppOrder(this);
            List<String> shortcutOrder = Prefs.getShortcutOrder(this);
            java.util.Set<String> slotted = shortcutOrder == null
                    ? new java.util.HashSet<>() : new java.util.HashSet<>(shortcutOrder);
            // 快捷区永远渲染全部内置快捷方式：app_order 里的残留副本（老版本跨区拖动
            // 写进去的 pipdesk://nav-home 等）必须当作「已在快捷区」滤掉，
            // 否则应用页网格里再画一个 —— 表现就是出现两个「主页」图标。
            slotted.addAll(AppListAdapter.shortcutSlotPkgs());
            List<AppInfo> slottedInfos = new ArrayList<>();   // 已换进快捷区的真实应用：给 adapter 缓存图标用
            List<AppInfo> out;
            if (!order.isEmpty()) {
                java.util.Map<String, AppInfo> byPkg = new java.util.HashMap<>();
                for (AppInfo a : res) byPkg.put(a.pkg, a);
                java.util.Set<String> used = new java.util.HashSet<>();
                List<AppInfo> ordered = new ArrayList<>(res.size());
                for (String pkg : order) {
                    if (AppInfo.isHidden(pkg)) continue;   // 已隐藏的内置快捷方式（音量 ±）不再露面
                    if (!used.add(pkg)) continue;
                    if (AppInfo.isBuiltin(pkg)) {
                        ordered.add(new AppInfo(AppListAdapter.shortcutLabel(pkg), pkg,
                                AppListAdapter.shortcutIcon(pkg)));
                    } else {
                        AppInfo ai = byPkg.get(pkg);
                        if (ai != null) ordered.add(ai);
                    }
                }
                for (AppInfo a : res) if (used.add(a.pkg)) ordered.add(a);
                out = ordered;
            } else {
                out = res;
            }
            // 已被换进快捷区的真实应用从应用区移除（渲染在快捷槽位里）
            for (int i = out.size() - 1; i >= 0; i--) {
                if (slotted.contains(out.get(i).pkg)) {
                    slottedInfos.add(out.remove(i));
                }
            }
            final List<AppInfo> resF = out;
            final List<AppInfo> slottedF = slottedInfos;
            // 全量索引：Dock 找图标用它，跟「应用区/快捷区」的分区无关
            final java.util.Map<String, AppInfo> allF = new java.util.HashMap<>();
            for (AppInfo a : res) allF.put(a.pkg, a);
            for (AppInfo a : slottedInfos) allF.put(a.pkg, a);
            runOnUiThread(() -> {
                appList = resF;
                // allApps 会被语音线程读（apps()），换数据必须整体加锁，
                // 否则并发 clear()/putAll() 期间读到的是半张表
                synchronized (allApps) {
                    allApps.clear();
                    allApps.putAll(allF);
                }
                if (appListAdapter != null) {
                    appListAdapter.setData(appList);
                    for (AppInfo a : slottedF) appListAdapter.rememberApp(a);
                    appListAdapter.notifyDataSetChanged();
                }
                if (dockAdapter != null) dockAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private String labelOf(String pkg) {
        AppInfo ai = findApp(pkg);
        return ai != null ? ai.label : pkg;
    }

    // ------------------------------------------------------------ 打开画中画

    /**
     * 打开到画中画。三条互斥的走法，优先级从上到下：
     *
     *  1) **显式指定屏位**（preferred ≥ 1，来自「每屏默认应用」自动拉起、拖到某屏、调试命令）：
     *     恢复原布局（单画临时布局先退出），在指定那块屏打开。
     *  2) **已绑定的应用**（长按「指定画中画位置」= pane_map，设置里「每屏默认应用」= pane_default_N）：
     *     恢复原布局，回到它自己那块屏打开。绑定是用户明确建立的关系，
     *     优先级高于「打开方式」—— 设置只管没绑定的应用。
     *  3) **未绑定的应用**：完全按「打开方式」走
     *     （活动画中画【默认】/ 单画中画 / 全屏）。**打开动作本身不写入任何绑定**，
     *     所以它下次点开还是按设置走，不会因为"开过一次"就被钉在某块屏上。
     */
    /** 常规打开（单击图标 / Dock / 语音 / 自动拉起）：按设置页的「默认打开方式」 */
    private void openPip(String pkg, int preferred) {
        openPip(pkg, preferred, Prefs.getOpenMode(this), false);
    }

    /**
     * 应用页长按：临时按指定方式打开这一次。
     * 不写 Prefs、不写 pane_map / pane_default_N、不建立任何绑定关系；
     * 下一次点开仍回到设置页的默认方式。只对未绑定应用适用
     * （已绑定应用走绑定屏位，菜单里也不会给出点了没反应的临时项）。
     */
    private void openPipTemp(String pkg, int mode) {
        if (pip != null && pip.isAssigned(pkg)) {
            toast("这个应用已绑定画中画位置，临时打开方式不适用");
            return;
        }
        openPip(pkg, 0, mode, true);
    }

    private void openPip(String pkg, int preferred, int mode, boolean temp) {
        if (pkg == null) return;
        boolean bound = pip.isAssigned(pkg);
        FileLog.append("打开 " + pkg + " 显式屏位=" + preferred
                + " 绑定屏位=" + (bound ? pip.paneOfPkg(pkg) + 1 : 0)
                + " 打开方式=" + (mode == Prefs.OPEN_SINGLE ? "单画"
                : mode == Prefs.OPEN_FULLSCREEN ? "全屏" : "活动画中画")
                + (temp ? "（临时）" : "（默认）")
                + " 单画布局=" + pip.inSingleMode());
        if (preferred <= 0 && !bound) {
            if (mode == Prefs.OPEN_SINGLE) {
                // 单画用独立的虚拟屏，主页画中画一个都不碰（返回时零闪烁，见 PipManager.openSingle）
                pip.openSingle(pkg);
                resetSingleBackCounter();   // 新一轮单画：返回计数从头开始
                if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
                return;
            }
            if (mode == Prefs.OPEN_FULLSCREEN) {
                pip.launchFullScreen(pkg);
                int i = pip.indexOfPkg(pkg);
                if (i >= 0) lastActivePane = pip.pane(i);
                if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
                return;
            }
            // OPEN_ACTIVE（活动画中画）：停在单画临时布局里换应用就名不副实了，
            // 先回到原布局，再开进活动那块屏。
            if (pip.inSingleMode()) {
                FileLog.append("活动画中画打开 " + pkg + "：恢复原布局");
                pip.restoreSingleLayout();
            }
        } else if (pip.inSingleMode()) {
            // 绑定 / 指定屏位的应用：单画布局必须退回去（即使它还在隐藏的屏上跑着）
            FileLog.append("打开已绑定 " + pkg + "（屏位=" + (preferred > 0 ? preferred : pip.paneOfPkg(pkg) + 1)
                    + "）：恢复原布局");
            pip.restoreSingleLayout();
        }
        // 幂等：已在跑的应用由 PipPane.open 自己判断（同一 pkg 先校验 task 是否还活着）。
        // 不能因为"记着这个 pkg"就整段跳过 —— 应用被「返回」退掉后 pkg 还记着，跳过就变成"点了没反应"。
        pip.open(pkg, preferred);
        resetSingleBackCounter();   // 切换/重新打开应用：单画返回计数从头开始
        // 刚在某个屏上打开了应用 → 那块屏成为返回键的目标（用户的心理预期就是"返回到刚开的那个"）
        int idx = pip.indexOfPkg(pkg);
        if (idx >= 0) lastActivePane = pip.pane(idx);
        if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
    }

    /**
     * 画中画默认应用（设置里配置）：空闲且有默认应用的屏，自动把应用拉起来。
     * 只补空闲屏，已经在跑应用/应用已开在别的屏的不动。
     */
    private void openDefaults() {
        if (pip == null) return;
        for (int i = 1; i <= pip.count(); i++) {
            PipPane p = pip.pane(i - 1);
            if (p == null || p.isBusy()) continue;
            // 被折叠隐藏的屏不自动拉起应用：用户把它收起来就是想让它空着
            if (pip.isCollapsed(i - 1)) continue;
            String pkg = Prefs.getDefaultApp(this, i);
            if (pkg == null || pip.indexOfPkg(pkg) >= 0) continue;
            pip.open(pkg, i);
        }
    }

    // ------------------------------------------------------------ Dock 回调

    @Override
    public List<String> dockPkgs() {
        return dockPkgs;
    }

    /** Dock 长按拖动排序结束：只持久化新顺序，不重建（notifyItemMoved 已把动画走完） */
    @Override
    public void onDockOrderChanged() {
        Log.i("PipDesk", "Dock 排序 → " + dockPkgs);
        Prefs.setDock(this, dockPkgs);
    }

    @Override
    public AppInfo findApp(String pkg) {
        // 桌面内置快捷方式（深浅模式 / 重启桌面 / 返回 / 主页）：不是真应用，图标现画
        if (AppInfo.isBuiltin(pkg)) {
            android.graphics.drawable.Drawable icon = AppListAdapter.shortcutIcon(pkg);
            return icon == null ? null
                    : new AppInfo(AppListAdapter.shortcutLabel(pkg), pkg, icon);
        }
        if (pkg == null) return null;
        if (appList != null) {
            for (AppInfo a : appList) if (pkg.equals(a.pkg)) return a;
        }
        // 兜底：全量索引。应用被拖进快捷区后就不在 appList 里了，
        // 但它在 Dock 上的图标还得画得出来（否则退化成系统默认图标）
        return allApps.get(pkg);
    }

    @Override
    public void onDockClick(String pkg) {
        if (AppInfo.isBuiltin(pkg)) {
            runBuiltin(pkg);
            return;
        }
        openPip(pkg, 0);
    }

    /** 执行一个内置快捷方式（应用列表图标、Dock 图标都走这里） */
    private void runBuiltin(String pkg) {
        if (AppInfo.SETTINGS_PKG.equals(pkg)) {
            onSettingsClick();
        } else if (AppInfo.THEME_PKG.equals(pkg)) {
            onThemeToggle();
        } else if (AppInfo.RESTART_PKG.equals(pkg)) {
            restartLauncher();
        } else if (AppInfo.BACK_PKG.equals(pkg)) {
            sendBack();
        } else if (AppInfo.HOME_PKG.equals(pkg)) {
            Keys.home(this);
        } else if (AppInfo.APP_LIST_PKG.equals(pkg)) {
            // 翻到应用列表（应用页）；已经在那儿就翻回主页，一个按钮兼顾来回
            if (pager != null) {
                pager.setCurrentPageSmooth(pager.getCurrentPage() == 1 ? 0 : 1);
            }
        } else if (AppInfo.VOICE_PKG.equals(pkg)) {
            // 点一下开始听，说完自动断句执行；不需要唤醒词
            Voice.get().listenOnce();
        } else if (AppInfo.SLEEP_PKG.equals(pkg)) {
            ScreenOff.sleep(this);
        } else if (AppInfo.RECENTS_PKG.equals(pkg)) {
            Keys.recents(this);
        } else if (AppInfo.VOL_UP_PKG.equals(pkg)) {
            showVolumeHud(Keys.volume(this, true), true);
        } else if (AppInfo.VOL_DOWN_PKG.equals(pkg)) {
            showVolumeHud(Keys.volume(this, false), false);
        } else if (AppInfo.MEDIA_PLAY_PKG.equals(pkg)) {
            Media.play(this);
        } else if (AppInfo.MEDIA_PAUSE_PKG.equals(pkg)) {
            Media.pause(this);
        } else if (AppInfo.MEDIA_NEXT_PKG.equals(pkg)) {
            Media.next(this);
        } else if (AppInfo.MEDIA_PREV_PKG.equals(pkg)) {
            Media.prev(this);
        } else if (AppInfo.MEDIA_MUTE_PKG.equals(pkg)) {
            // 静音是切换：说一次静音，再说一次恢复。语音播报这时自己也听不见了，
            // 所以除了 toast 再浮一个音量条，让用户看得到当前音量。
            Boolean muted = Media.toggleMute(this);
            if (muted != null) {
                toast(muted ? "已静音" : "已恢复声音");
                showVolumeHud(Media.musicVolume(this), !muted);
            } else {
                toast("这台车机不允许改音量");
            }
        }
    }

    // ------------------------------------------------------------ 语音

    /**
     * 语音只认"能启动的应用"。这里给的是 pkg → AppInfo 的全量索引副本：
     * 语音线程在后台跑，直接返回内部 HashMap 的话，loadApps() 在 UI 线程
     * clear()/putAll() 的过程中会读到半截数据（HashMap 并发读可能死循环）。
     */
    @Override
    public List<AppInfo> apps() {
        synchronized (allApps) {
            return new ArrayList<>(allApps.values());
        }
    }

    @Override
    public void onVoiceEvent(int state, String text, String detail) {
        switch (state) {
            case Voice.S_LOADING:
                // 新一轮开始：上一轮留下的候选列表要收掉，别新旧叠在一起
                if (voiceCandMenu != null) {
                    voiceCandMenu.dismiss();
                    voiceCandMenu = null;
                }
                kickVoiceTick();
                VoiceCard.show(this, detail, text, 0);
                break;
            case Voice.S_LISTEN:
                kickVoiceTick();
                VoiceCard.show(this, detail, text, Voice.get().level());
                break;
            case Voice.S_THINK:
                VoiceCard.showThenHide(this, detail, text, 1600);
                break;
            case Voice.S_ERROR:
                VoiceCard.showThenHide(this, "语音不可用", detail, 3200);
                break;
            default:
                // 这一轮结束（回到待唤醒 / 关掉）：候选列表跟着收掉
                voiceTicking = false;
                if (voiceCandMenu != null) {
                    voiceCandMenu.dismiss();
                    voiceCandMenu = null;
                }
                VoiceCard.hide(this);       // S_OFF / S_WAKE 不打扰用户
        }
    }

    @Override
    public void onVoiceAction(String pkg) {
        if (pkg == null) return;
        if (AppInfo.isBuiltin(pkg)) {
            runBuiltin(pkg);
            return;
        }
        // 打开方式完全复用桌面原有的一条路：默认画中画 / 全屏 / 单画 + 屏位指派都自动生效
        openPip(pkg, 0);
        if (Prefs.voiceBringFront(this) && !AppState.mainResumed) bringLauncherToFront();
    }

    @Override
    public void onVoiceCandidates(String phrase, List<AppInfo> cands) {
        // 先无条件收掉上一张：cands 为空代表语音侧说了「都不是」要取消，
        // 旧版这里直接 return，卡片会一直挂在屏幕上。
        if (voiceCandMenu != null) {
            voiceCandMenu.dismiss();
            voiceCandMenu = null;
        }
        if (cands == null || cands.isEmpty()) return;
        List<String> items = new ArrayList<>(cands.size() + 2);
        for (AppInfo a : cands) items.add(a.label);
        items.add("都不是");
        items.add("下一页 ›");
        voiceCandMenu = GlassMenu.show(this, "「" + phrase + "」是哪个？说序号 / 说名字也行", items, which -> {
            voiceCandMenu = null;
            if (which == cands.size() + 1) {      // 翻页：完整候选按 9 个一页，列表和序号一起换
                Voice.get().flipPickPage(1);
                return;
            }
            if (which < 0 || which >= cands.size()) return;   // 「都不是」：静默取消
            AppInfo pick = cands.get(which);
            Prefs.addVoiceAlias(this, pick.pkg, phrase);
            FileLog.append("VOICE 学到别名 \"" + phrase + "\" → " + pick.label
                    + "（本页第 " + (which + 1) + " 个）");
            openPip(pick.pkg, 0);
        });
    }

    /** 用嘴选了第几个：把候选列表收掉，顺手把这个说法记成别名 */
    @Override
    public void onVoiceCandidatesPicked(String phrase, AppInfo pick) {
        if (voiceCandMenu != null) {
            voiceCandMenu.dismiss();
            voiceCandMenu = null;
        }
        if (pick == null || pick.pkg == null || phrase == null) return;
        Prefs.addVoiceAlias(this, pick.pkg, phrase);
        FileLog.append("VOICE 学到别名 \"" + phrase + "\" → " + pick.label + "（语音选号）");
    }

    /**
     * 音量提示：车机上系统音量条经常不显示（FLAG_SHOW_UI 被 ROM 吞掉），
     * 这里自己在窗口顶部浮一个小玻璃条，1.2 秒后淡出。
     */    private void showVolumeHud(int[] vol, boolean up) {
        if (vol == null || vol.length < 2) return;
        final ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        View old = decor.findViewWithTag("pipdesk-vol-hud");
        final TextView hud;
        if (old instanceof TextView) {
            hud = (TextView) old;
        } else {
            hud = new TextView(this);
            hud.setTag("pipdesk-vol-hud");
            hud.setTextSize(15);
            // 深色玻璃 + 白字：停在浅色壁纸上也能看清（用主题玻璃的话白字会糊掉）
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xF22A3240, 0xE6181D26});
            bg.setCornerRadius(Ui.dp(this, 18));
            bg.setStroke(Math.max(1, Ui.dp(this, 1)), 0x40FFFFFF);
            hud.setBackground(bg);
            hud.setTextColor(0xFFFFFFFF);
            hud.setShadowLayer(4, 0, 1, 0xCC000000);
            int ph = Ui.dp(this, 22);
            hud.setPadding(ph, Ui.dp(this, 12), ph, Ui.dp(this, 12));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 屏幕上方居中（约 1/10 高度处）：不挡中间的画中画内容，也比正中间更不碍事
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = Math.max(Ui.dp(this, 48),
                    (int) (decor.getHeight() * 0.10f));
            decor.addView(hud, lp);
        }
        hud.setText((up ? "音量 +  " : "音量 -  ") + vol[0] + " / " + vol[1]);
        // 复用时也把位置刷新一遍（旋转/重建后 decor 高度可能变了）
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hud.getLayoutParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        lp.topMargin = Math.max(Ui.dp(this, 48),
                (int) (decor.getHeight() * 0.10f));
        hud.setLayoutParams(lp);
        hud.animate().cancel();
        hud.setAlpha(1f);
        hud.animate().alpha(0f).setStartDelay(1200).setDuration(400).start();
        Log.i(TAG, "音量提示 " + vol[0] + "/" + vol[1]);
    }

    /**
     * 返回键（Dock / 应用列表里的「返回」快捷方式）。
     *
     * 点 Dock 图标那一刻窗口焦点已被桌面抢走，全局注入的 BACK 只会落到桌面身上
     * （表现就是"虚拟桌面先丢了焦点，返回按钮失效"）。所以优先把 BACK
     * **定向注入到返回目标画中画**的虚拟屏 —— Android 11+ 的 InputDispatcher
     * 按 displayId 维护每块屏自己的焦点窗口，定向键不依赖全局焦点。
     *
     * 目标挑选：最近触碰过的屏 → 活动画中画 → 第一块有应用的屏；
     * 全都没有（一块画中画都没跑应用）才退回全局注入（相当于按给桌面自己）。
     */
    private void sendBack() {
        PipPane t = backTargetPane();
        if (t != null) {
            final String pkg = t.pkg();
            Keys.backOnDisplay(this, t.displayId());
            // 单画临时布局：这一下返回可能把「临时应用」弄走（退出、或只是退到后台）。
            // 两种情况下这块屏的前台都会换人，而用户下一步想看的是原来的双画/三画。
            if (pip != null && pip.inSingleMode()) {
                // 【bug 2】有些 app（万能遥控等）自己消费 BACK，按多少次都不退出。
                // 连续几次仍赖在前台就强制恢复原布局，别把用户卡在单画里。
                if (!forceExitSingleIfStuck()) watchSingleExitIfNeeded();
            } else if (pkg != null) {
                // 普通画中画：应用被真按退出后这块屏就空了，得把画面清掉 ——
                // 否则纹理停在最后一帧，用户会以为"返回键没反应"
                watchPaneExit(t, pkg);
            }
        } else {
            Keys.back(this);
            watchSingleExitIfNeeded();
        }
    }

    /**
     * 普通画中画按了返回之后：这块屏的前台如果换人（应用退出/被 Finish），
     * 就把这块画中画清空（显示"点图标打开应用"的空态），不要留着最后一帧。
     * 应用内部返回（详情→列表）时前台还是同一个包名，不会误清。
     */
    private void watchPaneExit(final PipPane pane, final String pkg) {
        final int d = pane.displayId();
        if (d < 0) return;
        new Thread(() -> {
            long[] waits = {120, 200, 260, 320};
            for (long w : waits) {
                try {
                    Thread.sleep(w);
                } catch (InterruptedException e) {
                    return;
                }
                if (isDestroyed() || pip == null) return;
                String top;
                try {
                    top = pip.topPkgOnDisplay(d);
                } catch (Throwable t) {
                    return;
                }
                if (top == null) continue;
                if (top.equals(pkg)) continue;
                final String nowTop = top;
                runOnUiThread(() -> {
                    if (pane.pkg() == null || !pkg.equals(pane.pkg())) return;
                    // 【bug 1】按返回后前台换人，不一定是「应用真退出了」：
                    // PipPane.open() 换应用时旧 task 留在栈底，返回露出的常常就是这块屏
                    // 原来的常驻/默认应用（日志实测 com.cz.gps / com.baidu.naviauto）。
                    // 旧版一律 pane.close()，连同栈底一起清空，且 openDefaults() 只在启动时跑，
                    // 于是「双画布局恢复了，但活动画中画里的 app 没显示回来」。
                    final int slot = pane.index + 1;
                    String base = Prefs.getDefaultApp(MainActivity.this, slot);
                    if (base == null) base = pane.basePkg();
                    if (base == null || base.equals(pkg)) {
                        FileLog.append("画中画 " + pkg + " 已退出（现在 "
                                + (nowTop.isEmpty() ? "空屏" : nowTop) + "）→ 无基准应用，清空这块屏");
                        pane.close();
                        return;
                    }
                    if (nowTop.equals(base)) {
                        // 前台已经是基准应用：只把记录对齐，别重开、别清屏（零闪烁）
                        FileLog.append("画中画 " + pkg + " 返回后露出基准应用 " + base
                                + " → 对齐记录，保持显示");
                        pane.adoptForeground(base);
                    } else {
                        // 空屏 / 换成无关应用：把基准应用重新拉回这块屏
                        FileLog.append("画中画 " + pkg + " 已退出（现在 "
                                + (nowTop.isEmpty() ? "空屏" : nowTop) + "）→ 恢复基准应用 " + base);
                        if (pip != null) pip.open(base, slot);
                    }
                });
                return;
            }
        }, "pipdesk-pane-exit").start();
    }

    /**
     * 【bug 2 兜底】单画模式下连续点返回，临时应用却始终不退出时，强制恢复原布局。
     *
     * 为什么需要：watchSingleExit 只在每次 sendBack 之后起一个约 790ms 的一次性线程，
     * 判据是「前台还是不是这个 pkg」。万能遥控这类 app 自己消费 BACK（或返回栈不 finish），
     * top 一直是它 → 每次都被判成「应用内返回，保持单画」，按十次也不会恢复。
     * 小米社区按 1~2 次就变空屏，走正常 watchSingleExit 分支，不会碰到这个兜底。
     *
     * 保留「应用内返回（详情→列表）不恢复布局」的语义：只有连续 3 次返回都没让
     * top 变化才出手。restoreSingleAfterExit() 自带 singleMode 保护，重复调用幂等。
     *
     * @return true = 已强制恢复布局，调用方不必再起 watchSingleExit 线程
     */
    private void resetSingleBackCounter() {
        singleBackCount = 0;
        singleBackForPkg = null;
    }

    private boolean forceExitSingleIfStuck() {
        if (pip == null || !pip.inSingleMode()) return false;
        final String cur = pip.singleAppPkg();
        if (cur == null) return false;
        if (!cur.equals(singleBackForPkg)) {
            singleBackForPkg = cur;      // 换了临时应用：计数从头开始
            singleBackCount = 0;
        }
        singleBackCount++;
        if (singleBackCount < 3) return false;
        singleBackCount = 0;
        FileLog.append("单画 " + cur + " 连续 3 次返回仍在前台 → 强制恢复原布局");
        if (pip.inSingleMode()) {
            pip.restoreSingleAfterExit();   // 只恢复布局，单画屏留着复用，不做冷启动
            toast("已退出单画");
        }
        return true;
    }

    /** 单画布局中时，盯一下单画专用屏的前台应用有没有换人 */
    private void watchSingleExitIfNeeded() {
        if (pip == null || !pip.inSingleMode()) return;
        String pkg = pip.singleAppPkg();
        PipPane p = pip.singlePane();
        if (pkg == null || p == null) return;
        watchSingleExit(pkg, p.displayId());
    }

    /**
     * 单画里按了返回 / 回到桌面：确认这块屏的前台还是不是那个临时应用，不是了就恢复原布局。
     *
     * 【为什么看"前台是谁"而不是"task 还在不在"】用户遇到的实际情况是应用**退到了后台**
     * （task 还在），此时屏幕上露出的是底下那个应用（比如百度地图），单画就变成了
     * "把百度地图单独铺满"，与单画的目的（临时全屏看某个应用）不符。
     * 同时应用内部还有返回栈（详情→列表），只看"按了返回"就把布局收掉也是错的。
     * 判据只有一个：这块屏的 topResumedActivity 还是不是它。
     *
     * 两次采样（0.45s / 0.75s）都换人才收，避免切换瞬间误判；读不到就不动布局。
     */
    private void watchSingleExit(final String pkg, final int displayId) {
        if (displayId < 0) return;
        // 同一个目标同时只允许一条监视线程。
        // onResume、每次返回、布局恢复都会调到这里；以前每次都新起一条，于是同时有
        // 2~3 条线程各自每 250ms 跑一次全量 dumpsys —— 越用越卡，且判据还会互相打架。
        final String key = pkg + "@" + displayId;
        synchronized (this) {
            if (key.equals(singleExitKey)
                    && SystemClock.elapsedRealtime() - singleExitAt < 12_000L) return;
            singleExitKey = key;
            singleExitAt = SystemClock.elapsedRealtime();
        }
        new Thread(() -> {
          try {
            /*
             * 【两段式】（用户实测："第一次点不显示，第二次才正常"，日志 09:41:58 现场抓到）
             *
             * 首次单画打开时应用刚被 force-stop 重启，头几百毫秒这块屏没帧、
             * topResumedActivity 是空的 —— 旧版直接把"空屏"判成"已退出"，
             * 单画布局刚摆好就被撤回双画，应用起来了用户也看不见。
             * （0.23.2 的第一版护栏用"帧数超过打开时刻基线"防这个，但虚拟屏建好时
             *   会吐一帧空帧，frames 立刻超过基线，护栏被穿透 —— 手机上复现。）
             *
             * 所以判据改为**状态机**：只有确认应用真的到过前台（top==pkg）之后，
             * "空屏 / 换成别的应用"才算退出；应用还没出现过时，空屏是正常的启动
             * 空窗，继续等；10 秒还没出现过就放弃监视、不动布局
             * （PipPane.firstOpenWatchdog 会兜底重新拉起）。
             */
            long deadline = android.os.SystemClock.elapsedRealtime() + 10_000L;
            boolean everForeground = false;
            while (true) {
                if (isDestroyed() || pip == null) return;
                if (!pip.inSingleMode() || !pkg.equals(pip.singleAppPkg())) return;
                String top;
                try {
                    top = pip.topPkgOnDisplay(displayId);
                } catch (Throwable t) {
                    return;                     // 读不到就别乱动布局
                }
                if (top != null) {
                    if (top.equals(pkg)) {
                        everForeground = true;
                        break;                  // 到过前台了：进入退出采样
                    }
                    if (!top.isEmpty() && everForeground) {
                        // 应用出现过、现在前台是别的应用：真退出
                        final String nowTop = top;
                        runOnUiThread(() -> {
                            if (pip == null || !pip.inSingleMode()) return;
                            if (!pkg.equals(pip.singleAppPkg())) return;
                            FileLog.append("单画临时应用 " + pkg + " 已不在前台（现在 "
                                    + nowTop + "）→ 恢复原布局");
                            pip.restoreSingleAfterExit();
                        });
                        return;
                    }
                    // 空屏（启动空窗）或别的应用但本应用还没出现过：继续等
                }
                if (android.os.SystemClock.elapsedRealtime() > deadline) break;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!everForeground) {
                FileLog.append("单画退出检测：" + pkg
                        + " 10s 未到过前台，放弃监视（不动布局，等首开看门狗兜底）");
                return;
            }
            // 采样：返回键刚下发时这块屏的前台可能还没换人，所以从 80ms 起密集看几次。
            // **第一次读到换了人就恢复**，不再要求连续两次 ——
            // 判据是「这块屏的前台还是不是那个临时应用」，应用内部返回（详情→列表）
            // 时前台仍是同一个包名，不存在误判；多等一轮只会多一帧"全屏露出底下应用"。
            long[] waits = {40, 60, 100, 150, 200, 240};
            for (long w : waits) {
                try {
                    Thread.sleep(w);
                } catch (InterruptedException e) {
                    return;
                }
                if (isDestroyed() || pip == null) return;
                String top;
                try {
                    top = pip.topPkgOnDisplay(displayId);
                } catch (Throwable t) {
                    return;                     // 读不到就别乱动布局
                }
                if (top == null) continue;      // 这一次没读到（命令超时等），过一会儿再试
                if (top.equals(pkg)) continue;  // 还在前台：应用内返回，保持单画
                final String nowTop = top;
                runOnUiThread(() -> {
                    if (pip == null || !pip.inSingleMode()) return;
                    if (!pkg.equals(pip.singleAppPkg())) return;
                    FileLog.append("单画临时应用 " + pkg + " 已不在前台（现在 "
                            + (nowTop.isEmpty() ? "空屏" : nowTop) + "）→ 恢复原布局");
                    pip.restoreSingleAfterExit();
                });
                return;
            }
          } finally {
            synchronized (MainActivity.this) {
                if (key.equals(singleExitKey)) singleExitKey = null;
            }
          }
        }, "pipdesk-single-exit").start();
    }

    /** 返回键该去哪块画中画；返回 null = 没有可返回的目标 */
    private PipPane backTargetPane() {
        if (pip == null) return null;
        // 单画布局：屏幕上只有单画专用屏，返回当然发给它
        if (pip.inSingleMode()) {
            PipPane sp = pip.singlePane();
            if (isBackTarget(sp)) return sp;
        }
        PipPane p = lastActivePane;
        if (isBackTarget(p)) return p;
        // 没碰过/那块屏已空：退回「活动画中画」，再不行找第一块跑着应用的
        PipPane act = pip.pane(Prefs.getActivePane(this, pip.count()) - 1);
        if (isBackTarget(act)) return act;
        for (int i = 0; i < pip.count(); i++) {
            PipPane q = pip.pane(i);
            if (isBackTarget(q)) return q;
        }
        return null;
    }

    private boolean isBackTarget(PipPane p) {
        // 应用被"全屏打开"到主屏时，BACK 应该走主屏的全局注入（那条路径会把焦点还给应用）
        return p != null && p.pkg() != null
                && p.displayId() >= 0 && !p.isFullscreened();
    }

    @Override
    public void onDockAdd() {
        if (dockPkgs.size() >= Prefs.getDockCount(this)) {
            toast("Dock 已满，可在设置里加大格数");
            return;
        }
        showPicker(-1, 0);
    }

    @Override
    public void onDockLongClick(int pos, String pkg) {
        // 内置快捷方式（设置/深浅模式/重启/返回/主页）在 Dock 里长按：
        // 菜单和普通应用一致 ——「重新选择应用」「删除绑定」。
        // 没有「从 Dock 移除」（语义重复）；「全屏打开 / 指定画中画位置」它们也用不上。
        if (AppInfo.isBuiltin(pkg)) {
            OverlayMenu.show(this, labelOf(pkg),
                    new CharSequence[]{"添加图标", "重新选择应用", "删除绑定"},
                    which -> {
                        if (which == 0) {
                            onDockAdd();            // 追加一个新图标（满了会提示）
                        } else if (which == 1) {
                            showPicker(pos, 0);     // 换绑：选中谁就占这个槽位
                        } else if (pos >= 0 && pos < dockPkgs.size()) {
                            dockPkgs.remove(pos);
                            Prefs.setDock(this, dockPkgs);
                            dockAdapter.notifyDataSetChanged();
                        }
                    });
            return;
        }
        OverlayMenu.show(this, labelOf(pkg),
                new CharSequence[]{"添加图标", "全屏打开", "重新选择应用", "删除绑定", "指定画中画位置"},
                which -> {
                    switch (which) {
                        case 0: onDockAdd(); break;
                        case 1: pip.launchFullScreen(pkg); break;
                        case 2: showPicker(pos, 0); break;
                        case 3:
                            if (pos >= 0 && pos < dockPkgs.size()) {
                                dockPkgs.remove(pos);
                                Prefs.setDock(this, dockPkgs);
                                dockAdapter.notifyDataSetChanged();
                            }
                            break;
                        default: showPaneAssignDialog(pkg); break;
                    }
                });
    }

    // ------------------------------------------------------------ 应用列表回调

    @Override
    public void onAppClick(AppInfo app) {
        openPip(app.pkg, 0);
    }

    /** 应用列表长按拖动排序结束：持久化新顺序（只含普通应用，前 5 个快捷方式固定） */
    @Override
    public void onAppOrderChanged(List<AppInfo> ordered) {
        List<String> pkgs = new ArrayList<>(ordered.size());
        for (AppInfo a : ordered) pkgs.add(a.pkg);
        Log.i("PipDesk", "应用列表排序 → 前5: " + pkgs.subList(0, Math.min(5, pkgs.size())));
        Prefs.setAppOrder(this, pkgs);
    }

    /** 应用列表快捷区拖动排序结束：持久化新顺序（9 个内置功能图标之间的次序） */
    @Override
    public void onShortcutOrderChanged(List<String> order) {
        Prefs.setShortcutOrder(this, order);
    }

    @Override
    public void onAppLongClick(AppInfo app) {
        // 口径：这里选的都是「临时」打开方式（只对本次生效，不写配置、不建绑定）；
        // 设置页那个才是「默认」方式。两者用菜单文案明确区分，别和「指定画中画位置」
        // （建立持久绑定）混在一起。
        // 已绑定应用不参与打开方式路由，菜单里就不摆点了没反应的临时项。
        final boolean bound = pip != null && pip.isAssigned(app.pkg);
        final CharSequence[] items = bound
                ? new CharSequence[]{"在绑定的画中画打开", "全屏打开", "指定画中画位置", "绑定到 Dock"}
                : new CharSequence[]{"临时：活动画中画打开", "临时：全屏打开", "临时：单画中画打开",
                        "指定画中画位置", "绑定到 Dock"};
        OverlayMenu.show(this, app.label, items, which -> {
            if (bound) {
                switch (which) {
                    case 0: openPip(app.pkg, 0); break;
                    case 1: pip.launchFullScreen(app.pkg); break;
                    case 2: showPaneAssignDialog(app.pkg); break;
                    default: addToDock(app.pkg); break;
                }
                return;
            }
            switch (which) {
                case 0: openPipTemp(app.pkg, Prefs.OPEN_ACTIVE); break;
                case 1: openPipTemp(app.pkg, Prefs.OPEN_FULLSCREEN); break;
                case 2: openPipTemp(app.pkg, Prefs.OPEN_SINGLE); break;
                case 3: showPaneAssignDialog(app.pkg); break;
                default: addToDock(app.pkg); break;
            }
        });
    }

    @Override
    public void onSettingsClick() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 应用列表 / Dock 里的「深浅模式」图标：点一下**临时**在深色/浅色之间切换。
     *
     * 需求：不许改设置里的外观模式。所以这里只改内存里的 Ui.dark（Ui.setTransientDark），
     * 一个字都不写 Prefs —— 重启桌面 / 重启机器后仍是设置里的模式；
     * 跟随时间到点、系统夜间模式变化、壁纸变化这些"自动触发"发生时，
     * Ui.updateAppearance 会重算并丢掉这个临时值，按真实情况走。
     */
    @Override
    public void onThemeToggle() {
        boolean toDark = !Ui.dark;
        Ui.setTransientDark(this, toDark);   // 临时外观：不落盘
        syncSystemNightMode(Ui.dark);        // 屏内应用（虚拟屏）跟着系统一起变
        refreshAppearance();
        toast(toDark ? "已临时切换到深色模式" : "已临时切换到浅色模式");
    }

    /** 长按「深浅模式」图标：切换 / 放进 Dock（放进 Dock 后桌面上就直接点得着） */
    @Override
    public void onThemeLongClick() {
        showBuiltinMenu(AppInfo.THEME_PKG);
    }

    /** 点了「重启桌面 / 返回 / 主页」 */
    @Override
    public void onShortcutClick(String pkg) {
        runBuiltin(pkg);
    }

    /** 长按「重启桌面 / 返回 / 主页」 */
    @Override
    public void onShortcutLongClick(String pkg) {
        showBuiltinMenu(pkg);
    }

    /**
     * 内置快捷方式的长按菜单：执行一次 / 放进（或移出）Dock。
     * 四种快捷方式共用一套，免得每加一个图标就要再抄一遍 Dock 逻辑。
     */
    private void showBuiltinMenu(final String pkg) {
        final String label = AppListAdapter.shortcutLabel(pkg);
        final boolean inDock = dockPkgs.contains(pkg);
        final String run = AppInfo.THEME_PKG.equals(pkg)
                ? (Ui.dark ? "切换到浅色" : "切换到深色") : "执行一次";
        final String act = inDock ? "从 Dock 移除" : "放到 Dock";
        OverlayMenu.show(this, label, new CharSequence[]{run, act}, which -> {
            if (which == 0) {
                runBuiltin(pkg);
                return;
            }
            if (inDock) {
                dockPkgs.remove(pkg);
            } else {
                if (dockPkgs.size() >= Prefs.getDockCount(this)) {
                    toast("Dock 已满，可在设置里加大格数");
                    return;
                }
                dockPkgs.add(pkg);
            }
            Prefs.setDock(this, dockPkgs);
            dockAdapter.notifyDataSetChanged();
            toast(inDock ? "已从 Dock 移除" : "已放到 Dock");
        });
    }

    /**
     * 重启桌面。
     *
     * 不杀进程：recreate() 会把 Activity 整个拆掉重建 —— 画中画（虚拟屏 + TextureView）、
     * Dock、玻璃全部重新走一遍 onCreate/onResume。屏内应用被折腾出来的错位、残影、
     * 触摸转发卡住这类问题，这一步就能恢复；比 killProcess 稳（进程活着，
     * 桌面不会有一小段"什么都没有"的空窗）。
     *
     * 但这台车机上 recreate() 重建出来的桌面**不一定自己回到前台**（用户实测：
     * 重启后桌面退出去了，再起来时压在别的应用下面，看起来像"起不来"）。
     * 所以重建前置个标志，onResume 里 moveTaskToFront 拎回前台 + 延迟补一发
     * HOME intent 双保险（见 {@link #bringLauncherToFront}）。
     */
    private void restartLauncher() {
        toast("正在重启桌面…");
        pendingToFront = true;
        // 「重启桌面」= 回到设置里的外观模式：丢掉图标点出来的临时深浅色。
        // 这里是 recreate()，进程没死，光靠静态字段不会自己失效 —— 必须显式清。
        Ui.clearTransientDark();
        recreate();
    }

    /** recreate() 重建完成后要把 task 拎回前台（车机上重建后的 HOME 不一定自己到前台） */
    private static boolean pendingToFront;

    /**
     * 双保险拉回前台：
     * 1) moveTaskToFront 把桌面 task 拎到最前（uid 1000 有 REORDER_TASKS）；
     * 2) 再以 HOME intent 拉一次 —— HOME 是桌面身份，系统必然把它放最前。
     * 重建正常时这两步都是空操作（task 本来就在最前）。
     */
    private void bringLauncherToFront() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(getTaskId(), 0);
        } catch (Throwable t) {
            Log.i(TAG, "moveTaskToFront 不可用：" + t.getClass().getSimpleName());
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable t) {
            Log.i(TAG, "HOME 兜底失败：" + t.getClass().getSimpleName());
        }
    }

    /** 外观（深浅色）变化后，把桌面上所有玻璃和文字刷成新配色 */
    private void refreshAppearance() {
        if (rvDock == null) return;
        appliedDark = Ui.dark;      // 这里刷完即「已应用」，避免下次回来重复整块重刷
        if (dockRow != null) dockRow.setBackground(Ui.glass(this, Prefs.getDockCorner(this)));
        if (dockLeftDiv != null) dockLeftDiv.setBackground(glassDivider());
        if (dockRightDiv != null) dockRightDiv.setBackground(glassDivider());
        if (dockClock != null) dockClock.setTextColor(Ui.tint());
        if (dockWeather != null) dockWeather.setTextColor(Ui.tintDim());
        if (dockClockL != null) dockClockL.setTextColor(Ui.tint());
        if (dockWeatherL != null) dockWeatherL.setTextColor(Ui.tintDim());
        // 功能区的图标/文字都分深浅色两套 → 整块重建最稳（内容只有几个图标，开销可忽略）
        buildDockPanel(dockLeftBox, true);
        buildDockPanel(dockRightBox, false);
        dockAdapter.notifyDataSetChanged();
        appListAdapter.notifyDataSetChanged();
        // 名字 / 标题的字色只跟外观走：重新绑定 + invalidate 即可（WallText 自己会比对 Ui.dark）
        if (appListTitle != null) appListTitle.invalidate();
        pip.refreshAppearance();
        applyDockSlots();
    }

    private void addToDock(String pkg) {
        if (dockPkgs.size() >= Prefs.getDockCount(this)) {
            toast("Dock 已满，可在设置里加大格数");
            return;
        }
        if (!dockPkgs.contains(pkg)) {
            dockPkgs.add(pkg);
            Prefs.setDock(this, dockPkgs);
            dockAdapter.notifyDataSetChanged();
            toast("已加入 Dock");
        }
    }

    // ------------------------------------------------------------ 选应用 / 指定槽位

    /**
     * 选应用：底部半屏弹窗（AppPickerSheet）。
     * replacePos >= 0：替换 Dock 上的该格；paneIndex > 0：在该画中画里打开。
     * 顶部横条可上拉展开 / 下拉关闭，点外部暗区也能关。
     */
    private void showPicker(final int replacePos, final int paneIndex) {
        showPicker(replacePos, paneIndex, true);
    }

    private void showPicker(final int replacePos, final int paneIndex, final boolean rememberDefault) {
        if (appList == null) {
            toast("应用还在加载中…");
            return;
        }
        if (pickerDialog != null && pickerDialog.isShowing()) return;

        // 绑 Dock 时把 5 个内置快捷方式排在最前面（需求：Dock 也能绑桌面设置/深浅模式/
        // 重启桌面/返回/主页）；给画中画选应用时不掺进来 —— 那些不是能开进虚拟屏的应用
        final List<AppInfo> source;
        if (replacePos >= 0 || paneIndex <= 0) {
            source = new ArrayList<>(AppListAdapter.shortcuts());
            source.addAll(appList);
        } else {
            source = appList;
        }

        pickerDialog = new AppPickerSheet(this,
                replacePos >= 0 ? "重新选择应用"
                        : (paneIndex > 0 ? ("画中画 " + paneIndex + " · 选择应用") : "绑定应用到 Dock"),
                source, Prefs.getListColumns(this), Prefs.getIconSize(this),
                ai -> {
                    if (replacePos >= 0) {
                        if (replacePos < dockPkgs.size()) dockPkgs.set(replacePos, ai.pkg);
                    } else if (paneIndex > 0) {
                        pip.open(ai.pkg, paneIndex);
                        // 需求：在空白画中画里选定的应用记为这块屏的默认应用，
                        // 下次开机 / 桌面重建时空闲屏会自动把它拉起来
                        if (rememberDefault) {
                            Prefs.setDefaultApp(MainActivity.this, paneIndex, ai.pkg);
                            toast("已把「" + ai.label + "」设为画中画 " + paneIndex + " 的默认应用");
                        }
                        if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
                        return;
                    } else {
                        if (dockPkgs.contains(ai.pkg)) {
                            toast("已经在 Dock 里了");
                            return;
                        }
                        if (dockPkgs.size() >= Prefs.getDockCount(MainActivity.this)) {
                            toast("Dock 已满，可在设置里加大格数");
                            return;
                        }
                        dockPkgs.add(ai.pkg);
                    }
                    Prefs.setDock(MainActivity.this, dockPkgs);
                    dockAdapter.notifyDataSetChanged();
                });
        pickerDialog.show();
    }

    /** 指定某应用固定在第几号画中画打开 */
    private void showPaneAssignDialog(final String pkg) {
        PaneChooser.show(this, "「" + labelOf(pkg) + "」打开位置",
                pip.count(), Prefs.getPaneOf(this, pkg), which -> {
                    Prefs.setPaneOf(MainActivity.this, pkg, which);
                    toast(which == 0 ? "已改为自动分配" : "已固定到画中画 " + which);
                });
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onStart() {
        super.onStart();
        // 宿主窗口恢复可见：记一次帧快照。与 onStop 那条对比就能看出
        // 「桌面不在前台期间，三块屏的纹理是否停止出帧」——这是判断
        // "屏内应用被反压冻结" vs "应用自己坏了" 的直接证据。
        Log.i(TAG, "LIFE onStart " + paneSnapshot());
    }

    @Override
    protected void onStop() {
        super.onStop();
        PaneGuard.setForeground(false);
        // 遮罩模式下离开桌面就别留着一张黑屏：回来时必须能看到内容
        if (ScreenOff.curtainShown(this)) ScreenOff.dismissCurtain(this);
        Log.i(TAG, "LIFE onStop " + paneSnapshot());
    }

    /** 各画中画当前包名 + 累计出帧数（诊断用，一行看完） */
    private String paneSnapshot() {
        if (pip == null) return "(pip=null)";
        StringBuilder b = new StringBuilder("panes=").append(pip.count());
        for (int i = 0; i < pip.count(); i++) {
            PipPane p = pip.pane(i);
            if (p == null) continue;
            b.append(" p").append(i).append('=')
                    .append(p.pkg() == null ? "idle" : p.pkg())
                    .append(":f").append(p.frames())
                    .append(":st").append(p.displayState())
                    .append(":d").append(p.displayId());
        }
        return b.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PaneGuard.setForeground(true);
        PaneGuard.arm("桌面回到前台");
        AppState.mainResumed = true;
        // 屏幕已经亮着（人醒了/被唤醒）：把息屏兜底闹钟撤掉，免得待会儿又亮一次
        ScreenOff.cancelGuard(this);
        // recreate() 重建完的第一帧：车机上不一定自己回前台，主动拎一次
        if (pendingToFront) {
            pendingToFront = false;
            bringLauncherToFront();
        }
        applySettings();
        // 回到桌面：把「全屏打开」过、还挂在主屏的应用搬回画中画继续显示
        pip.bringBackFullscreened();
        // 单画临时布局里，如果那个临时应用已经不在前台了（退到后台、退出、崩溃），
        // 回到桌面这一下顺手收掉单画，别停在一块只剩底应用的布局上
        watchSingleExitIfNeeded();
        // 语音：接上宿主；设置里开着常驻唤醒就顺带把 KWS 拉起来（已起则幂等）
        Voice.get().attach(this, this);
        // 最后一道兜底：AppOps / pm grant 都没拿到录音权限时，才真的弹系统权限框。
        // 只弹一次，用户拒绝也不再纠缠（设置页可以手动授权）。
        if (Prefs.voiceWake(this) && !VoicePerm.has(this) && !voicePermAsked) {
            voicePermAsked = true;
            try {
                requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 0x5b01);
            } catch (Throwable t) {
                Log.w(TAG, "申请录音权限失败", t);
            }
        }
        // 开机首次 onResume 在首帧之前，别在这里 fork 注入服务抢首帧 CPU：
        // 交给 runBootWork()；之后每次回桌面照常预热。
        if (bootHeavyDone) {
            pip.primeTouch();
        } else {
            // 首帧后的重活到前台再排：中途被 stop 时回调作废，回来重新调度。
            scheduleBootWork();
        }
        Log.i(TAG, "LIFE onResume " + paneSnapshot());
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppState.mainResumed = false;
        stopDockTicker();      // 不在前台就不用每秒刷时间/查天气了
    }

    /** 从设置页返回时的增量刷新 */
    private void applySettings() {
        // 深浅色可能刚在设置页里改过：Ui.dark 变了就必须整块重刷外观，
        // 否则 Dock 玻璃 / 画中画（虚拟桌面）玻璃底 / 图标底板还停在旧配色 ——
        // 低性能模式下是纯色底，看着就是「切了深浅色但桌面没变」。
        Ui.updateAppearance(this);
        boolean appearanceChanged = appliedDark != Ui.dark;
        appliedDark = Ui.dark;

        // 性能模式是全局绘制开关：变了同样要重刷所有背景。
        // 只改 View 属性，不碰虚拟屏和屏内应用。
        boolean perfLow = Prefs.perfLow(this);
        boolean perfChanged = perfLow != appliedPerfLow;
        if (perfChanged) {
            appliedPerfLow = perfLow;
            Ui.syncPerfMode(this);
        }
        if (perfChanged || appearanceChanged) {
            refreshAppearance();
            pip.refreshHandles();              // 分隔条按新配色重画
        }
        // 应用名称底板：只重绑列表，不动布局/虚拟屏
        boolean namePlate = Prefs.appNamePlate(this);
        if (namePlate != appliedNamePlate) {
            appliedNamePlate = namePlate;
            appListAdapter.setNamePlate(namePlate);
        }
        // Dock 位置可能在设置页刚改过：原地换横/竖排，不重建画中画（虚拟屏不受影响）。
        // 下面的 refreshDockPanels() 会按新方向重建两端功能区的子 View。
        boolean wantLeft = Prefs.dockOnLeft(this);
        if (wantLeft != dockVertical) applyDockSide(wantLeft);
        int paneCount = Prefs.getPaneCount(this);
        int dpi = Prefs.getDpi(this);
        int pipCorner = Prefs.getPipCorner(this);
        int dockCount = Prefs.getDockCount(this);
        int iconSize = Prefs.getIconSize(this);
        boolean statusBar = Prefs.showStatusBar(this);

        if (paneCount != appliedPaneCount) {
            List<String> running = new ArrayList<>();
            for (int i = 0; i < pip.count(); i++) {
                String p = pip.pkgAt(i);
                if (p != null) running.add(p);
            }
            pip.build(paneCount);
            bindPaneGestures();
            pip.setPaneLongClickListener(this::showPaneMenu);
            appliedPaneCount = paneCount;
            appliedDpi = dpi;
            for (String p : running) pip.openAuto(p);
            openDefaults();        // 新增的空闲屏也吃默认应用配置
            // 重建后的窗口期：正在飞的应用 task 可能落在刚被替换掉的旧虚拟屏上
            // （recreate 重启桌面实测会走到：task 在旧屏、面板换了新屏 → PiP 冻结）。
            // 2.5s 后逐屏校验，跑错屏的搬回、没了的重新拉起（PipPane.reensureTask）。
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(pip::reensureAll, 2500);
        }
        if (dpi != appliedDpi) {
            pip.setDpi(dpi);
            appliedDpi = dpi;
        }
        // 屏内渲染分辨率：**只在建屏那一刻生效**（PipPane 构造时读 Prefs）。
        //
        // 不在线 apply。车机实测：resize 不会让屏内应用重新测量；换屏后 activity 实例
        // 还挂在旧 display 上，startActivity 只做 START_DELIVERED_TO_TOP，也不 reparent。
        // 两种做法都只会留下一个中间态 —— 调小缩在左上角、调大显示不全。
        // 设置页那边会提示"重启桌面后生效"，并提供一键重启。
        appliedRenderScale = Prefs.getRenderScale(this) / 100f;
        // 圆角只有一套：画中画和 Dock 一起变（需求：调画中画圆角要同时改 Dock）
        if (pipCorner != appliedPipCorner) {
            pip.setCorner(pipCorner);
            if (dockRow != null) dockRow.setBackground(Ui.glass(this, pipCorner));
            appliedPipCorner = pipCorner;
            appliedDockCorner = pipCorner;
        }
        if (dockCount != appliedDockCount) {
            appliedDockCount = dockCount;
            applyDockSlots();
        }
        // Dock 高度只由「Dock 图标大小」反推，所以每次同步都重新应用一遍
        applyDockHeight();
        int dockIcon = Prefs.getDockIcon(this);
        if (dockIcon != appliedDockIcon) {
            appliedDockIcon = dockIcon;
            dockAdapter.setIconDp(dockIcon);
            // 图标变大后 Dock 必须一起加高：否则 View 高度还是旧值，
            // 适配器又拿「Dock 高度 - 24dp」当上限，图标就被这道理应不存在的天花板卡回去，
            // 表现成「Dock 图标设成和应用列表一样大，看起来却明显小一圈」。
            applyDockHeight();
        }
        if (iconSize != appliedIconSize) {
            appliedIconSize = iconSize;
            appListAdapter.setIconDp(iconSize);
            // 图标变大后原列数可能装不下了：列数/图标要一起收敛，否则图标溢出格子被裁
            applyListSpan();
        }
        int listCols = Prefs.getListColumns(this);
        if (listCols != appliedListColumns || effectiveListColumns() != listSpan) {
            appliedListColumns = listCols;
            applyListSpan();
        }
        if (statusBar != appliedStatusBar) {
            appliedStatusBar = statusBar;
            applyStatusBar(statusBar);
        }
        // Dock 左右功能区 / 时间天气 / 亮度自动调节：每次回到桌面都按当前配置重建一遍
        // （开关、左区图标、亮度值都可能刚在设置页里改过）
        refreshDockPanels();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转后：面板方向、Dock 格宽都按新尺寸重算；虚拟屏由 TextureView 的尺寸回调触发重建
        if (paneBox != null && pip != null) {
            paneBox.setHorizontal(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
            pip.applyOrientation();
        }
        // 横屏导航栏在侧边、竖屏在底部：系统栏留白必须重新取一次
        if (pager != null && pager.getRootView() != null) {
            pager.getRootView().requestApplyInsets();
        }
        // 列数按新宽度重算（图标必须完整放进格子），Dock 高度按新高度收紧
        applyListSpan();
        applyDockHeight();
        // 换方向后页面宽度变了，滚动位置必须重新对齐，否则会停在两页中间
        if (pager != null) {
            pager.post(() -> {
                pager.setCurrentPage(pager.getCurrentPage());
                applyDockSlots();
            });
        }
        if (rvDock != null) rvDock.postDelayed(this::applyDockSlots, 120);
        // 旋转：**不需要**再延迟重建虚拟屏。TextureView 的 onSurfaceTextureSizeChanged
        // 会原地 resize（等价于设备旋转，屏内应用收到 config change 后自己重排），
        // 且虚拟屏已 setIgnoreOrientationRequest，竖屏锁定应用也不会被 letterbox 成窄带。
        // 之前在这里 postRebuildAll(700) 会把已经转好的屏整个拆掉重建 —— 用户看到的
        // 就是"旋转成功后，又闪了一下然后又重建"。切深浅色本来也不会走到这里。
        // 系统夜间模式切换（uiMode 变化）也走这里：跟随安卓模式下外观要跟着换
        refreshAppearanceIfChanged();
    }

    @Override
    public void onBackPressed() {
        // 桌面不该被返回键退出：在应用列表页就翻回首页，在首页就什么都不做
        if (pager != null && pager.getCurrentPage() != 0) {
            pager.setCurrentPageSmooth(0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        untrackInstance(this);
        if (instance == this) instance = null;
        Voice.get().detach();
        Voice.get().stop();          // 内部丢后台线程收尾，不阻塞主线程
        VoiceCard.reset();
        if (wallpaperReceiver != null) {
            try { unregisterReceiver(wallpaperReceiver); } catch (Throwable ignored) { }
            wallpaperReceiver = null;
        }
        if (themeTicker != null) {
            try { unregisterReceiver(themeTicker); } catch (Throwable ignored) { }
            themeTicker = null;
        }
        // 静态变量里挂着本 Activity 的东西全部摘掉：后台线程（听诊器/探针/心跳）
        // 原来只在 start 时赋值一次，recreate 之后还指着**旧**的 PipManager ——
        // 整棵旧视图树（3 个 TextureView）跟着泄漏，重启几次桌面就能吃掉几十 MB 显存。
        PaneGuard.detach();
        AudioProbe.detach();
        PerfMon.detach();
        UiJank.stop();                      // 主线程抖动监测跟着桌面停（重建后再 start）
        Watchdog.setProbe(null);
        WallTint.setOnReady(null);
        Ui.onWallpaperRechecked = null;     // 静态回调别挂着旧 Activity
        if (pip != null) pip.release();
        pip = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
