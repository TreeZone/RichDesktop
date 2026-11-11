package com.cz.richdesk;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 画中画容器：自己按「槽位顺序 + 权重比例」摆放子 View，**不摘挂视图**。
 *
 * 为什么要自己写：调换两个画中画的位置时，如果用 LinearLayout 调 addView 顺序，
 * 必然要先 removeView —— 一摘挂，TextureView 的 SurfaceTexture 就销毁了，
 * 虚拟屏跟着释放重建，屏内应用要重新拉起，观感就是"调个顺序整屏闪一下"。
 * 这里改成只改「逻辑顺序」，子 View 始终贴在窗口上，Surface 一直活着。
 *
 * 分屏比例（需求）：
 *   2 屏 —— 1:1 / 1:2 / 2:1
 *   3 屏 —— 1:1:1 / 1:2 / 2:1（后两种会折叠掉**最边上**那一屏，中间那块永远不被挤扁）
 *
 * 交互（需求：拖动条，不要长按弹框）：
 *   两块屏之间的缝里放一条**玻璃小竖条**（横屏是竖条，竖屏是横条），
 *   - 按住它左右/上下拖动即可实时改比例，松手吸附到上面的标准比例（220ms 缓动）；
 *     手指要先走过 DRAG_START_DP 才开始生效，避免"一碰就跑"；
 *   - **轻点它 = 交换这两块画中画的内容**（比例不变，竖条不会跳位置）；
 *   - 拖到极限会把被挤扁的那屏折叠掉；被折叠的屏会在**屏幕边缘**留一条同样的竖条，
 *     反方向拖（或轻点）就能恢复，不需要任何菜单。
 */
public class PaneBox extends FrameLayout {

    public interface Listener {
        /** 拖动/动画中每一帧（weights 为最新比例，0 = 折叠） */
        void onRatioChanging(float[] weights);
        /** 松手吸附完成（此时才值得落盘 + 对齐虚拟屏尺寸） */
        void onRatioSettled(float[] weights);
        /** 单击分隔条：交换它左右（上下）两块画中画的顺序 */
        void onHandleTap(int pair);
    }

    /** 2 屏时能压到的下限（3 屏是"整条吃掉最边上那块"，没有下限） */
    private static final float MIN_RATIO_2 = 1f / 3f;
    /** 3 屏时某块被吃到不足这个占比，就算"不要这一屏了" */
    private static final float COLLAPSE_AT = 0.25f;
    /** 拖动生效距离（dp）：手指要走过这么远才开始改比例，避免"一碰就跑" */
    private static final int DRAG_START_DP = 28;
    /** 吸附动画时长（要跟手，太快看不出缓动、太慢像卡住） */
    private static final long SETTLE_MS = 220;
    /** 两屏的标准比例（对应 1:2 / 1:1 / 2:1） */
    private static final float[] RATIOS = {1f / 3f, 0.5f, 2f / 3f};

    private final List<View> order = new ArrayList<>();
    private final List<Handle> handles = new ArrayList<>();
    private int gapPx;
    private boolean horizontal = true;
    private float[] weights = new float[0];
    private Listener listener;
    private ValueAnimator animator;
    /** 上一次布局的参数签名：只在变化时打日志，避免每帧刷屏（排查"画中画缩小"用） */
    private String lastLayoutSig = "";

    public PaneBox(Context c) {
        super(c);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setGapPx(int px) {
        if (gapPx != px) {
            gapPx = px;
            requestLayout();
        }
    }

    public void setHorizontal(boolean h) {
        if (horizontal != h) {
            horizontal = h;
            requestLayout();
        }
    }

    public boolean isHorizontal() {
        return horizontal;
    }

    /** 按给定顺序摆放（第一个在最左/最上）；weights 长度不足的位置按 1 补齐 */
    public void setOrder(List<View> views) {
        order.clear();
        order.addAll(views);
        if (weights.length != order.size()) {
            float[] w = new float[order.size()];
            for (int i = 0; i < w.length; i++) w[i] = i < weights.length ? weights[i] : 1f;
            weights = w;
        }
        syncHandles();
        applyAlpha();
        requestLayout();
    }

    public void setWeights(float[] w) {
        apply(w);
    }

    public float[] getWeights() {
        return weights.clone();
    }

    /** 当前可见（未被折叠）的屏数 */
    public int visibleCount() {
        int n = 0;
        for (float w : weights) if (w > 0.0001f) n++;
        return n;
    }

    public boolean isCollapsed(int i) {
        return i < 0 || i >= weights.length || weights[i] <= 0.0001f;
    }

    /** 平滑过渡到目标比例（恢复折叠屏、切标准比例时用） */
    public void animateTo(float[] target) {
        cancelAnim();
        final float[] from = weights.clone();
        final float[] to = new float[order.size()];
        for (int i = 0; i < to.length; i++) {
            to[i] = (target != null && i < target.length) ? Math.max(0f, target[i]) : 1f;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SETTLE_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.4f));
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float[] w = new float[from.length];
            for (int i = 0; i < w.length; i++) w[i] = from[i] + (to[i] - from[i]) * f;
            apply(w);
            if (listener != null) listener.onRatioChanging(getWeights());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                apply(to);
                if (listener != null) listener.onRatioSettled(getWeights());
            }
        });
        animator.start();
    }

    private void cancelAnim() {
        if (animator == null) return;
        animator.removeAllListeners();
        animator.removeAllUpdateListeners();
        animator.cancel();
        animator = null;
    }

    // ------------------------------------------------------------ 权重 / 折叠

    private void apply(float[] w) {
        int n = order.size();
        float[] r = new float[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            r[i] = (w != null && i < w.length) ? Math.max(0f, w[i]) : 1f;
            if (r[i] > 0.0001f) any = true;
        }
        if (!any) for (int i = 0; i < n; i++) r[i] = 1f;
        weights = r;
        applyAlpha();
        requestLayout();
    }

    /** 折叠的屏整块透明（保留 TextureView / 虚拟屏，恢复时秒回） */
    private void applyAlpha() {
        for (int i = 0; i < order.size(); i++) {
            order.get(i).setAlpha(isCollapsed(i) ? 0f : 1f);
        }
    }

    /** 性能模式切换后让分隔条按新配色重画（Handle.onDraw 每次都读 Ui.isLowPerf()） */
    public void refreshHandles() {
        for (Handle h : handles) h.invalidate();
    }

    // ------------------------------------------------------------ 拖动条

    private void syncHandles() {
        int need = Math.max(0, order.size() - 1);
        while (handles.size() < need) {
            Handle h = new Handle(getContext());
            handles.add(h);
            addView(h);
        }
        while (handles.size() > need) {
            Handle h = handles.remove(handles.size() - 1);
            removeView(h);
        }
        // 分隔条必须压在画中画之上，否则拖动会被屏内应用吃掉
        for (Handle h : handles) {
            if (h.getParent() == null) addView(h);
            else h.bringToFront();
        }
    }

    /** 触摸区宽度：缝只有 9dp，按住得靠放宽；中间画的那条玻璃条只有 6dp */
    private int handleThickness() {
        return Math.max(gapPx + Ui.dp(getContext(), 14), Ui.dp(getContext(), 26));
    }

    // ------------------------------------------------------------ 测量 / 布局

    /** 按权重算出每块屏在主方向上的像素长度（0 = 折叠） */
    private int[] computeSizes(int w, int h) {
        int n = order.size();
        int[] sizes = new int[n];
        float sum = 0;
        int vis = 0;
        for (int i = 0; i < n; i++) {
            if (isCollapsed(i)) continue;
            sum += weights[i];
            vis++;
        }
        if (vis == 0 || sum <= 0) return sizes;
        int content = horizontal ? w : h;
        int avail = Math.max(0, content - gapPx * (vis - 1));
        int used = 0, seen = 0;
        for (int i = 0; i < n; i++) {
            if (isCollapsed(i)) continue;
            seen++;
            int s = (seen == vis) ? Math.max(0, avail - used)
                    : Math.round(avail * weights[i] / sum);
            sizes[i] = s;
            used += s;
        }
        return sizes;
    }

    /** 每块屏在主方向上的起止坐标；折叠的屏长度为 0、位置就在当前边界 */
    private void computeBounds(int[] sizes, int[] start, int[] end) {
        int pos = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] <= 0) {
                start[i] = end[i] = pos;
                continue;
            }
            start[i] = pos;
            end[i] = pos + sizes[i];
            pos = end[i] + gapPx;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int[] sizes = computeSizes(w, h);
        for (int i = 0; i < order.size(); i++) {
            View child = order.get(i);
            int cw, ch;
            if (sizes[i] <= 0) {
                // 折叠：别再量成 0（0x0 会触发 TextureView 的尺寸回调，把虚拟屏搞坏），保持原尺寸
                cw = Math.max(1, child.getMeasuredWidth());
                ch = Math.max(1, child.getMeasuredHeight());
            } else if (horizontal) {
                cw = sizes[i];
                ch = h;
            } else {
                cw = w;
                ch = sizes[i];
            }
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        int t = handleThickness();
        for (Handle hd : handles) {
            if (horizontal) {
                hd.measure(MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            } else {
                hd.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(t, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int[] sizes = computeSizes(w, h);
        int n = order.size();
        int[] start = new int[n], end = new int[n];
        computeBounds(sizes, start, end);

        for (int i = 0; i < n; i++) {
            if (sizes[i] <= 0) continue;      // 折叠：保持上一次的位置（透明、不吃触摸）
            View child = order.get(i);
            if (horizontal) child.layout(start[i], 0, end[i], h);
            else child.layout(0, start[i], w, end[i]);
        }

        // 不在 order 里的视图：挪到画布外，别让它停在上一次的位置继续显示/吃触摸。
        // 单画布局只放一块屏，其余屏（以及"单画专用屏"在恢复布局后）的 root 还挂在容器里，
        // onLayout 不管它们的话它们会停在原地 —— 用户看到的就是
        // "第一个画中画全屏、第二个悬浮在它上面"。
        //
        // 为什么不用 setVisibility(INVISIBLE/GONE)：visibility 变化在部分 ROM 上会让
        // TextureView 释放 SurfaceTexture，虚拟屏跟着回收、屏内应用掉到手机主屏上。
        // 只改 layout 位置：surface、尺寸、虚拟屏全都不动，恢复时秒回。
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof Handle || order.contains(c)) continue;
            int cw = Math.max(1, c.getMeasuredWidth());
            int ch = Math.max(1, c.getMeasuredHeight());
            if (horizontal) c.layout(w, 0, w + cw, ch);
            else c.layout(0, h, cw, h + ch);
        }

        StringBuilder sig = new StringBuilder();
        sig.append(horizontal ? "H " : "V ").append(w).append('x').append(h).append(" n=").append(n).append(" w=[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sig.append(',');
            sig.append(String.format(java.util.Locale.US, "%.3f", weights[i])).append(':').append(sizes[i]);
        }
        sig.append(']');
        if (!sig.toString().equals(lastLayoutSig)) {
            lastLayoutSig = sig.toString();
            android.util.Log.i("PipDesk", "PaneBox layout " + lastLayoutSig);
        }

        int thickness = handleThickness();
        int lastCenter = Integer.MIN_VALUE;
        for (int k = 0; k < handles.size(); k++) {
            Handle hd = handles.get(k);
            boolean va = k < n && !isCollapsed(k);
            boolean vb = k + 1 < n && !isCollapsed(k + 1);
            if (!va && !vb) {
                hd.layout(0, 0, 0, 0);
                continue;
            }
            // 中间那块被折叠时，左右两条分隔条会落在同一个位置 —— 只留一条
            if (!va && k > 0 && !isCollapsed(k - 1) && lastCenter != Integer.MIN_VALUE) {
                hd.layout(0, 0, 0, 0);
                continue;
            }
            int center;
            if (va && vb) center = end[k] + gapPx / 2;         // 正常：落在缝里
            else if (va) center = end[k] - thickness / 2;      // 右边那块折叠：贴在本屏外侧边
            else center = start[k + 1] + thickness / 2;        // 左边那块折叠：贴在本屏内侧边
            lastCenter = center;
            hd.pair = k;
            if (horizontal) hd.layout(center - thickness / 2, 0, center + thickness / 2, h);
            else hd.layout(0, center - thickness / 2, w, center + thickness / 2);
        }
    }

    // ------------------------------------------------------------ 玻璃拖动条

    private final class Handle extends View {

        int pair;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private boolean pressed;
        private boolean dragging;
        private float downRaw;
        private float startA;      // 左/上那块当前像素长度
        private float pairAvail;   // 两块合计（不含缝）
        private float[] startW;
        private boolean startCollapsed;
        private int aIdx = -1, bIdx = -1;
        /** 按下时三块屏全都露着 → 走"链条式"拖动（见 cascadeDrag） */
        private boolean cascade;

        Handle(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, Ui.dp(c, 1)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float thick = Ui.dp(getContext(), pressed || dragging ? 8 : 6);
            // 长度：跟屏的长边成比例，但比之前更长（用户反馈偏短）
            float len = Math.min((horizontal ? h : w) * 0.62f,
                    Ui.dp(getContext(), pressed || dragging ? 120 : 108));
            len = Math.max(len, Ui.dp(getContext(), 56));
            float cx = w / 2f, cy = h / 2f;
            if (horizontal) rect.set(cx - thick / 2f, cy - len / 2f, cx + thick / 2f, cy + len / 2f);
            else rect.set(cx - len / 2f, cy - thick / 2f, cx + len / 2f, cy + thick / 2f);

            // 玻璃质感：**和 Dock 完全同一份色**（Ui.glassColors / glassStroke）——
            // 深色模式是黑玻璃、浅色模式是白玻璃，透明度也一模一样，改一处两边一起变
            int[] gc = Ui.glassColors();
            fill.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    gc[0], gc[1], Shader.TileMode.CLAMP));
            float rad = Math.min(rect.width(), rect.height()) / 2f;
            canvas.drawRoundRect(rect, rad, rad, fill);
            fill.setShader(null);

            // 亮边：黑玻璃少了它就糊成一片（浅色白玻璃上是白边，几乎看不见，不影响）
            edge.setColor(Ui.glassStroke());
            canvas.drawRoundRect(rect, rad, rad, edge);
        }

        @SuppressWarnings("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    cancelAnim();
                    // 上一段动画被按下打断：先收尾（解除虚拟屏 resize 冻结）
                    if (listener != null) listener.onRatioSettled(getWeights());
                    aIdx = pair;
                    bIdx = pair + 1;
                    if (aIdx >= order.size() || bIdx >= order.size()) return false;
                    if (isCollapsed(aIdx) && isCollapsed(bIdx)) return false;
                    startW = getWeights();
                    startCollapsed = isCollapsed(aIdx) || isCollapsed(bIdx);
                    // 注意：这里必须取容器（PaneBox）的尺寸，Handle 自己的宽高是那条竖条
                    int[] sizes = computeSizes(PaneBox.this.getWidth(), PaneBox.this.getHeight());
                    int[] st = new int[sizes.length], en = new int[sizes.length];
                    computeBounds(sizes, st, en);
                    startA = Math.max(0, en[aIdx] - st[aIdx]);
                    float startB = Math.max(0, en[bIdx] - st[bIdx]);
                    pairAvail = Math.max(1f, startA + startB);
                    // 三块都露着时才用"链条式"：中间那块只平移、不缩，空间从最边上吃
                    int vis0 = 0;
                    for (float v : startW) if (v > 0.0001f) vis0++;
                    cascade = startW.length >= 3 && vis0 == startW.length;
                    downRaw = horizontal ? e.getRawX() : e.getRawY();
                    pressed = true;
                    dragging = false;
                    // 横向拖动条在横屏时会被 SimplePager 当成翻页手势 —— 抢过来
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (aIdx < 0) return true;
                    float delta = (horizontal ? e.getRawX() : e.getRawY()) - downRaw;
                    int dead = Ui.dp(getContext(), DRAG_START_DP);
                    if (!dragging) {
                        // 拖动生效距离：手指没走过 dead 之前一律不理（太灵敏会一碰就乱跳）
                        if (Math.abs(delta) < dead) return true;
                        dragging = true;
                    }
                    // 扣掉这段"死区"，手指和分隔条才不会在开始的一瞬间错位
                    dragTo(delta - Math.signum(delta) * dead);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean was = dragging;
                    dragging = false;
                    pressed = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (was) {
                        settle();
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP && aIdx >= 0) {
                        // 没拖动 = 单击：交换这两块画中画的顺序（需求）
                        if (listener != null) listener.onHandleTap(pair);
                    }
                    aIdx = bIdx = -1;
                    cascade = false;
                    return true;
                }
            }
            return false;
        }

        private void dragTo(float deltaPx) {
            if (cascade) {
                cascadeDrag(deltaPx);
                return;
            }
            // 两屏（或从折叠里往回拖）：相邻两块此消彼长
            float minR = startCollapsed ? 0f : MIN_RATIO_2;
            float r = (startA + deltaPx) / pairAvail;
            r = Math.max(minR, Math.min(1f - minR, r));
            float sum = startW[aIdx] + startW[bIdx];
            if (sum <= 0) return;
            float[] w = startW.clone();
            w[aIdx] = sum * r;
            w[bIdx] = sum * (1f - r);
            apply(w);
            if (listener != null) listener.onRatioChanging(getWeights());
        }

        /**
         * 3 屏全露着时的拖动：**链条式**。
         *
         * 老做法是"相邻两块此消彼长"，于是拖 A|B 之间的竖条，被压扁的是中间那块 B，
         * 而最右边的 C 一点没动 —— 看着像"中间的屏被夹成一条缝"（用户反馈的正是这个）。
         * 改成推箱子那样：
         *   往右/下拖 → 左边这块变大，右边所有屏整体右移，**最边上那块**被顶出去（变小）；
         *   往左/上拖 → 右边这块变大，左边所有屏整体左移，**最边上那块**被顶出去。
         * 这样被折叠的永远是最边上的屏（需求），而且你手指按着的竖条始终跟着手走。
         * 当分隔条本身就是"最后一条"时，最边上那块正好就是相邻那块，行为退化成原来的样子。
         */
        private void cascadeDrag(float deltaPx) {
            int n = startW.length;
            float sumW = 0;
            for (float v : startW) sumW += Math.max(0f, v);
            if (sumW <= 0) return;
            int content = horizontal ? PaneBox.this.getWidth() : PaneBox.this.getHeight();
            float avail = Math.max(1f, content - (float) gapPx * Math.max(0, n - 1));
            float pxPerW = avail / sumW;          // 一像素 = 多少权重

            float[] w = startW.clone();
            if (deltaPx > 0) {
                int eater = n - 1;                 // 最右/最下那块被顶出去
                float d = Math.min(deltaPx / pxPerW, Math.max(0f, w[eater]));
                w[aIdx] += d;
                w[eater] -= d;
            } else if (deltaPx < 0) {
                int eater = 0;                     // 最左/最上那块被顶出去
                float d = Math.min(-deltaPx / pxPerW, Math.max(0f, w[eater]));
                w[bIdx] += d;
                w[eater] -= d;
            } else {
                return;
            }
            apply(w);
            if (listener != null) listener.onRatioChanging(getWeights());
        }

        /**
         * 松手吸附：
         *  - 3 屏（需求只有 1:1:1 / 1:2 / 2:1）—— 只看"有没有哪块被吃到几乎没有"：
         *    有就把它折叠，剩下两块吸附 1:1 / 1:2 / 2:1；没有就回到等宽。
         *    （被吃掉的必然是最边上那块：cascadeDrag 只从两端取空间）
         *  - 2 屏 → 吸附到 1:3 / 1:1 / 3:1 里最近的一档（即 1:2 / 1:1 / 2:1）。
         */
        private void settle() {
            float[] w = getWeights();
            if (aIdx < 0 || bIdx < 0 || aIdx >= w.length || bIdx >= w.length) return;
            if (cascade) {
                float sum = 0;
                int min = 0;
                for (int i = 0; i < w.length; i++) {
                    sum += Math.max(0f, w[i]);
                    if (w[i] < w[min]) min = i;
                }
                if (sum <= 0) return;
                if (!startCollapsed && w[min] / sum < COLLAPSE_AT) {
                    w[min] = 0f;
                    snapRemainingTwo(w);
                } else {
                    for (int i = 0; i < w.length; i++) if (w[i] > 0.0001f) w[i] = 1f;   // 1:1:1
                }
                animateTo(w);
                return;
            }
            float sum = w[aIdx] + w[bIdx];
            if (sum <= 0) return;
            float t = nearestRatio(w[aIdx] / sum);
            w[aIdx] = sum * t;
            w[bIdx] = sum * (1f - t);
            animateTo(w);
        }
    }

    /**
     * 调试用（这台机器禁止 adb 注入，只能自己往视图树里塞事件）：
     * 模拟按住第 pair 条分隔条拖动 delta 像素（横屏是 x 方向，竖屏是 y 方向）。
     * 走的是真实分发链路（dispatchTouchEvent → Handle.onTouchEvent → 吸附动画），
     * 和手指拖动的差别只有时间戳。
     */
    public void debugDrag(int pair, float delta) {
        if (pair < 0 || pair >= handles.size()) {
            Log.w("PipDesk", "debugDrag: 没有第 " + pair + " 条分隔条（共 " + handles.size() + " 条）");
            return;
        }
        Handle h = handles.get(pair);
        if (h.getWidth() <= 0 || h.getHeight() <= 0) {
            Log.w("PipDesk", "debugDrag: 分隔条还没布局");
            return;
        }
        float cx = h.getLeft() + h.getWidth() / 2f;
        float cy = h.getTop() + h.getHeight() / 2f;
        long t = android.os.SystemClock.uptimeMillis();
        final int steps = 20;
        dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0));
        for (int i = 1; i <= steps; i++) {
            float d = delta * i / steps;
            float x = horizontal ? cx + d : cx;
            float y = horizontal ? cy : cy + d;
            dispatchTouchEvent(MotionEvent.obtain(t, t + i * 8L, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        float ex = horizontal ? cx + delta : cx;
        float ey = horizontal ? cy : cy + delta;
        dispatchTouchEvent(MotionEvent.obtain(t, t + steps * 8L, MotionEvent.ACTION_UP, ex, ey, 0));
        Log.i("PipDesk", "debugDrag pair=" + pair + " delta=" + delta
                + " -> " + java.util.Arrays.toString(getWeights()));
    }

    /** 调试用：模拟轻点第 pair 条分隔条（验证"单击交换两侧画中画"） */
    public void debugTap(int pair) {
        if (pair < 0 || pair >= handles.size()) {
            Log.w("PipDesk", "debugTap: 没有第 " + pair + " 条分隔条（共 " + handles.size() + " 条）");
            return;
        }
        Handle h = handles.get(pair);
        if (h.getWidth() <= 0 || h.getHeight() <= 0) {
            Log.w("PipDesk", "debugTap: 分隔条还没布局");
            return;
        }
        float cx = h.getLeft() + h.getWidth() / 2f;
        float cy = h.getTop() + h.getHeight() / 2f;
        long t = android.os.SystemClock.uptimeMillis();
        dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0));
        dispatchTouchEvent(MotionEvent.obtain(t, t + 60L, MotionEvent.ACTION_UP, cx, cy, 0));
        Log.i("PipDesk", "debugTap pair=" + pair
                + " -> " + java.util.Arrays.toString(getWeights()));
    }

    /**
     * 折叠掉一屏之后，剩下两屏要吸附到标准比例。
     * 这里不假设是哪两屏（折叠的可能是最左/最右），直接按可见顺序取前两块。
     */    private static void snapRemainingTwo(float[] w) {
        int a = -1, b = -1;
        for (int i = 0; i < w.length; i++) {
            if (w[i] <= 0.0001f) continue;
            if (a < 0) a = i;
            else if (b < 0) b = i;
        }
        if (a >= 0 && b >= 0) snapPair(w, a, b);
    }

    private static float nearestRatio(float r) {
        float best = RATIOS[0];
        for (float v : RATIOS) if (Math.abs(v - r) < Math.abs(best - r)) best = v;
        return best;
    }

    /** 两屏之间吸附到最近的标准比例（1:2 / 1:1 / 2:1） */
    private static void snapPair(float[] w, int i, int j) {
        if (i < 0 || j < 0 || i >= w.length || j >= w.length) return;
        float a = w[i], b = w[j];
        if (a <= 0 || b <= 0) return;
        float t = nearestRatio(a / (a + b));
        float sum = a + b;
        w[i] = sum * t;
        w[j] = sum * (1f - t);
    }
}
