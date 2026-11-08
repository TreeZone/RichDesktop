package com.cz.richdesk;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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

import java.util.List;

/**
 * 玻璃样式的「操作菜单」弹窗（长按画中画 / 长按图标 / 长按应用都走它）。
 *
 * 为什么不用 AlertDialog.setItems：
 *  1) 列表项文字色由系统主题的 textColorAlertDialogListItem 决定，在车机的深色框架主题下
 *     **始终是白色** —— 我们把底换成了浅色玻璃，白字压白底，什么都看不见；
 *  2) 标题和列表项之间没有任何分隔，一眼分不清哪行是标题、哪行能点。
 * 所以这里自己画：标题（小一号、淡一档、靠上留白）→ 一条横线 → 菜单行（可滚动）。
 *
 * 所有颜色都走 {@link Ui#tint()} / {@link Ui#tintDim()}，深浅两套外观都看得清。
 */
public final class GlassMenu {

    public interface OnPick {
        void onPick(int index);
    }

    private GlassMenu() { }

    public static Dialog show(Context ctx, String title, List<String> items, OnPick cb) {
        return show(ctx, title, items.toArray(new CharSequence[0]), cb);
    }

    public static Dialog show(Context ctx, String title, CharSequence[] items, OnPick cb) {
        final Dialog d = new Dialog(Ui.dialogContext(ctx));
        // 主题里 windowNoTitle=false，Dialog 会在内容上方留一条**空的标题栏**（实测 45dp）。
        // 这条空栏就是"标题怎么调都靠不下来"的真凶：面板顶上先空掉 45dp，再才是我们的内容。
        // 必须在 setContentView 之前请求去掉标题栏。
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(ctx, 4);
        // 上沿留白：4dp 太贴（用户反馈"太靠上了"），12dp 是"贴近顶部但还有口气"的位置
        root.setPadding(side, Ui.dp(ctx, 12), side, side);

        // ---- 标题：明显比菜单行大一号，且贴着面板上沿 ----
        // 用户两次反馈"标题还偏靠下、字比菜单行小"：17sp → 20sp 加粗，上留白 14dp → 6dp。
        // 标题贴近上沿 + 加粗，视觉上才像"这一页的名字"，而不是又一行能点的菜单。
        if (title != null && title.length() > 0) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(20);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.tint());
            t.setAlpha(0.82f);      // 用透明度压一档，既能和菜单行区分，又不会读不清
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 去掉字形上下的额外行距（includeFontPadding），否则 20sp 的字顶上又空出一块，
            // 看起来永远"离上沿太远"
            t.setIncludeFontPadding(false);
            // 上 padding 归零：上沿留白统一由 root 的 4dp 给，避免"标题自己再空一块"
            t.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 10));
            root.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ---- 分隔线：标题与可点菜单的界线 ----
            View line = new View(ctx);
            line.setBackgroundColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            root.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1))));
        }

        // ---- 菜单行（多了可以滚，不至于顶出屏幕） ----
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int lp = Ui.dp(ctx, 2);
        list.setPadding(0, lp, 0, 0);

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView row = new TextView(ctx);
            row.setText(items[i]);
            row.setTextSize(15);
            row.setTextColor(Ui.tint());     // 浅色玻璃上黑字，深色玻璃上白字
            row.setGravity(Gravity.CENTER_VERTICAL);
            int hp = Ui.dp(ctx, 16);
            row.setPadding(hp, Ui.dp(ctx, 13), hp, Ui.dp(ctx, 13));
            row.setBackground(rowBg(ctx));
            row.setOnClickListener(v -> {
                d.dismiss();
                if (cb != null) cb.onPick(idx);
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

        // 圆角 / 玻璃底 / 宽度 / 压暗 统一交给 Ui（紧凑版：上限 300dp，别铺成一整条）
        Ui.glassWindow(d, ctx, true);
        clampHeight(d, ctx, sv);
        // 便于核对"标题离上沿多远"：跑一次看日志就知道是留白多了还是标题自己的 padding
        root.post(() -> {
            try {
                android.view.View first = root.getChildCount() > 0 ? root.getChildAt(0) : null;
                if (first == null) return;
                int[] ts = new int[2];
                first.getLocationOnScreen(ts);
                int[] ds = new int[2];
                android.view.View decor = d.getWindow().getDecorView();
                decor.getLocationOnScreen(ds);
                android.util.Log.i("PipDesk", "GlassMenu 标题屏y=" + ts[1] + " 面板屏y=" + ds[1]
                        + " 差=" + (ts[1] - ds[1]) + " 标题相对root=" + first.getTop()
                        + " 高=" + first.getHeight() + " rootPad=" + root.getPaddingTop()
                        + " decorPad=" + decor.getPaddingTop() + " decorH=" + decor.getHeight()
                        + " rootH=" + root.getHeight());
            } catch (Throwable ignored) { }
        });
        return d;
    }

    /** 菜单行：水波纹反馈，圆角小一点，别顶到面板圆角上 */
    private static RippleDrawable rowBg(Context ctx) {
        int c = Ui.dark ? 0x33FFFFFF : 0x1F000000;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(ctx, 12));
        return new RippleDrawable(ColorStateList.valueOf(c), null, mask);
    }

    /**
     * 行数多 / 横屏矮屏时会顶出窗口：把窗口高度压到屏高的 82%，内容由 ScrollView 自己滚。
     * 只在超出时才改，正常几行的菜单不会被动到。
     */
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
