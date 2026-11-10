package com.cz.richdesk;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Dock 栏：固定 N 个格位（N 可配置）。
 *  - 前 k 格放已绑定应用，第 k+1 格是「＋」；
 *  - 当 k == N 时不再有「＋」，最后一格也是应用——加满后仍然能通过长按重新绑定。
 */
public class DockAdapter extends RecyclerView.Adapter<DockAdapter.VH> {

    public interface Host {
        List<String> dockPkgs();
        AppInfo findApp(String pkg);
        int dockSlots();
        void onDockClick(String pkg);
        void onDockAdd();
        void onDockLongClick(int pos, String pkg);
        /** 拖动排序结束（真的换过位置）：持久化新顺序 */
        void onDockOrderChanged();
    }

    private final Host host;
    private final int cornerDp;
    private int slotWidth;
    /** 图标大小（dp），设置里可调。Dock 高度由它反推（图标 + 24dp），不再单独设 */
    private int iconDp = 42;
    private ItemTouchHelper dragHelper;
    /** 本次拖动是否真的交换过位置：松手时用来区分「拖动」和「原地长按」 */
    private boolean dragMoved;

    public DockAdapter(Host host, int cornerDp) {
        this.host = host;
        this.cornerDp = cornerDp;
    }

    public void setSlotWidth(int px) {
        if (px > 0 && px != slotWidth) {
            slotWidth = px;
            notifyDataSetChanged();
        }
    }

    public void setIconDp(int dp) {
        if (dp > 0 && dp != iconDp) {
            iconDp = dp;
            notifyDataSetChanged();
        }
    }

    /**
     * 实际图标 px = 用户设置的 dp，**不再被 Dock 高度或别的什么削一刀**。
     * 与应用列表同一把尺子：同一个 dp 两边就是同一个像素尺寸。
     * 只保留一个物理上限：不超出格宽（格数很多时才会碰到，否则会互相压住）。
     */
    private int iconPx(Context c) {
        int base = Ui.dp(c, iconDp);
        if (slotWidth > 0) base = Math.min(base, Math.round(slotWidth * 0.94f));
        return Math.max(Ui.dp(c, 20), base);
    }

    public int slotWidth() {
        return slotWidth;
    }

    @Override
    public int getItemCount() {
        int slots = Math.max(1, host.dockSlots());
        int used = host.dockPkgs().size();
        // 不挂常驻「＋」：Dock 只画真实图标占的格子，添加入口 = 长按 Dock 空白处
        return Math.min(slots, used);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        int w = slotWidth > 0 ? slotWidth : Ui.dp(parent.getContext(), 60);
        // 高度 MATCH_PARENT：跟 RecyclerView 内容区一样高，图标/加号靠 Gravity.CENTER
        // 才是真正的垂直居中（固定 58dp 时内容区一旦更矮就会偏上）
        cell.setLayoutParams(new RecyclerView.LayoutParams(w,
                RecyclerView.LayoutParams.MATCH_PARENT));
        cell.setClipChildren(false);

        // Dock 图标同样套 iOS 风格遮罩，和应用列表外形统一
        ImageView iv = new MaskedIconView(parent.getContext());
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int ic = iconPx(parent.getContext());
        iv.setLayoutParams(new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
        cell.addView(iv);

        TextView add = new TextView(parent.getContext());
        int sz = Math.min(Ui.dp(parent.getContext(), 44), iconPx(parent.getContext()));
        add.setLayoutParams(new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x1FFFFFFF);
        g.setStroke(Math.max(1, Ui.dp(parent.getContext(), 1)), 0x4DFFFFFF);
        add.setBackground(g);
        add.setGravity(Gravity.CENTER);
        add.setText("＋");
        add.setTextSize(18);
        add.setTextColor(0xE6FFFFFF);
        add.setShadowLayer(3, 0, 1, 0x99000000);
        add.setVisibility(View.GONE);
        cell.addView(add);
        // 长按后菜单立刻弹出（浮层），手指一移动就把它关掉，拖动继续换位。
        // 只做这一件事，返回 false 不影响点击 / 长按 / ItemTouchHelper 的任何流程。
        cell.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - downX) > Ui.dp(v.getContext(), 8)
                                || Math.abs(e.getRawY() - downY) > Ui.dp(v.getContext(), 8)) {
                            OverlayMenu.dismissCurrent();
                        }
                        break;
                }
                return false;
            }
        });

        return new VH(cell, iv, add);
    }

    @Override
    public void onBindViewHolder(@NonNull VH vh, int position) {
        List<String> pkgs = host.dockPkgs();
        int w = slotWidth > 0 ? slotWidth : Ui.dp(vh.itemView.getContext(), 60);
        ViewGroup.LayoutParams lp = vh.itemView.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            vh.itemView.setLayoutParams(lp);
        }
        int ic = iconPx(vh.itemView.getContext());
        ViewGroup.LayoutParams ilp = vh.ivIcon.getLayoutParams();
        if (ilp != null && ilp.width != ic) {
            ilp.width = ic;
            ilp.height = ic;
            vh.ivIcon.setLayoutParams(ilp);
        }

        if (position < pkgs.size()) {
            final String pkg = pkgs.get(position);
            AppInfo ai = host.findApp(pkg);
            vh.ivIcon.setVisibility(View.VISIBLE);
            vh.tvAdd.setVisibility(View.GONE);
            vh.ivIcon.setImageDrawable(ai != null ? ai.icon
                    : vh.itemView.getContext().getDrawable(android.R.drawable.sym_def_app_icon));
            vh.itemView.setOnClickListener(v -> host.onDockClick(pkg));
            // 长按不再直接弹菜单：先进入拖动待命，原地松手 = 菜单，拖走 = 排序
            vh.itemView.setOnLongClickListener(v -> {
                onLongPress(vh);
                return true;
            });
            // 全局关闭震感：关掉系统默认触感（含 ItemTouchHelper.select 那一刀）
            vh.itemView.setHapticFeedbackEnabled(false);
        } else {
            vh.ivIcon.setVisibility(View.GONE);
            vh.tvAdd.setVisibility(View.VISIBLE);
            // 「＋」颜色跟外观走：深色玻璃上白圈，浅色玻璃上黑圈
            if (vh.tvAdd.getBackground() instanceof GradientDrawable) {
                GradientDrawable g = (GradientDrawable) vh.tvAdd.getBackground();
                g.setColor(Ui.dark ? 0x1FFFFFFF : 0x26000000);
                g.setStroke(Math.max(1, Ui.dp(vh.itemView.getContext(), 1)),
                        Ui.dark ? 0x4DFFFFFF : 0x40000000);
            }
            vh.tvAdd.setTextColor(Ui.tint());
            vh.itemView.setOnClickListener(v -> host.onDockAdd());
            vh.itemView.setOnLongClickListener(null);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvAdd;

        VH(View v, ImageView iv, TextView add) {
            super(v);
            ivIcon = iv;
            tvAdd = add;
        }
    }

    // ------------------------------------------------------------ 长按拖动排序

    /**
     * 长按：进入拖动待命，**同时立刻弹菜单**（浮层 OverlayMenu，不抢手势）。
     *
     * 交互顺序按用户要求改过一次：不是「等手指停住 280ms 才弹」，而是长按即弹；
     * 一旦检测到拖动（item 的 OnTouchListener / onMove）就把菜单关掉，换位继续。
     */
    private void onLongPress(VH vh) {
        if (dragHelper == null) return;
        int pos = vh.getBindingAdapterPosition();
        if (pos < 0 || pos >= host.dockPkgs().size()) return;
        // 全局关闭震感：关掉系统默认触感反馈，不再主动震
        vh.itemView.setHapticFeedbackEnabled(false);
        OverlayMenu.setAnchor(vh.itemView);   // 菜单贴着这个图标弹出
        dragHelper.startDrag(vh);
        host.onDockLongClick(pos, host.dockPkgs().get(pos));
    }

    /** MainActivity 在 rvDock.setAdapter 之后调用一次 */
    public void attachDragHelper(RecyclerView rv) {
        ItemTouchHelper.Callback cb = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rvc, @NonNull RecyclerView.ViewHolder vh) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= host.dockPkgs().size()) return makeMovementFlags(0, 0);
                // rvDock 是水平 LayoutManager（视觉上被旋转成竖排，坐标仍是数据坐标）
                return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rvc,
                                  @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                int used = host.dockPkgs().size();
                if (from < 0 || to < 0 || from >= used || to >= used) return false;
                OverlayMenu.dismissCurrent();   // 真的开始换位了 → 菜单该让路
                List<String> pkgs = host.dockPkgs();
                pkgs.add(to, pkgs.remove(from));
                notifyItemMoved(from, to);
                dragMoved = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }

            /**
             * 拖动中每一帧都走这里：手指只要挪开一点就把长按弹出的菜单收掉。
             * 比 cell 的 OnTouchListener 可靠 —— ItemTouchHelper 接管手势后，
             * 子 View 只会收到 CANCEL，拿不到后面的 MOVE（用户报的
             * 「移动后弹框不自动关闭」就是这个原因）。
             */
            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                if (isCurrentlyActive && actionState == ItemTouchHelper.ACTION_STATE_DRAG
                        && (Math.abs(dX) > Ui.dp(rv.getContext(), 6)
                        || Math.abs(dY) > Ui.dp(rv.getContext(), 6))) {
                    OverlayMenu.dismissCurrent();
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;   // 长按由 adapter 手动 startDrag，才能和"长按弹菜单"共存
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    dragMoved = false;
                    vh.itemView.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120).start();
                    vh.itemView.setElevation(Ui.dp(vh.itemView.getContext(), 10));
                }
                super.onSelectedChanged(vh, actionState);
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                vh.itemView.setElevation(0);
                boolean moved = dragMoved;
                dragMoved = false;
                // 菜单在长按那一刻就弹出来了（OverlayMenu），这里绝不再弹一次 ——
                // 用户反馈的「拖动后松手又蹦一个弹框」就是这里重复弹出来的。
                if (moved) {
                    host.onDockOrderChanged();   // 拖动过 → 只存新顺序
                }
            }
        };
        dragHelper = new ItemTouchHelper(cb);
        dragHelper.attachToRecyclerView(rv);
    }
}
