package com.cz.richdesk;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * 「指定应用打开位置」选择器。
 *
 * 为什么不用 AlertDialog.setSingleChoiceItems：
 *   单选列表的行底色/文字色由各 ROM 的主题决定，叠在玻璃底上经常出现
 *   「深色底 + 深灰字 + 深色选中条」糊成一片的情况（需求 5）。
 *   这里自己画行：白字 + 当前项高亮描边 + 右侧 ✓，任何版本都清晰。
 */
public class PaneChooser {

    public interface OnPicked {
        void onPicked(int pane);   // 0 = 自动；1..n = 第几屏
    }

    private PaneChooser() { }

    public static void show(final Context ctx, final String title,
                            final int paneCount, final int current, final OnPicked cb) {
        show(ctx, title, paneCount, current, true, cb);
    }

    /**
     * @param withAuto true 时第一项是「自动」（应用→屏位路由用）；
     *                 false 时只有 1..n 屏（活动画中画配置用）
     */
    public static void show(final Context ctx, final String title,
                            final int paneCount, final int current, final boolean withAuto,
                            final OnPicked cb) {
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(ctx, 8);
        box.setPadding(padH, Ui.dp(ctx, 6), padH, Ui.dp(ctx, 14));

        final String[] labels = new String[paneCount + (withAuto ? 1 : 0)];
        int offset = withAuto ? 1 : 0;
        if (withAuto) labels[0] = "自动（按顺序）";
        for (int i = 0; i < paneCount; i++) labels[i + offset] = "画中画 " + (i + 1);
        // current：withAuto 时 0=自动；否则 1..n 表示第几屏 → 换算成列表下标
        int curIdx = withAuto ? current : current - 1;
        curIdx = Math.max(0, Math.min(labels.length - 1, curIdx));

        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = Ui.dp(ctx, 14);
            row.setPadding(rp, Ui.dp(ctx, 14), rp, Ui.dp(ctx, 14));
            row.setBackground(rowBg(ctx, which == current));

            TextView tv = new TextView(ctx);
            tv.setText(labels[i]);
            tv.setTextSize(15);
            // 跟着外观走：浅色玻璃上用深色字（原来写死白色，浅色模式整行看不见）
            tv.setTextColor(Ui.tint());
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (which == current) {
                TextView ok = new TextView(ctx);
                ok.setText("✓");
                ok.setTextSize(16);
                ok.setTextColor(Ui.dark ? 0xFF7FD0FF : 0xFF2C6BC8);
                row.addView(ok);
            }

            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (cb != null) cb.onPicked(which);
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        holder[0] = new AlertDialog.Builder(Ui.dialogContext(ctx))
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
        Ui.glassWindow(holder[0], ctx);
    }

    private static Drawable rowBg(Context ctx, boolean checked) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(ctx, 12));
        // 深色玻璃用白色淡层，浅色玻璃用黑色淡层 —— 反过来会几乎看不见
        if (Ui.dark) {
            g.setColor(checked ? 0x33FFFFFF : 0x14FFFFFF);
            if (checked) g.setStroke(Math.max(1, Ui.dp(ctx, 1)), 0x66FFFFFF);
        } else {
            g.setColor(checked ? 0x1F000000 : 0x0D000000);
            if (checked) g.setStroke(Math.max(1, Ui.dp(ctx, 1)), 0x55000000);
        }
        return g;
    }
}
