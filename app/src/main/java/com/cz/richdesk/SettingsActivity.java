package com.cz.richdesk;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面设置。全部改完立即写 Prefs，返回桌面时 MainActivity.onResume 增量生效。
 *
 * 观感：整页是「毛玻璃」——窗口透明 + 壁纸/桌面在底下，Android 12+ 再叠一层背景模糊；
 * 内容放在半透明玻璃卡片里。低版本（安卓 9~11）没有背景模糊，用稍高不透明度的罩层兜底。
 */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout content;
    /** 整页根容器：低性能开关切换时要就地换掉罩层（不等重建） */
    private FrameLayout root;
    /** 进入页面时的外观，返回/回来时对比，变了就重建（设置页没有虚拟屏，重建无副作用） */
    private boolean createdDark;
    /** 测试功能页：带 test_page Intent 打开本类，只渲染实验性内容（亮度等），稳定后并回主页 */
    private boolean testPage;

    /** 内容滚动容器：重建前记录位置、重建后还原 */
    private ScrollView scrollView;
    /** 重建后要还原的滚动位置（-1 = 不还原）。recreate() 会换一个新的 Activity 实例，
     *  instance 字段带不过去，所以用 static 暂存一次。 */
    private static int pendingScrollY = -1;
    /** 「退出再进来」的滚动记忆（主页/测试页分开）。只在进程存活期间有效：
     *  进程被杀 / 重启桌面 / 重启机器后自动归零，从头进。 */
    private static int lastScrollMain = -1;
    private static int lastScrollTest = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.start(this);   // 设置页也可能被单独拉起，日志入口保持幂等
        Ui.syncPerfMode(this);   // 全局绘制开关：早于所有卡片 / 罩层创建
        Ui.updateAppearance(this);
        createdDark = Ui.dark;
        testPage = getIntent() != null && getIntent().getBooleanExtra("test_page", false);
        // 调试钩子（无手指自动化截图核对用，与 debug_set_theme 同一思路）：
        //   --ez debug_name_plate true → 打开「应用名称背景」开关
        // 必须在建页面之前处理，否则开关的初始状态还是旧值。
        if (getIntent() != null && getIntent().hasExtra("debug_name_plate")) {
            Prefs.setAppNamePlate(this, getIntent().getBooleanExtra("debug_name_plate", false));
            getIntent().removeExtra("debug_name_plate");
        }
        androidx.appcompat.app.ActionBar bar = getSupportActionBar();
        if (bar != null) bar.hide();

        // 沉浸式：玻璃罩铺到状态栏/导航栏下面（Android 11+ 用 decorFitsSystemWindows，
        // 9/10 用老的 systemUiVisibility，两条路都留）
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        FrameLayout root = new FrameLayout(this);
        this.root = root;

        // 玻璃罩：只压暗/提亮一点点，模糊负责质感（模糊不可用时这一层保证文字可读）
        root.setBackground(buildScrim());
        // Android 12+ 才真正模糊（低性能模式 / 安卓 9~11 内部直接跳过）
        Ui.blurWindow(getWindow(), 40);

        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 18);
        content.setPadding(padH, 0, padH, Ui.dp(this, 28));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header 与列表分成上下两块：标题行是固定的一条，不参与滚动，
        // 列表在它下面滚 —— 原来 header 放在 content 里，一往下滚就整个划走了。
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildHeader(page);
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 铺满后系统栏的留白要自己加：留白给整页（header 也跟着下移），玻璃罩仍然顶到屏幕边缘
        this.scrollView = sv;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(android.view.WindowInsets.Type.systemBars());
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
            int g = Ui.dp(this, Ui.GAP_DP);
            page.setPadding(l, t, r, b);
            sv.setPadding(0, g, 0, g);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        // 切外观会重建本页（recreate）：重建前记下的滚动位置在这里还原，
        // 否则选完模式整页会跳回最顶部，想接着调下面的项还得重新滚一遍。
        // 普通的「退出再进来」也记忆，但只在进程存活期间：static 变量随进程
        // 死亡自动归零，重启桌面 / 重启机器后从头进（进程被杀重建时 lastScroll
        // 已经是 -1，自然回到顶部）。
        int restoreY = pendingScrollY;
        pendingScrollY = -1;
        if (restoreY < 0) restoreY = testPage ? lastScrollTest : lastScrollMain;
        final int fRestoreY = restoreY;
        if (fRestoreY > 0) {
            sv.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            sv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            sv.scrollTo(0, fRestoreY);
                            // 首帧时内容高度可能还没最终定稿（scrollTo 会被夹住），再补一次
                            sv.post(() -> sv.scrollTo(0, fRestoreY));
                        }
                    });
        }

        registerTick();

        // 调试钩子（无手指自动化验证用，与 DebugTapReceiver 同一思路）：
        //   am start ... --ez debug_pick_theme true   → 直接弹出外观模式选择框
        //   am start ... --ei debug_set_theme 2       → 直接应用某个外观模式
        // 用完必须从 Intent 里删掉：applyThemeMode 会 recreate()，重建后 onCreate 会
        // 再读一遍同一个 Intent —— 参数还在就会"选完关掉，一秒后又自己弹出来"。
        android.content.Intent dbg = getIntent();
        boolean needRebuild = false;
        // 测试功能页不处理这些调试钩子（页面内容不同，themeBox 等字段都是空的）
        if (dbg != null && !testPage) {
            if (dbg.getBooleanExtra("debug_pick_theme", false)) {
                dbg.removeExtra("debug_pick_theme");
                content.postDelayed(this::pickThemeMode, 600);
            }
            if (dbg.hasExtra("debug_set_theme")) {
                int m = clamp(dbg.getIntExtra("debug_set_theme", 0), 0, 3);
                dbg.removeExtra("debug_set_theme");
                content.postDelayed(() -> applyThemeMode(m), 600);
            }
            // 滚到「外观」区块（截图核对用）
            if (dbg.getBooleanExtra("debug_scroll_theme", false)) {
                dbg.removeExtra("debug_scroll_theme");
                content.postDelayed(() -> sv.smoothScrollTo(0, themeBox.getTop()), 500);
            }
            // 滚到底部（息屏/亮度区块截图核对用）
            if (dbg.getBooleanExtra("debug_scroll_bottom", false)) {
                dbg.removeExtra("debug_scroll_bottom");
                content.postDelayed(() -> sv.smoothScrollTo(0, content.getHeight()), 500);
            }
            // 滚到指定像素（截图核对用，免去模拟手指滑动）
            if (dbg.hasExtra("debug_scroll_y")) {
                int y = dbg.getIntExtra("debug_scroll_y", 0);
                dbg.removeExtra("debug_scroll_y");
                content.postDelayed(() -> sv.scrollTo(0, y), 450);
            }
            // 性能模式开关自测：--ez debug_perf_low true
            // 走的是开关监听里同一套逻辑（同步静态开关 → 当场换罩层/清模糊 → 重建）
            if (dbg.hasExtra("debug_perf_low")) {
                final boolean v = dbg.getBooleanExtra("debug_perf_low", true);
                dbg.removeExtra("debug_perf_low");
                content.postDelayed(() -> {
                    Prefs.setPerfLow(this, v);
                    Ui.syncPerfMode(this);
                    applyPerfModeNow();
                    content.postDelayed(this::recreateKeepScroll, 160);
                }, 800);
            }
            // 两侧功能区自测：--ei debug_dockleft 1 --ei debug_lefttype 1（1=时间天气 0=快捷图标）
            if (dbg.hasExtra("debug_dockleft")) {
                Prefs.setDockLeftEnabled(this, dbg.getIntExtra("debug_dockleft", 0) == 1);
                dbg.removeExtra("debug_dockleft");
                needRebuild = true;
            }
            if (dbg.hasExtra("debug_dockright")) {
                Prefs.setDockRightEnabled(this, dbg.getIntExtra("debug_dockright", 0) == 1);
                dbg.removeExtra("debug_dockright");
                needRebuild = true;
            }
            if (dbg.hasExtra("debug_lefttype")) {
                Prefs.setDockLeftType(this, dbg.getIntExtra("debug_lefttype", 0));
                dbg.removeExtra("debug_lefttype");
                needRebuild = true;
            }
            if (dbg.hasExtra("debug_righttype")) {
                Prefs.setDockRightType(this, dbg.getIntExtra("debug_righttype", 0));
                dbg.removeExtra("debug_righttype");
                needRebuild = true;
            }
            // 直接设定某一侧快捷图标：--es debug_lefticons "a|b|c"（pkg 或内置 pipdesk://…）
            if (dbg.hasExtra("debug_lefticons")) {
                String v = dbg.getStringExtra("debug_lefticons");
                List<String> l = new ArrayList<>();
                if (v != null) for (String x : v.split("\\|")) if (!x.isEmpty()) l.add(x);
                Prefs.setDockLeft(this, l);
                dbg.removeExtra("debug_lefticons");
                needRebuild = true;
            }
            if (needRebuild) content.postDelayed(() -> {
                pendingScrollY = scrollView.getScrollY();
                recreate();
            }, 500);
        }

        // 测试功能页（主页点版本行 5 次进入）：只渲染实验性内容。
        // 分支必须放在所有常规 section 之前 —— 之前放在末尾，
        // 导致测试页把主页的全部设置项都带出来了。
        if (testPage) {
            buildBrightness();
            buildPerfOverlay();
            return;
        }

        section("虚拟桌面");
        seek("画中画数量", 1, Prefs.PANE_MAX, 1, Prefs.getPaneCount(this),
                v -> {
                    Prefs.setPaneCount(this, v);
                    rebuildPaneDefaults();   // 屏数变了，路由/默认应用行跟着重建
                }, v -> v + " 屏");
        seek("画中画 DPI", Prefs.DPI_MIN, Prefs.DPI_MAX, 20, Prefs.getDpi(this),
                v -> Prefs.setDpi(this, v), String::valueOf);
        // 屏内渲染分辨率：虚拟屏按这个比例建（像素和 dpi 同比例缩），屏内应用看到的
        // dp 尺寸与 100% 完全一致，只是实际渲染的像素变少、再由纹理放大铺满 ——
        // 是"全屏但略软"，不是"画面变小"。
        //
        // 早先这一项会把画面缩到左上角，原因不是分辨率本身，而是改比例时走了
        // VirtualDisplay#resize（屏内应用不会重新 measure）。现在比例记在虚拟屏槽位上，
        // 比例一变就换屏重建，应用第一次测量就是正确尺寸。详见 PipPane.createDisplay()。
        seek("屏内渲染分辨率（越低越省 GPU）", 50, 100, 10, Prefs.getRenderScale(this),
                v -> Prefs.setRenderScale(this, v), v -> v + "%");
        // 这一项只能靠「重新建屏」生效：运行中改比例会留下中间态 ——
        // 调小则屏内应用还按旧像素画（缩在左上角），调大则显示不全。
        // 根因是车机上 resize / 换屏都不会让屏内应用重新 measure（详见 PipPane.setRenderScale）。
        addRestartHint();
        // 「车机系统分辨率修复」等显示相关入口已去掉：属于系统设置，不该由桌面管。
        // 圆角只有一套：改这里画中画和 Dock 一起变（需求 1）
        seek("圆角（画中画 + Dock）", 0, Prefs.CORNER_MAX, 1, Prefs.getPipCorner(this),
                v -> Prefs.setPipCorner(this, v), v -> v + " dp");
        // 窗口不透明：省掉合成器每一帧的「壁纸层 × 桌面层」混合，
        // 代价是壁纸由桌面自己画（动态壁纸与视差失效）。需要重启桌面生效。
        toggle("桌面窗口不透明（自绘壁纸，需重启桌面）", Prefs.opaqueWindow(this),
                b2 -> Prefs.setOpaqueWindow(this, b2));
        toggle("显示系统状态栏", Prefs.showStatusBar(this),
                b -> Prefs.setShowStatusBar(this, b));

        section("外观");
        toggle("低性能模式（纯色显示）", Prefs.perfLow(this), b -> {
            Prefs.setPerfLow(this, b);
            Ui.syncPerfMode(this);
            // 先当场把模糊 / 罩层换掉（不依赖重建），再重建一次换掉所有卡片背景。
            // 只靠重建的话，窗口背景模糊经常要等下次进页面才真正消失。
            applyPerfModeNow();
            content.postDelayed(this::recreateKeepScroll, 160);
        });
        // 应用列表里的名字默认按「背后壁纸」自动取黑白；壁纸花的时候仍会糊，
        // 打开这个开关就改成「垫一块浅白底 + 黑字」，不再看壁纸。
        toggle("应用名称背景", Prefs.appNamePlate(this),
                b -> Prefs.setAppNamePlate(this, b));
        themeBox = new LinearLayout(this);
        themeBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(themeBox);
        rebuildThemeBox();

        section("Dock");
        action("Dock 位置：" + DOCK_SIDE_LABELS[Prefs.getDockSide(this)], this::pickDockSide);
        seek("Dock 应用数量", Prefs.DOCK_MIN, Prefs.DOCK_MAX, 1, Prefs.getDockCount(this),
                v -> Prefs.setDockCount(this, v), v -> v + " 格");
        // 「Dock 高度」这一项已删除：高度 = 图标 dp + 24dp，跟着图标自动走。
        // 两个独立的高度/尺寸设置只会互相打架（高度设小了就把图标削一刀）。
        seek("Dock 图标大小", Prefs.DOCK_ICON_MIN, Prefs.DOCK_ICON_MAX, 2, Prefs.getDockIcon(this),
                v -> Prefs.setDockIcon(this, v), v -> v + " dp（Dock 高度随之自适应）");

        // Dock 两端的功能区：一行搞定 —— 点进去选「关闭 / 快捷功能 / 时间 + 天气」。
        // 以前是「开关 + 子项：内容」，两项说的其实是同一件事，合并后少点一次。
        action(panelTitle("功能区", true) + "：" + PANEL_MODE_LABELS[panelMode(true)],
                () -> pickPanelMode(true));
        action(panelTitle("功能区", false) + "：" + PANEL_MODE_LABELS[panelMode(false)],
                () -> pickPanelMode(false));

        section("应用列表");
        seek("图标大小", Prefs.ICON_MIN, Prefs.ICON_MAX, 4, Prefs.getIconSize(this),
                v -> Prefs.setIconSize(this, v), v -> v + " dp");
        seek("每行列数", 3, 8, 1, Prefs.getListColumns(this),
                v -> Prefs.setListColumns(this, v), v -> v + " 列");

        // 语音助手：唤醒词常驻默认关（车机上可能和厂商语音抢麦克风），
        // 麦克风自检放同一块 ——「到底有没有采到音」是这套功能能不能用的唯一前提。
        section("语音助手");
        toggle("唤醒词「" + VoiceWords.wakeWord(this) + "」（常驻监听）", Prefs.voiceWake(this), b -> {
            Prefs.setVoiceWake(this, b);
            if (b) Voice.get().startWake();
            else Voice.get().stop();
        });
        action("唤醒词：" + VoiceWords.wakeWord(this) + "（点按修改）", this::pickWakeWord);
        seek("唤醒灵敏度", 10, 90, 5, Prefs.voiceThreshold(this), v -> {
            Prefs.setVoiceThreshold(this, v);
            Voice.get().reloadWake();     // 阈值变了：丢掉旧模型，下次起麦按新值加载
        }, v -> v + "%（越小越容易唤醒）");
        toggle("提示音", Prefs.voiceBeep(this), b -> Prefs.setVoiceBeep(this, b));
        toggle("语音回复", Prefs.voiceReply(this),
                b -> Prefs.setVoiceReply(this, b));
        action("语音引擎：" + voiceEngineLabel(), this::pickVoiceEngine);
        // 已选内置离线女声：打开设置页就后台预热模型，点试播不再等加载（消除「点击后等一下」）
        if (Prefs.voiceTtsMode(this) == Prefs.TTS_LOCAL) Voice.get().preheatLocalTts(this);
        toggle("唤醒后把桌面拉到前台", Prefs.voiceBringFront(this),
                b -> Prefs.setVoiceBringFront(this, b));
        action("麦克风自检（录 5 秒，正常说话）", this::runMicProbe);
        int aliasCount = 0;
        for (java.util.Set<String> s : Prefs.parseAlias(Prefs.voiceAliasRaw(this)).values()) {
            aliasCount += s.size();
        }
        action("语音别名：" + aliasCount + " 条（点按查看 / 删除）", this::showVoiceAliasManager);
        micResult = new TextView(this);
        micResult.setTextSize(12);
        micResult.setLineSpacing(Ui.dp(this, 3), 1f);
        micResult.setTextColor(Ui.tintHint());
        micResult.setShadowLayer(3, 0, 1, Ui.shadow());
        micResult.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, 0);
        micResult.setText("自检结果会显示在这里。");
        content.addView(micResult);

        // 画中画路由：活动画中画 + 每屏默认应用（替代原来的「多屏分配」：
        // 单应用固定屏位在长按图标里设置）
        section("画中画路由");
        paneDefaultsBox = new LinearLayout(this);
        paneDefaultsBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(paneDefaultsBox);
        rebuildPaneDefaults();

        section("壁纸");
        action("更换壁纸（静态图片）", () -> pickWallpaper());

        // 息屏：应用列表里的内置图标（和「主页」一样），可绑到 Dock。
        // 息屏方式固定为黑屏遮罩：不真关屏，按设定的点击次数恢复（默认双击，防误触）。
        section("息屏");
        action("息屏后亮屏方式：" + wakeTapsLabel(), this::pickWakeTaps);

        section("说明");
        note("· 画中画顺序固定：横排从左到右、竖排从上到下，依次是画中画 1/2/3。\n"
                + "· 未绑定应用的「默认打开方式」在上面设置：活动画中画 / 全屏 / 单画中画；\n"
                + "  应用页长按可以「临时」换一种打开方式，只对那一次生效，不改默认设置。\n"
                + "  想让某个应用固定在某一屏：长按应用图标 →「指定画中画位置」（这是建立绑定，持久生效）。\n"
                + "· 画中画默认应用：桌面启动时，空闲的屏会自动打开这里指定的应用。\n"
                + "· 画中画为空时是玻璃态，点 Dock 图标即在其中打开。\n"
                + "· 已经在画中画里的应用，再点一次图标只会回到首页，不会重开。\n"
                + "· 长按画中画（或 Dock 图标、应用图标）可打开更多操作。\n"
                + "· 壁纸只支持静态图片（不支持动态壁纸）。\n"
                + "· 运行日志写入：" + FileLog.dirPath());
        buildVersionRow();
    }

    /**
     * 亮度整块（实验中，挂在「测试功能」页）：总开关 / 日出日落 / 天气 / 写入通道 /
     * 亮度下限 / 诊断 / 车机亮度通道测试。哪一项验证稳定了，就把对应几行搬回主页设置。
     */
    private void buildBrightness() {
        // 亮度自动调节：日落后/日出后用不同亮度，阴天（联网天气）单独一档
        section("屏幕亮度（跟随日出日落 / 天气）");

        Switch brightSw = toggle("总开关：自动调节屏幕亮度", Prefs.brightAuto(this), b -> {
            Prefs.setBrightAuto(this, b);
            if (b) {
                Weather.refreshIfStale(this);       // 先把天气捞一下，阴天判断才准
                SunTimes.refreshIfStaleAsync(this);
                Brightness.applyIfAuto(this);
                toast("当前：" + Brightness.describe(this));
            } else {
                // 把亮度控制权还给系统（本来自动就恢复自动，本来手动就恢复原值）
                Brightness.restore(this);
                toast("已停止自动调节，亮度控制权已还给系统");
            }
        });

        Switch sunSw = toggleChild("跟随日出日落（日落后变暗）", Prefs.brightUseSun(this), b -> {
            Prefs.setBrightUseSun(this, b);
            if (b) SunTimes.refreshIfStaleAsync(this);
            Brightness.applyIfAuto(this);
            toast(b ? "已开启：日落后切到夜间亮度" : "已关闭：不再按日落变暗");
        });
        View nightSeek = seekChild("夜间亮度（日落后）", Prefs.BRIGHT_MIN, Prefs.BRIGHT_MAX, 5,
                Prefs.getBrightNight(this),
                v -> { Prefs.setBrightNight(this, v); Brightness.apply(this, v); },
                v -> v + " / 255");
        View daySeek = seekChild("白天亮度（日出后）", Prefs.BRIGHT_MIN, Prefs.BRIGHT_MAX, 5,
                Prefs.getBrightDay(this),
                v -> { Prefs.setBrightDay(this, v); Brightness.apply(this, v); },
                v -> v + " / 255");

        Switch weatherSw = toggleChild("跟随天气（阴雨天变暗）", Prefs.brightUseWeather(this), b -> {
            Prefs.setBrightUseWeather(this, b);
            if (b) Weather.refreshIfStale(this);
            Brightness.applyIfAuto(this);
            toast(b ? "已开启：阴/雨/雪天切到阴天亮度" : "已关闭：不再按天气变暗");
        });
        View cloudySeek = seekChild("阴天 / 雨雪亮度", Prefs.BRIGHT_MIN, Prefs.BRIGHT_MAX, 5,
                Prefs.getBrightCloudy(this),
                v -> { Prefs.setBrightCloudy(this, v); Brightness.apply(this, v); },
                v -> v + " / 255");

        // 三条写入通道 + 最低亮度下限：TS10 一类车机只认其中一条，谁黑屏就关谁
        Switch chSet = toggleChild("通道① 系统设置（标准 Android 路径）",
                Prefs.brightChSetting(this),
                b -> { Prefs.setBrightChSetting(this, b); Brightness.applyIfAuto(this); });
        Switch chSurf = toggleChild("通道② 显示层（HWC 直压，绕过系统策略）",
                Prefs.brightChSurface(this),
                b -> { Prefs.setBrightChSurface(this, b); Brightness.applyIfAuto(this); });
        Switch chSys = toggleChild("通道③ 背光节点（需 root）",
                Prefs.brightChSysfs(this),
                b -> { Prefs.setBrightChSysfs(this, b); Brightness.applyIfAuto(this); });
        View floorSeek = seekChild("最低亮度下限（防车机把低亮度当关屏）", 0, 40, 1,
                Prefs.getBrightFloorPct(this),
                v -> { Prefs.setBrightFloorPct(this, v); Brightness.apply(this, Brightness.target(this)); },
                v -> v == 0 ? "不设下限" : "不低于 " + v + "%");

        // 三层父子关系：总开关 → 跟随日出日落/跟随天气 → 各亮度滑条。
        // 任一层父开关关掉，它下面的子项整块置灰不可改。
        children(brightSw, sunSw, nightSeek, daySeek, weatherSw, cloudySeek,
                chSet, chSurf, chSys, floorSeek);
        children(sunSw, nightSeek, daySeek);
        children(weatherSw, cloudySeek);
        action("现在为什么是这个亮度？", () -> toast(Brightness.describe(this)
                + "\n天气：" + Weather.dockLabel(this) + "（" + Weather.ageLabel(this) + "）"
                + "\n日落：" + SunTimes.describe(this)
                + "\n" + Brightness.diagnose(this)));
        action("马上按当前档位写一次亮度（车机没反应时点这个）", () -> {
            Brightness.apply(this, Brightness.target(this));
            new android.os.Handler(getMainLooper()).postDelayed(
                    () -> toast("已写入 → " + Brightness.target(this) + " / 255\n"
                            + Brightness.diagnose(this)), 900);
        });
        action("亮度诊断（列出亮度通道/背光节点，可截图给我）", () -> {
            toast("正在采集…");
            new Thread(() -> {
                final String rep = Brightness.diagnoseReport(this);
                FileLog.append("===== 亮度诊断 =====\n" + rep);
                runOnUiThread(() -> {
                    try {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("亮度诊断")
                                .setMessage(rep)
                                .setPositiveButton("好", null)
                                .show();
                    } catch (Throwable t) {
                        toast(rep);
                    }
                });
            }, "bright-diag").start();
        });
        action("立即刷新天气（平时每 15 分钟自动刷新）", () -> {
            toast("正在刷新天气，稍等…");
            Weather.forceRefresh(this, msg -> toast(msg));
        });
        action("天气诊断（位置来源 / 失败原因 / 服务连通性）", () -> {
            toast("正在检测网络…");
            new Thread(() -> {
                final String rep = Weather.diagnose(this);
                FileLog.append("===== 天气诊断 =====\n" + rep);
                runOnUiThread(() -> toast(rep));
            }, "weather-diag").start();
        });
        // 车机亮度排查：TS10 一类车机的背光常被厂商服务接管，只会响应其中一条通道。
        // 一条一条开测试，看屏幕对哪条有反应；有反应的留着，没反应的黑屏通道关掉。
        section("车机亮度通道测试（点一条，盯 3 秒屏幕有没有变暗）");
        action("测试① 系统设置通道：写 40%", () -> {
            Brightness.testSetting(this, 0.4f);
            toast("已写系统亮度 = 40%（看屏幕）");
        });
        action("测试② 显示层通道：写 40%", () -> {
            Brightness.testSurface(this, 0.4f);
            toast("已写显示层 = 40%（看屏幕）");
        });
        action("测试③ 背光节点通道：写 40%", () -> {
            Brightness.testSysfs(this, 40);
            toast("已写背光节点 = 40%（看屏幕）");
        });
        action("测试完：按当前档位恢复", () -> {
            Brightness.apply(this, Brightness.target(this));
            toast("已恢复 → " + Brightness.target(this) + " / 255");
        });
        note("· 拖下面三个滑条会立刻写到屏幕（不等总开关，方便你现场对亮度）；"
                + "总开关只决定「桌面上要不要按日出日落/天气自动改」。\n"
                + "· 两个子项（日出日落 / 天气）可以各自开关；总开关关掉时后台完全不碰亮度，"
                + "适合车机自带自动背光的情况。\n"
                + "· 优先级：夜间 > 阴雨天 > 白天。晚上不管阴不阴都用夜间亮度，"
                + "否则一阴天反而更亮、很刺眼。\n"
                + "· 天气 15 分钟查一次：Open-Meteo 的数据本身十几分钟才更新，"
                + "查得再勤也是同一份结果；只在「Dock 显示天气」或「跟随天气」开着时才联网。\n"
                + "· 阴天判断只认「阴 / 雾 / 雨 / 雪 / 雷」，多云仍按白天亮度，"
                + "免得稍微有点云就把屏幕压暗。\n"
                + "· 日落/天气都联网获取，位置就是上面「日落地点」那个；断网退回固定时段。");
    }

    /**
     * 性能悬浮窗（实验中，挂在「测试功能」页）：实时 CPU / GPU / 系统内存 /
     * 本进程内存 / 线程 / fd / 虚拟屏数量 / 每块画中画帧率。排查卡顿用。
     */
    private void buildPerfOverlay() {
        section("性能悬浮窗（排查卡顿 / 泄漏）");
        toggle("显示性能悬浮窗", Prefs.perfOverlay(this), b -> {
            Prefs.setPerfOverlay(this, b);
            if (b) {
                PerfOverlay.show(this);
                toast(PerfOverlay.isShown()
                        ? "悬浮窗已显示（左上角，不影响触摸）"
                        : "悬浮窗显示失败：请在系统设置里允许本应用显示在其他应用上层");
            } else {
                PerfOverlay.hide();
                toast("悬浮窗已关闭");
            }
        });
        note("· 每秒刷新：CPU（整体+各核）、GPU、系统内存、本进程 pss/heap、\n"
                + "  thr=线程数、fd=文件句柄数、dsp=系统 Display 数、p0..=每块画中画（包名/帧数/帧间隔/状态）。\n"
                + "· 卡顿排查先看 PERF 行（同时会写进日志）：\n"
                + "  displays 持续上涨 = 虚拟屏泄漏；\n"
                + "  threads 上涨 = 线程泄漏；\n"
                + "  pss 涨而 heap 不涨 = native / 显存问题。\n"
                + "· GPU 占用依赖内核节点，各厂商不同（Adreno/Mali/MTK 等自动探测）；\n"
                + "  个别机型节点需要 root，读不到就显示 -，不影响其他数据。\n"
                + "· 高频触摸日志默认关闭，需要时创建 /sdcard/pipdesk_verbose 打开。");
    }

    // ------------------------------------------------------------ 息屏亮屏方式 / 版本彩蛋

    private String wakeTapsLabel() {
        switch (Prefs.getWakeTaps(this)) {
            case 1: return "单击";
            case 3: return "三击";
            default: return "双击";
        }
    }

    /** 选遮罩期点亮屏幕需要的点击次数 */
    private void pickWakeTaps() {
        // 与所有选择器一致：当前项淡底色 + ✓，行标题也显示当前值
        OptionPicker.show(this, "息屏后亮屏方式",
                new String[]{"单击亮屏", "双击亮屏（防误触）", "三击亮屏（更防误触）"},
                new String[]{"点一下就亮，最省事", "默认，避免误触", "最不容易误触"},
                Prefs.getWakeTaps(this) - 1,
                which -> {
                    Prefs.setWakeTaps(this, which + 1);
                    // 行标题是构建时写死的，改完重建页面（保持滚动位置）才能立刻看到新值
                    recreateKeepScroll();
                    toast("已改为" + wakeTapsLabel() + "亮屏");
                });
    }

    /** 连点计数状态（版本行彩蛋）；解锁后点版本行直接进测试页，不再计数、不再加行 */
    private boolean testUnlocked;
    private View testEntryRow;
    private int verTaps;
    private long verLastAt;

    /** 版本行：显示构建版本；2.5 秒内连点 5 次解锁「测试功能」入口（只加一次） */
    private void buildVersionRow() {
        TextView tv = new TextView(this);
        tv.setText("版本 " + versionLabel());
        tv.setTextSize(12);
        tv.setTextColor(Ui.tintHint());
        tv.setShadowLayer(3, 0, 1, Ui.shadow());
        tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, 0);
        content.addView(tv);
        tv.setOnClickListener(v -> {
            if (testUnlocked) { openTestPage(); return; }   // 已解锁：直接进，不再数数
            long now = android.os.SystemClock.uptimeMillis();
            if (now - verLastAt > 2500) verTaps = 0;   // 点慢了重新计
            verLastAt = now;
            if (++verTaps >= 5) {
                testUnlocked = true;
                verTaps = 0;
                toast("已解锁测试功能");
                // 入口只加一次：解锁状态由 testUnlocked 保证，重复连点也不会多行
                if (testEntryRow == null) {
                    testEntryRow = action("测试功能（谨慎使用，可能造成无法恢复的影响）",
                            this::openTestPage);
                }
            } else if (verTaps >= 2) {
                toast("再点 " + (5 - verTaps) + " 次进入测试功能");
            }
        });
    }

    private void openTestPage() {
        startActivity(new Intent(this, SettingsActivity.class).putExtra("test_page", true));
    }

    /** 本应用版本号：从 PackageManager 读，不依赖 BuildConfig 开关 */
    private String versionLabel() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + "（vc" + pi.versionCode + "）";
        } catch (Throwable t) {
            return "未知";
        }
    }

    // ------------------------------------------------------------ 壁纸

    private static final int REQ_WALLPAPER = 101;

    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择壁纸图片"), REQ_WALLPAPER);
        } catch (Throwable t) {
            toast("没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            applyWallpaper(data.getData());
        }
    }

    /** 解码图片（按壁纸期望尺寸采样，防 OOM）并设为系统静态壁纸 */
    private void applyWallpaper(final android.net.Uri uri) {
        toast("正在设置壁纸…");
        final android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
        new Thread(() -> {
            try {
                int wantW = Math.max(1, wm.getDesiredMinimumWidth());
                int wantH = Math.max(1, wm.getDesiredMinimumHeight());

                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                android.graphics.BitmapFactory.decodeStream(in, null, o);
                if (in != null) in.close();

                int sample = 1;
                while (o.outWidth / (sample * 2) >= wantW && o.outHeight / (sample * 2) >= wantH) {
                    sample *= 2;
                }
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = sample;
                java.io.InputStream in2 = getContentResolver().openInputStream(uri);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in2, null, o2);
                if (in2 != null) in2.close();

                if (bmp == null) {
                    runOnUiThread(() -> toast("图片读取失败"));
                    return;
                }
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    // 只设置主屏的静态壁纸；不碰动态壁纸/锁屏
                    wm.setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM);
                } else {
                    wm.setBitmap(bmp);
                }
                runOnUiThread(() -> toast("壁纸已更新"));
            } catch (Throwable t) {
                android.util.Log.w("PipDesk", "设置壁纸失败", t);
                runOnUiThread(() -> toast("设置失败：" + t.getClass().getSimpleName()));
            }
        }, "pipdesk-wallpaper").start();
    }

    private LinearLayout paneDefaultsBox;
    /** 外观设置卡片容器（模式/入夜/天亮/日落/地点，改一项只重建这块） */
    private LinearLayout themeBox;
    /** 父开关 → 子项卡片：父开关一关，子项整块变灰不可调 */
    private final java.util.Map<Switch, List<View>> childRows = new java.util.HashMap<>();
    /** 应用列表缓存（含图标），默认应用选择器用 */
    private List<AppInfo> appCache;

    /** 活动画中画 + 每屏默认应用两块动态内容；屏数变化后重建 */
    private void rebuildPaneDefaults() {
        if (paneDefaultsBox == null) return;
        paneDefaultsBox.removeAllViews();
        int n = Prefs.getPaneCount(this);
        int active = Prefs.getActivePane(this, n);   // 默认最后一屏
        actionTo(paneDefaultsBox, "默认打开方式：" + openModeLabel(),
                () -> pickOpenMode());
        actionTo(paneDefaultsBox, "活动画中画：画中画 " + active,
                () -> chooseActivePane(n));
        for (int i = 1; i <= n; i++) {
            final int pane = i;
            String cur = Prefs.getDefaultApp(this, pane);
            String shown = cur == null ? "未设置" : labelOf(cur);
            actionTo(paneDefaultsBox, "画中画 " + pane + " 默认打开：" + shown,
                    () -> pickDefaultApp(pane));
        }
    }

    /** 配置活动画中画（1..屏数，只有一个） */
    private void chooseActivePane(final int paneCount) {
        PaneChooser.show(this, "活动画中画（未绑定的应用在这里打开）",
                paneCount, Prefs.getActivePane(this, paneCount), false, which -> {
                    Prefs.setActivePane(this, which);
                    // 旧版选完不刷新，行上还显示旧值 → 用户看到的就是「改了不生效」
                    rebuildPaneDefaults();
                    toast("活动画中画已设为画中画 " + which);
                });
    }

    private String openModeLabel() {
        switch (Prefs.getOpenMode(this)) {
            case Prefs.OPEN_FULLSCREEN: return "全屏";
            case Prefs.OPEN_SINGLE: return "单画中画";
            default: return "活动画中画";
        }
    }

    /** 未指定屏位的应用默认怎么打开：活动画中画 / 全屏 / 单画中画 */
    /**
     * TTS 引擎选择：列出本机声明了 TTS_SERVICE 的应用。
     * 「跟随系统默认」和系统当前默认引擎是一回事，所以默认引擎不重复列出；
     * 其余引擎逐个显示，装了第三方 TTS（讯飞语记等）后重新打开本页即可看到。
     * 车机 ROM 往往没有系统 TTS 设置页，这里就是换音色的口子。选完立刻试听。
     */
    /**
     * TTS 引擎选择：列出本机声明了 TTS_SERVICE 的应用。
     *
     * **不再单列「跟随系统默认」** —— 它和"系统当前默认引擎"本来就是同一个东西，
     * 并排两行会让人以为是两个不同的引擎。现在把系统默认那个标成「（系统默认）」，
     * 选中它存的仍是空串 = 跟随系统默认（系统设置里换了引擎，这里自动跟着变）。
     *
     * 判断哪个是默认：优先 ResolveInfo.isDefault（PackageManager 直接算好的），
     * 再兜底读一次 tts_default_synth。之前只读 Secure 设置，在 MIUI 上取不到值，
     * 于是默认引擎被当成普通引擎又列了一行 —— 用户看到的就是「跟随系统默认」+
     * 「系统语音引擎」两项，其实是同一个引擎。
     */
    /**
     * 自定义唤醒词。
     *
     * 为什么是个输入框而不是预置列表：KWS 模型认的是拼音音素，任意中文都能现场
     * 转（{@link VoiceWords}：汉字 → 带声调拼音 → tokens.txt 音素切分），所以
     * 用户想叫它什么都行。转不出来的生僻字会退回默认词并在下面提示。
     */
    private void pickWakeWord() {
        // 与 GlassMenu 的菜单**同一套**玻璃底/标题/分隔线/按钮行 —— 之前用 AlertDialog
        // 直接继承应用的透明窗口主题，弹出来连底色都没有，字全糊在壁纸上。
        GlassMenu.input(this, "唤醒词（2~6 个汉字最好识别）",
                Prefs.voiceWakeWord(this), "例如：你好小瑞",
                "改完立刻生效。读不出的生僻字会自动退回默认词「" + VoiceWords.DEFAULT + "」。",
                v -> {
                    String word = (v == null || v.isEmpty()) ? VoiceWords.DEFAULT : v;
                    Prefs.setVoiceWakeWord(this, word);
                    String kw = VoiceWords.keywordsFile(this, Prefs.voiceWakeWord(this));
                    // 立刻按新词重载 KWS：先丢掉旧模型，再按设置决定是否重新起麦
                    Voice.get().reloadWake();
                    boolean want = Prefs.voiceWake(this);
                    Voice.get().stop();
                    if (want) Voice.get().startWake();
                    toast(kw == null
                            ? "已保存「" + Prefs.voiceWakeWord(this) + "」（这句模型读不出，实际用的是默认词）"
                            : "唤醒词已改为「" + Prefs.voiceWakeWord(this) + "」");
                    recreate();
                });
    }


    private String ttsEngineLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "跟随系统默认";
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence lb = getPackageManager().getApplicationLabel(ai);
            return lb == null ? pkg : lb.toString();
        } catch (Throwable t) {
            return pkg + "（已卸载）";
        }
    }

    private String voiceEngineLabel() {
        String sel = Prefs.voiceTtsSel(this);
        if (Prefs.TTS_SEL_LOCAL.equals(sel)) return "内置离线女声";
        if (Prefs.TTS_SEL_SYS.equals(sel)) return "跟随系统";
        return ttsEngineLabel(sel);   // 第三方引擎：显示 app 名
    }

    /**
     * 语音引擎：合并原来「回复音色」与「指定 TTS 引擎」两行为一行。
     * 两个固定项（内置离线女声 / 跟随系统）+ 本机已安装的非默认第三方 TTS 引擎。
     * 选完立刻试听。
     */
    private void pickVoiceEngine() {
        PackageManager pm = getPackageManager();
        String sysDefault = null;
        try {
            sysDefault = android.provider.Settings.Secure.getString(
                    getContentResolver(), "tts_default_synth");
        } catch (Throwable ignored) {
        }
        final List<String> labels = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        final List<String> sels = new ArrayList<>();   // 存进 prefs 的值
        final List<String> seen = new ArrayList<>();    // 已安装引擎包名

        // 固定项 1：内置离线女声
        labels.add("内置离线女声");
        hints.add("标贝中文女声，完全离线、不依赖系统 TTS；约 129MB 模型，首次会解压一次");
        sels.add(Prefs.TTS_SEL_LOCAL);
        // 固定项 2：跟随系统
        labels.add("跟随系统");
        hints.add("跟随 ROM 的 TTS 设置；引擎不可用或设置入口被裁掉时自动改用内置");
        sels.add(Prefs.TTS_SEL_SYS);

        // 第三方：本机所有 TTS_SERVICE，排除系统默认那个（已由「跟随系统」覆盖，避免重复）
        try {
            List<ResolveInfo> ris = pm.queryIntentServices(
                    new Intent("android.intent.action.TTS_SERVICE"), 0);
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.serviceInfo == null) continue;
                String p = ri.serviceInfo.packageName;
                boolean isDefault = ri.isDefault || p.equals(sysDefault);
                if (isDefault) continue;
                CharSequence lb = ri.loadLabel(pm);
                String name = lb == null ? p : lb.toString();
                seen.add(p);
                labels.add(name);
                hints.add(null);
                sels.add(p);
            }
        } catch (Throwable ignored) {
        }

        String cur = Prefs.voiceTtsSel(this);
        int curIdx = 0;
        for (int i = 0; i < sels.size(); i++) {
            if (sels.get(i).equals(cur)) {
                curIdx = i;
                break;
            }
        }
        // 当前选的第三方引擎已被卸载：补一行回退「跟随系统」
        if (!Prefs.TTS_SEL_LOCAL.equals(cur) && !Prefs.TTS_SEL_SYS.equals(cur)
                && !seen.contains(cur)) {
            labels.add("当前所选引擎已卸载（点这里回退「跟随系统」）");
            hints.add(null);
            sels.add(Prefs.TTS_SEL_SYS);
            curIdx = labels.size() - 1;
        }

        OptionPicker.show(this, "语音引擎",
                labels.toArray(new String[0]),
                hints.toArray(new String[0]),
                curIdx,
                "内置离线女声完全离线；想用其他音色，装一个第三方 TTS 引擎（如讯飞语记）后重新打开本页即可看到。",
                which -> {
                    if (which < 0 || which >= sels.size()) return;
                    String sel = sels.get(which);
                    Prefs.setVoiceTtsSel(this, sel);
                    Voice.get().onTtsEngineChanged();
                    toast("已选「" + labels.get(which) + "」");
                    recreateKeepScroll();
                    Voice.get().previewReply();
                });
    }

    /**
     * 未绑定应用的「默认」打开方式（持久、全局）。
     * 应用页长按里选的是「临时」方式，只对那一次打开生效，见 MainActivity.openPipTemp。
     * 样式统一走 OptionPicker（= Dock 位置 / 功能区那套：当前项淡底色 + ✓）。
     */
    private void pickOpenMode() {
        final String[] labels = {"活动画中画", "全屏打开", "单画中画"};
        final String[] hints = {
                "在「活动画中画」那一块里打开（默认）",
                "整个屏幕打开这个应用",
                "额外单独建一个画中画显示，主页上的画中画不受影响"};
        OptionPicker.show(this, "默认打开方式", labels, hints,
                Prefs.getOpenMode(this),
                "这是「默认」方式，管单击图标等常规打开；应用页长按可临时换一种，只对那一次生效。",
                which -> {
                    Prefs.setOpenMode(this, which);
                    toast("默认打开方式已改为「" + openModeLabel() + "」");
                    rebuildPaneDefaults();
                });
    }

    /** 与底部应用选择器同一套手势：上滑弹到最高、下滑关闭、列表到底再下滑也关闭 */
    private void pickDefaultApp(final int pane) {
        if (appCache == null) appCache = loadApps();

        List<AppInfo> items = new ArrayList<>();
        // 第一项是「不指定」：pkg 为 null，选中即清除该屏的默认应用
        items.add(new AppInfo("不指定（保持空白）", null,
                getDrawable(android.R.drawable.sym_def_app_icon)));
        items.addAll(appCache);

        new AppPickerSheet(this, "画中画 " + pane + " 默认打开", items,
                Prefs.getListColumns(this), 44, ai -> {
            Prefs.setDefaultApp(this, pane, ai.pkg);
            rebuildPaneDefaults();
            toast(ai.pkg == null ? "已清除默认应用" : "已设为 " + ai.label);
        }).show();
    }

    /** 读取可启动应用（按名称排序），图标一起带上，选择器里直接显示 */
    private List<AppInfo> loadApps() {
        PackageManager pm = getPackageManager();
        Intent it = new Intent(Intent.ACTION_MAIN);
        it.addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppInfo> res = new ArrayList<>();
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(it, 0);
        } catch (Throwable t) {
            ris = new ArrayList<>();
        }
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            res.add(new AppInfo(String.valueOf(ri.loadLabel(pm)),
                    ri.activityInfo.packageName, ri.loadIcon(pm)));
        }
        Collections.sort(res, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return res;
    }

    // ------------------------------------------------------------ 基础控件

    /**
     * 固定 header：素面（不加卡片/边框，直接压在页面玻璃背景上，观感与原来一致），
     * 只是挂到 ScrollView 外面，所以不随内容滚走。
     */
    private void buildHeader(LinearLayout parent) {
        // 返回键和标题合并成一个按钮：整个「‹ 桌面设置」是一块热区，
        // 按下时整块亮水波纹，点哪儿都是返回，不再有「图标能点、字不能点」的错觉。
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 4));
        btn.setOnClickListener(v -> finish());
        btn.setContentDescription("返回");
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Ui.dp(this, 24));
        mask.setColor(0xFFFFFFFF);
        btn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Ui.dark ? 0x33FFFFFF : 0x1F000000), null, mask));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(btn, lp);

        // ‹ 字形：44dp 热区撑宽度，字形仍在最左边，标题左对齐位置和之前一致
        final int box = Ui.dp(this, 44);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(34);
        back.setIncludeFontPadding(false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, Ui.dp(this, 5));   // ‹ 字形本身偏上，压一点才视觉居中
        back.setTextColor(Ui.tint());
        back.setMinWidth(box);
        back.setMinHeight(box);
        back.setClickable(false);                   // 点击统一交给外层按钮
        btn.addView(back);

        TextView t = new TextView(this);
        t.setText(testPage ? "测试功能" : "桌面设置");
        t.setTextSize(20);
        t.setTextColor(Ui.tint());
        t.setShadowLayer(4, 0, 1, Ui.shadow());
        t.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 24), 0);   // 右侧留热区
        t.setClickable(false);
        btn.addView(t);
    }

    private void section(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(12);
        tv.setTextColor(Ui.tintHint());
        tv.setShadowLayer(3, 0, 1, Ui.shadow());
        tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), 0, Ui.dp(this, 8));
        content.addView(tv);
    }

    /** 麦克风自检结果（录一段存 wav + 统计能量，见 VoiceMic.probe） */
    private TextView micResult;

    /**
     * 麦克风自检：阻塞 5 秒，必须丢后台线程。
     * 结果只有三种有意义：全 0 静音（被独占 / ROM 不放行）、能量极低、正常。
     * 这一步不做任何模型推理，却能一次性判定整套语音功能到底能不能落地。
     */
    private void runMicProbe() {
        if (micResult != null) {
            micResult.setText("录制中… 5 秒，请用平常的音量说句话。");
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            VoiceMic.Probe p = VoiceMic.probe(app, 5, "selftest");
            runOnUiThread(() -> {
                if (micResult != null) micResult.setText(p.summary);
            });
        }, "pipdesk-mic-probe").start();
    }

    private void note(String text) {        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setTextColor(Ui.tintHint());
        tv.setShadowLayer(3, 0, 1, Ui.shadow());
        tv.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        content.addView(tv);
    }

    private LinearLayout card() {
        return card(content, false);
    }

    /** 卡片挂到指定容器（动态列表用）。child 仅用于父子联动登记，样式与普通卡片一致 */
    private LinearLayout card(ViewGroup parent, boolean child) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, 16));
        int p = Ui.dp(this, 14);
        c.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        parent.addView(c, lp);
        return c;
    }

    interface IntSetter { void set(int v); }
    interface IntFormatter { String format(int v); }

    private View seek(String title, final int min, final int max, final int step,
                      int value, final IntSetter setter, final IntFormatter fmt) {
        return seekIn(content, title, min, max, step, value, setter, fmt, false);
    }

    /** 子级滑条：缩进 + 左侧亮条，视觉上属于上面那个父开关 */
    private View seekChild(String title, final int min, final int max, final int step,
                           int value, final IntSetter setter, final IntFormatter fmt) {
        return seekIn(content, title, min, max, step, value, setter, fmt, true);
    }

    private View seekIn(ViewGroup parent, String title, final int min, final int max, final int step,
                        int value, final IntSetter setter, final IntFormatter fmt, boolean child) {
        LinearLayout c = card(parent, child);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Ui.tint());
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView val = new TextView(this);
        val.setText(fmt.format(value));
        val.setTextSize(13);
        val.setTextColor(Ui.tintDim());
        head.addView(val);
        c.addView(head);

        final int steps = Math.max(1, (max - min) / step);
        SeekBar sb = new SeekBar(this);
        sb.setMax(steps);
        sb.setProgress(clamp((value - min) / step, 0, steps));
        sb.getProgressDrawable().setTint(Ui.tint());
        sb.getThumb().setTint(Ui.tint());

        // 滑条 + 刻度层叠放：刻度点直接画在轨道线上
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(sb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 离散档位刻度：选项不多（≤20 个）时沿轨道画一排分隔点，一眼看出有几档可选。
        // 端点与轨道线精确重合：直接量 progressDrawable 里 track 层的真实 bounds
        // （Material 轨道自带 4dp inset，按滑块宽估算必然对不上）。
        // 滑块压着的位置不画（onProgressChanged 里重绘），点不会叠到滑块上。
        // DPI/圆角/图标大小这类几十档的连续型滑条不画（点太密没有信息量）。
        View ticks = null;
        if (steps + 1 <= 20) {
            ticks = new View(this) {
                @Override protected void onDraw(Canvas canvas) {
                    float left, right, cy;
                    // 量轨道真实边界；拿不到就退回滑块宽估算
                    android.graphics.Rect tb = null;
                    try {
                        android.graphics.drawable.LayerDrawable ld =
                                (android.graphics.drawable.LayerDrawable) sb.getProgressDrawable();
                        android.graphics.drawable.Drawable bg =
                                ld.findDrawableByLayerId(android.R.id.background);
                        if (bg != null) tb = bg.getBounds();
                    } catch (Throwable ignored) { }
                    if (tb != null && tb.width() > 0) {
                        left = sb.getPaddingLeft() + tb.left;
                        right = sb.getPaddingLeft() + tb.right;
                        cy = sb.getPaddingTop() + tb.exactCenterY();
                    } else {
                        float inset = sb.getThumb().getIntrinsicWidth() / 2f;
                        left = inset;
                        right = getWidth() - inset;
                        cy = getHeight() / 2f;
                    }
                    float w = Math.max(1, right - left);
                    float r = Ui.dp(getContext(), 2f);
                    float thumbX = left + w * sb.getProgress() / (float) steps;
                    float hide = sb.getThumb().getIntrinsicWidth() / 2f + r;
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    for (int i = 0; i <= steps; i++) {
                        float x = left + w * i / (float) steps;
                        if (Math.abs(x - thumbX) < hide) continue;
                        p.setColor(i <= sb.getProgress() ? Ui.tint() : Ui.tintDim());
                        canvas.drawCircle(x, cy, r, p);
                    }
                }
            };
            ticks.setContentDescription("共 " + (steps + 1) + " 档");
            wrap.addView(ticks, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        final View ticksF = ticks;

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 4);
        c.addView(wrap, slp);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress * step;
                val.setText(fmt.format(v));
                setter.set(v);
                if (ticksF != null) ticksF.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        return c;
    }

    /**
     * 「改完要重启桌面才生效」的提示行 + 一键重启。
     *
     * 只给「必须重新建屏/重建进程才生效」的设置项用（目前是屏内渲染分辨率）。
     * 不挂在这类项上的普通设置都是即时生效的，不需要这条。
     */
    private void addRestartHint() {
        TextView t = new TextView(this);
        t.setText("改完需重启桌面才生效　点这里立即重启");
        t.setTextSize(12);
        t.setTextColor(Ui.tintDim());
        int pad = Ui.dp(this, 6);
        t.setPadding(0, pad, 0, pad);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> restartDesktopNow());
        content.addView(t);
    }

    /**
     * 重启桌面。
     *
     * 桌面是默认 HOME 时直接杀进程最彻底：系统会立刻把桌面重新拉起，虚拟屏和屏内应用
     * 全部从头建一遍，比例一次就对，绝不会出现"缩左上角"的中间态。
     *
     * 不是 HOME（用户还在用原车桌面）时**不能**杀进程 —— 杀完没人拉起它，桌面就没了。
     * 这种情况只提示手动重启。
     */
    private void restartDesktopNow() {
        boolean home = false;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            home = ri != null && ri.activityInfo != null
                    && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable ignored) { }
        if (!home) {
            toast("当前不是默认桌面，请手动重启桌面");
            return;
        }
        toast("正在重启桌面…");
        content.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()),
                400);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    interface BoolSetter { void set(boolean b); }

    private Switch toggle(String title, boolean value, final BoolSetter l) {
        return toggleIn(content, title, value, l, false);
    }

    /** 子级开关：缩进 + 亮条 */
    private Switch toggleChild(String title, boolean value, final BoolSetter l) {
        return toggleIn(content, title, value, l, true);
    }

    private Switch toggleTo(ViewGroup parent, String title, boolean value, final BoolSetter l) {
        return toggleIn(parent, title, value, l, false);
    }

    private Switch toggleIn(ViewGroup parent, String title, boolean value,
                            final BoolSetter l, boolean child) {
        LinearLayout c = card(parent, child);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Ui.tint());
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            l.set(checked);
            // 父开关直接控制子项能不能调：关掉就整块变灰、不可点/不可拖
            List<View> kids = childRows.get(btn);
            if (kids != null) setRowsEnabled(kids, checked);
        });
        row.addView(sw);
        c.addView(row);
        return sw;
    }

    /**
     * 登记「父开关 → 子项卡片」的关系：子项初始可用状态跟着父开关走。
     * 设置页是一次性建好的静态页面，所以父子联动只能"就地变灰"，
     * 不能像外观那样整页重建（那会丢掉滚动位置、也会闪一下）。
     */
    private void children(Switch parent, View... kids) {
        List<View> l = new ArrayList<>();
        java.util.Collections.addAll(l, kids);
        childRows.put(parent, l);
        setRowsEnabled(l, parent.isChecked());
    }

    /** 整组子项跟着父开关变灰 / 恢复 */
    private void setRowsEnabled(List<View> rows, boolean en) {
        for (View r : rows) {
            r.setAlpha(en ? 1f : 0.38f);
            setEnabledDeep(r, en);
        }
    }

    /**
     * 递归启用/禁用一整个卡片（SeekBar 拖不动、行不可点、文字变淡）。
     *
     * **只动 enabled，绝对不要 setClickable(true)**：卡片里的标题 TextView 本来是
     * clickable=false，事件才会冒泡到卡片身上的 onClick。一旦被这里改成 true，
     * 点标题就变成"TextView 自己消费掉、但没有任何回调"，整行只有边缘能点中 ——
     * 这就是用户说的「左右端功能区点好几次才弹出菜单」。
     */
    private void setEnabledDeep(View v, boolean en) {
        v.setEnabled(en);
        if (v instanceof SeekBar) ((SeekBar) v).setEnabled(en);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), en);
        }
    }

    interface Action { void run(); }

    private View action(String title, final Action a) {
        return actionIn(content, title, a, false);
    }

    /** 子级行（缩进 + 亮条） */
    private View actionChild(String title, final Action a) {
        return actionIn(content, title, a, true);
    }

    /** 指定挂到哪个容器：动态重建的内容必须落在自己的盒子里，否则会越点越多 */
    private View actionTo(ViewGroup parent, String title, final Action a) {
        return actionIn(parent, title, a, false);
    }

    private View actionIn(ViewGroup parent, String title, final Action a, boolean child) {
        LinearLayout c = card(parent, child);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(Ui.tint());
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(this);
        more.setText("›");
        more.setTextSize(20);
        more.setTextColor(Ui.tintHint());
        row.addView(more);

        c.addView(row);
        c.setOnClickListener(v -> a.run());
        return c;
    }

    // ------------------------------------------------------------ 深浅色模式

    /** Dock 左区已放的图标名（设置页回显用） */
    private String dockLeftNames() {
        return namesOf(Prefs.getDockLeft(this));
    }

    /** Dock 右区已放的图标名 */
    private String dockRightNames() {
        return namesOf(Prefs.getDockRightIcons(this));
    }

    private String namesOf(List<String> l) {
        if (l.isEmpty()) return "（空，点桌面功能区「＋」添加）";
        StringBuilder b = new StringBuilder();
        for (String p : l) {
            if (b.length() > 0) b.append(" / ");
            b.append(AppListAdapter.shortcutLabel(p));
        }
        if (l.size() >= Prefs.PANEL_SHORTCUT_MAX) b.append("（已达上限 ").append(Prefs.PANEL_SHORTCUT_MAX).append(" 个）");
        return b.toString();
    }

    /** 功能区三个状态：0 = 关闭，1 = 快捷功能，2 = 时间 + 天气（下标同时也是弹框选项序号） */
    private static final String[] PANEL_MODE_LABELS = {"关闭", "快捷功能（图标）", "时间 + 天气"};
    private static final String[] PANEL_MODE_HINTS = {
            "这一端不放东西，Dock 两头留空",
            "放自己挑的快捷图标（返回 / 主页 / 最近任务…），个数跟着 Dock 格数走",
            "显示当前时间 + 天气（竖排时上下两行）"};

    /** 当前这一端处于哪个模式（用于弹框打勾 / 行标题回显） */
    private int panelMode(boolean left) {
        boolean on = left ? Prefs.dockLeftEnabled(this) : Prefs.dockRightEnabled(this);
        if (!on) return 0;
        int type = left ? Prefs.getDockLeftType(this) : Prefs.getDockRightType(this);
        return type == Prefs.PANEL_CLOCK ? 2 : 1;
    }

    /**
     * 一端的功能区：一个弹框选「关闭 / 快捷功能 / 时间 + 天气」。
     * 以前是「开关」+「内容」两行，说的是同一件事，合并成一行少点一次。
     */
    private void pickPanelMode(final boolean left) {
        OptionPicker.show(this, panelTitle("功能区", left), PANEL_MODE_LABELS,
                PANEL_MODE_HINTS, panelMode(left), which -> {
                    if (which == 0) {
                        if (left) Prefs.setDockLeftEnabled(this, false);
                        else Prefs.setDockRightEnabled(this, false);
                    } else {
                        int t = which == 2 ? Prefs.PANEL_CLOCK : Prefs.PANEL_SHORTCUT;
                        if (left) {
                            Prefs.setDockLeftEnabled(this, true);
                            Prefs.setDockLeftType(this, t);
                        } else {
                            Prefs.setDockRightEnabled(this, true);
                            Prefs.setDockRightType(this, t);
                        }
                        // 第一次切到「快捷功能」且一个图标都没有：先给一套能用的，
                        // 否则这一端看着像没生效（图标全靠桌面长按加）
                        if (t == Prefs.PANEL_SHORTCUT) {
                            List<String> have = left ? Prefs.getDockLeft(this)
                                    : Prefs.getDockRightIcons(this);
                            if (have.isEmpty()) {
                                List<String> d = new ArrayList<>();
                                d.add(AppInfo.BACK_PKG);
                                d.add(AppInfo.HOME_PKG);
                                d.add(AppInfo.RECENTS_PKG);
                                if (left) Prefs.setDockLeft(this, d);
                                else Prefs.setDockRightIcons(this, d);
                                toast("已放入默认图标：返回 / 主页 / 最近任务");
                            }
                        }
                    }
                    // 行标题是建行时写死的，改完重建整页最省事（滚动位置会被还原）
                    if (scrollView != null) pendingScrollY = scrollView.getScrollY();
                    recreate();
                });
    }

    /** Dock 位置：底部横排 / 左侧竖排（选中值直接对应 Prefs.DOCK_SIDE_*） */
    private static final String[] DOCK_SIDE_LABELS = {"底部（横向）", "左侧（竖向）"};
    private static final String[] DOCK_SIDE_HINTS = {
            "Dock 横放在屏幕底部，画中画在上方（默认）",
            "Dock 竖放在屏幕左侧，画中画在右侧"};

    private void pickDockSide() {
        OptionPicker.show(this, "Dock 位置", DOCK_SIDE_LABELS, DOCK_SIDE_HINTS,
                Prefs.getDockSide(this), which -> {
                    Prefs.setDockSide(this, which);
                    // 行标题是建行时写死的，改完重建整页（滚动位置会被还原）。
                    // 桌面回到前台时由 applySettings() 检测变化并原地换布局。
                    if (scrollView != null) pendingScrollY = scrollView.getScrollY();
                    recreate();
                });
    }

    /**
     * 端名：「左（上）端」/「右（下）端」。
     * Dock 竖排时左端其实在屏幕上方、右端在下方，所以把方向塞在「左/右」后面；
     * 横排（底部 Dock）时左右就是左右，不加方向。
     */
    private String endLabel(boolean left) {
        StringBuilder b = new StringBuilder(left ? "左" : "右");
        if (Prefs.getDockSide(this) == Prefs.DOCK_SIDE_LEFT) b.append(left ? "（上）" : "（下）");
        return b.append("端").toString();
    }

    /** 选择功能区时用的标题：竖排 →「左（上）端功能区」 */
    private String panelTitle(String tail, boolean left) {
        return endLabel(left) + tail;
    }

    /**
     * 显示相关：不再提供「屏内渲染分辨率」——它在部分车机上会让画中画里的应用
     * 停在旧尺寸（画面缩到左上角），用户分不清是我们还是车机的问题，先关掉这个入口。
     */
    private void rebuildRenderBox() { }   // 已废弃：入口全部移除，方法留空防外部调用编译错

    private static String themeLabel(int mode) {
        switch (mode) {
            case Prefs.THEME_TIME: return "跟随时间";
            case Prefs.THEME_DARK: return "手动深色";
            case Prefs.THEME_LIGHT: return "手动浅色";
            default: return "跟随安卓";
        }
    }

    /** 点击弹出选择框（原来是点一次循环一个模式，容易点过头） */
    private static final String[] THEME_LABELS = {
            "跟随安卓", "跟随时间", "手动深色", "手动浅色"};
    private static final String[] THEME_HINTS = {
            "系统切深色/浅色时，桌面跟着一起变",
            "按日出日落自动切换（需联网，时间/地点在下面调）",
            "始终使用深色外观",
            "始终使用浅色外观"};

    /** 选择框刚关掉的时刻：刚选完手指抬起的事件偶尔会穿透到下面的「外观模式」行，
     *  结果"关掉又立刻弹出来"。这段时间内的重新打开请求一律忽略。 */
    private static long lastPickClosedAt;

    /** 当前打开着的外观选择框：重建前必须先关掉，否则模糊窗口会在重建期被软件渲染崩掉 */
    private android.app.Dialog openPicker;

    private void pickThemeMode() {
        if (android.os.SystemClock.uptimeMillis() - lastPickClosedAt < 600) return;
        android.app.Dialog d = OptionPicker.show(this, "外观模式", THEME_LABELS, THEME_HINTS,
                Prefs.getThemeMode(this), this::applyThemeMode);
        openPicker = d;
        if (d != null) {
            d.setOnDismissListener(x -> {
                lastPickClosedAt = android.os.SystemClock.uptimeMillis();
                if (openPicker == d) openPicker = null;
            });
        }
    }

    private void applyThemeMode(int mode) {
        if (mode == Prefs.getThemeMode(this)) return;
        Prefs.setThemeMode(this, mode);
        // 用户在设置里明确选了外观模式 → 桌面图标点出来的「临时深浅色」立刻作废
        Ui.clearTransientDark();
        Ui.updateAppearance(this);
        // 手动/定时模式：把结果同步给系统夜间模式，画中画里的应用才会跟着变深/变浅
        if (mode != Prefs.THEME_AUTO) Ui.applySystemNightMode(this, Ui.dark);
        toast("外观：" + themeLabel(mode));
        if (openPicker != null) {
            try { openPicker.dismiss(); } catch (Throwable ignored) { }
            openPicker = null;
        }
        // 不能立刻 recreate：带背景模糊的窗口这时往往还没移除完，重建期的软件渲染会
        // 撞上 "Software rendering doesn't support drawRenderNode" 直接崩掉整个进程。
        // 推迟一拍，等弹窗窗口真正退场再重建。
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::recreateKeepScroll, 150);
    }

    /** 重建页面但保住滚动位置：外观一变整页要重建，重建后不该跳回最顶部 */
    private void recreateKeepScroll() {
        if (scrollView != null) pendingScrollY = scrollView.getScrollY();
        recreate();
    }

    /**
     * 整页罩层：深色 = 压暗，浅色 = 提亮。
     * 能模糊时可以让它更透（质感交给模糊）；不能模糊（低性能模式 / 安卓 9~11）时
     * 改成接近不透明的纯色 —— 低性能模式要的是「纯色、不糊」，不能又透又糊。
     */
    private GradientDrawable buildScrim() {
        boolean blur = Ui.canBlur();
        if (blur) {
            return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    Ui.dark ? new int[]{0x59070B12, 0x40070B12}
                            : new int[]{0x8CFFFFFF, 0x73F2F5FA});
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.dark ? new int[]{0xE60A0E15, 0xD8121722}
                        : new int[]{0xF8E9EEF6, 0xF2E1E7F0});
    }

    /**
     * 低性能开关：**当场**生效，不等 Activity 重建。
     *
     * 为什么必须这样：窗口背景模糊（setBackgroundBlurRadius）是挂在 Window 上的，
     * 重建 Activity 时窗口里的模糊不一定按新值刷掉 —— 用户看到的就是「从高性能切到
     * 低性能后背景还在糊，得退出设置页重进才变成纯色」。这里直接对当前窗口重写一遍
     * 半径（低性能 = 0），顺手清掉 FLAG_BLUR_BEHIND 与 blurBehindRadius，再把罩层
     * 换成不透明版本。卡片背景（玻璃 / 纯色）由随后的 recreate 一次性换掉。
     */
    private void applyPerfModeNow() {
        if (root != null) root.setBackground(buildScrim());
        android.view.Window w = getWindow();
        if (w == null) return;
        Ui.blurWindow(w, 40);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                boolean changed = false;
                if ((lp.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0) {
                    lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    changed = true;
                }
                if (lp.getBlurBehindRadius() != 0) {
                    lp.setBlurBehindRadius(0);
                    changed = true;
                }
                if (changed) w.setAttributes(lp);
            } catch (Throwable ignored) { }
        }
    }

    /** 外观相关的几行重建，其它设置不动。
     *  时间相关的配置（入夜/天亮/日落/地点）只在「跟随时间」模式下有意义，
     *  其它模式一律不显示，省得摆一堆改了也没用的项。 */
    private void rebuildThemeBox() {
        if (themeBox == null) return;
        themeBox.removeAllViews();
        actionTo(themeBox, "外观模式：" + themeLabel(Prefs.getThemeMode(this)), this::pickThemeMode);        toggleTo(themeBox, "屏内应用跟随深浅色", Prefs.syncAppsNightMode(this), b -> {
            Prefs.setSyncAppsNightMode(this, b);
            if (b) {
                // 打开的瞬间先同步一次当前外观，别等下次切换才生效
                Ui.applySystemNightMode(this, Ui.dark);
                toast("已同步给系统；屏内应用会跟着变（个别应用会重启一遍）");
            } else {
                toast("已关闭：切深浅色只变桌面，屏内应用不再被切走");
            }
            rebuildThemeBox();
        });

        if (Prefs.getThemeMode(this) != Prefs.THEME_TIME) return;

        actionTo(themeBox, "入夜时间：" + sunsetLabel(), () -> pickTime(true));
        actionTo(themeBox, "天亮时间：" + SunTimes.fmt(Prefs.getNightEnd(this)), () -> pickTime(false));
        toggleTo(themeBox, "按日落/日出自动（需联网）", Prefs.useSunTime(this), b -> {
            Prefs.setUseSunTime(this, b);
            rebuildThemeBox();
            if (b) {
                new Thread(() -> {
                    SunTimes.refreshIfStale(getApplicationContext());
                    runOnUiThread(this::rebuildThemeBox);
                }, "pipdesk-sun").start();
            }
        });
        actionTo(themeBox, "日落地点：" + SunTimes.locationLabel(this), this::editLocation);
    }

    /** 入夜时间显示：日落可用时优先显示日落，同时带上手动兜底值 */
    private String sunsetLabel() {
        if (Prefs.useSunTime(this) && Prefs.getSunSetMin(this) >= 0) {
            return "日落 " + SunTimes.fmt(Prefs.getSunSetMin(this))
                    + "（兜底 " + SunTimes.fmt(Prefs.getNightStart(this)) + "）";
        }
        return SunTimes.fmt(Prefs.getNightStart(this));
    }

    private void pickTime(final boolean start) {
        int cur = start ? Prefs.getNightStart(this) : Prefs.getNightEnd(this);
        new android.app.TimePickerDialog(this, (view, hour, minute) -> {
            int m = hour * 60 + minute;
            if (start) Prefs.setNightStart(this, m);
            else Prefs.setNightEnd(this, m);
            rebuildThemeBox();
            toast("已保存：" + SunTimes.fmt(m));
        }, cur / 60, cur % 60, true).show();
    }

    private void editLocation() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, 0);

        TextView tip = new TextView(this);
        tip.setText("填纬度 / 经度，例如北京 39.9042 / 116.4074。\n留空表示用系统定位；定位不可用时按北京坐标算。");
        tip.setTextSize(12);
        tip.setTextColor(Ui.tintDim());
        tip.setLineSpacing(Ui.dp(this, 2), 1f);
        box.addView(tip);

        final android.widget.EditText lat = new android.widget.EditText(this);
        lat.setHint("纬度");
        lat.setTextColor(Ui.tint());
        lat.setHintTextColor(Ui.tintHint());
        lat.setText(Double.isNaN(Prefs.getLatitude(this)) ? ""
                : String.valueOf(Prefs.getLatitude(this)));
        box.addView(lat);

        final android.widget.EditText lng = new android.widget.EditText(this);
        lng.setHint("经度");
        lng.setTextColor(Ui.tint());
        lng.setHintTextColor(Ui.tintHint());
        lng.setText(Double.isNaN(Prefs.getLongitude(this)) ? ""
                : String.valueOf(Prefs.getLongitude(this)));
        box.addView(lng);

        Ui.glassWindow(new AlertDialog.Builder(Ui.dialogContext(this))
                .setTitle("日落地点")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String s1 = lat.getText().toString().trim();
                    String s2 = lng.getText().toString().trim();
                    if (s1.isEmpty() || s2.isEmpty()) {
                        Prefs.clearLocation(this);
                        toast("已改为自动定位");
                    } else {
                        try {
                            Prefs.setLocation(this, Double.parseDouble(s1), Double.parseDouble(s2));
                            toast("已保存坐标");
                        } catch (Throwable t) {
                            toast("坐标格式不对");
                        }
                    }
                    new Thread(() -> {
                        SunTimes.refreshIfStale(getApplicationContext());
                        runOnUiThread(this::rebuildThemeBox);
                    }, "pipdesk-sun").start();
                    rebuildThemeBox();
                })
                .setNeutralButton("自动定位", (d, w) -> {
                    Prefs.clearLocation(this);
                    rebuildThemeBox();
                })
                .setNegativeButton("取消", null)
                .show(), this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从桌面切回时壁纸可能变了 / 模式被图标切换了 → 外观不同就重建
        Ui.updateAppearance(this);
        if (Ui.dark != createdDark) recreateKeepScroll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退出（返回）/ 切走时记住滚动位置，下次进来还在原地。
        // 只记在进程内存里（static）：进程被杀 / 重启桌面 / 重启机器就自动清零，
        // 下次从头进。recreate 重建走 pendingScrollY 暂存，两条路互不干扰。
        if (scrollView != null) {
            if (testPage) lastScrollTest = scrollView.getScrollY();
            else lastScrollMain = scrollView.getScrollY();
        }
    }

    /**
     * 「跟随时间」在设置页里也要能跨点：每分钟查一次，外观变了就重建本页。
     * （这里没有虚拟屏，重建没有副作用）
     */
    private android.content.BroadcastReceiver tickReceiver;

    private void registerTick() {
        tickReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ctx, Intent i) {
                if (Prefs.getThemeMode(SettingsActivity.this) != Prefs.THEME_TIME) return;
                boolean was = Ui.dark;
                Ui.updateAppearance(SettingsActivity.this);
                if (was != Ui.dark) recreateKeepScroll();
            }
        };
        registerReceiver(tickReceiver, new android.content.IntentFilter(Intent.ACTION_TIME_TICK));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (toastView != null) {
            toastView.removeCallbacks(toastHide);
            toastView = null;
        }
        if (tickReceiver != null) {
            try { unregisterReceiver(tickReceiver); } catch (Throwable ignored) { }
            tickReceiver = null;
        }
    }

    /** 浮层提示的自动收起任务（新提示进来先取消旧的，永远只有一条在显示） */
    private final Runnable toastHide = new Runnable() {
        @Override public void run() {
            if (toastView != null) toastView.setVisibility(View.GONE);
        }
    };
    private TextView toastView;

    /**
     * 语音别名管理：列出学过的说法，点一条删除，或清空全部。
     * 弹框统一用 OptionPicker（设置页 Dock 位置 / 功能区那套基准样式）。
     *
     * 英文应用名每次识别出的汉字都可能不同，所以这里不提供「手写精确别名」——
     * 在候选里点一次就自动学一条，比手敲更贴合真实发音。
     * （0.23.25 起识别模型是 CTC，没有热词通道，别名只用于匹配打分。）
     */
    private void showVoiceAliasManager() {
        java.util.Map<String, java.util.Set<String>> map =
                Prefs.parseAlias(Prefs.voiceAliasRaw(this));
        final java.util.List<String[]> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Set<String>> e : map.entrySet()) {
            for (String phrase : e.getValue()) rows.add(new String[]{e.getKey(), phrase});
        }
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<String> hints = new ArrayList<>();
        for (String[] row : rows) {
            labels.add("「" + row[1] + "」");
            hints.add("→ " + row[0]);
        }
        if (rows.isEmpty()) {
            OptionPicker.show(this, "语音别名（0 条）",
                    new String[]{"还没有学过的说法"},
                    new String[]{"听不准时点一次候选，会自动记一条"},
                    -1,
                    "中文识别模型吐不出英文字母；第一次说英文应用，要在候选里点一次。",
                    null);
            return;
        }
        labels.add("清空全部（" + rows.size() + " 条）");
        hints.add("删掉所有已学说法，重新学");
        OptionPicker.show(this, "语音别名（" + rows.size() + " 条）",
                labels.toArray(new String[0]), hints.toArray(new String[0]), -1,
                "点一条即删除。别名用于匹配打分（同音直通 / 声母骨架），不进解码器热词。",
                which -> {
                    if (which == rows.size()) {
                        Prefs.clearVoiceAlias(this);
                        toast("已清空全部语音别名");
                    } else if (which >= 0 && which < rows.size()) {
                        Prefs.removeVoiceAlias(this, rows.get(which)[0], rows.get(which)[1]);
                        toast("已删除「" + rows.get(which)[1] + "」");
                    } else {
                        return;
                    }
                    recreateKeepScroll();     // 行标题里的条数要立刻更新
                });
    }

    /**
     * 窗口内浮层提示（替代系统 Toast）。
     *
     * 为什么不用 Toast：系统 Toast 是排队的 —— 连续点版本行 5 次，5 条「再点 N 次」
     * 要一条一条播完（每条 2 秒），用户看到的反馈慢半拍。这里改成同一个 TextView
     * 复用：新提示立即替换文本、重新计时，反馈是即时的。
     */
    private void toast(String s) {
        if (toastView == null) {
            toastView = new TextView(this);
            toastView.setTextSize(14);
            toastView.setTextColor(0xFFFFFFFF);
            toastView.setBackgroundColor(0xCC222A38);
            int p = Ui.dp(this, 10);
            toastView.setPadding(p, p, p, p);
            toastView.setElevation(Ui.dp(this, 12));
            toastView.setVisibility(View.GONE);
            ViewGroup decor = (ViewGroup) getWindow().getDecorView();
            decor.addView(toastView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) toastView.getLayoutParams();
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            lp.bottomMargin = Ui.dp(this, 48);
            toastView.setLayoutParams(lp);
        }
        toastView.setText(s);
        toastView.setVisibility(View.VISIBLE);
        toastView.removeCallbacks(toastHide);
        // 长文本（诊断结果等）多留一会儿；短提示 1.2 秒就收
        toastView.postDelayed(toastHide, s.length() > 40 ? 3200 : 1200);
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    @SuppressWarnings("unused")
    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
    }
}
