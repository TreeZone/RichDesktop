package com.cz.richdesk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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
public class MainActivity extends AppCompatActivity implements DockAdapter.Host, AppListAdapter.FullHost {

    private static final String TAG = "PipDesk";

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

    // 上次应用的配置，用于从设置页返回时做增量刷新
    private int appliedPaneCount = -1, appliedDpi = -1, appliedPipCorner = -1;
    private float appliedRenderScale = -1f;
    private int appliedDockCorner = -1, appliedDockCount = -1, appliedIconSize = -1;
    private int appliedDockIcon = -1;
    private int appliedListColumns = -1;
    /** 当前已应用的方向（旋转后要重建画中画，但切深浅色不能重建） */
    private int appliedOrientation = Configuration.ORIENTATION_UNDEFINED;
    private boolean appliedStatusBar = true;
    private int appliedSlotCount = -1, appliedSlotWidth = -1, appliedSlotCells = -1, appliedSidePad = -1;
    private GridLayoutManager appsGlm;
    private int listSpan = 4;

    /** 调试入口用（DebugTapReceiver 直接驱动界面自测；无手指/无 adb input 时靠它） */
    static MainActivity instance;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
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
                    // 只允许从 Dock 区域起手：Dock 顶沿 = pager 高 - dockWrap 高
                    return inDock(y);
                }
                return !toNext;                  // 应用页：整页右滑回主页，左滑不翻
            }

            @Override public float strictness(float x, float y) {
                // Dock 区：手指本来就贴在那儿点图标，稍微一碰就翻页太容易误触。
                // 2.2 倍位移门槛 + 2.2 倍速度门槛，正常「甩一下」照样能翻。
                return inDock(y) ? 2.2f : 1f;
            }

            private boolean inDock(float y) {
                return dockWrap != null && dockWrap.getHeight() > 0
                        && y >= pager.getHeight() - dockWrap.getHeight();
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

        if (!DataDirFix.ok()) {
            Toast.makeText(this, "配置暂时存不住：请安装权限模块后重启手机",
                    Toast.LENGTH_LONG).show();
        }

        appliedPaneCount = Prefs.getPaneCount(this);
        appliedOrientation = getResources().getConfiguration().orientation;
        appliedDpi = Prefs.getDpi(this);
        // 「屏内渲染分辨率」入口已隐藏（部分车机上会让画中画停在旧尺寸、画面缩左上角），
        // 旧配置里残留的 75/50 一律拉回 100，免得用户以为功能藏了效果还在。
        if (Prefs.getRenderScale(this) != 100) {
            Prefs.setRenderScale(this, 100);
            Log.i("PipDesk", "屏内渲染分辨率已停用，强制回 100%");
        }
        appliedRenderScale = Prefs.getRenderScale(this) / 100f;
        appliedPipCorner = Prefs.getPipCorner(this);
        appliedDockCorner = Prefs.getDockCorner(this);
        appliedDockCount = Prefs.getDockCount(this);
        appliedDockIcon = Prefs.getDockIcon(this);
        appliedIconSize = Prefs.getIconSize(this);
        appliedListColumns = Prefs.getListColumns(this);

        pip = new PipManager(this, paneBox);
        pip.setListener(new PipManager.Listener() {
            @Override public void onOpened(int index, String pkg) { }
            @Override public void onClosed(int index) { }
        });
        pip.build(appliedPaneCount);
        bindPaneGestures();
        pip.setPaneLongClickListener(this::showPaneMenu);
        pip.primeTouch();
        // 屏内应用卡死（实测系统蓝牙 HMI 在虚拟屏上会整片冻住、触摸无响应）
        // 是应用进程内部的问题，宿主侧救不回来，只能重启进程：
        // 这里盯着 ANR 自动兜底，长按菜单还留了手动「重启这个应用」。
        PaneDoctor.start(this, pip);
        // 音频控制面探针：车机上"点蓝牙播放 → 三块屏一起冻住但音乐正常"，
        // 直指 AudioService 在路由切换时被锁。probe 会把这个阻塞自动记进日志。
        AudioProbe.start(this, pip);
        // 资源心跳：每分钟一行（堆/PSS/线程/fd/Display 数/注入队列），
        // 「跑一段时间变卡」时看这行数字是哪一个在涨就知道根因在哪。
        PerfMon.start(this, pip);
        PerfOverlay.apply(this, pip);       // 性能悬浮窗：按设置开关恢复显示
        if (PerfOverlay.isShown()) UiJank.start();   // 桌面重建后重挂主线程抖动监测
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
        openDefaults();            // 有默认应用的空闲屏：桌面一起来就自动拉起

        dockPkgs.addAll(Prefs.getDock(this));
        loadApps();

        // 深浅色外观：先按手动/系统模式立即生效，壁纸亮度在后台精确算完后刷新
        Ui.updateAppearance(this);
        // 壁纸亮度后台重算完成 → 主线程回这里比对并刷新（不再在主线程等解码）
        Ui.onWallpaperRechecked = this::refreshAppearanceIfChanged;
        // 不是「跟随安卓」时，把结果反推给系统：开机后桌面和屏内应用保持一致
        if (Prefs.getThemeMode(this) != Prefs.THEME_AUTO) {
            syncSystemNightMode(Ui.dark);
        }
        new Thread(() -> {
            SunTimes.refreshIfStale(getApplicationContext());   // 「跟随时间」需要日落/日出
            Ui.updateAppearance(getApplicationContext());
            runOnUiThread(this::refreshAppearance);
        }, "pipdesk-appearance").start();
        // 应用名的黑白，按「壁纸那一块的实际颜色」定；壁纸缩略图后台取一次，
        // 取到后刷一遍界面（没取到时先按外观模式兜底）
        WallTint.setOnReady(this::refreshAppearance);
        WallTint.load(this);
        registerWallpaperWatcher();
        registerThemeTicker();
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
            // 只有在应用栏真的「放不下」、必须自己横向滚动时才拦截翻页手势。
            // 判断用「可滚距离 > 12dp」而不是 canScrollHorizontally：后者常常因为
            // 一两个像素的取整余量就返回 true，结果在应用栏上横滑永远翻不到应用列表页。
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int range = rvDock.computeHorizontalScrollRange();
                int extent = rvDock.computeHorizontalScrollExtent();
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
        refreshDockPanels();                     // 左右功能区按配置显示
        rvDock.post(this::applyDockSlots);
        dockWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyDockSlots());
        return pageHome;
    }

    private View buildAppListPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        WallText title = new WallText(this, true);
        title.setDim(true);
        title.setAdaptive(true);
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
        appListAdapter.setShortcutOrder(Prefs.getShortcutOrder(this));
        appListAdapter.attachDragHelper(rvApps);   // 长按拖动排序（原地松手仍是菜单）
        // 列表滚动时 View 只是平移，硬件加速会复用绘制缓存、不重跑 onDraw，
        // 「在 onDraw 里按位置取色」在滚动过程中等于死代码 —— 所以由宿主定期主动刷新：
        // 滚动中每 90ms 一次（跟上手感），停下时再补一次（落在最终位置）。
        rvApps.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long last;
            private int accDy;

            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                // 滚动中的刷新是滚动卡顿的老根因（每个可见标签都重取样），
                // 节流 = 时间（≥120ms）+ 空间（累计滚过约一行高度 48px）双门槛
                accDy += dy;
                if (Math.abs(accDy) < 48) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - last < 120) return;
                last = now;
                accDy = 0;
                repaintWallText(rv);
            }

            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    accDy = 0;
                    repaintWallText(rv);
                }
            }
        });
        return page;
    }

    /** 递归清掉 WallText 的取色缓存，让它在下次绘制时按当前位置重新识别壁纸明暗 */
    private void repaintWallText(View v) {
        if (v instanceof WallText) {
            ((WallText) v).refresh();
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) repaintWallText(g.getChildAt(i));
        }
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
                ((LinearLayout.LayoutParams) lp).height = Ui.dp(this, h) + paneGapPx();
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
        if (left) { dockClockL = null; dockWeatherL = null; dockGapL = null; dockPanelAvailWL = 0; }
        else { dockClock = null; dockWeather = null; dockGap = null; dockPanelAvailW = 0; }
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
            blank.setLayoutParams(new LinearLayout.LayoutParams(
                    pitch, ViewGroup.LayoutParams.MATCH_PARENT));
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
        box.setLayoutParams(new LinearLayout.LayoutParams(pitch, ViewGroup.LayoutParams.MATCH_PARENT));
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
                panelDragStartRawX = e.getRawX();
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (panelDragStartRawX < 0) panelDragStartRawX = e.getRawX();
                float dx = e.getRawX() - panelDragStartRawX;
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
                v.setTranslationX(shown);
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

    /** 按当前顺序重算每个格子的位移：位移 =（现在的位置 − 原始位置）× 格宽 */
    private void layoutPanelCells(int pitch) {
        if (panelDragOrigin == null || panelDragList == null) return;
        for (int i = 0; i < panelDragCells.size() && i < panelDragOrigin.size(); i++) {
            View c = panelDragCells.get(i);
            if (c == panelDragView) continue;   // 被拖的那个跟着手指走
            int now = panelDragList.indexOf(panelDragOrigin.get(i));
            if (now < 0) continue;
            c.setTranslationX((now - i) * (float) pitch);
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
            cell.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            cell.setElevation(0);
            if (cell.getParent() != null) cell.getParent().requestDisallowInterceptTouchEvent(false);
        }
        for (View c : panelDragCells) c.setTranslationX(0);   // 其余格子的位移动画一并不留
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
                (left ? "左侧" : "右侧") + "功能区 · 图标（已放 "
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
     * 时间 + 天气：竖排两行（左右两侧可以各有一套）。
     *
     * 需求后的版式：
     *   第一行  2026-11-10 · 晴 12°   （日期和天气合并一行，灰色小字）
     *   第二行  11:07:18              （当前时间，18sp 加粗，视觉主体）
     * 建的时候就把文字填上 —— 面板可能被 applyDockSlots 重建，空 TextView 会把宽度塌成一条。
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
        clock.setText(timeLabel(cal));

        top.addView(date);
        top.addView(gap);
        top.addView(weather);
        col.addView(top);
        col.addView(clock);
        // 面板宽度 = 占的格数 × 统一格宽（时间天气最少 2 格），内容居中
        int w = panelCellCount(left) * dockPitchPx();
        col.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        int padH = Ui.dp(this, 4);
        col.setPadding(padH, 0, padH, 0);
        // 面板高度变化（改 Dock 图标大小 → Dock 变高/变矮）后重新收敛字号：
        // 构建时 box.getHeight() 还是旧值，只能等这次布局完成再按新高度调一次
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (b - t != ob - ot) refitClockRow(left);
        });
        box.addView(col);
        // 字号自适应：竖屏时格宽窄，按可用宽度把两行字号缩到「尽量大且完整」
        int avail = w - 2 * padH;
        if (left) { dockDateL = date; dockClockL = clock; dockWeatherL = weather;
                    dockGapL = gap; dockPanelAvailWL = avail; }
        else { dockDate = date; dockClock = clock; dockWeather = weather;
               dockGap = gap; dockPanelAvailW = avail; }
        refitClockRow(left);
    }

    /**
     * Dock 时间面板两行字号自适应：宽度、高度**双向**取「能显示完整」的最大字号。
     * 第一行 = 日期 · 天气（13sp 起，最低 8sp）；第二行 = 时分秒（24sp 起，最大 30sp，最低 9sp）。
     * 宽度：在 availPx 里量出两行文本需要的宽，超了就按比例缩。
     * 高度：两行的行高加起来不能超过面板实际高度 —— Dock 图标改小后 Dock 变矮，
     * 只按宽度放大会把字裁掉（用户报的「时间日期显示不全」就是这个）。
     * 天气文案变化后要再调一次（updateDockRealtime）。
     */
    private void refitClockRow(boolean left) {
        int avail = left ? dockPanelAvailWL : dockPanelAvailW;
        TextView d = left ? dockDateL : dockDate;
        TextView g = left ? dockGapL : dockGap;
        TextView w = left ? dockWeatherL : dockWeather;
        TextView c = left ? dockClockL : dockClock;
        if (avail <= 0 || d == null || w == null || c == null) return;
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
        String time = timeLabel(cal);
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
        int dockW = dockWrap != null && dockWrap.getWidth() > 0
                ? dockWrap.getWidth() : getResources().getDisplayMetrics().widthPixels;
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
        int pitch = (dockW - fixed) / Math.max(1, slots + panels);
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
        // 图标区可用宽 = rvDock 自己的宽度（左右功能区 + 外框内边距已经扣掉）；
        // 首帧宽度还是 0 时按「整条 Dock − 功能区格数」估，随后的 onLayoutChange 会纠正
        int availW = rvDock.getWidth();
        if (availW <= 0) {
            int dockW = dockWrap.getWidth() > 0 ? dockWrap.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
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
        int content = cells * slotW;
        // 两侧留白只能是「真正剩余宽度的一半」。
        // 以前这里有个 8dp 下限：剩余不足 16dp 时，内容会比可用宽多出十几像素，
        // RecyclerView 于是有了可滚距离 —— 在应用栏上横滑就变成「列表自己滚几像素」，
        // 手不够长就翻不到应用列表页（用户报的正是这个）。
        int side = Math.max(0, (avail - content) / 2);
        if (slots == appliedSlotCount && slotW == appliedSlotWidth
                && cells == appliedSlotCells && side == appliedSidePad) return;
        appliedSlotCount = slots;
        appliedSlotWidth = slotW;
        appliedSlotCells = cells;
        appliedSidePad = side;

        dockAdapter.setSlotWidth(slotW);
        rvDock.setPadding(side, 0, side, 0);   // 纵向内边距由 dockRow 统一给
        dockAdapter.notifyDataSetChanged();
        // 排查「应用栏明明放得下却还能左右滑」用：range - extent 必须 ≤ 0，
        // 大于 0 就是内容比视口宽，rvDock 会把横滑手势吃掉、翻不到应用列表页。
        final int fAvail = avail, fContent = content, fSide = side;
        rvDock.post(() -> Log.i("PipDesk", "dock 格宽=" + appliedSlotWidth + " 可用=" + fAvail
                + " 内容=" + fContent + " 留白=" + fSide
                + " 可滚距离=" + (rvDock.computeHorizontalScrollRange()
                - rvDock.computeHorizontalScrollExtent())));
    }

    @Override
    public int dockSlots() {
        return appliedSlotCount > 0 ? appliedSlotCount : Prefs.getDockCount(this);
    }

    // ------------------------------------------------------------ 触摸 / 长按

    private void bindPaneGestures() {
        for (int i = 0; i < pip.count(); i++) {
            final PipPane pane = pip.pane(i);
            if (pane == null) continue;
            // 长按已接管：这次手势剩下的 MOVE/UP 一律不转发（否则松手会在屏内应用里点一下）
            final boolean[] consumed = new boolean[1];
            final GestureDetector gd = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public void onLongPress(MotionEvent e) {
                            consumed[0] = true;
                            pip.cancelTouch();     // 给屏内应用补 CANCEL，掐断它的按压
                            showPaneMenu(pane);
                        }

                        /** 空白的画中画：单击直接弹应用列表（需求 2） */
                        @Override
                        public boolean onSingleTapUp(MotionEvent e) {
                            if (pane.pkg() != null) return false;
                            consumed[0] = true;
                            openPickerForPane(pane);
                            return true;
                        }
                    });
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
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
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
        if (pip.count() > 1) {
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
                        pip.close(index);
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
            List<AppInfo> slottedInfos = new ArrayList<>();   // 已换进快捷区的真实应用：给 adapter 缓存图标用
            List<AppInfo> out;
            if (!order.isEmpty()) {
                java.util.Map<String, AppInfo> byPkg = new java.util.HashMap<>();
                for (AppInfo a : res) byPkg.put(a.pkg, a);
                java.util.Set<String> used = new java.util.HashSet<>();
                List<AppInfo> ordered = new ArrayList<>(res.size());
                for (String pkg : order) {
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
            runOnUiThread(() -> {
                appList = resF;
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
     * 打开到画中画。
     * 需求 3：这个应用已经在某块画中画里跑着时，不再重建/重开，只把桌面翻回首页。
     * （重建会清掉虚拟屏再拉起，观感上就是"应用闪一下又重进"）
     */
    private void openPip(String pkg, int preferred) {
        if (pkg == null) return;
        // 未指定屏位的应用：按设置的默认打开方式走
        //（0 = 活动画中画【默认】/ 1 = 全屏 / 2 = 单画中画）。
        // 指定了屏位的应用不受这个设置影响，永远在它的屏位打开（并把布局切回去）。
        if (preferred <= 0 && !pip.isAssigned(pkg)) {
            int mode = Prefs.getOpenMode(this);
            if (mode == Prefs.OPEN_FULLSCREEN) {
                pip.launchFullScreen(pkg);
                int i = pip.indexOfPkg(pkg);
                if (i >= 0) lastActivePane = pip.pane(i);
                if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
                return;
            }
            if (mode == Prefs.OPEN_SINGLE) {
                int idx = pip.openSingle(pkg);
                if (idx >= 0) lastActivePane = pip.pane(idx);
                if (pager.getCurrentPage() != 0) pager.setCurrentPageSmooth(0);
                return;
            }
        }
        boolean running = pip.indexOfPkg(pkg) >= 0;
        // 单画布局下点开「指定了屏位」的应用：即使它还在某块隐藏的屏上跑着
        //（running=true 会跳过 pip.open、绕过 PipManager.open 里的恢复逻辑），
        // 也要先恢复单画前的布局，否则单画布局永远退不回去。
        // 恢复后强制走一遍 open：PipPane.open 对已运行的应用是幂等的（不重建虚拟屏），
        // 只会把应用所在的屏重新放回显示。
        if (pip.inSingleMode()) {
            FileLog.append("单画中点击 " + pkg
                    + " 指定屏位=" + pip.isAssigned(pkg) + " 已在运行=" + running);
        }
        if (pip.inSingleMode() && (preferred >= 1 || pip.isAssigned(pkg))) {
            pip.restoreSingleLayout();
            running = false;
        }
        if (!running) pip.open(pkg, preferred);
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
        if (appList == null || pkg == null) return null;
        for (AppInfo a : appList) if (a.pkg.equals(pkg)) return a;
        return null;
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
        } else if (AppInfo.SLEEP_PKG.equals(pkg)) {
            ScreenOff.sleep(this);
        } else if (AppInfo.RECENTS_PKG.equals(pkg)) {
            Keys.recents(this);
        } else if (AppInfo.VOL_UP_PKG.equals(pkg)) {
            showVolumeHud(Keys.volume(this, true), true);
        } else if (AppInfo.VOL_DOWN_PKG.equals(pkg)) {
            showVolumeHud(Keys.volume(this, false), false);
        }
    }

    /**
     * 音量提示：车机上系统音量条经常不显示（FLAG_SHOW_UI 被 ROM 吞掉），
     * 这里自己在窗口顶部浮一个小玻璃条，1.2 秒后淡出。
     */
    private void showVolumeHud(int[] vol, boolean up) {
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
            Keys.backOnDisplay(this, t.displayId());
            return;
        }
        Keys.back(this);
    }

    /** 返回键该去哪块画中画；返回 null = 没有可返回的目标 */
    private PipPane backTargetPane() {
        if (pip == null) return null;
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
        OverlayMenu.show(this, app.label,
                new CharSequence[]{"画中画打开", "全屏打开", "指定画中画位置", "绑定到 Dock"},
                which -> {
                    switch (which) {
                        case 0: openPip(app.pkg, 0); break;
                        case 1: pip.launchFullScreen(app.pkg); break;
                        case 2: showPaneAssignDialog(app.pkg); break;
                        default: addToDock(app.pkg); break;
                    }
                });
    }

    @Override
    public void onSettingsClick() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 应用列表里的「深浅模式」图标：点一下立刻在深色/浅色之间切换。
     * 切换即锁定为手动模式（原来的「跟随安卓 / 跟随时间」被覆盖，可在设置页改回）。
     */
    @Override
    public void onThemeToggle() {
        boolean toDark = !Ui.dark;
        Prefs.setThemeMode(this, toDark ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
        Ui.updateAppearance(this);
        syncSystemNightMode(Ui.dark);   // 屏内应用（虚拟屏）跟着系统一起变
        refreshAppearance();
        toast(toDark ? "已切换到深色模式" : "已切换到浅色模式");
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
        // 文字颜色由「背后壁纸」决定 → 外观/壁纸一变，让标签重新取样
        if (appListTitle != null) appListTitle.refresh();
        if (rvApps != null) repaintWallText(rvApps);
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
        pip.primeTouch();
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
        // 屏内渲染分辨率（性能设置）：改动后原地 resize 虚拟屏
        float renderScale = Prefs.getRenderScale(this) / 100f;
        if (Math.abs(renderScale - appliedRenderScale) > 0.001f) {
            pip.setRenderScale(renderScale);
            appliedRenderScale = renderScale;
        }
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
        if (instance == this) instance = null;
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
        PaneDoctor.detach();
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
