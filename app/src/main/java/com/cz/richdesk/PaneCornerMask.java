package com.cz.richdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;

/**
 * 画中画的「四角壁纸遮罩」—— 用遮罩替代整块圆角裁剪。
 *
 * <h3>为什么要它</h3>
 * 画中画的圆角原来是 {@code setClipToOutline(true)} + 圆角 outline 实现的。
 * 被裁的是 TextureView（外部纹理），HWUI 对这种裁剪通常要开离屏：
 * 先把整块 199 万像素画进一张同尺寸的临时表面，裁完再贴回窗口。
 * 60fps 下等于每秒多搬约 1GB 显存带宽 —— 只为了切掉四个角。
 *
 * <h3>怎么做到"看不出差别"</h3>
 * 遮罩**不画纯色**，而是画**壁纸在这个位置的真实像素**。
 * 纯色遮罩必然和壁纸对不上（壁纸会换、深浅色会变），四角就是色块；
 * 画壁纸本身则和周围完全连续，视觉上等价于真的把内容裁成了圆角。
 *
 * <h3>实现要点（踩过的坑写在这）</h3>
 * 1. **不用 canvas 变换**：所有 Path 直接用窗口绝对坐标构造。
 *    第一版用 {@code translate + scale(±1)} 复用一段 Path，结果 BitmapShader 跟着
 *    canvas 变换走，壁纸被画反/偏移；而 clipPath 一旦失效就整块什么都不画（圆角消失）。
 * 2. **不用 clipPath**：直接用 {@code Path.op(DIFFERENCE)} 算出"矩形减圆角矩形"，
 *    也就是四个角，然后 {@code drawPath} 填充。填充比裁剪可靠，也不受非凸路径限制。
 * 3. shader 矩阵按整个窗口算一次并缓存；窗口尺寸变了才重算。
 */
final class PaneCornerMask extends View {

    private static final String TAG = "PipDesk";

    private int radiusPx;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final int[] loc = new int[2];

    private BitmapShader shader;
    private Bitmap shaderBmp;
    private int shaderW, shaderH;

    /** 四个角的形状（窗口绝对坐标），随位置/尺寸/半径变化重建 */
    private Path maskPath;
    private int pathX = -1, pathY = -1, pathW = -1, pathH = -1, pathR = -1;

    /** 拿不到壁纸时回调一次（上层据此退回整块裁剪，避免四角是方的） */
    Runnable onUnavailable;
    private boolean gaveUp;
    private boolean loggedOnce;

    PaneCornerMask(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        // 壁纸解码放到后台：onDraw 里做 IO 会掉帧，而且只解一次
        new Thread(() -> {
            Ui.wallpaperBitmap(c.getApplicationContext());
            post(this::invalidate);
        }, "pipdesk-wallpaper").start();
    }

    /** 圆角半径（像素）。0 = 不遮（直角，零成本）。 */
    void setRadiusPx(int px) {
        px = Math.max(0, px);
        if (radiusPx == px) return;
        radiusPx = px;
        maskPath = null;        // 形状跟半径绑定，重建
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (radiusPx <= 0) return;

        Bitmap bmp = Ui.wallpaperBitmap(getContext());
        if (bmp == null) {
            // 拿不到壁纸（动态壁纸等）：不能就这么放着 —— 四角会变成方的。
            // 通知上层切回裁剪方案，只通知一次。
            if (!gaveUp && onUnavailable != null) {
                gaveUp = true;
                post(onUnavailable);
            }
            return;
        }

        View rootView = getRootView();
        int sw = rootView.getWidth();
        int sh = rootView.getHeight();
        if (sw <= 0 || sh <= 0) return;

        getLocationInWindow(loc);
        int x = loc[0], y = loc[1], w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        ensureShader(bmp, sw, sh);
        ensureMask(x, y, w, h);
        if (maskPath == null) return;

        if (!loggedOnce) {
            loggedOnce = true;
            Log.i(TAG, "四角遮罩生效：pane 区域 " + x + "," + y + " " + w + "x" + h
                    + " r=" + radiusPx + " 壁纸=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " 窗口=" + sw + "x" + sh);
        }
        cv.drawPath(maskPath, paint);
    }

    /**
     * 四个角的形状 = 画中画矩形 减去 圆角矩形。
     *
     * 一次 {@code Path.op} 就得到全部四块，不用为每个角单独算角度和弧的起止点
     * （第一版手写 arcTo 的四个方向参数，很容易把某个角画到里面去）。
     */
    private void ensureMask(int x, int y, int w, int h) {
        if (maskPath != null && x == pathX && y == pathY
                && w == pathW && h == pathH && radiusPx == pathR) return;

        RectF rf = new RectF(x, y, x + w, y + h);
        Path full = new Path();
        full.addRect(rf, Path.Direction.CW);
        Path round = new Path();
        round.addRoundRect(rf, radiusPx, radiusPx, Path.Direction.CW);

        Path out = new Path();
        boolean ok = false;
        try {
            // 注意参数顺序：op(path1, op, path2) 的结果写进**调用者自己**，
            // 所以必须是 out.op(全矩形, DIFFERENCE, 圆角矩形)
            out.op(full, round, Path.Op.DIFFERENCE);
            ok = !out.isEmpty();
        } catch (Throwable t) {
            Log.w(TAG, "Path.op 不可用，四角遮罩放弃", t);
        }
        if (!ok) {
            maskPath = null;
            if (!gaveUp && onUnavailable != null) {
                gaveUp = true;
                post(onUnavailable);
            }
            return;
        }
        maskPath = out;
        pathX = x;
        pathY = y;
        pathW = w;
        pathH = h;
        pathR = radiusPx;
    }

    /**
     * 壁纸按整窗居中裁切铺满（center-crop），与系统壁纸的默认铺法一致。
     *
     * shader 的坐标就是**窗口坐标**：因为 Path 也是窗口坐标、canvas 不做任何变换，
     * 两者天然对齐 —— 这是第一版镜像方案栽过的地方。
     */
    private void ensureShader(Bitmap bmp, int sw, int sh) {
        if (shader != null && shaderBmp == bmp && shaderW == sw && shaderH == sh) return;
        shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(sw / (float) bmp.getWidth(), sh / (float) bmp.getHeight());
        shaderMatrix.reset();
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate((sw - bmp.getWidth() * scale) / 2f,
                (sh - bmp.getHeight() * scale) / 2f);
        shader.setLocalMatrix(shaderMatrix);
        paint.setShader(shader);
        shaderBmp = bmp;
        shaderW = sw;
        shaderH = sh;
    }

    /** 遮罩完全是装饰层，不能挡触摸 */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }
}
