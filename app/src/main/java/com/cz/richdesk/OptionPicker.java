package com.cz.richdesk;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 通用「单选」弹框：标题 + 分隔线 + 若干选项，当前项淡底色 + 右侧 ✓。
 *
 * 【全 App 弹框样式的唯一基准】设置页「Dock 位置 / 功能区」用的就是这一套，
 * 用户要求所有「选择当前值」的弹框都向它对齐：
 *   · Dialog(Ui.dialogContext) + FEATURE_NO_TITLE + Ui.glassWindow 紧凑版（宽 ≤300dp）
 *   · 标题 20sp 加粗 + 一条分隔线
 *   · 行：主标题 15sp + 可选 12sp 说明；当前项淡底色 + 右侧 ✓；统一 Ripple 反馈
 *   · 超高时压到屏高 82%，内部 ScrollView 滚动
 * 以前 PaneChooser 自绘「描边阴影 + ✓」、GlassMenu「只有 Ripple 没有选中态」、
 * 还有用 "✓ " 字符串前缀凑选中效果的，全部并到这里，去掉那几种异类表现。
 *
 * 操作菜单（点了就执行、没有「当前值」概念）仍用 GlassMenu / OverlayMenu，
 * 它们的标题/行高/玻璃窗口本来就是同一套视觉基线，不强行加对勾。
 *
 * 不用 AlertDialog：它的宽度走系统主题（很宽），且自定义 view 超高不会自己滚。
 */
public class OptionPicker {

    public interface OnPicked {
        void onPicked(int index);
    }

    private OptionPicker() { }

    /** @param hints 每项下方的说明，可传 null；传了就必须与 labels 等长 */
    public static Dialog show(final Context ctx, final String title,
                              final String[] labels, final String[] hints,
                              final int current, final OnPicked cb) {
        return show(ctx, title, labels, hints, current, null, cb);
    }

    /** 带底部说明文字的版本（列表下一条分隔线 + 12sp 提示色小字） */
    public static Dialog show(final Context ctx, final String title,
                              final String[] labels, final String[] hints,
                              final int current, final CharSequence footer, final OnPicked cb) {
        if (ctx == null || labels == null || labels.length == 0) return null;
        final Dialog d = new Dialog(Ui.dialogContext(ctx));
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);   // 去掉空标题栏，标题才贴得上沿

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(ctx, 4);
        root.setPadding(side, Ui.dp(ctx, 12), side, side);

        if (title != null && title.length() > 0) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(20);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.tint());
            t.setAlpha(0.82f);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setIncludeFontPadding(false);
            t.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 10));
            root.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View line = new View(ctx);
            line.setBackgroundColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            root.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1))));
        }

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(ctx, 2), 0, 0);

        // current < 0 =「没有当前值」的操作列表（例如「点一条删除」），不标 ✓
        final int cur = current < 0 ? -1 : Math.min(labels.length - 1, current);

        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            final boolean checked = (which == cur);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int hp = Ui.dp(ctx, 16);
            row.setPadding(hp, Ui.dp(ctx, 11), hp, Ui.dp(ctx, 11));
            row.setBackground(rowBg(ctx, checked));

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextSize(15);
            label.setTextColor(Ui.tint());
            texts.addView(label);

            if (hints != null && which < hints.length && hints[which] != null) {
                TextView hint = new TextView(ctx);
                hint.setText(hints[which]);
                hint.setTextSize(12);
                hint.setTextColor(Ui.tintHint());
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = Ui.dp(ctx, 2);
                texts.addView(hint, hlp);
            }

            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (checked) {
                TextView ok = new TextView(ctx);
                ok.setText("✓");
                ok.setTextSize(16);
                ok.setTextColor(Ui.dark ? 0xFF7FD0FF : 0xFF2C6BC8);
                row.addView(ok);
            }

            row.setOnClickListener(v -> {
                d.dismiss();
                if (cb != null) cb.onPicked(which);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (footer != null && footer.length() > 0) {
            View line2 = new View(ctx);
            line2.setBackgroundColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            root.addView(line2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1))));
            TextView note = new TextView(ctx);
            note.setText(footer);
            note.setTextSize(12);
            note.setTextColor(Ui.tintHint());
            int fp = Ui.dp(ctx, 16);
            note.setPadding(fp, Ui.dp(ctx, 10), fp, Ui.dp(ctx, 4));
            root.addView(note, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);
        d.show();

        Ui.glassWindow(d, ctx, true);     // 紧凑版：与长按菜单同宽（≤300dp）
        clampHeight(d, ctx, sv);
        return d;
    }

    /** 行底：选中项淡底色 + 水波纹反馈（与操作菜单的行反馈一致） */
    private static Drawable rowBg(Context ctx, boolean checked) {
        GradientDrawable content = new GradientDrawable();
        content.setCornerRadius(Ui.dp(ctx, 12));
        if (checked) content.setColor(Ui.dark ? 0x2EFFFFFF : 0x1A000000);
        else content.setColor(0x00000000);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Ui.dp(ctx, 12));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(
                ColorStateList.valueOf(Ui.dark ? 0x33FFFFFF : 0x1F000000), content, mask);
    }

    /** 选项多到装不下时压到屏高 82%，剩下的交给 ScrollView（同 GlassMenu 的处理） */
    private static void clampHeight(final Dialog d, final Context ctx, View content) {
        final Window w = d.getWindow();
        if (w == null) return;
        content.post(() -> {
            try {
                int decor = w.getDecorView().getHeight();
                int max = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.82f);
                if (decor > max) {
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.height = max;
                    w.setAttributes(lp);
                }
            } catch (Throwable ignored) { }
        });
    }
}
