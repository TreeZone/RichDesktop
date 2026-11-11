package com.cz.richdesk;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 多画中画控制器：
 *  - 按配置创建 1~3 个面板，接管开/关与槽位路由；
 *  - 旋转、DPI 变化时重建虚拟屏并把应用重新拉回来（解决画面只显示一部分）；
 *  - 槽位规则：显式指定 > 已在该屏 > 第一个空闲屏 > 第 1 屏。
 */
public class PipManager implements PipPane.Callback, PaneBox.Listener {

    private static final String TAG = "PipDesk";

    public interface Listener {
        void onOpened(int index, String pkg);
        void onClosed(int index);
    }

    private final Context app;
    private final PaneBox container;
    private final List<PipPane> panes = new ArrayList<>();
    private final TouchBridge touch;
    private Listener listener;
    private int dpi;
    private int cornerDp;
    /** 每块画中画的长度权重（与 panes 同序，0 = 折叠隐藏） */
    private float[] weights = new float[0];
    /** 「单画」临时布局：未指定屏位的应用按单画打开时置位，恢复原布局后清掉 */
    private boolean singleMode;
    private float[] savedWeights;   // 进入单画前的比例（恢复用）
    private String singleAppPkg;    // 单画此刻显示的应用（判断"临时应用退出了没"用）
    /**
     * 单画**专用**面板：不复用主页画中画的任何一块虚拟屏。
     *
     * 为什么不复用（血泪）：借用活动画中画时，单画期间那块屏的应用被换成了临时应用，
     * 恢复布局要么"把原应用重新拉起来"（多一次冷启动，观感是全屏闪一下），
     * 要么"什么都不做"（那块屏就停在临时应用/它退到后台后露出的别的东西上）。
     * 两者都不对，而且借用 pane 0 时还会因为 z 序把另一块屏悬浮到全屏之上。
     *
     * 独立一块屏之后：主页那 N 块屏**全程不被替换**，单画进出只是"只显示它/显示全部"，
     * 虚拟屏一个都不用动 —— 恢复是零成本的，也不存在闪烁。
     * 代价是多一块虚拟屏（只有真的用过单画才会创建，见 ensureSinglePane）。
     */
    private PipPane singlePane;

    public PipManager(Context ctx, PaneBox container) {
        this.app = ctx.getApplicationContext();
        this.container = container;
        this.touch = TouchBridge.get(app);
        this.dpi = Prefs.getDpi(ctx);
        this.cornerDp = Prefs.getPipCorner(ctx);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int count() {
        return panes.size();
    }

    public PipPane pane(int i) {
        return (i >= 0 && i < panes.size()) ? panes.get(i) : null;
    }

    public boolean hasAny() {
        for (PipPane p : panes) if (p.isBusy()) return true;
        return false;
    }

    public String pkgAt(int i) {
        PipPane p = pane(i);
        return p == null ? null : p.pkg();
    }

    public int indexOfPkg(String pkg) {
        for (int i = 0; i < panes.size(); i++) {
            if (pkg != null && pkg.equals(panes.get(i).pkg())) return i;
        }
        return -1;
    }

    /** 面板当前的位置序号（调换顺序后跟着变） */
    public int positionOf(PipPane p) {
        return panes.indexOf(p);
    }

    /**
     * 与相邻画中画调换位置（dir=-1 左/上，+1 右/下）。
     * 到边界循环到另一端。只交换视图位置和面板序号，虚拟屏完全不动
     * （屏内应用无感知，不会重建、不会闪）。
     *
     * 比例权重跟着面板一起走：大屏换到别的槽位后，那块屏还是大的。
     *
     * @return 是否真的换了
     */
    public boolean swap(int index, int dir) {
        int n = panes.size();
        if (n < 2 || index < 0 || index >= n) return false;
        int j = ((index + dir) % n + n) % n;
        if (j == index) return false;
        PipPane a = panes.get(index);
        PipPane b = panes.get(j);
        panes.set(index, b);
        panes.set(j, a);
        // 面板序号跟着位置走（「画中画 1」永远是从左/上数第一块）
        int t = a.index;
        a.index = b.index;
        b.index = t;
        if (index < weights.length && j < weights.length) {
            float tw = weights[index];
            weights[index] = weights[j];
            weights[j] = tw;
            Prefs.setPaneWeights(app, weights);
        }
        // 视图顺序只改逻辑顺序（不摘挂 → 不销毁 Surface、不重建）
        applyOrientation();
        Log.i(TAG, "swap pane " + index + " <-> " + j);
        return true;
    }

    // ------------------------------------------------------------ 构建 / 重建

    /** 按屏数重建面板视图（屏数或圆角变化时调用） */
    public void build(int paneCount) {
        release();
        panes.clear();
        singleMode = false;
        savedWeights = null;
        singleAppPkg = null;
        if (singlePane != null) {
            singlePane.release();
            singlePane = null;
        }
        container.removeAllViews();
        dpi = Prefs.getDpi(app);
        cornerDp = Prefs.getPipCorner(app);
        for (int i = 0; i < paneCount; i++) {
            PipPane p = new PipPane(app, i, dpi, cornerDp, this);
            container.addView(p.root);
            panes.add(p);
        }
        weights = Prefs.getPaneWeights(app, paneCount);
        // 槽位号≥paneCount 的虚拟屏（以前是单画那块、或屏数从多改少）真释放掉，
        // 否则它们会一直留在 DisplayManager 里当"野屏"，白白占合成器开销。
        PipDisplays.releaseFrom(paneCount);
        container.setListener(this);
        applyOrientation();
        // 存下来的比例可能是脏的（改过屏数/版本升级）：收敛到支持的那几种
        weights = sanitize(weights);
        container.setWeights(weights);
        Prefs.setPaneWeights(app, weights);
        syncCollapsed();
    }

    /**
     * 单画专用屏：第一次真要用时创建（不预建，没开单画模式的用户不付这份开销）。
     * 之后一直复用 —— PipPane.close() 只把应用顶回后台、保留虚拟屏，所以再开是秒级。
     */
    private PipPane ensureSinglePane() {
        if (singlePane != null) return singlePane;
        singlePane = new PipPane(app, panes.size(), dpi, cornerDp, this);
        container.addView(singlePane.root);
        FileLog.append("单画：创建专用虚拟屏（主页画中画 " + panes.size() + " 块不动）");
        return singlePane;
    }

    /** 单画面板（没进入过单画时是 null）——返回键 / 存活检测要用 */
    public PipPane singlePane() {
        return singlePane;
    }

    /**
     * 按容器方向重排版：横向 = 从左到右，纵向 = 从上到下。
     * 只在「逻辑顺序」上排，不摘挂视图（摘挂会销毁 TextureView 的 Surface，
     * 虚拟屏跟着重建 —— 调换顺序就成了一次整屏重启）。
     */
    public void applyOrientation() {
        container.setGapPx(Ui.dp(app, Ui.PANE_GAP_DP));
        // 单画临时布局：重排版时仍然只显示那一块（否则旋转会瞬间露出全部画中画）
        if (singleMode && singlePane != null) {
            List<View> only = new ArrayList<>(1);
            only.add(singlePane.root);
            container.setOrder(only);
            container.setWeights(new float[]{1f});
            return;
        }
        List<View> order = new ArrayList<>(panes.size());
        for (PipPane p : panes) order.add(p.root);
        container.setOrder(order);
        container.setWeights(weights);
        syncCollapsed();
    }

    /**
     * 轻点玻璃分隔条：交换它两侧两块画中画的**内容**（需求）。
     *
     * 与 {@link #swap} 的区别：这里**不动比例**。分隔条在哪儿就留在哪儿，
     * 只是把两边的应用换个位置 —— 用户点一下不会看到分隔条自己跳走。
     *
     * 如果这一侧已经被折叠（折叠屏的竖条贴在屏幕边缘），轻点当成"把那块屏放回来"，
     * 这是折叠状态最自然的手势（不用反方向慢慢拖）。
     */
    @Override
    public void onHandleTap(int pair) {
        int n = panes.size();
        if (pair < 0 || pair + 1 >= n) return;
        if (isCollapsed(pair) || isCollapsed(pair + 1)) {
            Log.i(TAG, "竖条轻点：边缘位置，恢复全部画中画");
            resetRatio();
            return;
        }
        swapContents(pair, pair + 1);
    }

    /** 只交换两块屏上的应用（顺序互换），分屏比例保持不变 */
    public boolean swapContents(int i, int j) {
        int n = panes.size();
        if (i < 0 || j < 0 || i >= n || j >= n || i == j) return false;
        PipPane a = panes.get(i), b = panes.get(j);
        panes.set(i, b);
        panes.set(j, a);
        int t = a.index;
        a.index = b.index;
        b.index = t;
        // 视图顺序只改逻辑顺序（不摘挂 → 不销毁 Surface、不重建）
        applyOrientation();
        Log.i(TAG, "竖条轻点：交换画中画 " + i + " <-> " + j);
        return true;
    }

    public void setCorner(int dp) {
        cornerDp = dp;
        for (PipPane p : panes) p.setCorner(dp);
        if (singlePane != null) singlePane.setCorner(dp);
    }

    /** 深浅色外观变化：所有面板一起刷 */
    public void refreshAppearance() {
        for (PipPane p : panes) p.refreshAppearance();
        if (singlePane != null) singlePane.refreshAppearance();
        refreshHandles();
    }

    /** 性能模式切换：分隔条在玻璃渐变 / 纯色之间重画 */
    public void refreshHandles() {
        if (container != null) container.refreshHandles();
    }

    public void setDpi(int value) {
        dpi = value;
        for (PipPane p : panes) p.setDpi(value);
        if (singlePane != null) singlePane.setDpi(value);
    }

    /** 屏内渲染分辨率比例（1.0 标准 / 0.75 / 0.5）：所有面板一起调 */
    public void setRenderScale(float v) {
        for (PipPane p : panes) p.setRenderScale(v);
        if (singlePane != null) singlePane.setRenderScale(v);
    }

    /** 旋转等尺寸大变化后：所有有内容的屏重建虚拟屏（重新排版、重算 letterbox） */
    public void postRebuildAll(long delayMs) {
        for (PipPane p : panes) p.postRebuild(delayMs);
        if (singlePane != null) singlePane.postRebuild(delayMs);
    }

    // ------------------------------------------------------------ 分屏比例

    /** 当前可见（未被折叠）的屏数 */
    public int visibleCount() {
        int n = 0;
        for (float w : weights) if (w > 0.0001f) n++;
        return n;
    }

    public boolean isCollapsed(int i) {
        return i < 0 || i >= weights.length || weights[i] <= 0.0001f;
    }

    public float[] weights() {
        return weights.clone();
    }

    /**
     * 收敛到「支持的那几种布局」，其它一律回等宽：
     *   2 屏 —— 1:1 / 1:2 / 2:1；3 屏 —— 1:1:1，或折叠一屏后的 1:2 / 2:1。
     * 只在启动/改屏数/改比例时跑，拖动过程中不跑（否则手感会被瞬间掰回）。
     */
    private float[] sanitize(float[] w) {
        int n = panes.size();
        float[] r = new float[n];
        int vis = 0;
        for (int i = 0; i < n; i++) {
            r[i] = (w != null && i < w.length) ? Math.max(0f, w[i]) : 1f;
            if (r[i] > 0.0001f) vis++;
        }
        if (n <= 1 || vis <= 1) {
            // 只剩一屏可看等于桌面空了一半 —— 直接恢复全部等宽
            for (int i = 0; i < n; i++) r[i] = 1f;
            return r;
        }
        if (vis == n) {
            if (n >= 3) {
                for (int i = 0; i < n; i++) r[i] = 1f;    // 3 屏只支持 1:1:1
            } else {
                snapTwo(r, 0, 1);
            }
            return r;
        }
        // 有折叠：只可能剩两块可见
        // 折叠的必须是最边上那块（需求：中间的屏永远不被挤扁）——
        // 老版本可能存下 "1,0,1"（中间被折叠），这里把 0 挪到最后修好
        if (n == 3 && r[1] <= 0.0001f) {
            r[1] = r[2];
            r[2] = 0f;
        }
        int a = -1, b = -1;
        for (int i = 0; i < n; i++) {
            if (r[i] <= 0.0001f) continue;
            if (a < 0) a = i;
            else if (b < 0) b = i;
        }
        if (a >= 0 && b >= 0) snapTwo(r, a, b);
        return r;
    }

    /** 两块屏吸附到 1:2 / 1:1 / 2:1 里最近的一档 */
    private static void snapTwo(float[] w, int i, int j) {
        float a = w[i], b = w[j];
        if (a <= 0 || b <= 0) return;
        float ratio = a / (a + b);
        float best = 0.5f;
        float diff = Float.MAX_VALUE;
        for (float v : new float[]{1f / 3f, 0.5f, 2f / 3f}) {
            float d = Math.abs(v - ratio);
            if (d < diff) {
                diff = d;
                best = v;
            }
        }
        float sum = a + b;
        w[i] = sum * best;
        w[j] = sum * (1f - best);
    }

    /** 恢复全部画中画为等宽（把折叠的屏放回来） */
    public void resetRatio() {
        float[] w = new float[panes.size()];
        for (int i = 0; i < w.length; i++) w[i] = 1f;
        Log.i(TAG, "分屏比例 -> 全部等宽");
        container.animateTo(w);
    }

    /**
     * 给第 slot 条分隔（可见序号）左右/上下两块屏设比例。
     *
     * @param mode 0 = 等宽 1:1，1 = 1:2（前小后大），2 = 2:1（前大后小）
     */
    public void applyPreset(int slot, int mode) {
        int n = panes.size();
        float[] w = weights.clone();
        // 三屏都露着时，"1:2 / 2:1" 就是隐藏掉一块边缘屏后的两屏布局
        // （和拖动分隔条拖到头得到的结果一致）：
        //   1:2 → 第 1 屏小、第 2 屏大，第 3 屏收起；2:1 → 第 1 屏收起，后两块 2:1
        if (n == 3 && visibleCount() >= 3) {
            for (int i = 0; i < n; i++) w[i] = 1f;
            if (mode == 1) {
                w[1] = 2f;
                w[2] = 0f;
            } else if (mode == 2) {
                w[0] = 0f;
                w[1] = 2f;
            }
            Log.i(TAG, "分屏比例 三屏 -> mode=" + mode);
            container.animateTo(w);
            return;
        }
        int a = -1, b = -1, k = 0;
        for (int i = 0; i < w.length; i++) {
            if (w[i] <= 0.0001f) continue;
            if (k == slot) {
                a = i;
                for (int j = i + 1; j < w.length; j++) {
                    if (w[j] > 0.0001f) {
                        b = j;
                        break;
                    }
                }
                break;
            }
            k++;
        }
        if (a < 0 || b < 0) return;
        float t = mode == 1 ? 1f / 3f : (mode == 2 ? 2f / 3f : 0.5f);
        float sum = w[a] + w[b];
        if (sum <= 0) sum = 2f;
        w[a] = sum * t;
        w[b] = sum * (1f - t);
        Log.i(TAG, "分屏比例 slot=" + slot + " mode=" + mode);
        container.animateTo(w);
    }

    /** 打开应用前先把它所在的屏从折叠状态放回来（否则应用开了也看不见） */
    private void restoreIfCollapsed(int idx) {
        if (idx < 0 || idx >= weights.length || weights[idx] > 0.0001f) return;
        float[] w = weights.clone();
        w[idx] = 1f;
        w = sanitize(w);
        Log.i(TAG, "pane " + idx + " 从折叠恢复");
        container.animateTo(w);
    }

    /** 调试用：直接驱动分隔条拖动（没有手指时验证比例拖动链路） */
    public void debugDrag(int pair, float delta) {
        container.debugDrag(pair, delta);
    }

    /** 调试用：直接模拟轻点分隔条（验证交换顺序） */
    public void debugTapHandle(int pair) {
        container.debugTap(pair);
    }

    private void syncCollapsed() {
        for (int i = 0; i < panes.size(); i++) {
            panes.get(i).setCollapsed(isCollapsed(i));
        }
    }

    // ---- PaneBox.Listener：拖动/动画时同步状态 ----

    @Override
    public void onRatioChanging(float[] w) {
        weights = w;
        // 动画期间冻结虚拟屏 resize：只让纹理拉伸，结束时一次性对齐
        for (PipPane p : panes) p.setDeferResize(true);
        syncCollapsed();
    }

    @Override
    public void onRatioSettled(float[] w) {
        if (singleMode) return;   // 单画布局没有分隔条，比例回调一律忽略
        weights = sanitize(w);
        container.setWeights(weights);
        syncCollapsed();
        Prefs.setPaneWeights(app, weights);
        // 等这一帧布局落定再对齐虚拟屏尺寸：动画结束的瞬间纹理还是上一帧的大小，
        // 立刻 resize 会差几个像素（而且最后还会被布局回调再改一次）
        for (PipPane p : panes) p.setDeferResize(true);
        container.post(() -> {
            for (PipPane p : panes) p.setDeferResize(false);   // 内部按最终尺寸对齐虚拟屏
        });
        Log.i(TAG, "分屏比例落定 " + java.util.Arrays.toString(weights));
    }

    // ------------------------------------------------------------ 打开

    /** 这个应用是否显式指定了画中画屏位（「指定屏位」 + 「每屏默认应用」都算） */
    public boolean isAssigned(String pkg) {
        return paneOf(pkg) >= 0;
    }

    /** 绑定屏位（0 起，-1 = 未绑定）。仅供日志/调试用。 */
    public int paneOfPkg(String pkg) {
        return paneOf(pkg);
    }

    /**
     * 应用归属的屏位（0 起），没归属返回 -1：
     *   1) 长按菜单「指定画中画位置」（pane_map）；
     *   2) 设置里「每屏默认应用」（pane_default_N）—— 开机会自动在那一屏打开它，
     *      用户同样认为它是"指定了屏位"的应用，单画布局下点它必须恢复原布局。
     */
    private int paneOf(String pkg) {
        if (pkg == null) return -1;
        int explicit = Prefs.getPaneOf(app, pkg);   // 1 起
        if (explicit >= 1 && explicit <= panes.size()) return explicit - 1;
        for (int i = 1; i <= panes.size(); i++) {
            if (pkg.equals(Prefs.getDefaultApp(app, i))) return i - 1;
        }
        return -1;
    }

    /**
     * 槽位挑选：显式指定 > 该应用已打开 > **活动画中画**。
     * 未指定屏位的应用一律在「活动画中画」打开（设置页可配置，默认最后一屏）；
     * 画中画顺序固定：横排从左到右、竖排从上到下依次是 1/2/3。
     */
    public int pickPane(String pkg) {
        int own = paneOf(pkg);                      // 指定屏位 / 每屏默认应用 → 回归属的屏
        if (own >= 0) return own;
        int existing = indexOfPkg(pkg);
        if (existing >= 0) return existing;
        return Prefs.getActivePane(app, panes.size()) - 1;
    }

    /** preferred：0 = 自动；1..n = 指定第几屏 */
    public boolean open(String pkg, int preferred) {
        if (panes.isEmpty()) return false;
        // 单画布局下的两种走向：
        //   已绑定（指定屏位 / 每屏默认）→ 恢复原布局，回它自己那块屏打开；
        //   未绑定 → 继续留在单画里，只换单画屏上的应用（主页那几块屏完全不动）。
        if (singleMode) {
            if ((preferred >= 1 && preferred <= panes.size()) || isAssigned(pkg)) {
                restoreSingle();
            } else {
                PipPane sp = ensureSinglePane();
                // 同一个应用别开两份：主页画中画里那份先收掉
                int dup = indexOfPkg(pkg);
                if (dup >= 0) panes.get(dup).close();
                sp.open(pkg);
                singleAppPkg = pkg;
                return true;
            }
        }
        int idx = (preferred >= 1 && preferred <= panes.size()) ? preferred - 1 : pickPane(pkg);
        PipPane p = panes.get(idx);
        // 单画屏里还留着同一个应用（单画刚退出、应用还没被收掉）→ 收掉，别开两份
        if (singlePane != null && pkg.equals(singlePane.pkg())) singlePane.close();
        // 同一应用已开在别的屏：先把它收掉，避免两份
        int other = indexOfPkg(pkg);
        if (other >= 0 && other != idx) panes.get(other).close();
        restoreIfCollapsed(idx);      // 被折叠的屏先放回来，否则应用开在看不见的屏上
        p.open(pkg);
        return true;
    }

    /**
     * 「单画中画」打开：**用单画专用屏**铺满整个画中画区域。
     *
     * 关键点：不碰主页那 N 块画中画（不换应用、不重建虚拟屏）。所以恢复布局时
     * 什么都不用重开 —— 屏幕上那两块屏从头到尾就是原来那两个应用，不存在
     * "先露出被顶掉的应用再切回来"的闪烁，也不可能出现 z 序错位。
     *
     * @return 单画屏不在 panes 里，统一返回 -1（调用方不必据此更新 lastActivePane）
     */
    public int openSingle(String pkg) {
        if (panes.isEmpty()) return -1;
        // 已绑定的应用不该走单画（openPip 会先恢复布局）；兜底回它自己的屏
        int own = paneOf(pkg);
        if (own >= 0) {
            open(pkg, own + 1);
            return own;
        }
        PipPane sp = ensureSinglePane();
        if (!singleMode) {
            savedWeights = weights.clone();
            singleMode = true;
            Log.i(TAG, "进入单画布局（专用屏 " + sp.index + "），主页比例 "
                    + java.util.Arrays.toString(savedWeights));
            FileLog.append("单画：专用屏 display=" + sp.displayId()
                    + " 主页比例=" + java.util.Arrays.toString(savedWeights));
        }
        // 主页画中画里跑着同一个应用 → 收掉，避免两份
        int dup = indexOfPkg(pkg);
        if (dup >= 0) panes.get(dup).close();
        sp.open(pkg);
        singleAppPkg = pkg;
        // 视觉：只显示这一块屏（order 只放一个 → 没有分隔条、铺满；其余屏由 PaneBox 设为 INVISIBLE）
        List<View> only = new ArrayList<>(1);
        only.add(sp.root);
        container.setOrder(only);
        container.setWeights(new float[]{1f});
        return -1;
    }

    /**
     * 恢复单画前的布局：把主页那 N 块屏重新摆回来（内容一直没动过，所以是瞬时的）。
     *
     * 【单画屏从此常驻】以前这里会在 1.2 秒后把单画屏里的应用收掉（close），
     * 下次再单画就要重新冷启动一个应用，三秒起步。现在**什么都不做**：
     * 虚拟屏留着、应用的任务也留在后台（最低资源：只是屏不显示，没有渲染），
     * 再点同一个/另一个未绑定应用时直接 launchOn 带到前台，基本是即时的。
     * 换应用由 openSingle 里的 trimTasks 负责把旧任务清掉，不会越积越多。
     */
    private void restoreSingle() {
        if (!singleMode) return;
        singleMode = false;
        singleAppPkg = null;
        float[] saved = savedWeights;
        savedWeights = null;
        List<View> all = new ArrayList<>(panes.size());
        for (PipPane p : panes) all.add(p.root);
        container.setOrder(all);
        weights = (saved != null && saved.length == panes.size())
                ? saved.clone() : Prefs.getPaneWeights(app, panes.size());
        container.setWeights(weights);
        syncCollapsed();
        Log.i(TAG, "退出单画布局：恢复比例 " + java.util.Arrays.toString(weights));
        FileLog.append("恢复布局：比例=" + java.util.Arrays.toString(weights)
                + "（主页屏未被动过，单画屏保留待复用）");
    }

    public void openAuto(String pkg) {
        open(pkg, 0);
    }

    public void close(int index) {
        PipPane p = pane(index);
        if (p != null) p.close();
    }

    /** 当前是否处于「单画」临时布局 */
    public boolean inSingleMode() {
        return singleMode;
    }

    /** 单画临时布局此刻显示的应用（不在单画返回 null）——用于判断"这个临时应用退出了没" */
    public String singleAppPkg() {
        if (!singleMode || singlePane == null) return null;
        return singlePane.pkg();
    }

    /** 这块屏的段落根本不在 dumpsys 里（displayId 过期 / 读不到） */
    private static final String TOP_UNKNOWN = "##UNKNOWN##";
    /** 这块屏的段落存在，但没有任何前台应用（屏是空的） */
    private static final String TOP_EMPTY = "##EMPTY##";

    /**
     * 这块虚拟屏上当前的前台应用包名。
     *   返回 null      = 读不到（命令失败 / 段落不存在），调用方**不要**据此改布局
     *   返回 ""        = 段落存在但没有 topResumedActivity（这屏上是空的）
     *   返回 "<pkg>"   = 前台就是这个应用
     *
     * 【为什么不用 getRunningTasks】这台机器（HyperOS 13）返回的 RunningTaskInfo
     * 里 displayId 一律是 null（实测 16 条任务全为 null），没法把 task 归到某块虚拟屏，
     * 也分不清"退到后台"和"已退出" —— 而用户要的恰恰是"应用不在前面了就把单画收掉"。
     * dumpsys 的每块 Display 段落里有 topResumedActivity，一次约 60ms，比 task 查询准得多。
     *
     * **子进程 + 反射，必须在后台线程。**
     */
    public String topPkgOnDisplay(int displayId) {
        if (displayId < 0) return null;
        String cmd = "dumpsys activity activities 2>/dev/null | awk -v h='Display #" + displayId
                + " (activities' 'index($0,h)==1{inb=1;found=1;next}"
                + " inb&&substr($0,1,9)==\"Display #\"{inb=0}"
                + " inb&&/topResumedActivity/{print;printed=1;exit}"
                + " END{if(!found)print \"" + TOP_UNKNOWN + "\";"
                + " else if(!printed)print \"" + TOP_EMPTY + "\"}'";
        String out;
        try {
            out = Shell.capture(cmd);
        } catch (Throwable t) {
            return null;
        }
        if (out == null) return null;
        out = out.trim();
        int nl = out.indexOf('\n');
        if (nl > 0) out = out.substring(0, nl).trim();
        if (out.equals(TOP_UNKNOWN)) return null;      // 段落没有 → displayId 可能过期，别动
        if (out.equals(TOP_EMPTY)) return "";          // 有段落、没前台应用 = 这屏空着
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\su\\d+\\s([A-Za-z0-9_.]+)/").matcher(out);
        if (m.find()) return m.group(1);
        return null;                                   // 命令失败/格式不认识 → 别动布局
    }

    /** 恢复单画前的布局（不在单画布局时什么都不做）。 */
    public void restoreSingleLayout() {
        if (!singleMode) return;
        Log.i(TAG, "restoreSingleLayout：恢复原布局");
        restoreSingle();
    }

    /**
     * 单画的临时应用自己走了（按返回退出 / 只是退到后台 / 崩了）：恢复原布局。
     *
     * 现在单画用的是**专用虚拟屏**，主页那几块屏从头到尾没被替换过，
     * 所以这里不需要"把原应用开回去"这种补救 —— 布局一恢复，画面上就是原来的双画，
     * 零延迟、零闪烁。只剩"把单画屏里的应用收掉"这件事，由 restoreSingle 内部延时做。
     */
    public void restoreSingleAfterExit() {
        if (!singleMode) return;
        restoreSingle();
    }

    /** 单画布局显示的是第几屏（不再借用主页画中画，恒为 -1，仅为兼容保留） */
    public int singleIndex() {
        return -1;
    }

    public void closeAll() {
        singleMode = false;
        savedWeights = null;
        singleAppPkg = null;
        for (PipPane p : panes) p.close();
        if (singlePane != null) singlePane.close();
    }

    /**
     * 全屏打开。路由到「跑着这个应用」的画中画（没有才用第 1 块）：
     * 只有路由对了，回到桌面时才能把应用搬回原来的屏。
     */
    public void launchFullScreen(String pkg) {
        if (panes.isEmpty()) return;
        int own = indexOfPkg(pkg);
        (own >= 0 ? panes.get(own) : panes.get(0)).launchFullScreen(pkg);
    }

    /** 回到桌面时调用：把全屏打开过的应用搬回各自的虚拟屏继续显示 */
    public void bringBackFullscreened() {
        for (PipPane p : panes) p.bringBackIfFullscreened();
    }

    /** 全局配置（夜间模式等）变化后调用：逐屏校验屏内应用还在不在本屏（见 PipPane.reensureTask） */
    public void reensureAll() {
        for (PipPane p : panes) p.reensureTask();
    }

    public void release() {
        for (PipPane p : panes) p.release();
        // 单画专用屏（PipDesk-2）也必须释放：build()/Activity 销毁都走这里，
        // 漏掉它就等于每次重建都多泄漏一块虚拟屏（车机上实测 6 块并存）。
        if (singlePane != null) {
            singlePane.release();
            singlePane = null;
        }
    }

    /** 包外部启动的 Intent，供全屏兜底使用 */
    public void startFullScreenIntent(Intent i) {
        if (panes.isEmpty()) return;
        panes.get(0).launchFullScreen(i.getPackage());
    }

    // ------------------------------------------------------------ 触摸转发

    /**
     * 把面板上的事件转给对应虚拟屏。
     *
     * 坐标必须从「面板视图空间」换算到「虚拟屏像素空间」：
     * 视图尺寸和虚拟屏尺寸在绝大多数时候相同（1:1），但旋转/insets 变化的瞬间
     * 视图已经变了、虚拟屏还是旧尺寸，此时不换算就会点偏甚至点到屏外被 Dispatcher 丢掉。
     */
    public void dispatch(int index, android.view.MotionEvent ev) {
        PipPane p = pane(index);
        if (p != null) dispatch(p, ev);
    }

    /** 按面板对象转发（调换顺序后监听器仍指向原面板，位置实时取） */
    public void dispatch(PipPane p, android.view.MotionEvent ev) {
        if (p == null) return;
        int display = p.displayId();
        if (display < 0) return;
        // 统一走矩阵映射（视图本地 → 虚拟屏像素），并按屏尺寸钳制越界点
        touch.send(ev, display, p.viewToDisplayMatrix(),
                p.displayWidth(), p.displayHeight());
    }

    public void primeTouch() {
        touch.prime();
    }

    /**
     * 长按菜单接管这次触摸：给屏内应用补一帧 CANCEL。
     * 之后 MainActivity 会停止转发本次手势的 MOVE/UP —— 这样长按就只弹菜单，
     * 不会顺带在屏内应用里触发一次点击。
     */
    public void cancelTouch() {
        if (touch != null) touch.cancelCurrent();
    }

    /** 调试用：面板 index 的视图尺寸 / 虚拟屏尺寸 */
    public String displaySize(int index) {
        PipPane p = pane(index);
        if (p == null) return "-";
        return p.viewWidth() + "x" + p.viewHeight() + "/" + p.displayWidth() + "x" + p.displayHeight();
    }

    // ------------------------------------------------------------ 回调

    @Override
    public void onPaneOpened(PipPane pane, String pkg) {
        Log.i(TAG, "pane " + pane.index + " opened " + pkg);
        if (listener != null) listener.onOpened(pane.index, pkg);
    }

    @Override
    public void onPaneClosed(PipPane pane) {
        if (listener != null) listener.onClosed(pane.index);
    }

    /**
     * 这个包名是否还跑在别的屏上（回收 task 前要确认）。
     * 注意按 displayId 排除当前屏：面板顺序调换过，不能按 index 比较。
     */
    @Override
    public boolean isPkgUsedElsewhere(String pkg, int exceptDisplay) {
        if (pkg == null) return false;
        for (PipPane p : panes) {
            if (p == null) continue;
            if (p.displayId() == exceptDisplay) continue;
            if (pkg.equals(p.pkg())) return true;
        }
        return false;
    }

    public interface OnPaneClickListener {
        void onPaneLongClick(PipPane pane);
    }

    /** 给面板挂长按（去掉工具条后，长按是唯一的操作入口）。按面板对象绑定：调换顺序后依然指向同一块屏 */
    public void setPaneLongClickListener(final OnPaneClickListener l) {
        for (PipPane p : panes) {
            p.root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (l != null) l.onPaneLongClick(p);
                    return true;
                }
            });
            p.root.setOnClickListener(null);
        }
        // setOnLongClickListener 会把 longClickable 置 true（折叠的屏会因此重新吞掉触摸），补一遍
        syncCollapsed();
    }

    public static LinearLayout.LayoutParams weightParams(int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, weight);
        lp.gravity = Gravity.CENTER;
        return lp;
    }
}
