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
    private int singleIdx = -1;     // 单画时显示哪块屏
    private String singlePrevPkg;   // 单画前这块屏跑的应用（恢复时开回去）

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
        singleIdx = -1;
        singlePrevPkg = null;
        container.removeAllViews();
        dpi = Prefs.getDpi(app);
        cornerDp = Prefs.getPipCorner(app);
        for (int i = 0; i < paneCount; i++) {
            PipPane p = new PipPane(app, i, dpi, cornerDp, this);
            container.addView(p.root);
            panes.add(p);
        }
        weights = Prefs.getPaneWeights(app, paneCount);
        container.setListener(this);
        applyOrientation();
        // 存下来的比例可能是脏的（改过屏数/版本升级）：收敛到支持的那几种
        weights = sanitize(weights);
        container.setWeights(weights);
        Prefs.setPaneWeights(app, weights);
        syncCollapsed();
    }

    /**
     * 按容器方向重排版：横向 = 从左到右，纵向 = 从上到下。
     * 只在「逻辑顺序」上排，不摘挂视图（摘挂会销毁 TextureView 的 Surface，
     * 虚拟屏跟着重建 —— 调换顺序就成了一次整屏重启）。
     */
    public void applyOrientation() {
        container.setGapPx(Ui.dp(app, Ui.PANE_GAP_DP));
        // 单画临时布局：重排版时仍然只显示那块屏（否则旋转会瞬间露出全部画中画）
        if (singleMode && singleIdx >= 0 && singleIdx < panes.size()) {
            List<View> only = new ArrayList<>(1);
            only.add(panes.get(singleIdx).root);
            container.setOrder(only);
            container.setWeights(new float[]{1f});
            weights = new float[]{1f};
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
    }

    /** 深浅色外观变化：所有面板一起刷 */
    public void refreshAppearance() {
        for (PipPane p : panes) p.refreshAppearance();
    }

    public void setDpi(int value) {
        dpi = value;
        for (PipPane p : panes) p.setDpi(value);
    }

    /** 屏内渲染分辨率比例（1.0 标准 / 0.75 / 0.5）：所有面板一起调 */
    public void setRenderScale(float v) {
        for (PipPane p : panes) p.setRenderScale(v);
    }

    /** 旋转等尺寸大变化后：所有有内容的屏重建虚拟屏（重新排版、重算 letterbox） */
    public void postRebuildAll(long delayMs) {
        for (PipPane p : panes) p.postRebuild(delayMs);
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

    /** 这个应用是否显式指定了画中画屏位 */
    public boolean isAssigned(String pkg) {
        if (pkg == null) return false;
        int explicit = Prefs.getPaneOf(app, pkg);
        return explicit >= 1 && explicit <= panes.size();
    }

    /**
     * 槽位挑选：显式指定 > 该应用已打开 > **活动画中画**。
     * 未指定屏位的应用一律在「活动画中画」打开（设置页可配置，默认最后一屏）；
     * 画中画顺序固定：横排从左到右、竖排从上到下依次是 1/2/3。
     */
    public int pickPane(String pkg) {
        int explicit = Prefs.getPaneOf(app, pkg);   // 1 起
        if (explicit >= 1 && explicit <= panes.size()) return explicit - 1;
        int existing = indexOfPkg(pkg);
        if (existing >= 0) return existing;
        return Prefs.getActivePane(app, panes.size()) - 1;
    }

    /** preferred：0 = 自动；1..n = 指定第几屏 */
    public boolean open(String pkg, int preferred) {
        if (panes.isEmpty()) return false;
        // 「单画」临时布局下打开了指定屏位的应用 → 先恢复原布局（单/双/三画）
        if (singleMode && ((preferred >= 1 && preferred <= panes.size()) || isAssigned(pkg))) {
            restoreSingle();
        }
        int idx = (preferred >= 1 && preferred <= panes.size()) ? preferred - 1 : pickPane(pkg);
        if (singleMode) {
            // 还在单画布局里：后续打开的应用都进当前这块屏（换着看，不破坏原布局）
            idx = Math.max(0, Math.min(singleIdx, panes.size() - 1));
        }
        PipPane p = panes.get(idx);
        // 同一应用已开在别的屏：先把它收掉，避免两份
        int other = indexOfPkg(pkg);
        if (other >= 0 && other != idx) panes.get(other).close();
        restoreIfCollapsed(idx);      // 被折叠的屏先放回来，否则应用开在看不见的屏上
        p.open(pkg);
        return true;
    }

    /**
     * 「单画中画」打开：只显示一块屏，布局和「画中画数量 = 1」完全一样。
     *
     * 复用现有画中画（性能要求）：不新建虚拟屏、不销毁任何一块屏 —— 其他屏只是
     * 暂时不显示（视图留在容器里，虚拟屏照常跑），恢复布局时原样回来、不用重启。
     * 单画前这块屏跑的应用记下来，恢复时在原屏上开回去（同一块屏换应用，秒回）。
     */
    public int openSingle(String pkg) {
        if (panes.isEmpty()) return -1;
        int idx = singleMode ? singleIdx : pickPane(pkg);
        if (idx < 0 || idx >= panes.size()) idx = 0;
        PipPane p = panes.get(idx);
        if (!singleMode) {
            savedWeights = weights.clone();
            singlePrevPkg = p.pkg();
            singleMode = true;
            Log.i(TAG, "进入单画布局：pane " + idx + "，保存比例 "
                    + java.util.Arrays.toString(savedWeights));
            FileLog.append("单画：pane=" + idx + " 比例=" + java.util.Arrays.toString(savedWeights)
                    + " 原应用=" + singlePrevPkg);
        }
        singleIdx = idx;
        restoreIfCollapsed(idx);
        int other = indexOfPkg(pkg);
        if (other >= 0 && other != idx) panes.get(other).close();
        p.open(pkg);
        // 视觉：只显示这一块屏（order 只放一个 → 没有分隔条、铺满整个画中画区域）
        List<View> only = new ArrayList<>(1);
        only.add(p.root);
        container.setOrder(only);
        container.setWeights(new float[]{1f});
        weights = new float[]{1f};
        return idx;
    }

    /** 恢复单画前的布局（比例 + 原应用），单画期间开进去的应用随之收掉 */
    private void restoreSingle() {
        singleMode = false;
        int idx = singleIdx;
        String prev = singlePrevPkg;
        singleIdx = -1;
        singlePrevPkg = null;
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
                + " 借用屏pane=" + idx + " 开回=" + prev);
        // 单画借用的那块屏开回原应用：同一块虚拟屏换应用，不重建、秒回
        if (idx >= 0 && idx < panes.size() && prev != null
                && !prev.equals(panes.get(idx).pkg())) {
            panes.get(idx).open(prev);
        }
    }

    public void openAuto(String pkg) {
        open(pkg, 0);
    }

    public void close(int index) {
        // 单画布局里关掉正在显示的那块屏：布局一起恢复（否则停在只剩一块空屏的状态）
        if (singleMode && index == singleIdx) restoreSingle();
        PipPane p = pane(index);
        if (p != null) p.close();
    }

    /** 当前是否处于「单画」临时布局 */
    public boolean inSingleMode() {
        return singleMode;
    }

    /** 恢复单画前的布局（不在单画布局时什么都不做）。供外部在打开指定屏位应用前调用。 */
    public void restoreSingleLayout() {
        if (!singleMode) return;
        Log.i(TAG, "restoreSingleLayout：指定屏位应用打开前恢复布局");
        restoreSingle();
    }

    /** 单画布局显示的是第几屏（0 起；不在单画布局返回 -1） */
    public int singleIndex() {
        return singleMode ? singleIdx : -1;
    }

    public void closeAll() {
        singleMode = false;
        savedWeights = null;
        singleIdx = -1;
        singlePrevPkg = null;
        for (PipPane p : panes) p.close();
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
