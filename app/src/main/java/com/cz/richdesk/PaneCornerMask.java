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
import android.view.View;

/**
 * 画中画的「四角壁纸遮罩」—— 用遮罩替代整块圆角裁剪。
 *
 * <h3>为什么要它</h3>
 * 现在画中画的圆角是 {@code setClipToOutline(true)} + 圆角 outline 实现的。
 * 被裁的是 TextureView（外部纹理），HWUI 对这种裁剪通常要开离屏：
 * 先把整块 199 万像素画进一张同尺寸的临时表面，裁完再贴回窗口。
 * 60fps 下等于每秒多搬约 1GB 显存带宽 —— 只为了切掉四个角。
 *
 * <h3>怎么做到"看不出差别"</h3>
 * 关键点：遮罩**不画纯色**，而是画**壁纸在这个位置的真实像素**。
 * 纯色遮罩必然和壁纸对不上（壁纸会换、深浅色会变），四角就是四个色块；
 * 画壁纸本身则和周围完全连续，视觉上等价于真的把内容裁成了圆角。
 *
 * 具体做法：只覆盖画中画的四个 {@code r×r} 角，每个角用一段"直角减圆弧"的
 * Path 裁出该画的区域（也就是圆角以外的那一小块），在里面铺壁纸。
 * 每帧面积 4 × r²（r=24dp 时约 1.4 万像素），比整块离屏便宜约两个数量级。
 *
 * <h3>失败回退</h3>
 * 拿不到壁纸位图（动态壁纸等）时 {@link Ui#wallpaperBitmap} 返回 null，
 * 本 View 直接不画；上层 {@link PipPane} 会据此退回原来的裁剪方案。
 */
final class PaneCornerMask extends View {

    private int radiusPx;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF screen = new RectF();
    private final RectF oval = new RectF();
    private final int[] loc = new int[2];

    /** 拿不到壁纸时回调一次（上层据此退回整块裁剪，避免四角是方的） */
    Runnable onUnavailable;
    private boolean gaveUp;

    private BitmapShader shader;
    private Bitmap shaderBmp;
    private int shaderW, shaderH;
    private Path corner;

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
        corner = null;          // 形状跟半径绑定，重建
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

        View root = getRootView();
        int sw = root.getWidth();
        int sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;

        ensureShader(bmp, sw, sh);
        ensureCorner();
        screen.set(0, 0, sw, sh);

        // 遮罩自己在窗口里的位置：壁纸按整窗坐标铺，所以要把它换算回去
        getLocationInWindow(loc);
        int x = loc[0], y = loc[1];
        int w = getWidth(), h = getHeight();

        // 四个角：靠 scale(±1) 复用同一段 Path，避免维护四套坐标
        paintCorner(cv, x, y, 1, 1);                 // 左上
        paintCorner(cv, x + w, y, -1, 1);            // 右上
        paintCorner(cv, x + w, y + h, -1, -1);       // 右下
        paintCorner(cv, x, y + h, 1, -1);            // 左下
    }

    /**
     * 画一个角。
     *
     * @param cx,cy 该角在窗口里的角点
     * @param sx,sy 1 或 -1，把左上角的形状翻到其余三个角
     *
     * 注意 scale 必须**撤回来**再 drawRect：BitmapShader 的坐标跟随 canvas 变换，
     * 带着镜像画会把壁纸画反（四角内容和周围对不上，一眼能看出来）。
     */
    private void paintCorner(Canvas cv, float cx, float cy, int sx, int sy) {
        cv.save();
        cv.translate(cx, cy);
        cv.scale(sx, sy);
        cv.clipPath(corner);        // 只留"圆角以外"的那一小块
        cv.scale(sx, sy);           // 撤销镜像（±1 × ±1 = 1）
        cv.translate(-cx, -cy);
        cv.drawRect(screen, paint);
        cv.restore();
    }

    /** 左上角的"直角减圆弧"形状：角点在 (0,0)，边长 r */
    private void ensureCorner() {
        if (corner != null) return;
        int r = radiusPx;
        Path p = new Path();
        p.moveTo(0, 0);
        p.lineTo(r, 0);
        oval.set(0, 0, 2 * r, 2 * r);
        // 从 (r,0) 沿圆弧走到 (0,r)：这一段弧就是圆角的边，弧以外的部分才填壁纸
        p.arcTo(oval, 270, -90);
        p.close();
        corner = p;
    }

    /**
     * 壁纸按整窗居中裁切铺满（center-crop），与系统壁纸的默认铺法一致。
     *
     * 用 shader 而不是 drawBitmap：只需要算出一次矩阵，之后所有角共用同一个 Paint。
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

    /** 壁纸能不能拿到（上层据此决定用遮罩还是退回裁剪） */
    static boolean available(Context c) {
        return Ui.wallpaperBitmap(c) != null;
    }

    /** 让遮罩不要挡触摸：它完全是装饰层 */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }
}
