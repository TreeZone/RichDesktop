package com.cz.richdesk;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 底部「选择应用」弹窗（单段式）。
 *
 * 交互（按需求简化为一档）：
 *  - 打开即固定在屏幕 66% 高度（上限 70% 防极端屏比越界）；
 *  - 按住顶部横条往下滑 → 自动关闭；
 *  - 应用列表滚到底后继续往下滑 → 自动关闭；
 *  - 点面板外的空白区域 → 关闭。
 *
 * 视觉：玻璃板铺满面板、面板贴底；窗口满屏、无投影；
 * Android 12+ 只对玻璃板背后做背景模糊（用窗口背景遮罩触发，无边界、不会出现"一圈"）。
 * 横屏：面板限宽 640dp 居中、列数按宽度自适应（高度按窗口实测，避免顶出窗外）。
 *
 * 兼容性：Dialog + 手势，安卓 9~16 通用，无 material 依赖。
 */
public class AppPickerSheet extends Dialog {

    public interface OnPick {
        void onPick(AppInfo ai);
    }

    private static final float START = 0.66f;   // 单段式：打开即 66% 高
    private static final float MIN = 0.42f;
    private static final float MAX = 0.70f;     // 上限（防极端屏比越界）

    private final LinearLayout panel;
    private final RecyclerView list;
    private AppListAdapter adapter;
    /** 图标尺寸（dp）：列数/宽度装不下时会被收敛（见 fitGrid），先存一份才能在 onStart 重算 */
    private int iconDp;
    /** 高度按「窗口实际高度」算（onStart 后校正）。用静态屏幕尺寸在横屏下会
     *  算出超过窗口的高度，面板顶部直接顶出窗口外——看起来就是"顶部缺一块" */
    private int minH, maxH, panelH, panelW;
    /** 只在面板区域触发的窗口背景模糊（全屏窗口里画一块不可见矩形当模糊遮罩） */
    private final BlurMask blurMask = new BlurMask();
    private int columns;

    public AppPickerSheet(Context c, String title, List<AppInfo> apps,
                          int columns, int iconDp, final OnPick cb) {        super(c, Ui.dark ? R.style.Theme_PipDesk_Dialog : R.style.Theme_PipDesk_Dialog_Light);
        this.columns = columns;
        this.iconDp = iconDp;

        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = screenW > screenH;
        // 横屏：面板不能铺满整宽（否则 4 列的图标一个比脸大），限宽居中
        panelW = landscape ? Math.min(screenW, Ui.dp(c, 640))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        // 横屏列数按宽度自动加密（每格约 110dp），竖屏用配置值
        if (landscape) {
            float wDp = screenW / c.getResources().getDisplayMetrics().density;
            this.columns = Math.max(columns, Math.min(8, Math.max(3, Math.round(wDp / 110f))));
        }
        // 列数/图标收敛：格子内宽必须装得下「图标 + 内边距」，否则固定 px 的图标
        // 会溢出格子 —— 被列表边缘裁掉一半、或被邻格图标盖住（"图标显示不完整"）。
        // 这里先用屏幕宽度估，onStart 拿到窗口真实尺寸后再校正一次。
        int fitAvail = (panelW > 0 ? panelW : screenW) - 2 * Ui.dp(c, 10);
        int[] fit = AppListAdapter.fitGrid(c, this.columns, this.iconDp, fitAvail);
        this.columns = fit[0];
        this.iconDp = fit[1];
        minH = Math.round(screenH * MIN);
        maxH = Math.round(screenH * MAX);
        panelH = Math.round(screenH * START);

        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnClickListener(v -> dismiss());   // 点空白处关闭

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.glassPanel(c, 24));
        Ui.clipRounded(panel, 24, c);
        panel.setOnClickListener(v -> { });   // 点面板本体不关闭

        // ---- 拖动热区：横条 + 标题 ----
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        int hp = Ui.dp(c, 10);
        header.setPadding(hp, Ui.dp(c, 8), hp, Ui.dp(c, 8));

        View bar = new View(c);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(Ui.dp(c, 2));
        barBg.setColor(Ui.dark ? 0x80FFFFFF : 0x40000000);
        header.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 4)));

        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextSize(14);
        // 浅色玻璃上必须是深色字（原来写死白色 → 浅色模式标题看不见）
        tv.setTextColor(Ui.tint());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(c, 8);
        header.addView(tv, tlp);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 应用网格 ----
        list = new RecyclerView(c);
        list.setLayoutManager(new GridLayoutManager(c, this.columns));
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int pad = Ui.dp(c, 10);
        list.setPadding(pad, pad, pad, Ui.dp(c, 16));
        adapter = new AppListAdapter(apps, new AppListAdapter.Host() {
            @Override public void onAppClick(AppInfo ai) {
                if (cb != null) cb.onPick(ai);
                dismiss();
            }
            @Override public void onAppLongClick(AppInfo ai) { }
            @Override public void onSettingsClick() { }
        }, false, iconDp, false);   // false = 名字压在玻璃面板上，按外观取色而非壁纸
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                panelW, panelH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(panel, plp);

        bindHeaderGesture(header);
        bindListBottomDrag(list);

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Window w = getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            try {
                w.setElevation(0f);   // 不要窗口阴影，避免面板外多一圈暗边
            } catch (Throwable ignored) { }
            if (Ui.canBlur()) {
                // 窗口是满屏的，但背景只在面板矩形内画一块 alpha=1 的遮罩：
                // 背景模糊就只会发生在面板背后（不会全屏糊、也没有"一圈"边界）。
                try {
                    blurMask.setPanel(panelW, panelH);
                    w.setBackgroundDrawable(blurMask);
                    w.setBackgroundBlurRadius(Ui.dp(c, 24));
                } catch (Throwable ignored) { }
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            // 浅色外观多压一点暗：底子越暗，白面板上的黑字越清楚
            lp.dimAmount = Ui.dark ? (Ui.canBlur() ? 0.28f : 0.42f)
                    : (Ui.canBlur() ? 0.34f : 0.50f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // 桌面本身隐藏了状态栏：弹窗是独立窗口，默认会把状态栏带回来，这里对齐
            if (!Prefs.showStatusBar(c)) {
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            w.setAttributes(lp);
        }
        setCanceledOnTouchOutside(true);
    }

    /**
     * 显示后按「窗口真实尺寸」校正面板高度和宽度。
     * 横屏（或分屏）下窗口比 displayMetrics 矮，静态算出的高度会超出窗口，
     * 面板顶部直接顶出窗外 —— 用户看到的"顶部缺一块"就是它。
     */
    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w == null) return;
        final View decor = w.getDecorView();
        decor.post(() -> {
            int wh = decor.getHeight();
            int ww = decor.getWidth();
            if (wh <= 0 || ww <= 0) return;
            boolean landscape = ww > wh;
            panelW = landscape ? Math.min(ww, Ui.dp(decor.getContext(), 640))
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            if (landscape) {
                float wDp = ww / decor.getResources().getDisplayMetrics().density;
                columns = Math.max(columns, Math.min(8, Math.max(3, Math.round(wDp / 110f))));
            }
            // 拿到窗口真实尺寸后再收敛一次列数/图标（构造时只有屏幕宽度的估计值）
            Context fc = decor.getContext();
            int fitAvail = (panelW > 0 ? panelW : ww) - 2 * Ui.dp(fc, 10);
            int[] fit = AppListAdapter.fitGrid(fc, columns, iconDp, fitAvail);
            columns = fit[0];
            iconDp = fit[1];
            if (list.getLayoutManager() instanceof GridLayoutManager) {
                ((GridLayoutManager) list.getLayoutManager()).setSpanCount(columns);
            }
            if (adapter != null) adapter.setIconDp(iconDp);
            minH = Math.round(wh * MIN);
            maxH = Math.round(wh * MAX);
            // 面板宽度也按窗口校正（构造时只能拿到 metrics 的猜测值）
            ViewGroup.LayoutParams lp = panel.getLayoutParams();
            if (lp.width != panelW) {
                lp.width = panelW;
                panel.setLayoutParams(lp);
            }
            int target = Math.max(minH, Math.min(maxH, Math.round(wh * START)));
            if (panelH != target) setPanelHeight(target);
            blurMask.setPanel(panelW <= 0 ? ww : panelW, panelH);
        });
    }

    // ------------------------------------------------------------ 手势

    private static final int TRIGGER_DP = 36;

    /** 横条：下滑 → 自动关闭（单段式，不再改高度） */
    private void bindHeaderGesture(View header) {
        final float[] downY = new float[1];
        header.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY[0];
                    if (dy > Ui.dp(header.getContext(), TRIGGER_DP)) {
                        dismissAnimated();              // 下滑 → 关闭
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });
    }

    /**
     * 列表滚到最底后继续往下滑：同样两档 —— 滑过阈值即自动关闭。
     * 不抢列表的正常滚动（未到底时不干预）。
     */
    private void bindListBottomDrag(final RecyclerView rv) {
        final float[] downY = {Float.NaN};
        final boolean[] atBottom = new boolean[1];
        final boolean[] fired = new boolean[1];
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView r, int newState) {
                // 停稳时记录一次"是否已在最底"
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    atBottom[0] = !rv.canScrollVertically(1);
                }
            }
        });
        rv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getY();
                    fired[0] = false;
                    atBottom[0] = !rv.canScrollVertically(1);
                    return false;   // 交给列表正常滚动
                case MotionEvent.ACTION_MOVE:
                    if (Float.isNaN(downY[0]) || fired[0]) return false;
                    if (atBottom[0] && e.getY() - downY[0] > Ui.dp(rv.getContext(), TRIGGER_DP)) {
                        fired[0] = true;
                        dismissAnimated();   // 已到底还往下拉 → 关闭
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    downY[0] = Float.NaN;
                    return false;
            }
            return false;
        });
    }

    // ------------------------------------------------------------ 高度

    private void setPanelHeight(int h) {
        h = Math.max(minH, Math.min(maxH, h));
        if (h == panelH) return;
        panelH = h;
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.height = h;
        panel.setLayoutParams(lp);
        blurMask.setPanel(panel.getLayoutParams().width, h);   // 模糊遮罩跟着面板走
    }

    private void dismissAnimated() {
        panel.animate().translationY(panelH).alpha(0f).setDuration(180)
                .withEndAction(AppPickerSheet.super::dismiss).start();
    }

    /**
     * 窗口背景模糊的"触发遮罩"：全屏窗口里只在面板矩形内画一块几乎不可见的像素，
     * 这样 Android 12+ 的背景模糊只发生在面板背后，面板外的壁纸保持清晰。
     */
    private static final class BlurMask extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private int panelW, panelH;

        void setPanel(int w, int h) {
            panelW = w;
            panelH = h;
            invalidateSelf();
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            if (panelH <= 0) return;
            android.graphics.Rect b = getBounds();
            paint.setColor(0x01000000);   // alpha=1：肉眼不可见，但足以触发模糊
            int left = panelW > 0 ? Math.max(0, (b.width() - panelW) / 2) : 0;
            int right = panelW > 0 ? left + panelW : b.width();
            canvas.drawRect(left, b.height() - panelH, right, b.height(), paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
