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
    static void bind(Slot s, android.graphics.SurfaceTexture st, int w, int h) {
        if (s == null || s.vd == null || st == null) return;
        try {
            st.setDefaultBufferSize(w, h);
        } catch (Throwable ignored) { }
        Surface surface = new Surface(st);
        try {
            s.vd.setSurface(surface);
        } finally {
            // 虚拟屏已持有 producer 引用，本地包装可以放掉
            try { surface.release(); } catch (Throwable ignored) { }
        }
    }

    /** pane 离开（Activity 销毁 / 关屏）：只解绑 surface，屏留着给下一次接 */
    static synchronized void detach(String key) {
        Slot s = SLOTS.get(key);
        if (s == null || s.vd == null) return;
        try {
            s.vd.setSurface(null);
        } catch (Throwable ignored) { }
    }

    /** 真正销毁一块屏（减屏数、强制重建时用）。注意：屏上的 task 会被系统丢回主屏 */
    static synchronized void release(String key) {
        Slot s = SLOTS.remove(key);
        if (s == null || s.vd == null) return;
        try { s.vd.setSurface(null); } catch (Throwable ignored) { }
        try { s.vd.release(); } catch (Throwable ignored) { }
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
