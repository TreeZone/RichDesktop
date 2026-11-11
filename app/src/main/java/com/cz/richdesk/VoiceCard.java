package com.cz.richdesk;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 语音状态的浮层卡片（挂 decorView，不走系统窗口）。
 *
 * 结构是「一层全屏遮罩 + 底部胶囊」：
 *   - 遮罩必须存在：聆听时如果用户还能点到底下的画中画/应用列表，就会出现
 *     "正在收音，手指却在操作别的应用" —— 车机上尤其危险。遮罩吃掉所有触摸，
 *     点一下就结束本轮聆听（{@link Voice#cancelListening()}），符合直觉。
 *   - 胶囊：一颗随音量脉动的圆点 + 大字号字幕 + 六段电平条，玻璃底、深浅色自适应。
 *
 * 动画句柄都在本类静态字段里，hide / reset 时统一取消，避免 decorView 换了之后
 * 旧动画还拿着已分离的 View 跑。
 */
public final class VoiceCard {

    private static final int BARS = 6;
    private static final String TAG = "pipdesk-voice-card";
    private static FrameLayout layer;      // 全屏遮罩层（含胶囊）
    private static View scrim;             // 遮挡 + 接收点击
    private static View card;              // 胶囊本体
    private static TextView title;
    private static TextView body;
    private static View pulseDot;
    private static ObjectAnimator pulse;
    private static View[] bars;
    private static Runnable hideTask;

    private VoiceCard() { }

    public static void show(Activity a, String t, String b, int level) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (layer == null || layer.getParent() != decor) {
            build(a, decor);
        }
        if (layer == null) return;
        title.setText(t == null ? "" : t);
        body.setText(b == null ? "" : b);
        body.setVisibility(b == null || b.isEmpty() ? View.GONE : View.VISIBLE);
        applyLevel(level);
        startPulse();
        layer.setVisibility(View.VISIBLE);
        if (layer.getAlpha() < 1f) {
            layer.animate().alpha(1f).setDuration(140)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
        card.setScaleX(1f);
        card.setScaleY(1f);
        if (hideTask != null) {
            layer.removeCallbacks(hideTask);
            hideTask = null;
        }
    }

    /** 到点自动淡出 */
    public static void showThenHide(Activity a, String t, String b, long delayMs) {
        show(a, t, b, 0);
        if (layer == null) return;
        if (hideTask != null) layer.removeCallbacks(hideTask);
        hideTask = () -> hide(a);
        layer.postDelayed(hideTask, delayMs);
    }

    /** 当前是否正显示（遮罩层在挡触摸） */
    /** 只刷新电平条：不走 show()，避免排队重排视图（每 150ms 调一次） */
    public static void updateLevel(int level) {
        if (card == null || bars == null) return;
        applyLevel(Math.max(0, level));
    }

    public static boolean showing() {
        return layer != null && layer.getVisibility() == View.VISIBLE;
    }

    public static void hide(Activity a) {
        if (layer == null) return;
        stopPulse();
        layer.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (layer != null) {
                        layer.setVisibility(View.GONE);
                        layer.setAlpha(1f);
                    }
                }).start();
    }

    private static void build(Activity a, ViewGroup decor) {
        layer = new FrameLayout(a);
        layer.setTag(TAG);
        layer.setClickable(false);

        // 遮罩：半透明压暗（告诉用户"现在别的地方不能点"），点击 = 结束聆听
        scrim = new View(a);
        scrim.setBackgroundColor(0x38000000);
        scrim.setOnClickListener(v -> {
            Voice.get().cancelListening();
            hide(a);
        });
        layer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(a, 16);
        box.setPadding(pad, Ui.dp(a, 13), pad + Ui.dp(a, 6), Ui.dp(a, 13));
        box.setBackground(Ui.glassPanel(a, 24));
        box.setElevation(Ui.dp(a, 6));

        // 左侧脉动圆点：呼吸的亮度告诉用户「正在听」，比任何文字都直观
        pulseDot = new View(a);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(Ui.tint());
        pulseDot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(a, 13), Ui.dp(a, 13));
        dlp.rightMargin = Ui.dp(a, 12);
        pulseDot.setLayoutParams(dlp);

        // 右侧：小字提示 + 大字幕 + 电平条
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(a);
        title.setTextSize(12);
        title.setTextColor(Ui.tintHint());
        title.setSingleLine(true);
        title.setLetterSpacing(0.08f);

        body = new TextView(a);
        body.setTextSize(20);
        body.setTextColor(Ui.tint());
        body.setSingleLine(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(a, 2);
        body.setLayoutParams(blp);

        // 六段电平条：按音量逐段点亮，比一条直线更有「波形」的感觉
        LinearLayout meterRow = new LinearLayout(a);
        meterRow.setOrientation(LinearLayout.HORIZONTAL);
        bars = new View[BARS];
        int barW = Ui.dp(a, 14), barH = Ui.dp(a, 5), gap = Ui.dp(a, 4);
        for (int i = 0; i < BARS; i++) {
            View v = new View(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(Ui.dp(a, 2.5f));
            g.setColor(Ui.alpha(Ui.tintDim(), 0.35f));
            v.setBackground(g);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(barW, barH);
            if (i > 0) lp2.leftMargin = gap;
            v.setLayoutParams(lp2);
            bars[i] = v;
            meterRow.addView(v);
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(a, 8);
        meterRow.setLayoutParams(mlp);

        col.addView(title);
        col.addView(body);
        col.addView(meterRow, mlp);

        box.addView(pulseDot, dlp);
        box.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Ui.dp(a, 128);       // 抬到 Dock 上方
        box.setLayoutParams(lp);
        box.setAlpha(1f);
        box.setScaleX(0.94f);
        box.setScaleY(0.94f);

        layer.addView(box);
        card = box;
        decor.addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setAlpha(0f);
    }

    /** level: 0..32767 的 RMS → 点亮前 k 段电平条 */
    private static void applyLevel(int level) {
        if (bars == null) return;
        float ratio = Math.min(1f, level / 5000f);
        int lit = Math.round(ratio * BARS);
        for (int i = 0; i < BARS; i++) {
            android.graphics.drawable.GradientDrawable g =
                    (android.graphics.drawable.GradientDrawable) bars[i].getBackground();
            int want = i < lit ? Ui.tint() : Ui.alpha(Ui.tintDim(), 0.35f);
            if (g != null) g.setColor(want);
        }
    }

    private static void startPulse() {
        if (pulseDot == null) return;
        if (pulse == null) {
            pulse = ObjectAnimator.ofFloat(pulseDot, View.ALPHA, 0.35f, 1f);
            pulse.setDuration(700);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
        }
        if (!pulse.isRunning()) pulse.start();
    }

    private static void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            if (pulseDot != null) pulseDot.setAlpha(1f);
        }
    }

    /** 桌面重建（recreate / 换壁纸）后残留的卡片要清掉 */
    static void reset() {
        stopPulse();
        pulse = null;
        card = null;
        title = null;
        body = null;
        pulseDot = null;
        bars = null;
        scrim = null;
        layer = null;
        hideTask = null;
    }
}
