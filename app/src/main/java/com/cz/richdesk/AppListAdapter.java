package com.cz.richdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用列表网格。
 *  - 前 5 项是桌面内置快捷图标，样式和普通应用完全一样（方形圆角图标 + 名称）：
 *    「桌面设置」「深浅模式」「重启桌面」「返回」「主页」，点按各自生效；
 *  - 图标尺寸读配置，改完设置回来即时生效；
 *  - 术语：点=画中画打开，长按=菜单（全屏 / 指定画中画 / 绑到 Dock）。
 */
public class AppListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Host {
        void onAppClick(AppInfo app);
        void onAppLongClick(AppInfo app);
        void onSettingsClick();
        /**
         * 长按拖动排序结束（真的换过位置）：宿主按新顺序持久化。
         * picker 弹窗里禁用拖动，不会走到。
         */
        default void onAppOrderChanged(List<AppInfo> ordered) { }
    }

    private static final int TYPE_APP = 0;

    /** 快捷方式格子的伪包名（下标 0 = 「桌面设置」，它没有伪包名，单独处理） */
    private static final String[] SHORTCUT_PKGS = {
            AppInfo.THEME_PKG, AppInfo.RESTART_PKG, AppInfo.BACK_PKG, AppInfo.HOME_PKG,
            AppInfo.SLEEP_PKG, AppInfo.RECENTS_PKG, AppInfo.VOL_UP_PKG, AppInfo.VOL_DOWN_PKG};
    private static final String[] SHORTCUT_LABELS = {
            "深浅模式", "重启桌面", "返回", "主页", "息屏", "最近任务", "音量 +", "音量 -"};
    /** 快捷方式一共几格（含「桌面设置」） */
    private static final int SHORTCUT_COUNT = SHORTCUT_PKGS.length + 1;

    private List<AppInfo> data;
    private final Host host;
    private final boolean withSettings;
    /** 图标尺寸按 **dp** 存，画的时候再换成 px（之前误把 dp 当 px 用，图标只有设定值的 40%） */
    private int iconDp;
    /**
     * 文字是否直接压在壁纸上（桌面应用页 = true）。
     * 选应用弹窗里的同一个网格是压在浅/深玻璃上的，必须按外观取色，
     * 否则按壁纸取色会拿到弹窗外面那块壁纸的亮度，反而更糊。
     */
    private final boolean overWallpaper;
    /** 是否允许长按拖动排序（桌面应用页 = true，选择器弹窗 = false） */
    private boolean dragEnabled;
    private ItemTouchHelper dragHelper;
    /** 本次拖动是否真的交换过位置：松手时用来区分「拖动」和「原地长按」 */
    private boolean dragMoved;
    /** 快捷区的显示顺序（含「桌面设置」共 9 项，pkg 列表）。可含真实应用 pkg（跨区互换后） */
    private List<String> shortcutOrder = null;

    /** 真实应用被换进快捷区后，图标/名字从这份缓存恢复（reload 后 bindShortcut 也会回填） */
    private final java.util.Map<String, AppInfo> appCache = new java.util.HashMap<>();
    /** 本次拖动改了哪块：松手时两块都要落盘（跨区互换会同时改两块） */
    private boolean dirtyShortcuts;
    private boolean dirtyApps;

    /** 宿主（MainActivity）在 setAdapter 前把持久化的顺序塞进来 */
    public void setShortcutOrder(List<String> order) {
        if (order != null && order.size() == SHORTCUT_PKGS.length + 1) {
            shortcutOrder = new ArrayList<>(order);
        }
    }

    /**
     * 快捷区顺序，缺配置时补一份默认值。
     * 必须保证非空：以前拖动快捷区图标时，如果用户从没拖过（配置里没这条），
     * shortcutOrder 是 null → onMove 直接 return false → 表现为「拖不动」。
     */
    private List<String> shortcutOrderOrInit() {
        if (shortcutOrder == null || shortcutOrder.size() != SHORTCUT_COUNT) {
            shortcutOrder = new ArrayList<>(SHORTCUT_COUNT);
            shortcutOrder.add(AppInfo.SETTINGS_PKG);
            for (String p : SHORTCUT_PKGS) shortcutOrder.add(p);
        }
        return shortcutOrder;
    }

    /** slot（0..8）→ 伪包名：按可配置顺序取，配置缺失时退回固定顺序 */
    private String shortcutPkgAt(int slot) {
        if (shortcutOrder != null && slot >= 0 && slot < shortcutOrder.size()) {
            return shortcutOrder.get(slot);
        }
        if (slot <= 0) return AppInfo.SETTINGS_PKG;
        return SHORTCUT_PKGS[slot - 1];
    }

    /** 宿主在装载数据后把「已被换进快捷区的真实应用」塞进缓存，重载后图标才不丢 */
    public void rememberApp(AppInfo a) {
        if (a != null && a.pkg != null) appCache.put(a.pkg, a);
    }

    /** 在当前应用数据里找真实应用（跨区互换后快捷槽位可能放着真实 app） */
    private AppInfo findInData(String pkg) {
        if (data == null || pkg == null) return null;
        for (AppInfo a : data) if (pkg.equals(a.pkg)) return a;
        return null;
    }

    /** 换进应用区的那个 pkg 还原成 AppInfo：内置 → 自绘图标；真实 app → 缓存/数据里找 */
    private AppInfo appInfoForSlot(String pkg) {
        if (AppInfo.isBuiltin(pkg)) {
            return new AppInfo(shortcutLabel(pkg), pkg, shortcutIcon(pkg));
        }
        AppInfo ai = findInData(pkg);
        if (ai == null) ai = appCache.get(pkg);
        return ai != null ? ai : new AppInfo(pkg, pkg, null);
    }

    public AppListAdapter(List<AppInfo> data, Host host, boolean withSettings, int iconDp) {
        this(data, host, withSettings, iconDp, true);
    }

    public AppListAdapter(List<AppInfo> data, Host host, boolean withSettings, int iconDp,
                          boolean overWallpaper) {
        this.data = data;
        this.host = host;
        this.withSettings = withSettings;
        this.iconDp = iconDp;
        this.overWallpaper = overWallpaper;
    }

    public interface FullHost extends Host {
        /** 应用列表快捷区拖动排序结束：宿主持久化新顺序 */
        default void onShortcutOrderChanged(java.util.List<String> order) { }
        void onThemeToggle();
        /** 长按「深浅模式」图标（可以把它放进 Dock） */
        void onThemeLongClick();
        /** 点了「重启桌面 / 返回 / 主页」 */
        void onShortcutClick(String pkg);
        /** 长按「重启桌面 / 返回 / 主页」（可以把它放进 Dock） */
        void onShortcutLongClick(String pkg);
    }

    /** 伪包名 → 快捷方式的名字（Dock 里画标签也要用） */
    static String shortcutLabel(String pkg) {        if (AppInfo.SETTINGS_PKG.equals(pkg)) return "桌面设置";
        for (int i = 0; i < SHORTCUT_PKGS.length; i++) {
            if (SHORTCUT_PKGS[i].equals(pkg)) return SHORTCUT_LABELS[i];
        }
        return pkg;
    }

    /**
     * 伪包名 → 自绘图标（Dock 里显示它要用）。
     * 注意：**必须每次 new**，不能缓存共享——Drawable 的 bounds/callback 是单份状态，
     * 一个实例同时挂到 Dock 和应用列表的多个 ImageView 上会互相改 bounds，
     * 表现就是图标不显示或被拉成奇怪的形状（0.13.1 踩过）。
     */
    static Drawable shortcutIcon(String pkg) {
        return newShortcutIcon(pkg);
    }

    private static Drawable newShortcutIcon(String pkg) {
        if (AppInfo.SETTINGS_PKG.equals(pkg)) return new SettingsIcon();
        if (AppInfo.THEME_PKG.equals(pkg)) return new ThemeIcon();
        if (AppInfo.RESTART_PKG.equals(pkg)) return new RestartIcon();
        if (AppInfo.BACK_PKG.equals(pkg)) return new BackIcon();
        if (AppInfo.HOME_PKG.equals(pkg)) return new HomeIcon();
        if (AppInfo.SLEEP_PKG.equals(pkg)) return new SleepIcon();
        if (AppInfo.RECENTS_PKG.equals(pkg)) return new RecentsIcon();
        if (AppInfo.VOL_UP_PKG.equals(pkg)) return new VolUpIcon();
        if (AppInfo.VOL_DOWN_PKG.equals(pkg)) return new VolDownIcon();
        return null;
    }

    /**
     * 五个内置快捷方式（桌面设置 / 深浅模式 / 重启桌面 / 返回 / 主页）。
     * 「选择应用」弹窗把它们排在最前面 —— 需求：Dock 也能绑这几个。
     */
    static List<AppInfo> shortcuts() {
        List<AppInfo> r = new java.util.ArrayList<>(SHORTCUT_PKGS.length + 1);
        r.add(new AppInfo(shortcutLabel(AppInfo.SETTINGS_PKG), AppInfo.SETTINGS_PKG,
                new SettingsIcon()));
        for (String pkg : SHORTCUT_PKGS) {
            r.add(new AppInfo(shortcutLabel(pkg), pkg, shortcutIcon(pkg)));
        }
        return r;
    }

    // ------------------------------------------------------------ 网格适配（防图标被裁）

    /**
     * 单元格最小宽度（px）= 图标 + 单元格左右内边距 16dp + 两侧最小间隙 6dp。
     * 格子比这还窄时，固定 px 的图标会溢出格子：被 RecyclerView 边界裁掉
     * （第一列/最后一列缺半边），或被相邻格子的图标盖住 —— 看起来就是"图标显示不完整"。
     */
    public static int minCellPx(Context c, int iconDp) {
        return Ui.dp(c, iconDp + 22);
    }

    /**
     * 列数适配：请求的列数装不下「图标 + 内边距」就减列，**图标永远完整**。
     * availPx 传网格内容的可用宽度（RecyclerView 宽 - 左右 padding）。
     * availPx 拿不到（还没布局）时原样返回请求值，布局后由调用方再算一次。
     */
    public static int fitColumns(Context c, int requested, int iconDp, int availPx) {
        if (availPx <= 0 || requested <= 0) return requested;
        int max = availPx / minCellPx(c, iconDp);
        return Math.max(1, Math.min(requested, max));
    }

    /**
     * 图标适配（列数已定的兜底）：格子内宽装不下图标时缩图标，
     * 下限 24dp。正常情况下列数先让步（fitColumns），这里只在屏真的太窄时生效。
     */
    public static int fitIconDp(Context c, int iconDp, int availPx, int columns) {
        if (availPx <= 0 || columns <= 0) return iconDp;
        int cellInner = availPx / columns - Ui.dp(c, 22);
        int want = Ui.dp(c, iconDp);
        if (cellInner >= want) return iconDp;
        return Math.max(24, Math.min(iconDp, Math.round(cellInner / density(c))));
    }

    private static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    /** 一次算完：{列数, 图标dp}，调用方各取所需 */
    public static int[] fitGrid(Context c, int requestedCols, int iconDp, int availPx) {
        int cols = fitColumns(c, requestedCols, iconDp, availPx);
        int icon = fitIconDp(c, iconDp, availPx, cols);
        return new int[]{cols, icon};
    }

    public void setData(List<AppInfo> data) {
        this.data = data;
    }    public void setIconDp(int dp) {
        if (dp > 0 && dp != iconDp) {
            iconDp = dp;
            notifyDataSetChanged();
        }
    }

    public int offset() {
        return withSettings ? SHORTCUT_COUNT : 0;
    }

    @Override
    public int getItemViewType(int position) {
        // 所有格子都是同一个版式（方形圆角图标 + 名称），只是点击行为不同
        return TYPE_APP;
    }

    @Override
    public int getItemCount() {
        return (data == null ? 0 : data.size()) + offset();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 设置 / 深浅模式 / 重启桌面 / 返回 / 主页都是普通应用格子（方形圆角图标 + 名称），只是点击行为不同
        return new AppVH(buildAppCell(parent));
    }

    private View buildAppCell(ViewGroup parent) {
        LinearLayout cell = new LinearLayout(parent.getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(parent.getContext(), 8);
        cell.setPadding(p, p, p, p);
        // 图标是硬性 dp（不再被格宽缩小），列多/图标大时可能比格子的内宽还宽一点，
        // 允许它画出格子边界（居中溢出，两侧均匀），否则会被父容器裁成缺边
        cell.setClipChildren(false);
        // 全局关闭震感：关掉系统默认触感（含 ItemTouchHelper.select 那一刀）
        cell.setHapticFeedbackEnabled(false);

        // iOS 风格圆角方遮罩：Dock 与应用列表用同一个类，外形才统一
        ImageView iv = new MaskedIconView(parent.getContext());
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = Ui.dp(parent.getContext(), iconDp);
        // 宽高都写死成 px（以前宽度是 MATCH_PARENT）：遮罩按 min(宽,高) 取边长，
        // 一旦格子内宽小于图标高度，图标就被"格子宽度"悄悄缩小 ——
        // 于是在设置里把 Dock 和应用列表设成同一个 dp，Dock（正方形、不受格宽限制）
        // 看起来反而更大。现在两边都是硬性 iconDp，同 dp 必然同大小。
        cell.addView(iv, new LinearLayout.LayoutParams(px, px));

        // 名字的颜色由「背后壁纸」决定（浅壁纸→黑字，深壁纸→白字），不跟外观模式走。
        // 逐行自适应：每个名字只看自己脚底下那块壁纸，滚动时跟着变 —— 壁纸一半亮一半暗
        // 时（比如米色在上、深绿在下）整页统一一个色号必然有一半糊掉，所以按行判。
        WallText tv = new WallText(parent.getContext(), overWallpaper);
        tv.setAdaptive(true);
        tv.setTextSize(11);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(parent.getContext(), 6);
        cell.addView(tv, tlp);

        // 菜单是长按即弹的浮层：手指一移动就把它收掉，换位拖动继续。
        // 返回 false，不影响点击 / 长按 / ItemTouchHelper 的原有流程。
        final Context c = parent.getContext();
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
                        if (Math.abs(e.getRawX() - downX) > Ui.dp(c, 8)
                                || Math.abs(e.getRawY() - downY) > Ui.dp(c, 8)) {
                            OverlayMenu.dismissCurrent();
                        }
                        break;
                }
                return false;
            }
        });

        return cell;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        AppVH vh = (AppVH) holder;
        // 前 5 个快捷图标：桌面设置 / 深浅模式 / 重启桌面 / 返回 / 主页 —— 和普通应用一模一样的格子
        if (withSettings && position < offset()) {
            bindShortcut(vh, position);
            return;
        }
        int idx = position - offset();
        if (data == null || idx < 0 || idx >= data.size()) return;
        final AppInfo ai = data.get(idx);
        vh.ivIcon.setImageDrawable(ai.icon);
        applyIconSize(vh);
        vh.tvLabel.setText(ai.label);
        vh.itemView.setOnClickListener(v -> host.onAppClick(ai));
        vh.itemView.setLongClickable(true);
        vh.itemView.setOnLongClickListener(v -> {
            // 长按立刻弹菜单（浮层，不抢手势）；检测到拖动就把菜单关掉继续换位
            if (dragEnabled) onLongPress(vh);
            else host.onAppLongClick(ai);
            return true;
        });
    }

    /**
     * 图标尺寸统一在这里改：快捷图标以前创建时写死 px，
     * 用户在设置里把「图标大小」改大后它原地不动，和旁边真实应用一比就明显偏小。
     */
    private void applyIconSize(AppVH vh) {
        ViewGroup.LayoutParams lp = vh.ivIcon.getLayoutParams();
        int px = Ui.dp(vh.itemView.getContext(), iconDp);
        if (lp.width != px || lp.height != px) {
            lp.width = px;
            lp.height = px;
            vh.ivIcon.setLayoutParams(lp);
        }
    }

    /** 快捷方式格子：slot 0 = 桌面设置，1..4 = SHORTCUT_PKGS —— 五个走同一套点击/长按 */
    private void bindShortcut(AppVH vh, int slot) {
        applyIconSize(vh);
        final String pkg = shortcutPkgAt(slot);
        // 跨区互换后快捷槽位可能放的是真实应用：按普通应用渲染/点击
        if (!AppInfo.isBuiltin(pkg)) {
            AppInfo found = findInData(pkg);
            if (found == null) found = appCache.get(pkg);
            if (found == null) found = new AppInfo(pkg, pkg, null);
            appCache.put(pkg, found);
            final AppInfo ai = found;
            vh.ivIcon.setImageDrawable(ai.icon);
            vh.tvLabel.setText(ai.label);
            vh.itemView.setOnClickListener(v -> host.onAppClick(ai));
            vh.itemView.setLongClickable(true);
            vh.itemView.setOnLongClickListener(v -> {
                if (dragEnabled) onLongPress(vh);
                else host.onAppLongClick(ai);
                return true;
            });
            return;
        }
        vh.ivIcon.setImageDrawable(shortcutIcon(pkg));
        vh.tvLabel.setText(shortcutLabel(pkg));
        vh.itemView.setOnClickListener(v -> {
            if (host instanceof FullHost) ((FullHost) host).onShortcutClick(pkg);
        });
        vh.itemView.setOnLongClickListener(v -> {
            // 自建图标（桌面设置 / 深浅模式 / 重启桌面…）也要能长按拖动换位置，
            // 所以这里跟普通应用走同一条入口，不再直接弹菜单。
            if (dragEnabled) {
                onLongPress(vh);
                return true;
            }
            if (!(host instanceof FullHost)) return false;
            ((FullHost) host).onShortcutLongClick(pkg);
            return true;
        });
    }

    static class AppVH extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvLabel;

        AppVH(View v) {
            super(v);
            LinearLayout ll = (LinearLayout) v;
            ivIcon = (ImageView) ll.getChildAt(0);
            tvLabel = (TextView) ll.getChildAt(1);
        }
    }

    // ------------------------------------------------------------ 长按拖动排序

    /**
     * 长按：进入拖动待命 + **立刻**弹菜单（OverlayMenu 浮层）。
     *
     * 快捷区（桌面设置 / 深浅模式 / 重启桌面 / 返回 / 主页 / 息屏 / 最近任务 / 音量±）
     * 现在也允许拖动 —— 以前这里 `pos < offset()` 直接 return，所以那几个自建图标
     * 怎么长按都拖不动（用户报的第三条）。跨区仍然禁止，快捷区只在内部换位。
     */
    private void onLongPress(RecyclerView.ViewHolder vh) {
        if (dragHelper == null) return;
        final int pos = vh.getBindingAdapterPosition();
        if (pos < 0 || pos >= getItemCount()) return;
        // 全局关闭震感：关掉系统默认触感反馈，不再主动震
        vh.itemView.setHapticFeedbackEnabled(false);
        OverlayMenu.setAnchor(vh.itemView);   // 菜单贴着这个图标弹出
        dragHelper.startDrag(vh);
        // 区域划分：pos 0..offset()-1 是快捷区，之后才是应用区（data 下标 = pos - offset）。
        // 之前写成 idx < offset()，把快捷区后面的前 9 个应用也当成了快捷区 ——
        // 长按 A 应用弹出的是别的（快捷区）图标的名称和菜单（用户报的错乱就是这个）。
        int idx = pos - offset();
        if (idx < 0) {
            // 快捷区：pos 本身就是槽位（0..8）
            String pkg = shortcutPkgAt(pos);
            if (AppInfo.isBuiltin(pkg)) {
                if (host instanceof FullHost) ((FullHost) host).onShortcutLongClick(pkg);
            } else {
                AppInfo ai = findInData(pkg);
                if (ai == null) ai = appCache.get(pkg);
                if (ai != null) host.onAppLongClick(ai);
            }
        } else if (data != null && idx < data.size()) {
            host.onAppLongClick(data.get(idx));
        }
    }

    /** 桌面应用页在 setAdapter 之后调用一次；picker 弹窗不调（无拖动） */
    public void attachDragHelper(RecyclerView rv) {
        dragEnabled = true;
        ItemTouchHelper.Callback cb = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rvc, @NonNull RecyclerView.ViewHolder vh) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= getItemCount()) return makeMovementFlags(0, 0);
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN
                        | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rvc,
                                  @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                int sc = offset();
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                if (from < 0 || to < 0 || from >= getItemCount() || to >= getItemCount()) return false;
                OverlayMenu.dismissCurrent();   // 开始换位 = 菜单让路
                if (from < sc && to < sc) {
                    // 快捷区内部换位（9 个自建功能图标之间）
                    List<String> order = shortcutOrderOrInit();
                    String tmp = order.set(from, order.get(to));
                    order.set(to, tmp);
                    notifyItemMoved(from, to);
                    dragMoved = true;
                    dirtyShortcuts = true;
                    return true;
                }
                if (from < sc && to >= sc && data != null && to - sc < data.size()) {
                    // 跨区互换（快捷区 → 应用区）：内容互换，不是移位 ——
                    // 快捷槽位放进这个真实应用，原槽位的内置图标换到应用区这个位置
                    List<String> order = shortcutOrderOrInit();
                    int ai = to - sc;
                    String sp = order.get(from);
                    AppInfo movedApp = data.get(ai);
                    appCache.put(movedApp.pkg, movedApp);
                    order.set(from, movedApp.pkg);
                    data.set(ai, appInfoForSlot(sp));
                    notifyItemMoved(from, to);
                    dragMoved = true;
                    dirtyShortcuts = true;
                    dirtyApps = true;
                    return true;
                }
                if (to < sc && from >= sc && data != null && from - sc < data.size()) {
                    // 跨区互换（应用区 → 快捷区）：同上，方向相反
                    List<String> order = shortcutOrderOrInit();
                    int ai = from - sc;
                    String sp = order.get(to);
                    AppInfo movedApp = data.get(ai);
                    appCache.put(movedApp.pkg, movedApp);
                    order.set(to, movedApp.pkg);
                    data.set(ai, appInfoForSlot(sp));
                    notifyItemMoved(from, to);
                    dragMoved = true;
                    dirtyShortcuts = true;
                    dirtyApps = true;
                    return true;
                }
                if (from < sc || to < sc || data == null) return false;   // 不跨区拖
                data.add(to - sc, data.remove(from - sc));
                notifyItemMoved(from, to);
                dragMoved = true;
                dirtyApps = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }

            /**
             * 拖动中每一帧都走这里：手指只要挪开一点就把长按弹出的菜单收掉。
             * 必须放在这里 —— ItemTouchHelper 一旦接管手势，格子本身只会收到
             * ACTION_CANCEL，收不到后续 MOVE，所以 cell 上的 OnTouchListener
             * 在"已经拖起来"的场景里是收不到位移的（用户报的「移动后弹框不关」）。
             */
            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
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
                    dirtyShortcuts = false;
                    dirtyApps = false;
                    vh.itemView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
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
                // 菜单在长按那一刻已经弹过（OverlayMenu），这里不再补弹 ——
                // 否则「拖动后松手」就会突然蹦出一个菜单（用户反馈的老问题）。
                if (!moved) return;
                // 跨区互换会同时改快捷区和应用区，两块都要落盘；
                // 区内拖动只 dirty 自己那一块
                if (dirtyApps && data != null) {
                    host.onAppOrderChanged(new ArrayList<>(data));
                }
                if (moved) {
                    // 跨区互换走的是「内容交换」（data.set / order.set）而非「位置移动」，
                    // 却只发了 notifyItemMoved（只挪视图、不重新绑定）→ 屏幕上的图标和
                    // 数据错位：显示 A 的格子，data 里是 B，长按弹出的菜单就张冠李戴，
                    // 且错位会一直留着。拖动结束统一全量重绑，强制显示=数据。
                    notifyDataSetChanged();
                }
            }
        };
        dragHelper = new ItemTouchHelper(cb);
        dragHelper.attachToRecyclerView(rv);
    }

    /**
     * 「深浅模式」的图标：一张自绘的 app 图标 —— 左上半轮太阳 + 右下弯月。
     *
     * 要点（踩过的坑）：Drawable 必须有自己的「固有尺寸」，否则 ImageView 在
     * FIT_CENTER 下会把 drawable 拉满整个格子（变成一个横条），看起来就不像应用图标了。
     * 这里按 48dp 的方形给尺寸，并且绘制时再按 min(w,h) 居中画正方形，
     * 保证任何格子宽度下都是「方形圆角」的样子。
     */
    static class ThemeIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        private android.graphics.ColorFilter filter;

        private static int px(float dp) {
            return Math.round(dp * android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density);
        }

        /**
         * 不给固有尺寸（返回 -1）：ImageView 会把绘制区域直接铺成格子大小，
         * 图标才会**跟着设置里的「图标大小」一起变大变小**。
         * 之前写死 48dp 的固有尺寸，格子变大后这个图标原地不动，
         * 和旁边真实应用的图标一比就明显偏小。
         */
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int side = Math.min(b.width(), b.height());
            if (side <= 0) return;
            float l = b.left + (b.width() - side) / 2f;
            float t = b.top + (b.height() - side) / 2f;
            float r = l + side, bo = t + side;
            float rad = side * 0.24f;          // 自适应图标那种圆角

            p.setAlpha(alpha);
            // 底板：深色外观冷蓝，浅色外观近白
            int[] colors = Ui.dark
                    ? new int[]{0xFF5C739E, 0xFF232C3E}
                    : new int[]{0xFFFFFFFF, 0xFFC9D4E8};
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(l, t, r, bo, colors, null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(l, t, r, bo, rad, rad, p);
            p.setShader(null);

            // 亮边
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, side * 0.015f));
            p.setColor(Ui.dark ? 0x44FFFFFF : 0x33000000);
            float in = Math.max(1f, side * 0.008f);
            cv.drawRoundRect(l + in, t + in, r - in, bo - in, rad, rad, p);
            p.setStyle(Paint.Style.FILL);

            // 太阳：左上
            p.setColor(0xFFFFC85C);
            cv.drawCircle(l + side * 0.34f, t + side * 0.35f, side * 0.135f, p);

            // 弯月：右下（两圆相减）
            float mx = l + side * 0.64f;
            float my = t + side * 0.65f;
            float mr = side * 0.19f;
            Path moon = new Path();
            moon.addCircle(mx, my, mr, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(mx + mr * 0.60f, my - mr * 0.30f, mr * 0.95f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            p.setColor(Ui.dark ? 0xFFEAF0FF : 0xFF46536B);
            cv.drawPath(moon, p);
        }

        @Override public void setAlpha(int a) { alpha = a; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { filter = cf; }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 「桌面设置」的图标：和 ThemeIcon 同一套版式（方形圆角 + 亮边），中间画齿轮。
     * 尺寸要点同 ThemeIcon：必须有固有尺寸，否则会被 FIT_CENTER 拉成横条。
     */
    static class SettingsIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;

        private static int px(float dp) {
            return Math.round(dp * android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density);
        }

        /**
         * 不给固有尺寸（返回 -1）：ImageView 会把绘制区域直接铺成格子大小，
         * 图标才会**跟着设置里的「图标大小」一起变大变小**。
         * 之前写死 48dp 的固有尺寸，格子变大后这个图标原地不动，
         * 和旁边真实应用的图标一比就明显偏小。
         */
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int side = Math.min(b.width(), b.height());
            if (side <= 0) return;
            float l = b.left + (b.width() - side) / 2f;
            float t = b.top + (b.height() - side) / 2f;
            float r = l + side, bo = t + side;
            float rad = side * 0.24f;

            p.setAlpha(alpha);
            // 底板：石墨蓝，和深浅模式图标区分
            int[] colors = Ui.dark
                    ? new int[]{0xFF6E7B90, 0xFF272E3C}
                    : new int[]{0xFFF2F5FA, 0xFFB9C4D6};
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(l, t, r, bo, colors, null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(l, t, r, bo, rad, rad, p);
            p.setShader(null);

            // 亮边
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, side * 0.015f));
            p.setColor(Ui.dark ? 0x44FFFFFF : 0x33000000);
            float in = Math.max(1f, side * 0.008f);
            cv.drawRoundRect(l + in, t + in, r - in, bo - in, rad, rad, p);

            // 齿轮：外圈 + 8 根齿 + 中孔
            float cx = l + side / 2f;
            float cy = t + side / 2f;
            float gearR = side * 0.26f;
            int gearColor = Ui.dark ? 0xFFE8EEF9 : 0xFF3D4859;
            p.setColor(gearColor);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.075f);
            cv.drawCircle(cx, cy, gearR, p);
            p.setStrokeWidth(side * 0.055f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float spokeIn = gearR;
            float spokeOut = gearR + side * 0.085f;
            for (int k = 0; k < 8; k++) {
                double ang = Math.toRadians(k * 45f);
                float sx = cx + (float) Math.cos(ang) * spokeIn;
                float sy = cy + (float) Math.sin(ang) * spokeIn;
                float ex = cx + (float) Math.cos(ang) * spokeOut;
                float ey = cy + (float) Math.sin(ang) * spokeOut;
                cv.drawLine(sx, sy, ex, ey, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            cv.drawCircle(cx, cy, side * 0.085f, p);
        }

        @Override public void setAlpha(int a) { alpha = a; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------ 快捷方式图标
    // 「重启桌面 / 返回 / 主页」三张图标和「深浅模式 / 桌面设置」同一套版式：
    // 方形圆角底板 + 亮边 + 一个自绘符号，底板色各不相同，扫一眼就能分辨。

    /**
     * 快捷图标基类：底板 / 亮边 / 尺寸规则都在这里，子类只给"底板色"和"画什么符号"。
     *
     * 尺寸要点（踩过的坑）：必须返回 -1 的固有尺寸，ImageView 才会把绘制区域
     * 铺成格子大小，图标才能跟着设置里的「图标大小」一起变；写死固有尺寸会导致
     * 格子变大后这个图标原地不动，和旁边真实应用一比明显偏小。
     */
    abstract static class ShortcutIcon extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int alpha = 255;

        /** 底板渐变（TL→BR），按深浅色各一套 */
        abstract int[] plate();

        /** 画符号：(l,t) 是正方形左上角，side 是边长 */
        abstract void glyph(Canvas cv, float l, float t, float side, Paint p);

        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int side = Math.min(b.width(), b.height());
            if (side <= 0) return;
            float l = b.left + (b.width() - side) / 2f;
            float t = b.top + (b.height() - side) / 2f;
            float r = l + side, bo = t + side;
            float rad = side * 0.24f;

            p.setAlpha(alpha);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(l, t, r, bo, plate(), null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(l, t, r, bo, rad, rad, p);
            p.setShader(null);

            // 亮边（和另两张图标一致）
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, side * 0.015f));
            p.setColor(Ui.dark ? 0x44FFFFFF : 0x33000000);
            float in = Math.max(1f, side * 0.008f);
            cv.drawRoundRect(l + in, t + in, r - in, bo - in, rad, rad, p);

            p.setStyle(Paint.Style.FILL);
            glyph(cv, l, t, side, p);
        }

        @Override public void setAlpha(int a) { alpha = a; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 「重启桌面」：暖橙底板 + 一圈顺时针箭头 */
    static class RestartIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFFB0763F, 0xFF3A2714}
                    : new int[]{0xFFFDC894, 0xFFD9A05A};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            float cx = l + side / 2f, cy = t + side / 2f;
            float r = side * 0.23f;
            p.setColor(Ui.dark ? 0xFFFBEEDC : 0xFF4A3623);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.085f);
            p.setStrokeCap(Paint.Cap.ROUND);
            // 起点 -55°、扫 290°，留个缺口给箭头
            cv.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r),
                    -55f, 290f, false, p);
            // 箭头：挂在圆弧起点上
            float ax = cx + (float) Math.cos(Math.toRadians(-55f)) * r;
            float ay = cy + (float) Math.sin(Math.toRadians(-55f)) * r;
            Path tri = new Path();
            tri.moveTo(ax - side * 0.10f, ay - side * 0.02f);
            tri.lineTo(ax + side * 0.11f, ay - side * 0.06f);
            tri.lineTo(ax + side * 0.01f, ay + side * 0.12f);
            tri.close();
            p.setStyle(Paint.Style.FILL);
            cv.drawPath(tri, p);
        }
    }

    /** 「返回」：紫灰底板 + 一个左尖括号 */
    static class BackIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF7C7396, 0xFF2A2536}
                    : new int[]{0xFFE9E4F4, 0xFFB9B1CE};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            p.setColor(Ui.dark ? 0xFFF1EEF9 : 0xFF3B3550);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.10f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            path.moveTo(l + side * 0.61f, t + side * 0.27f);
            path.lineTo(l + side * 0.39f, t + side * 0.50f);
            path.lineTo(l + side * 0.61f, t + side * 0.73f);
            cv.drawPath(path, p);
        }
    }

    /** 「主页」：墨绿底板 + 一间小房子 */
    static class HomeIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF4E8F82, 0xFF1B332E}
                    : new int[]{0xFFDCF3EC, 0xFF9AC5B8};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            p.setColor(Ui.dark ? 0xFFE4F7F1 : 0xFF1E4A40);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.085f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // 屋顶（一个"人"字，两侧出檐）
            Path roof = new Path();
            roof.moveTo(l + side * 0.21f, t + side * 0.49f);
            roof.lineTo(l + side * 0.50f, t + side * 0.23f);
            roof.lineTo(l + side * 0.79f, t + side * 0.49f);
            cv.drawPath(roof, p);
            // 屋身
            cv.drawRoundRect(l + side * 0.30f, t + side * 0.48f,
                    l + side * 0.70f, t + side * 0.78f, side * 0.06f, side * 0.06f, p);
        }
    }

    /** 「息屏」：深靛底板 + 电源符号（圆环 + 顶部缺口 + 竖线） */
    static class SleepIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF5E6C93, 0xFF1B2133}
                    : new int[]{0xFFDDE3F6, 0xFFA7B3D4};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            float cx = l + side * 0.5f, cy = t + side * 0.55f;
            float r = side * 0.21f;
            p.setColor(Ui.dark ? 0xFFEFF2FF : 0xFF242B45);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.085f);
            p.setStrokeCap(Paint.Cap.ROUND);
            // 从 -60° 起扫 300°，顶部留个缺口给竖线
            cv.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r),
                    -60f, 300f, false, p);
            cv.drawLine(cx, t + side * 0.26f, cx, cy - r * 0.15f, p);
        }
    }

    /** 「最近任务」：石板灰底板 + 两张叠放的卡片（后一张淡一点） */
    static class RecentsIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF727E8B, 0xFF23282E}
                    : new int[]{0xFFE3E8EE, 0xFFAAB6C3};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            float rad = side * 0.055f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.062f);
            // 后面那张：只留上半圈，看起来像被压住
            p.setColor(Ui.dark ? 0x66EFF3F8 : 0x66232A32);
            cv.drawRoundRect(l + side * 0.27f, t + side * 0.24f,
                    l + side * 0.65f, t + side * 0.58f, rad, rad, p);
            // 前面那张
            p.setColor(Ui.dark ? 0xFFEFF3F8 : 0xFF232A32);
            cv.drawRoundRect(l + side * 0.35f, t + side * 0.38f,
                    l + side * 0.73f, t + side * 0.76f, rad, rad, p);
        }
    }

    /** 喇叭底座（两个音量图标共用）：矩形 + 梯形 */
    private static void speaker(Canvas cv, float l, float t, float side, float alpha, Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setAlpha((int) (255 * alpha));
        Path sp = new Path();
        sp.moveTo(l + side * 0.22f, t + side * 0.42f);
        sp.lineTo(l + side * 0.32f, t + side * 0.42f);
        sp.lineTo(l + side * 0.43f, t + side * 0.29f);
        sp.lineTo(l + side * 0.43f, t + side * 0.71f);
        sp.lineTo(l + side * 0.32f, t + side * 0.58f);
        sp.lineTo(l + side * 0.22f, t + side * 0.58f);
        sp.close();
        cv.drawPath(sp, p);
        p.setAlpha(255);
    }

    /** 「音量 +」：青底板 + 喇叭 + 加号 */
    static class VolUpIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF3F8FA8, 0xFF0F2B36}
                    : new int[]{0xFFD6F0F8, 0xFF8FC4D6};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            int tone = Ui.dark ? 0xFFE9F8FF : 0xFF12323D;
            p.setColor(tone);
            speaker(cv, l, t, side, 1f, p);
            p.setColor(tone);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.075f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float ax = l + side * 0.66f, ay = t + side * 0.5f, arm = side * 0.10f;
            cv.drawLine(ax - arm, ay, ax + arm, ay, p);
            cv.drawLine(ax, ay - arm, ax, ay + arm, p);
        }
    }

    /** 「音量 -」：紫底板 + 喇叭 + 减号 */
    static class VolDownIcon extends ShortcutIcon {
        @Override int[] plate() {
            return Ui.dark ? new int[]{0xFF8A6FB0, 0xFF2A1F3A}
                    : new int[]{0xFFEDE3F7, 0xFFBFA9DA};
        }

        @Override void glyph(Canvas cv, float l, float t, float side, Paint p) {
            int tone = Ui.dark ? 0xFFF6EFFF : 0xFF2E2140;
            p.setColor(tone);
            speaker(cv, l, t, side, 1f, p);
            p.setColor(tone);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(side * 0.075f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float ax = l + side * 0.66f, ay = t + side * 0.5f, arm = side * 0.10f;
            cv.drawLine(ax - arm, ay, ax + arm, ay, p);
        }
    }
}
