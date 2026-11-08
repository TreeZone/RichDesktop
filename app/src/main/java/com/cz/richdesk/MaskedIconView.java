package com.cz.richdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.ImageView;

/**
 * 带「iOS 图标遮罩」的 ImageView。
 *
 * 为什么要遮罩：安卓各家应用的图标形状五花八门 —— 圆角方、正圆、不规则、带透明边，
 * 并排放在 Dock / 应用列表里参差不齐。套一层统一的圆角方（squircle）遮罩之后，
 * 所有图标外形一致，观感接近 iOS 桌面。
 *
 * 形状用的是 **超椭圆**（|x/a|^n + |y/b|^n = 1，n≈5）而不是普通的圆角矩形：
 * iOS 图标的圆角是"连续曲率"的，普通圆角矩形看起来会发死，超椭圆才像。
 *
 * 同时把图标放大一点点（{@link #ZOOM}），让图标自身的边角被遮罩切掉，
 * 看起来是"铺满整个形状"的，而不是"一个形状里又缩了一个小图标"。
 *
 * 兼容性：clipPath 画凸多边形路径在 Android 5（API 21）起就支持硬件加速，9~16 都可用。
 */
public class MaskedIconView extends ImageView {

    /** 图标相对遮罩的放大倍数：切掉图标自带的边距/圆角，做到「铺满」 */
    private static final float ZOOM = 1.06f;

    /** 超椭圆的指数：越大越接近方，5 接近 iOS 的手感 */
    private static final float SQUIRCLE_N = 5f;

    /** 曲线采样点数（整圈）。256 段已经看不出折线 */
    private static final int SEGMENTS = 256;

    private final Path shape = new Path();
    private final RectF bounds = new RectF();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 上次建路径用的遮罩方框（用它判断要不要重建，别只比边长） */
    private boolean shapeReady;

    public MaskedIconView(Context c) {
        super(c);
        setScaleType(ScaleType.FIT_CENTER);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0x1A000000);        // 极淡的一圈内描边，浅色壁纸上图标边界更清楚
    }

    /** 超椭圆路径（以控件矩形为外框） */
    private static void buildSquircle(Path out, RectF r, int segments) {
        out.reset();
        float a = r.width() / 2f;
        float b = r.height() / 2f;
        float cx = r.centerX();
        float cy = r.centerY();
        float e = 2f / SQUIRCLE_N;       // 参数方程里的指数
        for (int i = 0; i <= segments; i++) {
            double t = -Math.PI / 2 + 2 * Math.PI * i / segments;
            double ct = Math.cos(t), st = Math.sin(t);
            float x = (float) (Math.signum(ct) * Math.pow(Math.abs(ct), e)) * a;
            float y = (float) (Math.signum(st) * Math.pow(Math.abs(st), e)) * b;
            if (i == 0) out.moveTo(cx + x, cy + y);
            else out.lineTo(cx + x, cy + y);
        }
        out.close();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            super.onDraw(cv);
            return;
        }
        // 遮罩按「图标实际占用的正方块」算，而不是整个控件：
        // ImageView 宽度常常是 MATCH_PARENT（比图标宽），按控件算会让圆角落在图标外面，
        // 结果图标根本没被切到，形状也没统一。
        int side = Math.min(w, h);
        float l = (w - side) / 2f;
        float t = (h - side) / 2f;
        // 重建条件必须包含左上角位置，不能只比边长：
        // 横竖屏切换/改列数时控件变宽、高度（=图标高）不变，side 一样但 l 变了，
        // 只比边长就不会重建 —— 遮罩留在旧位置，图标右半边被竖直切掉（"图标缺边"）。
        if (!shapeReady || bounds.left != l || bounds.top != t
                || bounds.width() != side || bounds.height() != side) {
            bounds.set(l, t, l + side, t + side);
            buildSquircle(shape, bounds, SEGMENTS);
            shapeReady = true;
        }
        int sc = cv.save();
        cv.clipPath(shape);
        float z = getZoom();
        if (z != 1f) cv.scale(z, z, w / 2f, h / 2f);
        super.onDraw(cv);
        cv.restoreToCount(sc);

        // 描边画在裁切之外，不会被自己切掉
        edge.setStrokeWidth(Math.max(1f, side * 0.006f));
        cv.drawPath(shape, edge);
    }

    /** 放大倍数：只有真正"铺满"的图标才放大，圆形/小图标放大反而会糊边 */
    protected float getZoom() {
        return ZOOM;
    }

    /** 供外部（设置页预览等）复用同一套形状 */
    public static Path squirclePath(int w, int h) {
        Path p = new Path();
        buildSquircle(p, new RectF(0, 0, w, h), SEGMENTS);
        return p;
    }
}
