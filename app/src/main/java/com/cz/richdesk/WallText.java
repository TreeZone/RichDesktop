package com.cz.richdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.widget.TextView;

/**
 * 跟着「背后壁纸颜色」自动变字色的 TextView。
 *
 * 用户需求：应用列表里的应用名，深色/浅色模式下到底是黑字还是白字，
 * **要看壁纸那一块的实际颜色**，不能只按外观模式拍。浅色模式 + 深色壁纸时，
 * 原来的黑字直接糊在深色背景上，看不见。
 *
 * 取样区域取的是**文字真正绘制的那一小块**（行宽 × 行高，上下各扩 40%），
 * 而不是整个控件矩形：标签的控件宽是整格宽度，里面有大量空白甚至别的壁纸颜色，
 * 按整个格子取样会把「文字压在哪」这件事平均掉，判出来经常是错的。
 *
 * 注意：硬件加速下 View 只是平移时不会重跑 onDraw，所以滚动要靠外部调 refresh()。
 */
public class WallText extends TextView {

    /** true：按壁纸取样定色（桌面上直接压壁纸的场合）；false：按外观定色（弹窗里的浅/深玻璃上） */
    private boolean overWallpaper;

    /** 次要文字（比如页面标题）：颜色淡一档 */
    private boolean dim;

    /** true：每个文字按自己脚下那块壁纸取色；false：整页统一（默认关，桌面用 true） */
    private boolean adaptive;

    /** 上次取样用的文字矩形 + 壁纸版本 + 外观，任一变化才重新算 */
    private int lastL = Integer.MIN_VALUE, lastT, lastR, lastB;
    private int lastVer = Integer.MIN_VALUE;
    private boolean lastDark;

    /** 上次用的字色 / 明暗档位（-1 表示还没定过），避免重复 set 引起无谓重绘 */
    private int lastColor = Integer.MIN_VALUE;
    private int lastTone = -1;

    /** 最近一次测到的背景相对亮度（-1 = 没有样本），调试用 */
    private float lastLum = -1f;

    private final int[] box = new int[4];
    private final int[] locTmp = new int[2];

    public WallText(Context c, boolean overWallpaper) {
        super(c);
        this.overWallpaper = overWallpaper;
        // 行数/对齐/省略号交给调用方设置：标题要单行不省略，标签要单行省略，两者不一样
    }

    /** 打开「逐行随壁纸自适应」 */
    public void setAdaptive(boolean v) {
        if (adaptive != v) {
            adaptive = v;
            invalidate();
        }
    }

    public void setOverWallpaper(boolean v) {
        if (overWallpaper != v) {
            overWallpaper = v;
            invalidate();
        }
    }

    public void setDim(boolean v) {
        if (dim != v) {
            dim = v;
            invalidate();
        }
    }

    /** 最近一次测到的背景相对亮度（0~1，-1 表示无样本），排查颜色判定用 */
    public float lastLum() {
        return lastLum;
    }

    /** 立刻按当前位置取一次样（不等绘制），调试用 */
    public float sampleNow() {
        computeBox();
        lastLum = WallTint.sampleRelLum(this, box[0], box[1], box[2], box[3]);
        return lastLum;
    }

    /** 调试用：当前取样矩形，格式 "l,t,r,b" */
    public String boxText() {
        computeBox();
        return box[0] + "," + box[1] + "," + box[2] + "," + box[3];
    }

    @Override
    protected void onDraw(Canvas cv) {
        syncColor();
        super.onDraw(cv);
    }

    /**
     * 强制重新按当前位置取色。
     *
     * 为什么需要它：硬件加速下 View 的绘制被缓存成 RenderNode，**只是位置移动**
     * （列表滚动）时系统直接复用缓存、根本不会再调 onDraw —— 所以「在 onDraw 里
     * 检查位置变化」这条路在滚动时是死代码，颜色会一直停在第一次算出的值。
     * 滚动时由宿主动手调这个方法，把缓存清掉并 invalidate，才会真正重算。
     */
    public void refresh() {
        lastL = Integer.MIN_VALUE;
        lastVer = Integer.MIN_VALUE;
        invalidate();
    }

    /** 算出文字实际绘制的矩形（窗口坐标），写进 box[4] */
    private void computeBox() {
        getLocationInWindow(locTmp);
        int x = locTmp[0], y = locTmp[1];
        int l = x, t = y, r = x + getWidth(), b = y + getHeight();
        Layout lay = getLayout();
        if (lay != null && lay.getLineCount() > 0) {
            int pl = getCompoundPaddingLeft();
            int pt = getExtendedPaddingTop();
            l = x + pl + (int) Math.floor(lay.getLineLeft(0));
            r = x + pl + (int) Math.ceil(lay.getLineRight(0));
            t = y + pt + lay.getLineTop(0);
            b = y + pt + lay.getLineBottom(0);
            // 上下各扩 40%：字形周围那圈留白也是背景，只盯笔画会被壁纸的细高光骗
            int pad = Math.max(2, (b - t) * 2 / 5);
            t -= pad;
            b += pad;
            if (r <= l) r = l + 1;
        }
        box[0] = l;
        box[1] = t;
        box[2] = r;
        box[3] = b;
    }

    private void syncColor() {
        int ver = overWallpaper ? WallTint.version() : 0;
        if (overWallpaper) computeBox();
        if (ver == lastVer && Ui.dark == lastDark
                && box[0] == lastL && box[1] == lastT && box[2] == lastR && box[3] == lastB) {
            return;
        }
        lastVer = ver;
        lastDark = Ui.dark;
        lastL = box[0];
        lastT = box[1];
        lastR = box[2];
        lastB = box[3];

        boolean bright;
        if (!overWallpaper) {
            bright = !Ui.dark;
            lastLum = -1f;
        } else if (adaptive) {
            float l = WallTint.sampleRelLum(this, box[0], box[1], box[2], box[3]);
            lastLum = l;
            // 无样本（动态壁纸）时才退回外观
            bright = l < 0 ? !Ui.dark : WallTint.isBrightLum(l);
        } else {
            bright = WallTint.isBrightGlobal();
            lastLum = -1f;
        }

        int color;
        int shadow;
        if (bright) {
            color = dim ? 0xB3000000 : 0xE6000000;
            // 黑字配白晕：壁纸局部偏暗时也能看清（相当于一圈浅色描边）
            shadow = dim ? 0x66FFFFFF : 0x99FFFFFF;
        } else {
            color = dim ? 0xCCFFFFFF : 0xF2FFFFFF;
            // 白字的阴影要比黑字重：默认给白字（见 WallTint.REL_BRIGHT），
            // 就会经常压在米色、浅蓝这类中亮背景上，靠这层深阴影把轮廓压出来。
            shadow = dim ? 0x99000000 : 0xCC000000;
        }
        // 颜色没变就不要 set：setTextColor 会再 invalidate 一次，滚动节流刷新时会白抖
        int tone = bright ? 1 : 0;
        if (color != lastColor) {
            lastColor = color;
            setTextColor(color);
        }
        if (tone != lastTone) {
            lastTone = tone;
            setShadowLayer(bright ? 3.5f : 4f, 0, 1, shadow);
        }
    }
}
