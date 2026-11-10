package com.cz.richdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.widget.TextView;

/**
 * 桌面文字：压在壁纸上的应用名 / 页面标题（overWallpaper = true），
 * 以及弹窗里压在玻璃上的文字（overWallpaper = false）。
 *
 * 字色**只跟外观模式走**，不再按壁纸取样（需求 2026-11-10）：
 *
 *   浅色模式：加底板 = 白底黑字；不加底板 = 白字
 *   深色模式：加底板 = 黑底白字；不加底板 = 黑字
 *
 * 弹窗（压在玻璃上）例外：字色跟玻璃底色相反 —— 浅色外观黑字、深色外观白字，
 * 否则浅玻璃上压白字直接看不见。
 *
 * 底板按**文字实际占的那一行**画（行宽 × 行高外扩），不按整个控件：标签控件的宽度
 * 是整格，整格涂底会变成一条横贯的长条，和图标完全不对齐。
 */
public class WallText extends TextView {

    /** true：桌面上直接压壁纸；false：弹窗里压在浅/深玻璃上 */
    private boolean overWallpaper;

    /** 次要文字（比如页面标题）：颜色淡一档 */
    private boolean dim;

    /** 名称底板（设置里的「应用名称背景」） */
    private boolean plate;
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plateRect = new RectF();
    private float plateRadius = -1f;

    /** 上次用到的外观 + 是否需要重算（底板/使用场景变化时置位） */
    private boolean lastDark;
    private boolean dirty = true;

    /** 上次用的字色 / 阴影档位，避免重复 set 引起无谓重绘 */
    private int lastColor = Integer.MIN_VALUE;
    private int lastTone = -1;

    private final int[] box = new int[4];
    private final int[] locTmp = new int[2];

    public WallText(Context c, boolean overWallpaper) {
        super(c);
        this.overWallpaper = overWallpaper;
        // 行数/对齐/省略号交给调用方设置：标题要单行不省略，标签要单行省略，两者不一样
    }

    /**
     * 应用名称底板：开启后字色按深浅模式固定（白底黑字 / 黑底白字）。
     * 必须清掉字色缓存，否则 syncColor 会因为「外观没变」直接 return，颜色不翻转。
     */
    public void setPlate(boolean v) {
        if (plate == v) return;
        plate = v;
        dirty = true;
        invalidate();
    }

    public boolean isPlate() {
        return plate;
    }

    public void setOverWallpaper(boolean v) {
        if (overWallpaper != v) {
            overWallpaper = v;
            dirty = true;
            invalidate();
        }
    }

    public void setDim(boolean v) {
        if (dim != v) {
            dim = v;
            dirty = true;
            invalidate();
        }
    }

    /** 调试用：按当前位置取一次壁纸亮度（0~1，-1 表示无样本） */
    public float sampleNow() {
        computeBox();
        return WallTint.sampleRelLum(this, box[0], box[1], box[2], box[3]);
    }

    /** 调试用：当前取样矩形，格式 "l,t,r,b" */
    public String boxText() {
        computeBox();
        return box[0] + "," + box[1] + "," + box[2] + "," + box[3];
    }

    @Override
    protected void onDraw(Canvas cv) {
        syncColor();
        if (plate) drawPlate(cv);
        super.onDraw(cv);
    }

    /**
     * 在文字实际占的那一块（行宽 × 行高，再外扩一点）画圆角底板。
     */
    private void drawPlate(Canvas cv) {
        float l, t, r, b;
        Layout lay = getLayout();
        if (lay != null && lay.getLineCount() > 0) {
            int pl = getCompoundPaddingLeft();
            int pt = getExtendedPaddingTop();
            l = pl + lay.getLineLeft(0);
            r = pl + lay.getLineRight(0);
            t = pt + lay.getLineTop(0);
            b = pt + lay.getLineBottom(0);
        } else {
            l = 0; t = 0; r = getWidth(); b = getHeight();
        }
        float ts = getTextSize();
        float padX = ts * 0.34f;      // 左右留白：够成一个"药丸"，又不会盖住旁边的格子
        float padY = ts * 0.10f;
        plateRect.set(l - padX, t - padY, r + padX, b + padY);
        // 文字被省略号截到控件边界时，底板不能画出控件外（会被父容器裁出硬边）
        if (plateRect.left < 0) plateRect.left = 0;
        if (plateRect.top < 0) plateRect.top = 0;
        if (plateRect.right > getWidth()) plateRect.right = getWidth();
        if (plateRect.bottom > getHeight()) plateRect.bottom = getHeight();
        if (plateRadius < 0) plateRadius = Math.max(2f, ts * 0.36f);
        platePaint.setStyle(Paint.Style.FILL);
        // 底板配色跟着深浅模式：深色外观 = 黑底（配白字），浅色外观 = 白底（配黑字）。
        // 都留一点透明度，压在花壁纸上不至于像贴了张不干胶。
        platePaint.setColor(Ui.dark ? 0xF00A0C10 : 0xF2FFFFFF);
        cv.drawRoundRect(plateRect, plateRadius, plateRadius, platePaint);
    }

    /** 算出文字实际绘制的矩形（窗口坐标），写进 box[4]，调试取样用 */
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

    /**
     * 定字色。四种组合全部只由「外观模式 + 底板 + 是否压壁纸」决定，
     * 不再随位置/壁纸变化，所以只在任一项变化时重算一次。
     */
    private void syncColor() {
        if (!dirty && Ui.dark == lastDark) return;
        dirty = false;
        lastDark = Ui.dark;

        // 是否白字：
        //   有底板   → 深色白字 / 浅色黑字
        //   压壁纸   → 浅色白字 / 深色黑字（需求）
        //   压玻璃   → 深色白字 / 浅色黑字（跟玻璃底色相反，否则浅玻璃上看不见）
        boolean white = plate ? Ui.dark : (overWallpaper ? !Ui.dark : Ui.dark);

        int color, shadow, tone;
        if (plate) {
            // 底板本身就够对比，不要阴影（黑字压黑底/白字压白底时阴影只会发脏）
            color = white ? 0xFFFFFFFF : 0xFF000000;
            shadow = 0;
            tone = white ? 2 : 3;
        } else if (white) {
            color = dim ? 0xCCFFFFFF : 0xF2FFFFFF;
            // 白字的阴影要比黑字重：经常压在米色、浅蓝这类中亮背景上，
            // 靠这层深阴影把轮廓压出来。
            shadow = dim ? 0x99000000 : 0xCC000000;
            tone = 0;
        } else {
            color = dim ? 0xB3000000 : 0xE6000000;
            // 黑字配白晕：壁纸局部偏暗时也能看清（相当于一圈浅色描边）
            shadow = dim ? 0x66FFFFFF : 0x99FFFFFF;
            tone = 1;
        }

        // 颜色没变就不要 set：setTextColor 会再 invalidate 一次
        if (color != lastColor) {
            lastColor = color;
            setTextColor(color);
        }
        // tone 唯一决定 (白/黑字 + 有无阴影)，阴影只在档位真变时重设 ——
        // setShadowLayer 内部会 invalidate，每帧调一次等于自我刷屏。
        if (tone != lastTone) {
            lastTone = tone;
            if (shadow == 0) {
                setShadowLayer(0, 0, 0, 0);
            } else {
                setShadowLayer(white ? 4f : 3.5f, 0, 1, shadow);
            }
        }
    }
}
