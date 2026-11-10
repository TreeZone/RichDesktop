package com.cz.richdesk;

import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;

/**
 * 统一的绘制/尺寸工具：玻璃材质、圆角轮廓、dp 换算、背景模糊、深浅色外观。
 *
 * 兼容性（Android 9 ~ 16）：
 *  - 窗口背景模糊是 Android 12（API 31）才有的能力，低版本一律走「高不透明度半透明底」兜底，
 *    所以所有模糊调用都包在 SDK>=31 判断 + try/catch 里，任何版本都不会崩；
 *  - 其余使用的是 API 21 就有的 GradientDrawable / ClipToOutline，Android 9 起完全可用。
 */
public class Ui {

    private static final String TAG = "PipDesk";

    /** 全局最小边距：四个间距必须相等（需求），统一从这里取值 */
    public static final int GAP_DP = 6;

    /**
     * 画中画**之间**的缝。需求：这条缝要和「Dock ↔ 画中画」的间距完全相等，
     * 值取旧值 12dp 减 3dp = 9dp（用户反馈 12dp 偏大）。
     * MainActivity 里 Dock 的上间距也取这个值，两处永远一致。
     */
    public static final int PANE_GAP_DP = 9;

    /** 背景模糊半径（dp）。只有 Android 12+ 会真正生效 */
    public static final int BLUR_DP = 26;

    /** 外观模式常量统一放在 Prefs 里（AUTO / TIME / DARK / LIGHT） */

    /** 当前是否深色外观（深玻璃 + 白字）。updateAppearance() 里计算 */
    public static volatile boolean dark = true;

    /**
     * 低性能模式（Prefs.perf_low）：**默认 true**。
     *
     * 只改两件事，其余绘制 / 布局 / 触摸 / 虚拟屏逻辑与高性能模式逐字一致：
     *   1. 玻璃材质降级为不透明纯色（{@link #glassColors()} / {@link #glass()} /
     *      {@link #glassPanel()}，亮边 {@link #glassStroke()} 一并去掉）；
     *   2. 关闭窗口背景模糊（{@link #canBlur()} 为 false、{@link #blurWindow()} 写 0）。
     *
     * 由 MainActivity / SettingsActivity 的 onCreate 通过 syncPerfMode() 从配置同步；
     * 静态初值也设 true，保证冷启动时若某个 Receiver/Broadcast 先于 Activity 跑起来，
     * 在 sync 之前同样走低性能路径。
     */
    private static volatile boolean lowPerf = true;

    public static boolean isLowPerf() {
        return lowPerf;
    }

    public static void syncPerfMode(Context c) {
        lowPerf = Prefs.perfLow(c);
    }

    /** 低性能模式：玻璃面板的纯色底（深、浅外观各一档） */
    public static int flatBg() {
        return dark ? 0xFF141821 : 0xFFF2F4F8;
    }

    /** 低性能模式：弹窗 / 卡片的纯色底 */
    public static int flatPanel() {
        return dark ? 0xFF1B2230 : 0xFFFFFFFF;
    }

    /** 壁纸亮度缓存：null = 还没测过。只在后台线程解码时写入 */
    private static volatile Boolean wallpaperDarkCache;
    /** 上一次测得的壁纸亮度：后台重算期间拿它顶住外观，不让主线程等解码 */
    private static volatile Boolean lastKnownWallpaperDark;
    private static volatile boolean wallpaperRecheckPending;
    /** 后台重算完成后的回调（主线程执行）——MainActivity 用它触发外观刷新 */
    public static volatile Runnable onWallpaperRechecked;

    public static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * density(c));
    }

    /** 当前系统是否支持窗口背景模糊（Android 12 / API 31 起）。低性能模式一律不支持 */
    public static boolean canBlur() {
        return !lowPerf && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    // ------------------------------------------------------------ 深浅色外观

    /**
     * 计算当前外观：
     *  - 手动深色/浅色：直接用用户选择；
     *  - 跟随时间：入夜区间内深色（固定时段，或联网日落/日出）；
     *  - 跟随安卓：系统夜间模式开着 → 深色；否则按壁纸亮度自适应
     *    （浅色壁纸 → 浅玻璃 + 深色字，深色壁纸 → 深玻璃 + 白字）。
     * 壁纸亮度在后台算一次并缓存，壁纸变化时调 invalidateWallpaper()。
     */
    public static void updateAppearance(Context c) {
        int mode = Prefs.getThemeMode(c);
        if (mode == Prefs.THEME_DARK) {
            dark = true;
            return;
        }
        if (mode == Prefs.THEME_LIGHT) {
            dark = false;
            return;
        }
        if (mode == Prefs.THEME_TIME) {
            dark = SunTimes.isDarkNow(c);
            return;
        }
        // auto：先看系统深色模式（Android 10+ 的夜间模式）
        try {
            Configuration conf = c.getResources().getConfiguration();
            if ((conf.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES) {
                dark = true;
                return;
            }
        } catch (Throwable ignored) { }
        // 系统没开深色 → 按壁纸亮度。
        // 有缓存直接用；没缓存先用上一次的值顶住、同时丢后台线程重算。
        // 绝不在调用线程（onCreate / TIME_TICK 都是主线程）同步解码整张壁纸 ——
        // 那是启动卡顿和 ANR 的老根因。
        Boolean cached = wallpaperDarkCache;
        if (cached != null) {
            dark = cached;
        } else {
            Boolean last = lastKnownWallpaperDark;
            dark = last != null ? last : true;
            requestWallpaperRecheck(c);
        }
    }

    /** 后台重算壁纸亮度（去重：已有重算在跑就不重复起）；完成后回调主线程 */
    private static void requestWallpaperRecheck(Context c) {
        if (wallpaperRecheckPending) return;
        wallpaperRecheckPending = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean r = computeWallpaperDark(app);
            wallpaperDarkCache = r;
            lastKnownWallpaperDark = r;
            wallpaperRecheckPending = false;
            Runnable cb = onWallpaperRechecked;
            if (cb != null) {
                try {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
                } catch (Throwable ignored) { }
            }
        }, "pipdesk-wallpaper-lum").start();
    }

    // ------------------------------------------------------------ 系统夜间模式联动

    /**
     * 把「深色/浅色」同步给安卓系统本身（UiModeManager）。
     *
     * 为什么必须做：画中画里的应用跑在虚拟屏上，它们只认系统的 uiMode，
     * 桌面自己的玻璃/文字是私有的绘制，跟系统没有关系。不同步的话，
     * 用户点「深浅模式」只会看到桌面文字变色，屏内应用仍是原来的样子。
     *
     * uid 1000（system）持有 MODIFY_DAY_NIGHT_MODE，可以直接改；
     * 普通应用身份会被拒（SecurityException），此时静默降级只切桌面。
     *
     * **受「屏内应用跟随深浅色」开关控制（默认关）**：全局 uiMode 变更会销毁
     * 重建屏内应用，实测 QQ 会直接崩溃（进程 died: fg TOP，task 被系统移除），
     * 用户看到的就是"切个主题，画中画里的应用被切换掉"。关掉同步后
     * 只切桌面玻璃/文字，屏内应用保持原样。
     */
    public static void applySystemNightMode(Context c, boolean dark) {
        try {
            if (!Prefs.syncAppsNightMode(c)) return; // 不同步：屏内应用不动
            if (!isSystemUid()) return;              // 非系统身份做不到，别白折腾
            Object um = c.getSystemService(Context.UI_MODE_SERVICE);
            if (um == null) return;
            android.app.UiModeManager m = (android.app.UiModeManager) um;
            int want = dark ? android.app.UiModeManager.MODE_NIGHT_YES
                    : android.app.UiModeManager.MODE_NIGHT_NO;
            if (m.getNightMode() != want) {
                m.setNightMode(want);
                Log.i(TAG, "系统夜间模式 -> " + (dark ? "深色" : "浅色"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "系统夜间模式切换不可用：" + t.getClass().getSimpleName());
        }
    }

    /** 当前进程是否是 system uid（sysuid 版才有系统权限） */
    public static boolean isSystemUid() {
        try {
            return android.os.Process.myUid() == android.os.Process.SYSTEM_UID;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只在后台线程调用：取系统壁纸，缩到 32x32 求平均亮度 */
    private static boolean computeWallpaperDark(Context c) {
        boolean result = true;
        try {
            Drawable dw = WallpaperManager.getInstance(c).getDrawable();
            if (dw != null) {
                Bitmap b;
                if (dw instanceof BitmapDrawable) {
                    b = ((BitmapDrawable) dw).getBitmap();
                } else {
                    b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(b);
                    dw.setBounds(0, 0, 32, 32);
                    dw.draw(cv);
                }
                if (b != null) {
                    int w = Math.min(32, b.getWidth()), h = Math.min(32, b.getHeight());
                    double lum = 0;
                    int n = 0;
                    for (int y = 0; y < h; y += 2) {
                        for (int x = 0; x < w; x += 2) {
                            int p = b.getPixel(x, y);
                            // sRGB 感知灰度 0~1（旧实现阈值量纲错了 255 倍，几乎恒判浅色）
                            lum += (Color.red(p) * 0.2126 + Color.green(p) * 0.7152
                                    + Color.blue(p) * 0.0722) / 255.0;
                            n++;
                        }
                    }
                    // 平均灰度 < 0.58 → 深色壁纸
                    result = n == 0 || (lum / n) < 0.58;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "壁纸亮度检测失败，按深色处理", t);
        }
        return result;
    }

    /** 壁纸变了：清缓存（重新算），下一轮 updateAppearance 生效 */
    public static void invalidateWallpaper() {
        wallpaperDarkCache = null;
    }

    /** 文字/图标颜色：深色外观用白字，浅色外观用黑字 */
    public static int tint() {
        return dark ? 0xFFFFFFFF : 0xE6000000;
    }

    public static int tintDim() {
        return dark ? 0xCCFFFFFF : 0xB3000000;
    }

    public static int tintHint() {
        return dark ? 0xB3FFFFFF : 0x8C000000;
    }

    /** 阴影：深色外观下白字需要黑阴影；浅色外观黑字不投影 */
    public static int shadow() {
        return dark ? 0x99000000 : 0x00000000;
    }

    // ------------------------------------------------------------ 玻璃材质

    /**
     * 玻璃材质的两个渐变端色（TL→BR）。**自绘玻璃必须从这里取色**，
     * 这样画中画分隔条、Dock、卡片永远是同一档透明度，不会各写一套。
     *
     * 深色外观 = **黑玻璃**（需求：Dock 和画中画分隔条都改成黑玻璃），
     * 浅色外观 = 白玻璃。两档只差底色，透明度是同一套 GLASS_ALPHA_*。
     */
    public static int[] glassColors() {
        if (lowPerf) {
            int c = flatBg();
            return new int[]{c, c};
        }
        return dark ? new int[]{GLASS_ALPHA_TOP << 24, GLASS_ALPHA_BOTTOM << 24}
                : new int[]{0x9CFFFFFF, 0x4DFFFFFF};
    }

    /** 玻璃材质的 1dp 亮边色（与 {@link #glassColors()} 配套）。纯色模式不画亮边 */
    public static int glassStroke() {
        if (lowPerf) return 0x00000000;
        return dark ? 0x33FFFFFF : 0x80FFFFFF;
    }

    /**
     * Dock / 黑玻璃的透明度档位：上面亮一点、下面暗一点，才有"整块玻璃"的厚度感。
     * 画中画分隔条也用同一套（用户要求：竖条的透明度和 Dock 一致）。
     */
    public static final int GLASS_ALPHA_TOP = 0x66;
    public static final int GLASS_ALPHA_BOTTOM = 0x2E;

    /**
     * 玻璃材质：半透明底 + 白色渐变高光 + 1dp 亮边，圆角按配置。
     * 深色外观 = 黑玻璃；浅色外观 = 白玻璃。
     * 用于贴在壁纸上的 Dock / 画中画 / 设置卡片。
     */
    public static Drawable glass(Context c, int radiusDp) {
        int r = dp(c, radiusDp);
        if (lowPerf) {
            // 纯色替代玻璃：圆角保持不变，只去掉半透明与渐变高光
            GradientDrawable g = new GradientDrawable();
            g.setColor(flatBg());
            g.setCornerRadius(r);
            return g;
        }
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, glassColors());
        g.setStroke(Math.max(1, dp(c, 1)), glassStroke());
        g.setCornerRadius(r);
        return g;
    }

    /**
     * 深色/浅色玻璃面板：对话框正文用。
     * Android 12+ 有背景模糊，底色可以更透；Android 9~11 用高不透明度保证可读。
     */
    public static Drawable glassPanel(Context c, int radiusDp) {
        int r = dp(c, radiusDp);
        if (lowPerf) {
            // 纯色替代玻璃面板：圆角保持不变，只去掉半透明与渐变
            GradientDrawable g = new GradientDrawable();
            g.setColor(flatPanel());
            g.setCornerRadius(r);
            return g;
        }
        boolean blur = canBlur();
        GradientDrawable g;
        if (dark) {
            int top = blur ? 0xC02B3446 : 0xF02B3446;
            int bottom = blur ? 0xA8171D29 : 0xE6171D29;
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{top, bottom});
            g.setStroke(Math.max(1, dp(c, 1)), 0x66FFFFFF);
        } else {
            // 浅色玻璃：做到接近不透明。
            // 之前是 C8/B0（约 78%/69%），浅色壁纸一衬就是个半透明的白纱，
            // 黑字压上去对比不够（用户反馈"太透明、看不清字体"）。
            // 现在只保留一点点透明感，模糊有没有都够清楚。
            int top = blur ? 0xF6FBFCFF : 0xFEFBFCFF;
            int bottom = blur ? 0xF0EEF2F9 : 0xF8EEF2F9;
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{top, bottom});
            // 浅底配白描边等于没有边，改成极淡的深色描边才有"板子"的轮廓
            g.setStroke(Math.max(1, dp(c, 1)), 0x33000000);
        }
        g.setCornerRadius(r);
        return g;
    }

    /** 给窗口开背景模糊（Android 12+ 生效，低版本静默跳过） */
    public static void blurWindow(Window w, int radiusDp) {
        if (w == null) return;
        if (Build.VERSION.SDK_INT < 31) return;   // 安卓 9~11：没有背景模糊，靠半透明底兜底
        try {
            if (lowPerf) {
                // 低性能模式：明确写 0。模式可能在窗口存活期间被切换，只 return 会让
                // 之前设过的模糊一直生效 —— GPU 每帧都要采样背后正在出帧的画中画。
                w.setBackgroundBlurRadius(0);
                return;
            }
            // 只做「背景模糊」：跟随窗口背景的绘制区域（= 玻璃板矩形），
            // 不用 FLAG_BLUR_BEHIND —— 它按整个窗口矩形做模糊，
            // 会在玻璃板外留出一圈"糊掉但没底"的边带（就是用户看到的"外圈遮挡"）。
            w.setBackgroundBlurRadius(Math.max(1, dp(w.getContext(), radiusDp)));
        } catch (Throwable t) {
            Log.w(TAG, "背景模糊不可用，退回半透明底", t);
        }
    }

    /** 把 View 裁成圆角矩形（子 View 一起裁，TextureView 也能被切角） */
    public static void clipRounded(View v, final int radiusDp, final Context c) {
        final int r = dp(c, radiusDp);
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.invalidateOutline();
    }

    public static int alpha(int color, float a) {
        return Color.argb((int) (255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 对话框 context：按当前外观选深色（白字）或浅色（黑字）主题 */
    public static Context dialogContext(Context c) {
        return new android.view.ContextThemeWrapper(c,
                dark ? R.style.Theme_PipDesk_Dialog : R.style.Theme_PipDesk_Dialog_Light);
    }

    /**
     * 把对话框窗口换成玻璃底：毛玻璃 + 圆角 + 亮边。
     *
     * 关键：**玻璃板必须铺满窗口**。
     * 老实现用 InsetDrawable 在窗口内缩 12dp/4dp 画玻璃板，窗口比玻璃板大一圈，
     * 这一圈既没有玻璃板、又落在窗口的背景模糊范围里，于是玻璃板外出现一圈
     * "被糊过/更暗"的矩形边带 —— 看起来就是"弹框外面又套了一层"。
     * 现在改成：
     *   1) 窗口背景 = 玻璃板本身（无 inset）；
     *   2) 窗口宽度 = 屏幕宽 - 24dp（左右各 12dp 给圆角留白）；
     *   3) 窗口阴影归零（深色壁纸上的一圈阴影也会被看成外框）；
     *   4) 背景模糊跟随背景绘制区域，边界正好是玻璃板边缘。
     * 内容内边距由 {@link #padDialogContent} 补，避免文字贴到圆角上。
     */
    public static void glassWindow(Dialog d, Context c) {
        glassWindow(d, c, false);
    }

    /**
     * compact = 紧凑版：长按弹出的操作菜单用。
     * 完整版（560dp 上限）适合设置类内容；操作菜单只有几行文字，
     * 铺那么宽显得又空又大（用户反馈"菜单弹框太大"）。
     */
    public static void glassWindow(Dialog d, Context c, boolean compact) {
        if (d == null || d.getWindow() == null) return;
        Window w = d.getWindow();

        w.setBackgroundDrawable(glassPanel(c, compact ? 16 : 20));

        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        // 横屏时屏幕很宽，弹框按整宽会变成一条横幅 —— 上限 560dp 更适合阅读
        int maxW = compact ? dp(c, 300) : dp(c, 560);
        int dialogW = Math.min(screenW - dp(c, 24), maxW);
        w.setLayout(Math.max(dp(c, 200), dialogW), ViewGroup.LayoutParams.WRAP_CONTENT);
        try {
            w.setElevation(0f);     // 去掉窗口阴影（否则玻璃板外还有一圈暗边）
        } catch (Throwable ignored) { }

        blurWindow(w, BLUR_DP);

        // 全屏压暗是 Dialog 的常规遮罩，它没有边界，不会出现"一圈"。
        // 浅色外观下多压一点：底下的壁纸越暗，白玻璃上的黑字越清楚。
        float dim;
        if (Build.VERSION.SDK_INT >= 31) dim = dark ? 0.26f : 0.34f;
        else dim = dark ? 0.42f : 0.50f;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = dim;
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setAttributes(lp);

        padDialogContent(w, c, compact);
    }

    /**
     * 玻璃板铺满窗口后，标题/列表会贴到窗口边缘，这里统一给内容加内边距。
     * 只加一次（用 tag 记一下），否则重复调用会越加越宽。
     */
    private static void padDialogContent(Window w, Context c, boolean compact) {
        View decor = w.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        if (root.getTag(R.id.pipdesk_padded) != null) return;   // 已处理
        View content = w.findViewById(android.R.id.content);
        if (content == null) return;
        View target = (content.getParent() instanceof View) ? (View) content.getParent() : content;
        int h = dp(c, compact ? 8 : 14), v = dp(c, compact ? 2 : 6);
        if (compact) {
            // 操作菜单：把 content 到 decor 这一路的留白**全部清零**。
            // AppCompat 的 Dialog 主题会在其中某一层塞进 16dp 左右的留白（层级随主题/版本变），
            // 叠加在我们自己的 padding 上，结果标题离上沿一大截 —— 标题自己的 padding 压到 0
            // 也没用（用户反复反馈"标题太靠下"就是这个）。留白改由 GlassMenu 的根布局统一给。
            View v2 = content;
            View decor0 = w.getDecorView();
            while (v2 != null) {
                v2.setPadding(0, 0, 0, 0);
                if (v2 == decor0) break;
                android.view.ViewParent p = v2.getParent();
                if (!(p instanceof View)) break;
                v2 = (View) p;
            }
        } else {
            target.setPadding(h + target.getPaddingLeft(), v + target.getPaddingTop(),
                    h + target.getPaddingRight(), v + target.getPaddingBottom());
        }
        root.setTag(R.id.pipdesk_padded, Boolean.TRUE);
    }
}
