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
 * 外观与长按图标的操作菜单（{@link GlassMenu}）完全一致：同一个
 * {@link Ui#glassWindow(Dialog, Context, boolean)} 紧凑版（宽度上限 300dp），
 * 同样的标题样式、行高与行反馈 —— 用户要求"两个弹框大小一样"。
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

        final int cur = Math.max(0, Math.min(labels.length - 1, current));

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
