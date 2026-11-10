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
    /** 进入页面时的外观，返回/回来时对比，变了就重建（设置页没有虚拟屏，重建无副作用） */
    private boolean createdDark;

    /** 内容滚动容器：重建前记录位置、重建后还原 */
    private ScrollView scrollView;
    /** 重建后要还原的滚动位置（-1 = 不还原）。recreate() 会换一个新的 Activity 实例，
     *  instance 字段带不过去，所以用 static 暂存一次。 */
    private static int pendingScrollY = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.start(this);   // 设置页也可能被单独拉起，日志入口保持幂等
        Ui.updateAppearance(this);
        createdDark = Ui.dark;
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

        // 玻璃罩：只压暗/提亮一点点，模糊负责质感（模糊不可用时这一层保证文字可读）
        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                Ui.dark ? new int[]{Ui.canBlur() ? 0x59070B12 : 0xA6070B12,
                        Ui.canBlur() ? 0x40070B12 : 0x8C070B12}
                        : new int[]{Ui.canBlur() ? 0x8CFFFFFF : 0xCFFFFFFF,
                        Ui.canBlur() ? 0x73F2F5FA : 0xBFEDF1F8});
        root.setBackground(scrim);
        // Android 12+ 才真正模糊（安卓 9~11 内部直接跳过）
        Ui.blurWindow(getWindow(), 40);

        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(this, 18);
        content.setPadding(padH, Ui.dp(this, 8), padH, Ui.dp(this, 28));
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
        final int restoreY = pendingScrollY;
        pendingScrollY = -1;
        if (restoreY > 0) {
            sv.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            sv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            sv.scrollTo(0, restoreY);
                            // 首帧时内容高度可能还没最终定稿（scrollTo 会被夹住），再补一次
                            sv.post(() -> sv.scrollTo(0, restoreY));
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
        if (dbg != null) {
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
            // 滚到底部（核对息屏/亮度区块截图用）
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
            // 直接设置屏内渲染分辨率：--ei debug_render 75（50/75/100）
            if (dbg.hasExtra("debug_render")) {
                int pct = clamp(dbg.getIntExtra("debug_render", 100), 50, 100);
                dbg.removeExtra("debug_render");
                content.postDelayed(() -> {
                    Prefs.setRenderScale(this, pct);
                    rebuildRenderBox();
                    toast("屏内渲染分辨率 " + renderScaleLabel(pct));
                }, 600);
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

        section("虚拟桌面");
        seek("画中画数量", 1, Prefs.PANE_MAX, 1, Prefs.getPaneCount(this),
                v -> {
                    Prefs.setPaneCount(this, v);
                    rebuildPaneDefaults();   // 屏数变了，路由/默认应用行跟着重建
                }, v -> v + " 屏");
        seek("画中画 DPI", Prefs.DPI_MIN, Prefs.DPI_MAX, 20, Prefs.getDpi(this),
                v -> Prefs.setDpi(this, v), String::valueOf);
        // 屏内渲染分辨率：降低虚拟屏像素量换帧率（GPU 重的应用更稳，画面略糊）。
        // 放进盒子里：点击后要重建行标签（action 的标题是建行时写死的）
        renderBox = new LinearLayout(this);
        renderBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(renderBox);
        rebuildRenderBox();
        // 圆角只有一套：改这里画中画和 Dock 一起变（需求 1）
        seek("圆角（画中画 + Dock）", 0, Prefs.CORNER_MAX, 1, Prefs.getPipCorner(this),
                v -> Prefs.setPipCorner(this, v), v -> v + " dp");
        toggle("显示系统状态栏", Prefs.showStatusBar(this),
                b -> Prefs.setShowStatusBar(this, b));

        section("外观");
        themeBox = new LinearLayout(this);
        themeBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(themeBox);
        rebuildThemeBox();

        section("Dock");
        seek("Dock 应用数量", Prefs.DOCK_MIN, Prefs.DOCK_MAX, 1, Prefs.getDockCount(this),
                v -> Prefs.setDockCount(this, v), v -> v + " 格");
        // 「Dock 高度」这一项已删除：高度 = 图标 dp + 24dp，跟着图标自动走。
        // 两个独立的高度/尺寸设置只会互相打架（高度设小了就把图标削一刀）。
        seek("Dock 图标大小", Prefs.DOCK_ICON_MIN, Prefs.DOCK_ICON_MAX, 2, Prefs.getDockIcon(this),
                v -> Prefs.setDockIcon(this, v), v -> v + " dp（Dock 高度随之自适应）");

        // Dock 左右两端的功能区：两侧内容都能选「快捷功能」或「时间 + 天气」。
        // 父开关关掉时，下面的内容选择/图标列表整块变灰（子级不可调）。
        Switch leftSw = toggle("左端功能区", Prefs.dockLeftEnabled(this), b -> {
            Prefs.setDockLeftEnabled(this, b);
            if (b && Prefs.getDockLeft(this).isEmpty()
                    && Prefs.getDockLeftType(this) == Prefs.PANEL_SHORTCUT) {
                // 第一次打开先给一套能用的：返回 / 主页 / 最近任务
                List<String> d = new ArrayList<>();
                d.add(AppInfo.BACK_PKG);
                d.add(AppInfo.HOME_PKG);
                d.add(AppInfo.RECENTS_PKG);
                Prefs.setDockLeft(this, d);
                toast("已放入默认图标：返回 / 主页 / 最近任务");
            }
        });
        View leftType = actionChild("左端内容：" + panelLabel(Prefs.getDockLeftType(this)),
                () -> pickPanelType(true));
        // 只有「快捷功能」才有图标可列（时间天气没有图标），按当前类型决定显不显示
        View leftIcons = Prefs.getDockLeftType(this) == Prefs.PANEL_SHORTCUT
                ? actionChild("左端图标：" + dockLeftNames(),
                        () -> toast("长按功能区图标 →「添加图标」；也可移除或移到中间图标区"))
                : null;
        if (leftIcons != null) children(leftSw, leftType, leftIcons);
        else children(leftSw, leftType);

        Switch rightSw = toggle("右端功能区", Prefs.dockRightEnabled(this),
                b -> Prefs.setDockRightEnabled(this, b));
        View rightType = actionChild("右端内容：" + panelLabel(Prefs.getDockRightType(this)),
                () -> pickPanelType(false));
        View rightIcons = Prefs.getDockRightType(this) == Prefs.PANEL_SHORTCUT
                ? actionChild("右端图标：" + dockRightNames(),
                        () -> toast("长按功能区图标 →「添加图标」；也可移除或移到中间图标区"))
                : null;
        if (rightIcons != null) children(rightSw, rightType, rightIcons);
        else children(rightSw, rightType);

        note("· 两侧都可选「快捷功能」或「时间 + 天气」；不显示时那一侧的分隔竖线也不显示。\n"
                + "· 快捷功能最多 5 个图标，不留空位；添加方式 = 长按任一图标 →「添加图标」。\n"
                + "· 功能区图标间距与中间图标区完全一致（同一个格宽）；中间放不下时自动变窄、可滑动，"
                + "两侧功能区不会跟着变窄。");

        section("应用列表");
        seek("图标大小", Prefs.ICON_MIN, Prefs.ICON_MAX, 4, Prefs.getIconSize(this),
                v -> Prefs.setIconSize(this, v), v -> v + " dp");
        seek("每行列数", 3, 8, 1, Prefs.getListColumns(this),
                v -> Prefs.setListColumns(this, v), v -> v + " 列");

        // 画中画路由：活动画中画 + 每屏默认应用（替代原来的「多屏分配」：
        // 单应用固定屏位在长按图标里设置）
        section("画中画路由");
        paneDefaultsBox = new LinearLayout(this);
        paneDefaultsBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(paneDefaultsBox);
        rebuildPaneDefaults();

        section("壁纸");
        action("更换壁纸（静态图片）", () -> pickWallpaper());

        // 息屏 & 音量：都是应用列表里的内置图标（和「主页」一样），可绑到 Dock
        section("息屏与音量（应用列表图标，可绑 Dock）");
        Switch trueSleep = toggle("真息屏（关 = 黑屏遮罩，最安全）", Prefs.getSleepMode(this) == 0, b -> {
            Prefs.setSleepMode(this, b ? 0 : 1);
            toast(b ? "息屏方式：真息屏（触摸/按键唤醒 + 到点兜底点亮）"
                    : "息屏方式：黑屏遮罩（任意触摸立即恢复）");
        });
        View sleepGuard = seekChild("兜底自动亮屏（真息屏后强制点亮）", 0, Prefs.SLEEP_GUARD_MAX, 60,
                Prefs.getSleepGuardSec(this),
                v -> Prefs.setSleepGuardSec(this, v),
                v -> v == 0 ? "不自动（不建议）" : (v / 60) + " 分钟");
        children(trueSleep, sleepGuard);
        note("· 真息屏 = 等价于按电源键：屏幕和背光全关，最省电。唤醒方式：触摸屏幕/双击/按键。\n"
                + "· 兜底：真息屏后会挂一个闹钟，到点强制点亮——就算这台车机的触摸唤不醒系统，"
                + "也不会「黑了回不来」。默认 3 分钟，可改成 1~10 分钟。\n"
                + "· 黑屏遮罩 = 不真关屏，只在最上层盖一层纯黑，任意触摸立刻恢复；"
                + "100% 安全，代价是背光还亮着、稍微费电。\n"
                + "· 音量 ± 调的是媒体音量，点一下会在屏幕上浮出当前音量（车机系统音量条常常不显示）。");

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
                v -> { Prefs.setBrightNight(this, v); Brightness.applyIfAuto(this); },
                v -> v + " / 255");
        View daySeek = seekChild("白天亮度（日出后）", Prefs.BRIGHT_MIN, Prefs.BRIGHT_MAX, 5,
                Prefs.getBrightDay(this),
                v -> { Prefs.setBrightDay(this, v); Brightness.applyIfAuto(this); },
                v -> v + " / 255");

        Switch weatherSw = toggleChild("跟随天气（阴雨天变暗）", Prefs.brightUseWeather(this), b -> {
            Prefs.setBrightUseWeather(this, b);
            if (b) Weather.refreshIfStale(this);
            Brightness.applyIfAuto(this);
            toast(b ? "已开启：阴/雨/雪天切到阴天亮度" : "已关闭：不再按天气变暗");
        });
        View cloudySeek = seekChild("阴天 / 雨雪亮度", Prefs.BRIGHT_MIN, Prefs.BRIGHT_MAX, 5,
                Prefs.getBrightCloudy(this),
                v -> { Prefs.setBrightCloudy(this, v); Brightness.applyIfAuto(this); },
                v -> v + " / 255");

        children(brightSw, sunSw, nightSeek, daySeek, weatherSw, cloudySeek);
        action("现在为什么是这个亮度？", () -> toast(Brightness.describe(this)
                + "\n天气：" + Weather.dockLabel(this) + "（" + Weather.ageLabel(this) + "）"
                + "\n日落：" + SunTimes.describe(this)));
        action("立即刷新天气（平时每 15 分钟自动刷新）", () -> {
            Weather.forceRefresh(this);
            toast("正在刷新天气，稍等…");
        });
        note("· 两个子项（日出日落 / 天气）可以各自开关；总开关关掉时完全不碰系统亮度，"
                + "适合车机自带自动背光的情况。\n"
                + "· 优先级：夜间 > 阴雨天 > 白天。晚上不管阴不阴都用夜间亮度，"
                + "否则一阴天反而更亮、很刺眼。\n"
                + "· 天气 15 分钟查一次：Open-Meteo 的数据本身十几分钟才更新，"
                + "查得再勤也是同一份结果；只在「Dock 显示天气」或「跟随天气」开着时才联网。\n"
                + "· 阴天判断只认「阴 / 雾 / 雨 / 雪 / 雷」，多云仍按白天亮度，"
                + "免得稍微有点云就把屏幕压暗。\n"
                + "· 日落/天气都联网获取，位置就是上面「日落地点」那个；断网退回固定时段。");

        section("说明");
        note("· 画中画顺序固定：横排从左到右、竖排从上到下，依次是画中画 1/2/3。\n"
                + "· 未指定屏位的应用在「活动画中画」打开（可在上面改，默认最后一屏）；\n"
                + "  想让某个应用固定在某一屏：长按应用图标 →「指定画中画位置」。\n"
                + "· 画中画默认应用：桌面启动时，空闲的屏会自动打开这里指定的应用。\n"
                + "· 画中画为空时是玻璃态，点 Dock 图标即在其中打开。\n"
                + "· 已经在画中画里的应用，再点一次图标只会回到首页，不会重开。\n"
                + "· 长按画中画（或 Dock 图标、应用图标）可打开更多操作。\n"
                + "· 壁纸只支持静态图片（不支持动态壁纸）。\n"
                + "· 运行日志写入：" + FileLog.dirPath());
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
    private LinearLayout renderBox;
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
        PaneChooser.show(this, "活动画中画（未指定的应用在这里打开）",
                paneCount, Prefs.getActivePane(this, paneCount), false, which ->
                        Prefs.setActivePane(this, which));
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 14));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(row, lp);

        // 返回键：44dp 见方的热区（原来只有一行 26sp 的字，手指很难按到）。
        // 宽度靠 minWidth 撑出来，字形仍在最左边 —— 标题左对齐的位置和改之前几乎一样。
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
        // 无底色的圆形水波纹：只在按下时有反馈，静态完全透明
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        back.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Ui.dark ? 0x33FFFFFF : 0x1F000000), null, mask));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        row.addView(back);

        TextView t = new TextView(this);
        t.setText("桌面设置");
        t.setTextSize(20);
        t.setTextColor(Ui.tint());
        t.setShadowLayer(4, 0, 1, Ui.shadow());
        row.addView(t);
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

    private void note(String text) {
        TextView tv = new TextView(this);
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

    /** 递归启用/禁用一整个卡片（SeekBar 拖不动、行不可点、文字变淡） */
    private void setRowsEnabled(List<View> rows, boolean en) {
        for (View r : rows) {
            r.setAlpha(en ? 1f : 0.38f);
            setEnabledDeep(r, en);
        }
    }

    private void setEnabledDeep(View v, boolean en) {
        v.setEnabled(en);
        v.setClickable(en);
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
        if (l.size() >= Prefs.PANEL_SHORTCUT_MAX) b.append("（已满 ").append(Prefs.PANEL_SHORTCUT_MAX).append(" 个）");
        return b.toString();
    }

    /** 功能区内容类型文案 */
    private static String panelLabel(int type) {
        return type == Prefs.PANEL_CLOCK ? "时间 + 天气" : "快捷功能（图标）";
    }

    /** 选一侧功能区的内容：快捷功能（最多 5 个图标）或 时间 + 天气 */
    private void pickPanelType(final boolean left) {
        final int cur = left ? Prefs.getDockLeftType(this) : Prefs.getDockRightType(this);
        GlassMenu.show(this, (left ? "左端" : "右端") + "功能区内容",
                new CharSequence[]{"快捷功能（最多 " + Prefs.PANEL_SHORTCUT_MAX + " 个图标）",
                        "时间 + 天气"},
                which -> {
                    int t = which == 1 ? Prefs.PANEL_CLOCK : Prefs.PANEL_SHORTCUT;
                    if (left) Prefs.setDockLeftType(this, t);
                    else Prefs.setDockRightType(this, t);
                    // 本页的行标题是建行时写死的，改完重建整页最省事（滚动位置会被还原）
                    if (scrollView != null) pendingScrollY = scrollView.getScrollY();
                    recreate();
                });
    }

    /** 屏内渲染分辨率档位标签 */
    private static String renderScaleLabel(int pct) {
        switch (pct) {
            case 75: return "省电（75%）";
            case 50: return "流畅（50%）";
            default: return "标准（100%）";
        }
    }

    /** 「屏内渲染分辨率」行：点一下切档，标签跟着刷新 */
    private void rebuildRenderBox() {
        if (renderBox == null) return;
        renderBox.removeAllViews();
        actionTo(renderBox, "屏内渲染分辨率：" + renderScaleLabel(Prefs.getRenderScale(this)),
                () -> {
                    int cur = Prefs.getRenderScale(this);
                    int next = cur >= 100 ? 75 : (cur >= 75 ? 50 : 100);
                    Prefs.setRenderScale(this, next);
                    rebuildRenderBox();
                    toast(next == 100 ? "已恢复 1:1 渲染（最清晰）"
                            : "已切换：屏内应用渲染像素减少，画面更流畅（略糊）");
                });
    }

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
        if (tickReceiver != null) {
            try { unregisterReceiver(tickReceiver); } catch (Throwable ignored) { }
            tickReceiver = null;
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
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
