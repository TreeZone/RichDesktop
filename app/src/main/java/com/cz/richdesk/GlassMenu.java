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
        return show(ctx, title, items, null, cb);
    }

    /**
     * 带底部说明文字的版本：footer 是不可点的小字（12sp 提示色），挂在菜单列表下面。
     * 用来放"这里显示的是什么 / 怎么让列表里出现更多项"这类一行说明。
     */
    public static Dialog show(Context ctx, String title, CharSequence[] items,
                              CharSequence footer, OnPick cb) {
        // 菜单项打日志：没有手指（或屏幕被锁屏盖住）时也能确认弹的是哪几项
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < items.length; k++) {
            if (k > 0) sb.append(" / ");
            sb.append(items[k]);
        }
        android.util.Log.i("PipDesk", "菜单 标题=" + title + " 项=" + items.length + " [" + sb + "]");
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

        // ---- 底部说明：不可点的小字，与菜单行之间留条分隔线 ----
        if (footer != null && footer.length() > 0) {
            View line2 = new View(ctx);
            line2.setBackgroundColor(Ui.dark ? 0x26FFFFFF : 0x1A000000);
            root.addView(line2, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1))));
            TextView hint = new TextView(ctx);
            hint.setText(footer);
            hint.setTextSize(12);
            hint.setTextColor(Ui.tintHint());
            int fp = Ui.dp(ctx, 16);
            hint.setPadding(fp, Ui.dp(ctx, 10), fp, Ui.dp(ctx, 4));
            root.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

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

    /**
     * 玻璃样式的「输入框」弹框 —— 和菜单同一套外观。
     *
     * 存在的原因：桌面是透明窗口主题，AlertDialog 继承过去就**没有底色**，
     * 黑底黑字/白字全看不清（用户原话「连底色都没有」）。所以输入框也走
     * Ui.dialogContext + glassWindow + tint 这一套，跟菜单天然一致。
     */
    public static Dialog input(Context ctx, String title, String value, String editHint,
                               CharSequence footer, java.util.function.Consumer<String> onOk) {
        final Dialog d = new Dialog(Ui.dialogContext(ctx));
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

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

        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setSingleLine(true);
        et.setText(value == null ? "" : value);
        et.setSelection(et.getText().length());
        et.setHint(editHint == null ? "" : editHint);
        et.setTextSize(16);
        et.setTextColor(Ui.tint());
        et.setHintTextColor(Ui.tintHint());
        GradientDrawable box = new GradientDrawable();
        box.setColor(Ui.dark ? 0x33FFFFFF : 0x14000000);
        box.setCornerRadius(Ui.dp(ctx, 12));
        et.setBackground(box);
        int hp = Ui.dp(ctx, 16);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(side, Ui.dp(ctx, 12), side, Ui.dp(ctx, 4));
        et.setPadding(hp, Ui.dp(ctx, 12), hp, Ui.dp(ctx, 12));
        root.addView(et, elp);

        if (footer != null && footer.length() > 0) {
            TextView hint = new TextView(ctx);
            hint.setText(footer);
            hint.setTextSize(12);
            hint.setTextColor(Ui.tintHint());
            int fp = Ui.dp(ctx, 16);
            hint.setPadding(fp, Ui.dp(ctx, 6), fp, Ui.dp(ctx, 4));
            root.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 按钮行：和菜单行同一个 rowBg，左右各一个
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(ctx, 6);
        String[] names = {"取消", "保存"};
        for (int i = 0; i < names.length; i++) {
            final boolean ok = i == 1;
            TextView b = new TextView(ctx);
            b.setText(names[i]);
            b.setTextSize(15);
            b.setTextColor(Ui.tint());
            b.setGravity(Gravity.CENTER);
            b.setPadding(hp, Ui.dp(ctx, 13), hp, Ui.dp(ctx, 13));
            b.setBackground(rowBg(ctx));
            b.setOnClickListener(v -> {
                d.dismiss();
                if (ok && onOk != null) {
                    onOk.accept(et.getText() == null ? "" : et.getText().toString().trim());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(side, 0, side, 0);
            bar.addView(b, lp);
        }
        root.addView(bar, blp);

        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);
        d.show();
        Ui.glassWindow(d, ctx, true);
        et.postDelayed(() -> {
            try {
                et.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            } catch (Throwable ignored) { }
        }, 300);
        return d;
    }

    /** 菜单行：水波纹反馈，圆角小一点，别顶到面板圆角上 */
    static RippleDrawable rowBg(Context ctx) {
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
