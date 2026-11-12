package com.cz.richdesk;

import android.app.Dialog;
import android.content.Context;

/**
 * 「指定应用打开位置」/「活动画中画」屏位选择器。
 *
 * 【样式统一】不再自己画 AlertDialog（原来那套是「描边阴影 + 对勾」，和设置页
 * 其它弹框的「淡底色 + 对勾 + 水波纹」对不上）。现在全部委托 {@link OptionPicker}
 * —— 也就是设置页「Dock 位置 / 功能区」用的那一套。本类只负责：
 *   1) 生成「自动 / 画中画 1..n」标签；
 *   2) 「屏号」与「列表下标」之间的换算。
 *
 * 【修过的 bug】旧版把列表下标 i 直接当屏号回调出去：withAuto=false 时
 * 下标 0/1/2 被写进 active_pane（clamp 到 1..3）变成 1/1/2 —— 选第 2 屏存成 1、
 * 选第 3 屏存成 2，永远差一位；选中高亮也用下标比屏号，✓ 错位一格。
 * 用户反馈的就是「活动画中画在设置里改了不生效」。
 */
public class PaneChooser {

    public interface OnPicked {
        /** 0 = 自动；1..n = 第几屏 */
        void onPicked(int pane);
    }

    private PaneChooser() { }

    /** 应用→屏位路由用：第一项是「自动」 */
    public static Dialog show(final Context ctx, final String title,
                              final int paneCount, final int current, final OnPicked cb) {
        return show(ctx, title, paneCount, current, true, cb);
    }

    /**
     * @param withAuto true 时第一项是「自动」（应用→屏位路由用）；
     *                 false 时只有 1..n 屏（活动画中画配置用）
     * @param current  「屏号」语义：withAuto 时 0=自动 / 1..n；否则 1..n
     */
    public static Dialog show(final Context ctx, final String title,
                              final int paneCount, final int current, final boolean withAuto,
                              final OnPicked cb) {
        if (ctx == null || paneCount <= 0) return null;
        final int offset = withAuto ? 1 : 0;
        final String[] labels = new String[paneCount + offset];
        if (withAuto) labels[0] = "自动（按顺序）";
        for (int i = 0; i < paneCount; i++) labels[i + offset] = "画中画 " + (i + 1);

        // 屏号 → 列表下标（OptionPicker 的 current / onPicked 都是下标）
        int curIdx = withAuto ? current : current - 1;
        curIdx = Math.max(0, Math.min(labels.length - 1, curIdx));

        return OptionPicker.show(ctx, title, labels, null, curIdx, idx -> {
            if (cb == null) return;
            // 列表下标 → 屏号，保持 OnPicked 的既有契约不变
            cb.onPicked(withAuto ? idx : idx + 1);
        });
    }
}
