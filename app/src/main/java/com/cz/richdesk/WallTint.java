package com.cz.richdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

/**
 * 壁纸取样：判断「某个 View 背后那块壁纸」是亮还是暗。
 *
 * 为什么需要它：桌面是透壁纸的，文字颜色如果只看「深色/浅色外观」，
 * 就会出现「浅色模式下壁纸正好是黑图 → 黑字看不见」。用户要的是**按壁纸实际颜色**决定白字还是黑字。
 *
 * 做法：
 *  - 后台线程把壁纸缩成一张小图缓存起来（只读一次，壁纸变化时重取）；
 *  - 绘制时把 View 在窗口里的矩形换算成壁纸的归一化坐标，取一小块区域算平均亮度；
 *  - 亮度 > 0.55 判为「亮底 → 用深色字」，否则「暗底 → 用白色字」。
 *
 * 坐标换算：桌面窗口是铺满全屏的，壁纸是系统按屏幕等比铺满（center-crop）画的，
 * 所以「View 在窗口里的位置 / 窗口尺寸」就等于「在壁纸上的相对位置」，不用管壁纸真实像素尺寸。
 */
public final class WallTint {

    private static final String TAG = "PipDesk";

    /**
     * 判为亮底的阈值，用的是 **屏幕等效相对亮度**（已扣掉下面的暗化系数）。
     *
     * 0.32 = 只有明显亮的壁纸（白、米白、浅灰，屏幕灰度约 60% 以上）才用黑字，
     * 其余一律白字。这是产品取向：判错的代价不对称 —— 白字配深阴影在中灰背景上
     * 照样能读，黑字压在深色上就是完全看不见。所以门槛刻意定高。
     */
    private static final double REL_BRIGHT = 0.32;

    /**
     * 壁纸上取样得到的亮度，和「屏幕上真正看到的亮度」之间差一个压暗系数。
     *
     * 实测（手机 1080x2400，壁纸原图 839x1864，**深色模式**下）：屏幕那一块相对亮度
     * 只有壁纸的 0.65 倍左右（UC 浏览器处壁纸 0.691 / 屏幕 0.276，天气处 0.432 / 0.176）。
     * 原因是**深色模式**会给桌面壁纸压一层暗化（浅色模式不做），压暗发生在合成阶段，
     * 我们拿到的 getDrawable() 是原图、不含这一层。不补偿就会整体判偏亮 → 全用黑字。
     *
     * 所以这个系数必须跟着深浅模式走，不能写死一个 0.65 通吃：
     * 深色模式 0.65（实测值），浅色模式 1.0（不压暗）。
     */
    private static final double DIM_IN_DARK_MODE = 0.65;

    /** 当前该用的压暗系数 */
    private static double screenDim() {
        return Ui.dark ? DIM_IN_DARK_MODE : 1.0;
    }

    /** 缩略图长边：384 比原来的 192 精细一倍，一行文字在图上约 4~5 像素高，够取样了 */
    private static final int THUMB_SIDE = 384;

    /** 壁纸缩略图缓存（只读，所有线程共用） */
    private static volatile Bitmap thumb;
    /** 每个 View 都能按自己位置取样（静态壁纸） */
    private static volatile boolean perRegion;
    /**
     * 壁纸整体相对亮度（**未扣压暗系数**，-1 表示没样本）。
     *
     * 这里存原始亮度、不存「亮/暗」的布尔结果：压暗系数随深浅模式变，
     * 而深浅模式在一局运行里会变（日落自动切、跟随壁纸色切），
     * 一次算成布尔就会拿旧模式的结论去配新模式的屏幕，字色整体反掉。
     */
    private static volatile double globalWallLum = -1;
    /** 每次重新取样 +1，View 拿它判断「要不要重新算颜色」 */
    private static volatile int version;
    private static volatile boolean loading;
    /** 取到壁纸后回调（主线程），让桌面重绘一次 */
    private static volatile Runnable onReady;

    private WallTint() { }

    public static int version() {
        return version;
    }

    /** 有没有可用的壁纸判定（没有时调用方退回按深浅色外观取色） */
    public static boolean hasSample() {
        return perRegion || thumb != null;
    }

    public static void setOnReady(Runnable r) {
        onReady = r;
    }

    /**
     * 调试：把取样用的壁纸缩略图导出到应用外部目录，返回「路径 宽x高」。
     * 用来核对「屏幕上那块」和「缩略图上那块」到底对不对得上。
     */
    public static String dumpThumb(Context c) {
        Bitmap b = thumb;
        if (b == null) return null;
        try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            java.io.File f = new java.io.File(dir, "wall_thumb.png");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            return f.getAbsolutePath() + " " + b.getWidth() + "x" + b.getHeight()
                    + " perRegion=" + perRegion;
        } catch (Throwable t) {
            return "dump failed: " + t;
        }
    }

    /** 启动时调一次：后台取壁纸并算缩略图 */
    public static void load(Context c) {
        if (thumb != null || loading) return;
        startLoad(c);
    }

    /** 壁纸变了：丢掉缓存重新取 */
    public static void invalidate(Context c) {
        thumb = null;
        version++;
        loading = false;
        startLoad(c);
    }

    private static void startLoad(final Context c) {
        loading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Bitmap b = null;
            boolean live = false;
            boolean hasColors = false;
            double colorsLum = -1;
            String colorsTxt = "n/a";
            try {
                android.app.WallpaperManager wm =
                        android.app.WallpaperManager.getInstance(app);
                // 动态/画报壁纸：getDrawable() 给到的往往不是屏幕上真正在画的那张
                // （MIUI 实测拿到的是暗的占位图，按它取样会把浅米色壁纸判成深色）
                try {
                    live = wm.getWallpaperInfo() != null;
                } catch (Throwable ignored) { }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        android.app.WallpaperColors wc =
                                wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                        if (wc != null) {
                            android.graphics.Color pc = wc.getPrimaryColor();
                            if (pc != null) {
                                hasColors = true;
                                colorsLum = relLum(pc.toArgb());
                                colorsTxt = String.format("#%06X/L%.3f",
                                        pc.toArgb() & 0xFFFFFF, colorsLum);
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                Drawable dw = wm.getDrawable();
                if (dw != null) {
                    // 缩略图：长边 384px。太小的话一行文字在图上只剩 1~2 像素，取样会飘
                    int bw = Math.max(1, dw.getIntrinsicWidth());
                    int bh = Math.max(1, dw.getIntrinsicHeight());
                    float sc = THUMB_SIDE / (float) Math.max(bw, bh);
                    int w = Math.max(8, Math.round(bw * sc));
                    int h = Math.max(8, Math.round(bh * sc));
                    b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(b);
                    dw.setBounds(0, 0, w, h);
                    dw.draw(cv);
                    Log.i(TAG, "壁纸原图 " + bw + "x" + bh + " 缩略图 " + w + "x" + h);
                }
            } catch (Throwable t) {
                Log.w(TAG, "壁纸取样失败，文字颜色退回按外观取", t);
            }
            double avg = b == null ? -1 : avgRel(b);
            float var = b == null ? -1f : variance(b);
            // 像素可用 = 拿到图，且不是「一整片同色」的占位图
            boolean pixelsOk = b != null && var >= 0.004f;
            boolean positional = !live && pixelsOk;
            // 存「壁纸原始亮度」，压暗系数留到查询时再乘（深浅模式可能中途变）
            double wallLum;
            if (positional) {
                wallLum = avg;
            } else if (hasColors) {
                wallLum = colorsLum;        // 动态壁纸/占位图 → 用系统算好的壁纸主色
            } else {
                wallLum = pixelsOk ? avg : -1;
            }
            perRegion = positional;
            globalWallLum = wallLum;
            thumb = pixelsOk ? b : null;
            version++;
            loading = false;
            Log.i(TAG, String.format(
                    "壁纸取样 live=%s var=%.4f 壁纸L=%.3f 屏幕L=%.3f colors=%s -> 按位置=%s 全局亮=%s",
                    live, var, avg, b == null ? -1 : screenLum(avg),
                    colorsTxt, perRegion, isBrightGlobal()));
            Runnable r = onReady;
            if (r != null) {
                try {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
                } catch (Throwable ignored) { }
            }
        }, "pipdesk-walltint").start();
    }

    /** sRGB 亮度标准差：占位图/纯色图接近 0，真实照片远大于它。只看「图有没有内容」，故用 sRGB 即可 */
    private static float variance(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int stepX = Math.max(1, w / 24), stepY = Math.max(1, h / 24);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                sum += lum(b.getPixel(x, y));
                n++;
            }
        }
        if (n == 0) return 0f;
        double avg = sum / n;
        double sq = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                double d = lum(b.getPixel(x, y)) - avg;
                sq += d * d;
            }
        }
        return (float) Math.sqrt(sq / n);
    }

    /**
     * 全图平均「相对亮度」（WCAG，0=纯黑 1=纯白）。
     * 注意必须用 double 累加：曾经用 long 累加 0~1 的浮点，每个像素被截断成 0，
     * 平均恒为 0，把浅色壁纸全判成暗底。
     */
    private static double avgRel(Bitmap b) {
        double sum = 0;
        int n = 0;
        int w = b.getWidth(), h = b.getHeight();
        int stepX = Math.max(1, w / 32), stepY = Math.max(1, h / 32);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                sum += relLum(b.getPixel(x, y));
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 单像素 sRGB 感知亮度（0~1）：只用来做「图有没有内容」的方差判断 */
    private static float lum(int color) {
        return (Color.red(color) * 0.299f + Color.green(color) * 0.587f
                + Color.blue(color) * 0.114f) / 255f;
    }

    /** sRGB 单通道 → 线性光强 */
    private static double linear(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * WCAG 相对亮度（0~1）。这是判断「该配黑字还是白字」的正确度量：
     * 人眼对暗部极敏感（同样差值的 0.1 和 0.6 亮度，看起来差很多），
     * 直接拿 sRGB 平均会高估暗色壁纸的亮度。
     */
    private static double relLum(int color) {
        return 0.2126 * linear(Color.red(color))
                + 0.7152 * linear(Color.green(color))
                + 0.0722 * linear(Color.blue(color));
    }

    /** 相对亮度 → sRGB 灰度（0~1） */
    private static double srgbOf(double rel) {
        double v = rel <= 0.0031308 ? rel * 12.92 : 1.055 * Math.pow(rel, 1 / 2.4) - 0.055;
        return v < 0 ? 0 : Math.min(1, v);
    }

    private static double linearD(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * 壁纸取样亮度 → **屏幕上实际会呈现的亮度**（扣掉系统那层壁纸暗化）。
     * 压暗发生在 sRGB 空间（相当于叠一层半透明黑），所以要先转回 sRGB 再乘系数。
     */
    private static double screenLum(double wallRelLum) {
        return linearD(Math.max(0, Math.min(1, srgbOf(wallRelLum) * screenDim())));
    }

    /**
     * View 背后那块壁纸是否「亮」。
     * 没有样本时按当前外观给个合理默认（深色外观 → 暗底）。
     */
    /**
     * 整张壁纸统一判定（应用列表的图标名称用这个）：
     * 一页里所有名字同色，看着整齐；壁纸半明半暗时靠文字阴影兜底。
     */
    public static boolean isBrightGlobal() {
        double wl = globalWallLum;
        if (wl < 0) return !Ui.dark;       // 没样本 → 按外观兜底
        return screenLum(wl) > REL_BRIGHT;
    }

    /** 给定相对亮度，判断该处背景算不算「亮底」（亮底用黑字） */
    public static boolean isBrightLum(float relativeLuminance) {
        return relativeLuminance > REL_BRIGHT;
    }

    public static boolean isBright(View v) {
        float l = sampleRelLum(v, Integer.MIN_VALUE, 0, 0, 0);
        // 拿不到样本（动态壁纸 / 取图失败）才按外观兜底
        return l < 0 ? !Ui.dark : l > REL_BRIGHT;
    }

    /**
     * 采样一块矩形（窗口坐标）背后的壁纸「相对亮度」。
     *
     * 传 l == Integer.MIN_VALUE 表示用整个 View 的矩形。
     * 返回 -1 表示没有可用样本。
     *
     * 三个关键点（都是之前不准的原因）：
     *  1. 壁纸是 **center-crop** 铺满的，宽高比和窗口不一致时要先算裁剪偏移，
     *     直接把窗口 0~1 拉伸到整张壁纸会整体偏位；
     *  2. 用区域内的**中位数**而不是平均值：文字压在壁纸高光或深影上时，
     *     平均值会被极端值拉走，中位数才代表「这一块大致是什么底色」；
     *  3. 返回的是 WCAG 相对亮度，配合 REL_BRIGHT 用对比度相等点判定。
     */
    public static float sampleRelLum(View v, int l, int t, int r, int b) {
        Bitmap bm = thumb;
        if (!perRegion || bm == null || v == null) return -1f;
        try {
            View root = v.getRootView();
            int W = root.getWidth(), H = root.getHeight();
            if (W <= 0 || H <= 0) return -1f;
            if (l == Integer.MIN_VALUE) {
                int[] loc = new int[2];
                v.getLocationInWindow(loc);
                l = loc[0];
                t = loc[1];
                r = l + Math.max(1, v.getWidth());
                b = t + Math.max(1, v.getHeight());
            }
            int bw = bm.getWidth(), bh = bm.getHeight();
            // 每 1 个缩略图像素对应多少屏幕像素；多出来的部分被裁掉
            float s = Math.max(W / (float) bw, H / (float) bh);
            float ox = (bw * s - W) / 2f;
            float oy = (bh * s - H) / 2f;

            float fx0 = fclamp((l + ox) / s, 0, bw - 1);
            float fy0 = fclamp((t + oy) / s, 0, bh - 1);
            float fx1 = fclamp((r + ox) / s, fx0 + 0.5f, bw);
            float fy1 = fclamp((b + oy) / s, fy0 + 0.5f, bh);

            int x0 = (int) Math.floor(fx0), y0 = (int) Math.floor(fy0);
            int x1 = Math.min(bw, (int) Math.ceil(fx1)), y1 = Math.min(bh, (int) Math.ceil(fy1));
            if (x1 <= x0) x1 = Math.min(bw, x0 + 1);
            if (y1 <= y0) y1 = Math.min(bh, y0 + 1);

            int nx = 9, ny = 3;
            float[] vals = new float[nx * ny];
            int n = 0;
            for (int i = 0; i < ny; i++) {
                int y = y0 + (y1 - y0) * i / Math.max(1, ny - 1);
                if (y >= bh) y = bh - 1;
                for (int j = 0; j < nx; j++) {
                    int x = x0 + (x1 - x0) * j / Math.max(1, nx - 1);
                    if (x >= bw) x = bw - 1;
                    vals[n++] = (float) relLum(bm.getPixel(x, y));
                }
            }
            java.util.Arrays.sort(vals, 0, n);
            return (float) screenLum(vals[n / 2]);
        } catch (Throwable ex) {
            return -1f;
        }
    }

    private static float fclamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
