package com.cz.richdesk;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 长按弹出的玻璃菜单「浮层」。
 *
 * 和 {@link GlassMenu} 只差一件事，但这件事决定了交互能不能成立：
 *
 * GlassMenu 是 Dialog，弹出来就抢走窗口焦点，正在进行的那次手势立刻收到
 * ACTION_CANCEL —— 于是「长按先出菜单、手指继续拖就关掉菜单接着换位」根本走不通
 * （Dialog 出现的那一刻拖动就断了，松手后还会再弹一次菜单）。
 *
 * 这里把菜单做成 Activity 内容区里的一层普通 View：
 *  · 它是在手指已经按下之后才 addView 的 —— Android 的触摸目标在 ACTION_DOWN
 *    时就锁定到下面那个图标上了，后续 MOVE 仍然发给图标，所以拖动不受打断；
 *  · 这一层只在「面板区域内按下」时自己消费事件，其它位置一律吞掉并关闭自己
 *    （不把这一下漏给底层，避免关菜单的同时又点开了别的东西）。
 *
 * 于是交互变成：长按 → 菜单立刻出现 → 手指一移动，调用方 dismissCurrent()
 * 把菜单收掉，换位拖动继续。
 */
public final class OverlayMenu {

    public interface OnPick {
        void onPick(int index);
    }

    /** 长按的图标：菜单贴着它弹出（adapter 在回调宿主前记一下，宿主的签名就不用改） */
    private static WeakReference<View> lastAnchor = new WeakReference<>(null);
    private static OverlayMenu current;

    public static void setAnchor(View v) {
        lastAnchor = new WeakReference<>(v);
    }

    public static View lastAnchor() {
        return lastAnchor.get();
    }

    /** 拖动一开始就调它：菜单关掉，手势继续 */
    public static void dismissCurrent() {
        if (current != null) current.dismiss();
    }

    public static boolean isShowing() {
        return current != null;
    }

    private final Layer layer;
    private final LinearLayout panel;
    private boolean dismissed;

    private OverlayMenu(Layer layer, LinearLayout panel) {
        this.layer = layer;
        this.panel = panel;
    }

    public void dismiss() {
        if (dismissed) return;
        dismissed = true;
        if (current == this) current = null;
        final ViewGroup p = layer.getParent() instanceof ViewGroup ? (ViewGroup) layer.getParent() : null;
        if (p == null) return;
        // 不能立刻 removeView：拖动的每一帧是在 ItemTouchHelper.onChildDraw 里回调的，
        // 也就是 ViewGroup 正在 dispatchDraw / dispatchGetDisplayList 遍历 children 的过程中。
        // 这时改 children 数组，后续 traverse 会读到 null，直接
        //   NPE: Attempt to read from field 'int android.view.View.mViewFlags' on a null object
        // 崩在 ViewGroup.dispatchGetDisplayList（用户报的「应用列表页拖动就闪退」）。
        p.post(() -> {
            if (layer.getParent() == p) p.removeView(layer);
        });
    }

    public static OverlayMenu show(Activity act, String title, CharSequence[] items, OnPick cb) {
        return show(act, lastAnchor(), title, items, cb);
    }

    public static OverlayMenu show(Activity act, View anchor, String title,
                                   CharSequence[] items, OnPick cb) {
        if (act == null || act.isFinishing() || items == null || items.length == 0) return null;
        View content = act.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup root = (ViewGroup) content;
        dismissCurrent();

        Layer layer = new Layer(act);
        // 跟原 GlassMenu 的窗口压暗对齐：菜单浮起来时背景稍微沉一点，视线才聚到菜单上。
        // 这层只是颜色，不参与触摸判定（面板外的那一下仍由 dispatchTouchEvent 处理）。
        layer.setBackgroundColor(Ui.dark ? 0x40000000 : 0x2E000000);

        final int padSide = Ui.dp(act, 4);
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(padSide, Ui.dp(act, 12), padSide, padSide);
        panel.setBackground(Ui.glassPanel(act, 16));
        panel.setElevation(Ui.dp(act, 9));
        // 宽度必须定死：里面的行是 MATCH_PARENT，父面板若是 WRAP_CONTENT，
        // FrameLayout 给的 AT_MOST 会让行直接撑满整屏（第一版就是这个毛病，
        // 弹出来是一条横贯全屏的带子）。这里跟 GlassMenu 用同一把尺子：
        // 屏幕宽 - 24dp，上限 300dp。
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int panelW = Math.min(screenW - Ui.dp(act, 24), Ui.dp(act, 300));
        panelW = Math.max(Ui.dp(act, 200), panelW);
        panel.setMinimumWidth(panelW);

        if (title != null && title.length() > 0) {
            TextView t = new TextView(act);
            t.setText(title);
            t.setTextSize(20);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.tint());
            t.setAlpha(0.82f);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setIncludeFontPadding(false);
            t.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), Ui.dp(act, 10));
            panel.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View line = new View(act);
            line.setBackgroundColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            panel.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 1))));
        }

        OverlayMenu menu = new OverlayMenu(layer, panel);
        layer.setTag(menu);

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView row = new TextView(act);
            row.setText(items[i]);
            row.setTextSize(15);
            row.setTextColor(Ui.tint());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setSingleLine(true);
            int hp = Ui.dp(act, 16);
            row.setPadding(hp, Ui.dp(act, 13), hp, Ui.dp(act, 13));
            row.setBackground(rowBg(act));
            row.setOnClickListener(v -> {
                menu.dismiss();
                if (cb != null) cb.onPick(idx);
            });
            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        root.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.addView(panel, new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.WRAP_CONTENT));
        layer.bind(panel);

        if (anchor != null && anchor.isAttachedToWindow()) {
            int[] av = new int[2];
            int[] cv = new int[2];
            anchor.getLocationOnScreen(av);
            root.getLocationOnScreen(cv);
            layer.setAnchor(av[0] - cv[0], av[1] - cv[1], anchor.getWidth(), anchor.getHeight());
        }

        current = menu;
        return menu;
    }

    private static RippleDrawable rowBg(Context ctx) {
        int c = Ui.dark ? 0x33FFFFFF : 0x1F000000;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(ctx, 11));
        return new RippleDrawable(ColorStateList.valueOf(c), null, mask);
    }

    /** 铺满内容区的一层：只负责摆放面板 + 把「面板外的按下」变成关闭 */
    private static final class Layer extends FrameLayout {
        private final int[] a = new int[4];
        private boolean anchored;
        private View panel;

        Layer(Context c) {
            super(c);
            setClipChildren(false);
        }

        void bind(View p) {
            panel = p;
        }

        void setAnchor(int x, int y, int w, int h) {
            a[0] = x;
            a[1] = y;
            a[2] = w;
            a[3] = h;
            anchored = true;
            requestLayout();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (panel == null) {
                super.onLayout(changed, l, t, r, b);
                return;
            }
            int pw = panel.getMeasuredWidth();
            int ph = panel.getMeasuredHeight();
            int m = Ui.dp(getContext(), 10);
            int x, y;
            if (!anchored) {
                x = (getWidth() - pw) / 2;
                y = (getHeight() - ph) / 2;
            } else {
                x = a[0] + a[2] / 2 - pw / 2;
                x = Math.max(m, Math.min(getWidth() - pw - m, x));
                y = a[1] - ph - m;                 // 优先弹在图标上方
                if (y < m) y = a[1] + a[3] + m;    // 上面放不开就落到下方
            }
            y = Math.max(m, Math.min(getHeight() - ph - m, y));
            panel.layout(x, y, x + pw, y + ph);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !inPanel(ev)) {
                Object tag = getTag();
                if (tag instanceof OverlayMenu) ((OverlayMenu) tag).dismiss();
                return true;   // 只吃掉这一下：关菜单，但不把点击漏给底下的图标
            }
            return super.dispatchTouchEvent(ev);
        }

        private boolean inPanel(MotionEvent e) {
            if (panel == null) return false;
            float x = e.getX() - panel.getLeft();
            float y = e.getY() - panel.getTop();
            return x >= 0 && y >= 0 && x < panel.getWidth() && y < panel.getHeight();
        }
    }
}
