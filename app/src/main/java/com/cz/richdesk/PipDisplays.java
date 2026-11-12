package com.cz.richdesk;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import java.util.HashMap;

/**
 * 进程级虚拟屏池 —— 「画面卡死」的根治点就在这个类。
 *
 * <h3>为什么原来的写法必卡</h3>
 * 以前虚拟屏是 PipPane 的私有物：pane 每次重排（拖拽改比例、DPI/圆角变更、屏数变化、
 * Activity 重建）都走 {@code recreateNow()} → {@code vd.release()} + {@code createVirtualDisplay()}。
 * 释放再重建意味着 <b>displayId 变了</b>。而屏内的应用 task 是挂在**旧 displayId** 上的，
 * 它不会跟着到新屏上：
 * <ul>
 *   <li>旧屏释放 → task 被系统丢回主屏 display 0；</li>
 *   <li>旧屏没释放干净 → task 留在旧屏，新屏从建出来那天起就是空的。</li>
 * </ul>
 * 两种结果在用户眼里是同一件事：画中画停在最后一帧，点什么都没反应。而且这时候
 * {@code launchOn()} 也救不回来 —— activity 实例还在，AMS 走的是
 * {@code START_DELIVERED_TO_TOP}（只把 task 顶到前台，不做 reparent），
 * 所以日志里 85 次 wm_task_moved 没有一次真正落回虚拟屏。
 *
 * <h3>现在的做法</h3>
 * 虚拟屏按槽位（PipDesk-0/1/2）注册在**进程**里，生命周期跟 Activity 脱钩：
 * <ul>
 *   <li>pane 重排/重建 → {@link #slot} 命中同一块屏 → 只 {@code setSurface()} 换 producer
 *       + {@code resize()} 改尺寸，<b>displayId 始终不变</b>，屏内 task 一动不动，
 *       新 TextureView 挂上去立刻就有画面，根本不会出现"停帧"这个中间态；</li>
 *   <li>Activity 销毁 → 只 {@link #detach}（把 surface 解绑），屏留着，下次直接接上；</li>
 *   <li>只有进程被杀/减屏数才真正 {@code release()}。</li>
 * </ul>
 * 屏最多 3 块（key 固定），也就不存在以前那种"重启几次桌面冒出 6 块虚拟屏"的泄漏。
 */
final class PipDisplays {

    static final class Slot {
        VirtualDisplay vd;
        int w, h, dpi;
        /**
         * 当前交给虚拟屏的 Surface。**必须由我们持有强引用**：
         * 一旦本地 Surface 被 release，虚拟屏那头的输出队列就可能变成悬垂 producer，
         * SurfaceFlinger 往队列里投帧投不进去，画面就会定格在最后一帧（正是卡死的样子）。
         */
        Surface surface;
        /**
         * 当前绑定这根 surface 的 pane 实例标识（每个 PipPane 构造时取一次，单调递增）。
         *
         * 为什么必须有它：桌面重建时新旧两个 MainActivity 会**同时**存在一小段时间
         * （reapDuplicateInstances 只是让旧的 finish，onDestroy 是异步的）。槽位按
         * "PipDesk-0/1/2" 复用，新旧实例的 pane 用同一个 key：
         *   新实例 onCreate → bind() 接上新 Surface；
         *   旧实例 onDestroy → pip.release() → detach(key) →
         *       把**新实例刚接上**的 Surface setSurface(null) + release()。
         * 结果新 TextureView 从此没有任何 producer，onSurfaceTextureAvailable 也不会再来，
         * 画面永久停在最后一帧、frames 不再增长 —— 听诊器于是判"停帧"，每 1.2s 探一次、
         * 每次新起一条线程 + 两次 binder 全校举，越跑越卡。这就是"运行一段时间变卡"的闭环。
         * 带上 owner 之后，旧实例的 detach/release 会因为标识不匹配而直接跳过。
         */
        long owner;
    }

    private static final HashMap<String, Slot> SLOTS = new HashMap<>();

    private PipDisplays() { }

    /** 取一块还活着的屏；没有或已失效返回 null（失效的顺手摘掉） */
    static synchronized Slot slot(String key) {
        Slot s = SLOTS.get(key);
        if (s == null) return null;
        if (s.vd == null || s.vd.getDisplay() == null) {
            SLOTS.remove(key);
            return null;
        }
        return s;
    }

    static synchronized void put(String key, Slot s) {
        SLOTS.put(key, s);
    }

    /**
     * 换绑 surface：纹理的 buffer 尺寸先对齐屏的像素尺寸，再把 Surface 交给虚拟屏。
     * 这之后 SurfaceFlinger 会立刻开始把这块屏的图层合成到新的 TextureView 上。
     */
    static void bind(Slot s, android.graphics.SurfaceTexture st, int w, int h, long owner) {
        if (s == null || s.vd == null || st == null) return;
        try {
            st.setDefaultBufferSize(w, h);
        } catch (Throwable ignored) { }
        Surface surface = new Surface(st);
        try {
            s.vd.setSurface(surface);
        } catch (Throwable t) {
            try { surface.release(); } catch (Throwable ignored) { }
            return;
        }
        // 新 surface 装上之后再放旧的：VD 全程都有一份我们持有的强引用，
        // 不会出现"输出队列指向已释放 producer"导致整块屏不再出帧。
        Surface old;
        synchronized (PipDisplays.class) {
            old = s.surface;
            s.surface = surface;
            s.owner = owner;      // 认主：从这一刻起只有这个 pane 能摘它
        }
        if (old != null && old != surface) {
            try { old.release(); } catch (Throwable ignored) { }
        }
    }

    /** 登记新槽位并认主 */
    static synchronized void putOwned(String key, Slot s, long owner) {
        s.owner = owner;
        SLOTS.put(key, s);
    }

    /**
     * pane 离开（Activity 销毁 / 关屏）：只解绑 surface，屏留着给下一次接。
     *
     * **只有当前属主才能解绑** —— 否则重建期间旧实例的 onDestroy 会把新实例
     * 刚接上的 surface 摘掉，画面永久停帧（见 {@link Slot#owner}）。
     */
    static synchronized void detach(String key, long owner) {
        Slot s = SLOTS.get(key);
        if (s == null || s.vd == null) return;
        if (owner != 0 && s.owner != owner) {
            // 不是自己的线，别动。旧实例退场时走到这里就是正确行为。
            return;
        }
        s.owner = 0;
        try {
            s.vd.setSurface(null);
        } catch (Throwable ignored) { }
        if (s.surface != null) {
            try { s.surface.release(); } catch (Throwable ignored) { }
            s.surface = null;
        }
    }

    /** 兼容旧调用（无属主校验）：只给内部强制释放用，业务侧一律走带 owner 的版本 */
    static synchronized void detach(String key) {
        detach(key, 0);
    }

    /**
     * 只有属主才能真销毁这块屏（减屏数 / 改渲染分辨率）。
     * 旧 Activity 实例退场时不应该把新实例正在用的屏 release 掉。
     */
    static synchronized void releaseOwned(String key, long owner) {
        Slot s = SLOTS.get(key);
        if (s == null) return;
        // 严格只释放自己认领的那块。owner==0（已被别人 detach 的空槽）也不动：
        // 那块屏极可能正等着新实例接上，此时销毁会让 displayId 变化 → 屏内 task 掉回主屏。
        // 真要减屏数请走 releaseFrom()/releaseAll()（它们是真·强制释放）。
        if (s.owner != owner) return;
        release(key);
    }

    /** 真正销毁一块屏（减屏数、强制重建时用）。注意：屏上的 task 会被系统丢回主屏 */
    static synchronized void release(String key) {
        Slot s = SLOTS.remove(key);
        if (s == null) return;
        int dispId = -1;
        try {
            if (s.vd != null && s.vd.getDisplay() != null) dispId = s.vd.getDisplay().getDisplayId();
        } catch (Throwable ignored) { }
        // 锚是挂在这块屏上的窗口，屏没了要一起摘，否则会留一个孤儿窗口
        if (dispId >= 0) FocusAnchor.release(dispId);
        if (s.vd != null) {
            try { s.vd.setSurface(null); } catch (Throwable ignored) { }
            try { s.vd.release(); } catch (Throwable ignored) { }
        }
        if (s.surface != null) {
            try { s.surface.release(); } catch (Throwable ignored) { }
            s.surface = null;
        }
    }

    /** 屏数从 n 减到 keep：把多出来的槽位真释放掉 */
    static synchronized void releaseFrom(int keep) {
        java.util.ArrayList<String> drop = new java.util.ArrayList<>();
        for (String k : SLOTS.keySet()) {
            int idx = indexOf(k);
            if (idx >= keep) drop.add(k);
        }
        for (String k : drop) release(k);
    }

    static synchronized void releaseAll() {
        for (String k : new java.util.ArrayList<>(SLOTS.keySet())) release(k);
    }

    static synchronized int size() {
        int n = 0;
        for (Slot s : SLOTS.values()) if (s.vd != null && s.vd.getDisplay() != null) n++;
        return n;
    }

    static int indexOf(String key) {
        try {
            return Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
        } catch (Throwable t) {
            return -1;
        }
    }
}
