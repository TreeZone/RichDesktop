package com.cz.richdesk;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 一个画中画面板 = 一块虚拟屏 + 一个 TextureView。
 *
 * 为什么用 TextureView 而不是 SurfaceView：
 *   SurfaceView 的画面在单独的图层里，圆角裁切对它无效（会露出黑色直角块），
 *   而 TextureView 是普通 View，父容器 setClipToOutline(true) 就能精确切圆角，
 *   空闲时只要把它 alpha 归零即可露出下面的玻璃背景。
 *   代价是多一次 GPU 合成，对桌面这种静态场景可接受。
 */
public class PipPane {

    public interface Callback {
        void onPaneOpened(PipPane pane, String pkg);
        void onPaneClosed(PipPane pane);
        /**
         * 这个包名是不是还跑在**别的**屏上（按 displayId 排除当前屏）。
         * 换应用/关闭画中画时要回收旧应用的 task，但如果它同时在另一块屏上跑着，
         * 就不能动 —— 那会把用户在用的另一块屏一起关掉。
         */
        boolean isPkgUsedElsewhere(String pkg, int exceptDisplay);
    }

    private static final String TAG = "PipDesk";
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED（隐藏常量） */
    private static final int FLAG_TRUSTED = 1 << 10;
    /** VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP（@hide）：让这块屏自成 display group */
    private static final int FLAG_OWN_GROUP = 1 << 11;

    public int index;   // 位置序号：调换顺序时由 PipManager.swap 改写
    public final FrameLayout root;
    private final TextureView texture;
    private final TextView hint;
    private final TextView loading;
    /** 四角壁纸遮罩：替代整块圆角裁剪（见 {@link PaneCornerMask}） */
    private PaneCornerMask cornerMask;
    /** 当前是否用遮罩画圆角（false = 退回老的整体裁剪） */
    private boolean useMask;

    private final Context app;
    private final DisplayManager dm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;

    private VirtualDisplay vd;
    private int displayId = -1;
    /** 虚拟屏当前像素尺寸（触摸坐标换算用） */
    private int displayW, displayH;
    private String pkg;
    private int dpi;
    private int cornerDp;
    /**
     * 屏内渲染分辨率比例（1.0 标准 / 0.75 / 0.5）。
     * 虚拟屏与纹理 buffer 都按「面板像素 × 比例」创建：应用渲染的像素少了，
     * GPU 重的应用帧率更稳；纹理由 TextureView 放大回面板，代价是画面略糊。
     * 触摸映射按 displayW/viewW 算，比例变化自动跟上，无需特殊处理。
     */
    private float renderScale = 1f;

    private boolean surfaceReady;
    private int surfaceW, surfaceH;
    /** 纹理出帧计数 / 最后一次出帧时刻：画面"冻结"的判断依据（重启日志里带上） */
    private long frames;
    /** 最后一帧的时刻（uptimeMillis），0 = 还没出过帧。跨线程读，必须 volatile */
    private volatile long lastFrameAt;
    /** 本统计窗口（1s）内最大帧间隔 / >100ms 的断档次数：卡顿定位用，PerfOverlay 读一次清一次 */
    private long gapMaxWin;
    private long gapOver100Win;
    /** 这块屏上当前跑着的应用：换应用时必须清屏，否则旧应用的 task 还留在栈里会串台 */
    private String livePkg;
    /**
     * 这块屏的「基准应用」：被一个临时应用顶掉之前，屏上常驻的那个。
     * 按返回露出栈底时用它把 pkg/livePkg 对齐（见 adoptForeground），
     * 而不是把整块屏 close 成空态 —— 用户实测「活动画中画里的 app 没显示回来」就是这么丢的。
     * 只在第一次换应用时确立，后续不覆盖；屏被主动 close 时清空。
     */
    private String basePkg;
    /** 应用被「全屏打开」搬去主屏了：回到桌面时要搬回本屏继续显示 */
    private boolean fullscreened;
    /**
     * 比例动画中：先不给虚拟屏 resize。
     * 拖动分隔条时视图尺寸每帧都在变，如果每帧都 resize 虚拟屏，屏内应用会收到
     * 几十次 config change（疯狂重排，卡顿且观感很糟）。这里改成动画期间只让
     * TextureView 拉伸预览，动画结束再一次性对齐虚拟屏尺寸。
     */
    private boolean deferResize;
    /** 被折叠隐藏（权重 0）：整块不参与触摸，但虚拟屏与 TextureView 都留着，恢复是热的 */
    private boolean collapsed;

    /**
     * 本实例的属主标识：虚拟屏槽位按 "PipDesk-N" 复用，重建期间新旧两个 Activity
     * 会抢同一个槽位。带上它之后，旧实例退场时的 detach/release 才能被识别出来并跳过，
     * 不会把新实例刚接上的 Surface 摘掉（详见 {@link PipDisplays.Slot#owner}）。
     */
    private static final java.util.concurrent.atomic.AtomicLong OWNER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private final long ownerToken = OWNER_SEQ.incrementAndGet();

    public PipPane(Context ctx, int index, int dpi, int cornerDp, Callback cb) {
        this.app = ctx.getApplicationContext();
        this.dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        this.index = index;
        this.dpi = dpi;
        this.cornerDp = cornerDp;
        this.cb = cb;
        this.renderScale = Prefs.getRenderScale(app) / 100f;

        root = new FrameLayout(ctx);
        root.setBackground(Ui.glass(ctx, cornerDp));
        root.setClipChildren(true);
        useMask = shouldUseMask(ctx, cornerDp);
        cornerMask = new PaneCornerMask(ctx);
        cornerMask.onUnavailable = () -> {
            if (!useMask) return;
            Log.i(TAG, "拿不到壁纸位图，圆角退回整块裁剪");
            useMask = false;
            applyCorner();
        };

        texture = new TextureView(ctx);
        texture.setOpaque(false);
        texture.setAlpha(0f);
        root.addView(texture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 四角遮罩必须在 texture 之上（它要盖住方形纹理露出来的四个角）
        root.addView(cornerMask, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyCorner();

        // 兜底：insets 变化（状态栏/导航栏留白变化）后纹理尺寸变了但回调没来，
        // 虚拟屏就会停留在旧尺寸上，画面被横向压扁。布局后对齐一次。
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = texture.getWidth();
            int h = texture.getHeight();
            if (w > 0 && h > 0 && !deferResize && (w != surfaceW || h != surfaceH)) {
                surfaceW = w;
                surfaceH = h;
                android.graphics.SurfaceTexture st = texture.getSurfaceTexture();
                if (st != null) st.setDefaultBufferSize(buf(w), buf(h));
                // resize 只是"调尺寸"，失败也不重建屏 —— 重建会换 displayId，
                // 屏内 task 就再也回不到这块屏上（= 卡死）。宁可让纹理拉伸一下。
                if (vd != null) resizeDisplay(buf(w), buf(h), density());
            }
        });

        hint = new TextView(ctx);
        hint.setText("轻点选择应用\n也可点 Dock 图标");
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(12);
        hint.setTextColor(0xB3FFFFFF);
        hint.setShadowLayer(4, 0, 1, 0x99000000);
        root.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        loading = new TextView(ctx);
        loading.setText("正在启动…");
        loading.setGravity(Gravity.CENTER);
        loading.setTextSize(12);
        loading.setTextColor(0xCCFFFFFF);
        loading.setShadowLayer(4, 0, 1, 0x99000000);
        loading.setVisibility(View.GONE);
        root.addView(loading, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(buf(w), buf(h));
                surfaceReady = true;
                surfaceW = w;
                surfaceH = h;
                if (pkg != null && vd == null) {
                    FileLog.append("surface 就绪(" + w + "x" + h + ") → 补建屏 " + pkg);
                    recreateNow();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                surfaceReady = true;
                // 比例动画/拖动中：不改 buffer 尺寸，纹理先拉伸（结束由 applyDeferredResize 对齐）
                if (deferResize) return;
                st.setDefaultBufferSize(buf(w), buf(h));
                if (w != surfaceW || h != surfaceH) {
                    surfaceW = w;
                    surfaceH = h;
                    // 旋转/分屏/拖拽：原地 resize（等价于设备旋转，应用收到 config change）。
                    // 失败也不重建屏 —— 重建会换 displayId，屏内 task 会掉回主屏（= 卡死）。
                    if (vd != null && (buf(w) != displayW || buf(h) != displayH)) {
                        resizeDisplay(buf(w), buf(h), density());
                    }
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                releaseDisplay();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // 帧间隔统计：这里能真实反映「屏内画面多久来一帧」，
                // 从 16ms 跳到 200ms 就是用户肉眼看到的卡顿。
                long now = android.os.SystemClock.uptimeMillis();
                if (lastFrameAt > 0) {
                    long gap = now - lastFrameAt;
                    if (gap > gapMaxWin) gapMaxWin = gap;
                    if (gap > 100) gapOver100Win++;
                }
                frames++;
                lastFrameAt = now;
            }
        });

        hideLoading = () -> {
            loading.setVisibility(View.GONE);
            hint.setVisibility(pkg == null ? View.VISIBLE : View.GONE);
            // 有应用在跑 = 内容是不透明的：告诉合成器这一层不透明，可以跳过
            // 与底下玻璃背景的混合。三块屏都在出帧时这一项能省掉整屏的混合带宽。
            texture.setOpaque(pkg != null);
            texture.setAlpha(pkg == null ? 0f : 1f);
            if (pkg != null && cb != null) cb.onPaneOpened(this, pkg);
        };
    }

    public void setCorner(int dp) {
        cornerDp = dp;
        root.setBackground(Ui.glass(root.getContext(), dp));
        useMask = shouldUseMask(root.getContext(), dp);
        applyCorner();
    }

    /**
     * 圆角怎么画：遮罩（默认）还是整块裁剪。
     *
     * 遮罩只有在「拿得到壁纸位图」时才成立 —— 它靠重画壁纸来伪装圆角，
     * 拿不到壁纸就只能退回裁剪，否则四角会变成纯色补丁。
     */
    private static boolean shouldUseMask(Context c, int dp) {
        // 这里**不做**壁纸是否可用的检查：那会在主线程解码壁纸、拖慢启动。
        // 真拿不到壁纸时，遮罩会回调 onUnavailable，当场切回裁剪（见 applyCorner）。
        return dp > 0 && Prefs.cornerByMask(c);
    }

    /**
     * 把当前圆角方案落到 View 上。
     *
     * 两种方案互斥：
     *   · 遮罩 —— 不裁剪，四角交给 {@link PaneCornerMask} 画壁纸；
     *   · 裁剪 —— 老做法，setClipToOutline 让 HWUI 对整块纹理做离屏裁剪。
     */
    private void applyCorner() {
        if (useMask) {
            root.setClipToOutline(false);
            root.setOutlineProvider(null);
            cornerMask.setRadiusPx(Ui.dp(root.getContext(), cornerDp));
        } else {
            cornerMask.setRadiusPx(0);
            Ui.clipRounded(root, cornerDp, root.getContext());
        }
        root.invalidate();
    }

    /** 深浅色外观变化：玻璃底和提示文字一起换配色 */
    public void refreshAppearance() {
        root.setBackground(Ui.glass(root.getContext(), cornerDp));
        if (cornerMask != null) cornerMask.invalidate();
        hint.setTextColor(Ui.tintHint());
        hint.setShadowLayer(4, 0, 1, Ui.shadow());
        loading.setTextColor(Ui.tintDim());
        loading.setShadowLayer(4, 0, 1, Ui.shadow());
    }

    public void setDpi(int dpi) {
        if (this.dpi == dpi) return;
        this.dpi = dpi;
        // 改 dpi = 改密度配置：HyperOS 上原地 resize 不会让屏内应用重新测量，
        // 窗口会停在旧像素尺寸上（画面缩到左上角），所以一律重建虚拟屏。
        if (vd != null) {
            scheduleRecreate();
        }
    }

    /** 面板像素 → 虚拟屏/纹理 buffer 像素（乘渲染比例，最小 1） */
    private int buf(int viewPx) {
        return Math.max(1, Math.round(viewPx * renderScale));
    }

    /**
     * 虚拟屏密度（dpi）也按渲染比例一起缩。
     *
     * 只缩小像素、不缩 dpi 的后果：屏内应用看到的「dp 尺寸」跟着变小 —— 同一个界面
     * 在 75% 下只剩 3/4 的逻辑宽度，应用要么挤成小窗、要么直接换一套窄屏布局，
     * 表现就是「画面等比缩小」而不是「全屏但糊一点」。
     *
     * 像素和 dpi 同比例缩，逻辑尺寸（dp）就与 100% 完全一致：布局/字号/控件位置
     * 一模一样，只是实际渲染的像素少了 25%，再由 TextureView 放大铺满整个面板 ——
     * 这才是「全屏 + 分辨率降低」。
     */
    private int density() {
        return Math.max(72, Math.round(dpi * renderScale));
    }

    /**
     * 调整屏内渲染分辨率比例。
     *
     * **只改字段，不在线重建虚拟屏** —— 这是车机上实测过的硬结论：
     * 无论走 `VirtualDisplay#resize` 还是"释放旧屏 + 建新屏 + 重新 startActivity"，
     * 屏内应用都不会重新 measure：
     *   · resize 只改输出 buffer 的像素尺寸，应用收不到新的窗口尺寸；
     *   · 换屏后 displayId 变了，但应用 activity 实例还挂在旧屏的 token 上，
     *     `startActivity` 走的是 START_DELIVERED_TO_TOP（只顶到前台、不 reparent），
     *     除非 force-stop 成功把进程杀掉重来 —— 而这条在这台 ROM 上不稳定。
     * 结果就是：比例调小 → 应用还按旧像素画 → 画面缩在左上角；调大 → 显示不全。
     *
     * 所以比例只在**建屏那一刻**生效（{@link #buf} / {@link #density()} 读它），
     * 设置页那边明确提示"重启桌面后生效"并提供一键重启。
     * 重启后 {@link PipPane} 构造时会重新读 Prefs，建出来的屏从一开始就是正确尺寸，
     * 应用第一次测量就是对的 —— 不会再有"缩左上角"。
     */
    public void setRenderScale(float s) {
        s = Math.max(0.5f, Math.min(1f, s));
        if (Math.abs(s - renderScale) < 0.001f) return;
        renderScale = s;
        Log.i(TAG, "pane " + index + " renderScale=" + renderScale + "（下次建屏生效）");
    }

    /**
     * 比例动画期间冻结虚拟屏 resize（见字段 deferResize 的说明）。
     * 传 false 表示动画结束：立刻把虚拟屏对齐到纹理的最终尺寸（一次即可）。
     */
    public void setDeferResize(boolean defer) {
        if (defer) {
            deferResize = true;
            return;
        }
        if (!deferResize) return;
        deferResize = false;
        applyDeferredResize();
    }

    /** 把虚拟屏尺寸对齐到 TextureView 的当前尺寸（比例动画结束后调用） */
    public void applyDeferredResize() {
        if (deferResize) return;
        int w = texture.getWidth();
        int h = texture.getHeight();
        if (w <= 0 || h <= 0) return;
        // 被拖到折叠的那块屏只剩几像素：不要拿这个尺寸去 resize 虚拟屏，
        // 否则屏内应用会被踢成畸形配置（下次恢复还要重新排版）。留着旧尺寸，恢复时再对齐。
        int min = Ui.dp(app, 24);
        if (w < min || h < min) return;
        if (w == surfaceW && h == surfaceH) return;
        surfaceW = w;
        surfaceH = h;
        SurfaceTexture st = texture.getSurfaceTexture();
        if (st != null) st.setDefaultBufferSize(buf(w), buf(h));
        // 同上：尺寸对不齐也不重建屏，避免换 displayId 把屏内 task 甩到主屏
        if (vd != null) resizeDisplay(buf(w), buf(h), density());
    }

    /**
     * 折叠（被相邻屏挤掉，权重 0）：整块 alpha 由 PaneBox 归零，这里负责
     * 「不参与触摸」（否则它那块旧矩形会挡住旁边扩出来的屏 —— 只把 OnTouchListener
     * 返回 false 是不够的：root 挂了长按监听后 View 自己会把 DOWN 吞掉）。
     */
    public void setCollapsed(boolean c) {
        boolean changed = collapsed != c;
        collapsed = c;
        // 每次都重设（幂等）：宿主给 root 挂长按监听时会把 longClickable 又置成 true
        root.setClickable(!c);
        root.setLongClickable(!c);
        if (changed && !c && pkg != null) {
            hint.setVisibility(View.GONE);
            texture.setAlpha(1f);
        }
    }

    /** 折叠隐藏的屏不吃触摸（否则会挡住旁边那块屏的手势） */
    public boolean isInteractive() {
        return !collapsed;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** VirtualDisplay#resize（隐藏 API）：只查一次，别每次布局回调都做一遍反射查找 */
    private static java.lang.reflect.Method resizeMethod;
    private static boolean resizeLookupDone;

    /** 就地调整虚拟屏尺寸/密度（VirtualDisplay#resize 是隐藏 API，反射调用） */
    private boolean resizeDisplay(int w, int h, int density) {
        if (vd == null || w <= 0 || h <= 0) return false;
        try {
            if (!resizeLookupDone) {
                resizeLookupDone = true;
                try {
                    resizeMethod = VirtualDisplay.class
                            .getDeclaredMethod("resize", int.class, int.class, int.class);
                    resizeMethod.setAccessible(true);
                } catch (Throwable ignored) { }
            }
            if (resizeMethod == null) return false;
            resizeMethod.invoke(vd, w, h, density);
            displayW = w;
            displayH = h;
            Log.i(TAG, "pane " + index + " resize -> " + w + "x" + h + " dpi=" + density);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "VirtualDisplay#resize 不可用，退回重建", t);
            return false;
        }
    }

    public String pkg() {
        return pkg;
    }

    /**
     * 只记录「这块屏当前前台是谁」，**不拉应用、不动虚拟屏**。
     *
     * 用在单画临时应用自己退走的场景：按返回之后那块屏往下露出来的就是原应用，
     * 没必要再 launch 一次（多一次冷启动闪动）。但 pkg 字段得跟上，
     * 否则下次点同一个应用会被当成"没开过"重新冷启动一遍。
     */
    public void adoptForeground(String targetPkg) {
        if (targetPkg == null) return;
        pkg = targetPkg;
        livePkg = targetPkg;
        hint.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
    }

    /** 这块屏被临时应用顶掉之前的常驻应用（可能为 null） */
    public String basePkg() {
        return basePkg;
    }

    public boolean isBusy() {
        return pkg != null;
    }

    public int displayId() {
        return displayId;
    }

    /** 虚拟屏的像素尺寸（触摸坐标要按它换算） */
    public int displayWidth() {
        return displayW;
    }

    public int displayHeight() {
        return displayH;
    }

    /** 面板视图的像素尺寸 */
    public int viewWidth() {
        return texture.getWidth();
    }

    public int viewHeight() {
        return texture.getHeight();
    }

    /**
     * 视图本地坐标 → 虚拟屏像素坐标 的映射矩阵（方向固定 View→VD，可直接传给
     * TouchBridge.send）。当前是纯缩放（display/view）；将来若对面板施加旋转/
     * 斜切等仿射，把变换并进这里即可，注入端不用动。
     * 注意：displayW/H 是 VD 的逻辑宽高（createVirtualDisplay/resize 的 w/h），
     * 与 SurfaceTexture buffer 分辨率在 resize 瞬间可能短暂不一致。
     */
    public android.graphics.Matrix viewToDisplayMatrix() {
        android.graphics.Matrix m = new android.graphics.Matrix();
        int vw = viewWidth();
        int vh = viewHeight();
        if (vw > 0 && vh > 0 && displayW > 0 && displayH > 0) {
            m.postScale(displayW / (float) vw, displayH / (float) vh);
        }
        return m;
    }

    /** 延迟重建（旋转后调用；等布局稳定再建，否则拿到的是旧尺寸） */
    public void postRebuild(long delayMs) {
        if (pkg == null || !surfaceReady) return;
        main.removeCallbacks(recreateTask);
        main.postDelayed(recreateTask, Math.max(0, delayMs));
    }

    public Drawable background() {
        return root.getBackground();
    }

    // ------------------------------------------------------------ 打开 / 关闭

    public void open(String targetPkg) {
        if (targetPkg == null) return;
        // 这块屏记着这个应用：不重建虚拟屏、不换 texture，但**必须把它的 task 带到前台**。
        //
        // 踩过的坑：用户按「返回」后，栈可能退到上一个应用（A 还在栈里但不在前台），
        // 也可能直接把 A 退出（task 没了，只剩我们记的 pkg）。原来的"直接 return"在这两种
        // 情况下都表现为"点了图标没反应"（其实应用是开着的/被重新创建了，但画面不弹）。
        // launch intent 对这两种情况的处理正好都对：
        //   task 还在 → 一次 resume（不重建、不闪）；
        //   task 没了 → 在本屏重新创建。
        if (targetPkg.equals(pkg) && vd != null && displayId >= 0) {
            Log.i(TAG, "pane " + index + " 已记着 " + targetPkg + "，把它的 task 带回前台");
            livePkg = targetPkg;
            texture.setOpaque(true);
            texture.setAlpha(1f);
            launchOn(targetPkg, displayId);
            ensureOnDisplay(targetPkg, displayId);
            return;
        }
        // 兜底：TextureView 已经可用但回调被漏掉（例如先 attach 后注册）
        if (!surfaceReady && texture.isAvailable()) {
            surfaceReady = true;
            surfaceW = Math.max(1, texture.getWidth());
            surfaceH = Math.max(1, texture.getHeight());
        }
        android.util.Log.i("PipDesk", "open pane " + index + " pkg=" + targetPkg
                + " surfaceReady=" + surfaceReady + " size=" + surfaceW + "x" + surfaceH);
        pkg = targetPkg;
        hint.setVisibility(View.GONE);
        loading.setText("正在启动…");   // 重启应用会把文案改成「正在重启…」，这里复位
        loading.setVisibility(View.VISIBLE);
        texture.setOpaque(false);
        texture.setAlpha(0f);
        main.removeCallbacks(hideLoading);

        final String stale = livePkg;
        // 记下被临时顶掉的常驻应用：按返回露出它时，watchPaneExit 要把它显示回来，
        // 而不是 pane.close() 清空整块屏。只在首次确立，恢复基准应用时不要被覆盖。
        if (basePkg == null && stale != null && !stale.equals(targetPkg)) {
            basePkg = stale;
            android.util.Log.i(TAG, "pane " + index + " 基准应用=" + basePkg
                    + "（临时换成 " + targetPkg + "）");
        }
        if (vd != null && displayId >= 0) {
            if (livePkg == null || livePkg.equals(targetPkg)) {
                livePkg = targetPkg;
                launchOn(targetPkg, displayId);
                ensureOnDisplay(targetPkg, displayId);
                showContentAfter(450);
            } else {
                // 换了应用：**不再销毁重建虚拟屏**（旧方案会黑屏一大截，还会触发
                // task 被搬去主屏 → 桌面被顶掉 → 又拉回来的连锁反应）。
                // 直接在同一块屏上拉起新应用：新应用画好第一帧前旧画面仍然显示，
                // 用户看到的是"旧画面无缝变成新画面"。
                // 旧应用的 task 会留在栈底，新应用按返回键时才会露出来，可接受。
                livePkg = targetPkg;
                launchOn(targetPkg, displayId);
                ensureOnDisplay(targetPkg, displayId);
                keepContentVisible();
                // 但栈底的旧应用不能留着：虚拟屏是 ON 的，系统会一直把它算作
                // 「可见的 task」，进程留着不放、surface 也占着 —— 换几次应用之后
                // 三块屏能堆出十几个常驻进程，内存越用越紧（表现就是跑一阵子开始卡）。
                scheduleReap(stale, 2500);
                // 这块屏上除新应用以外的历史 task 一并清掉：一屏一个栈，
                // 返回键才是"退出应用"而不是"翻出上一个应用"
                main.postDelayed(() -> {
                    if (targetPkg.equals(pkg)) trimTasks(targetPkg);
                }, 2000);
            }
            return;
        }
        if (surfaceReady) {
            createDisplay();
            if (vd != null && displayId >= 0) {
                livePkg = targetPkg;
                launchOn(targetPkg, displayId);
                ensureOnDisplay(targetPkg, displayId);
                showContentAfter(450);
            } else {
                // 建屏失败：不吞掉，交给上层决定是否全屏兜底
                failOpen(targetPkg);
            }
        }
        // surface 没就绪会在 onSurfaceTextureAvailable 里补上
    }

    private void showContentAfter(long ms) {
        main.postDelayed(hideLoading, ms);
    }

    /**
     * 同屏换应用：内容保持可见（不闪 loading、不把纹理变黑），
     * 只在新应用把 loading 文案/占位清掉之后把回调补上。
     */
    private void keepContentVisible() {
        loading.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        texture.setOpaque(true);
        texture.setAlpha(1f);
        main.removeCallbacks(hideLoading);
        main.postDelayed(() -> {
            if (pkg != null && cb != null) cb.onPaneOpened(this, pkg);
        }, 450);
    }

    private final Runnable hideLoading;

    private void failOpen(String p) {
        pkg = null;
        loading.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
        texture.setAlpha(0f);
        launchFullScreen(p);
    }

    public void close() {
        // 应用正全屏跑在主屏上（罕见：桌面在前台时 onResume 已把标记清掉）：
        // 只解绑，不 blank 虚拟屏（应用不在本屏，blank 没意义）
        if (fullscreened) fullscreened = false;
        String p = pkg;
        pkg = null;
        livePkg = null;
        basePkg = null;      // 主动清空这块屏：不再有「要显示回来的」基准应用
        main.removeCallbacks(hideLoading);
        loading.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
        texture.setOpaque(false);
        texture.setAlpha(0f);
        // 用空白页把目标应用顶回后台（否则它还显示在虚拟屏上）
        clearDisplayApp(p);
        // **不销毁虚拟屏**：销毁会把屏内 task 搬去主屏、触发 restoreDesktop 拉回桌面，
        // 用户看到的就是"桌面闪一下"。屏留着（盖着空白页），下次 open 直接复用，秒开。
        // 但被关掉的应用的 task 要收掉：否则它会一直留在栈底当"可见 task"常驻内存。
        scheduleReap(p, 1500);
        // 屏已经空了：这块屏上残留的历史 task 全清（含被顶到后台的旧应用）
        main.postDelayed(() -> {
            if (pkg == null) trimTasks(null);
        }, 1200);
        if (cb != null) cb.onPaneClosed(this);
    }

    /** 释放（Activity 销毁时调用，必须先于 TextureView 销毁） */
    public void release() {
        main.removeCallbacksAndMessages(null);
        releaseDisplay();
    }

    // ------------------------------------------------------------ 重启应用（卡死急救）

    /**
     * 重启本屏上的应用。
     *
     * 为什么需要它：部分系统应用（车机上实测是系统蓝牙 HMI）在虚拟屏上适配不良，
     * 跑一段时间后会整体卡死 —— 画面停在最后一帧、触摸注入进去也毫无反应，
     * 而且**只坏自己这一块屏**，其他画中画正常。这类卡死发生在应用进程内部，
     * 宿主侧无论怎么重绑 surface 都救不回来；实测唯一可靠的恢复手段是重启该进程。
     * 系统应用改不了，只能在宿主侧提供这条一键恢复通道（长按菜单 / ANR 自动兜底）。
     */
    public void restartApp() {
        final String p = pkg;
        if (p == null) {
            Log.i(TAG, "pane " + index + " 空闲，无需重启");
            return;
        }
        Log.i(TAG, "pane " + index + " 重启应用 " + p + " frames=" + frames
                + " 距上次出帧=" + (lastFrameAt == 0 ? "从未"
                : (android.os.SystemClock.uptimeMillis() - lastFrameAt) + "ms"));
        FileLog.append("RESTART pane=" + index + " pkg=" + p + " frames=" + frames
                + " lastFrameAgo=" + (lastFrameAt == 0 ? "never"
                : (android.os.SystemClock.uptimeMillis() - lastFrameAt) + "ms")
                + " @ " + FileLog.nowStamp());
        fullscreened = false;   // 重启后应用回到本屏，不再算"全屏中"
        main.removeCallbacks(hideLoading);
        loading.setText("正在重启…");
        loading.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        texture.setAlpha(0f);
        relaunchAfterStop(p, 350);
    }

    /** 先杀掉进程（否则 startActivity 只会把卡死的旧进程顶到前台），再在后台线程重新拉起 */
    private void relaunchAfterStop(final String p, final long launchDelay) {
        new Thread(() -> {
            boolean stopped = forceStop(p);
            Log.i(TAG, "pane " + index + " force-stop " + p + " ok=" + stopped);
            main.postDelayed(() -> {
                if (!p.equals(pkg) || displayId < 0) {
                    FileLog.append("relaunchAfterStop 放弃：" + p
                            + " pkg=" + pkg + " display=" + displayId);
                    return;
                }
                livePkg = p;
                launchOn(p, displayId);
                ensureOnDisplay(p, displayId);
                showContentAfter(800);
            }, launchDelay);
        }, "pipdesk-restart").start();
    }

    /** 反射 forceStopPackage（uid 1000 有 FORCE_STOP_PACKAGES），失败退回 am 命令 */
    private boolean forceStop(String p) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method m = ActivityManager.class
                    .getMethod("forceStopPackage", String.class);
            m.invoke(am, p);
            return true;
        } catch (Throwable ignored) { }
        return Shell.runSync("am force-stop " + p);
    }

    public long frames() {
        return frames;
    }

    /**
     * 取走本统计窗口的帧间隔统计并清零（PerfOverlay 每秒调一次）：
     *   out[0] = 本窗口内最大帧间隔 ms
     *   out[1] = 本窗口内 >100ms 的断档次数
     * 两个数一起看才有意义：fps 低但最大间隔小 = 应用本身出帧慢；
     * 最大间隔突然几百 ms = 真的卡住了（合成/主线程/屏内应用三者之一）。
     */
    public synchronized void takeGapStats(long[] out) {
        out[0] = gapMaxWin;
        out[1] = gapOver100Win;
        gapMaxWin = 0;
        gapOver100Win = 0;
    }

    /**
     * 虚拟屏当前的电平状态（ON / OFF / DOZE…）。
     *
     * 为什么关心它：车机上"点蓝牙播放 → 三块画中画一起冻住、但音乐还在响"，
     * 一个强假设是 SurfaceFlinger 因为同时存在主屏 + 多块虚拟屏（音频捕获还会
     * 额外建镜像屏）而停止合成其中几块 —— 那种情况下 display 的 state 会被改成
     * OFF。日志里 state 从 ON 变 OFF，就等于拿到了"是系统把屏关掉的"直接证据，
     * 而不是应用自己画不出来。
     */
    public String displayState() {
        if (displayId < 0) return "none";
        try {
            android.view.Display d = dm.getDisplay(displayId);
            if (d == null) return "gone";
            switch (d.getState()) {
                case android.view.Display.STATE_ON: return "ON";
                case android.view.Display.STATE_OFF: return "OFF";
                case android.view.Display.STATE_DOZE: return "DOZE";
                case android.view.Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
                case android.view.Display.STATE_ON_SUSPEND: return "ON_SUSPEND";
                case android.view.Display.STATE_UNKNOWN: return "UNKNOWN";
                default: return "S" + d.getState();
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 应用是不是正被"全屏打开"（在主屏上）：此时本屏只是停着最后一帧，不算卡死 */
    public boolean isFullscreened() {
        return fullscreened;
    }

    // ------------------------------------------------------------ 虚拟屏

    private void createDisplay() {
        if (!surfaceReady) return;
        SurfaceTexture st = texture.getSurfaceTexture();
        if (st == null) return;
        int w = Math.max(1, buf(surfaceW));
        int h = Math.max(1, buf(surfaceH));

        // ① 槽位里已经有这块屏 → 只换 surface（必要时 resize），displayId 保持不变。
        //    displayId 不变，屏内应用的 task 就不会被系统搬走，新 TextureView 接上就有画面。
        //    这就是"不再卡死"的关键：重建 pane 不再是"换一块屏"，只是"换一根线"。
        PipDisplays.Slot reuse = PipDisplays.slot(key());
        // 渲染比例变了 → 这块屏必须换，**不能 resize**。
        //
        // 这就是当年「画面缩到左上角」的真正原因：比例改成 75% 后走的是
        // VirtualDisplay#resize，它只改输出 buffer 的像素尺寸，屏内应用收不到
        // 新的窗口尺寸（不会重新 measure），于是仍按旧像素画，整块画面缩在左上角。
        // 现在把「比例」记进槽位：只要和当前比例不同，直接丢掉旧屏、按新尺寸新建，
        // 应用在新屏上第一次 measure 就是正确尺寸 —— 不会再缩。
        // 代价是屏内应用会被重新拉起一次（这本来就是"性能设置"，改一次可接受）。
        if (reuse != null && Math.abs(reuse.scale - renderScale) > 0.001f) {
            Log.i(TAG, "pane " + index + " 屏内渲染比例 " + reuse.scale + " → " + renderScale
                    + "：换屏重建（resize 不会让屏内应用重新测量）");
            FileLog.append("RENDERSCALE pane=" + index + " " + reuse.scale + "→" + renderScale
                    + " 换屏重建");
            PipDisplays.release(key());
            reuse = null;
            vd = null;              // 旧屏已真释放，别让后面的兜底路径读到悬垂引用
            displayId = -1;
        }
        if (reuse != null) {
            vd = reuse.vd;
            displayId = reuse.vd.getDisplay() == null ? -1 : reuse.vd.getDisplay().getDisplayId();
            if (displayId < 0) {            // 读到一半失效了：当没有处理
                vd = null;
            } else {
                if (reuse.w != w || reuse.h != h || reuse.dpi != density()) {
                    // resize 失败也不重建屏：TextureView 会把旧尺寸的 buffer 拉伸显示，
                    // 顶多比例不完美；重建却会让屏内 task 掉回主屏（= 卡死）。
                    if (resizeDisplay(w, h, density())) {
                        reuse.w = w;
                        reuse.h = h;
                        reuse.dpi = density();
                    }
                }
                // bind 用的 buffer 尺寸必须是这块屏**当前真实**的像素尺寸，
                // 不是这次算出来的 w/h —— resize 没成功时两者并不相等。
                displayW = reuse.w;
                displayH = reuse.h;
                PipDisplays.bind(reuse, st, displayW, displayH, ownerToken);
                Log.i(TAG, "pane " + index + " 复用 display=" + displayId + " "
                        + displayW + "x" + displayH + " dpi=" + reuse.dpi + "（只换 surface，不动 task）");
                // 焦点锚：屏内应用重建窗口时，这块屏也得有个可聚焦窗口顶着，
                // 否则焦点会整块塌回主屏（→ pauseBackTasks 全屏 pause，见 FocusAnchor 注释）
                syncAnchor();
                showContentAfter(200);
                return;
            }
        }

        // ② 没有可用屏（冷启动 / 屏被真释放过）：新建，并登记到进程级槽位
        Surface surface = new Surface(st);
        // 屏的 flags 直接决定"谁能往这块屏上放窗口"，是"画中画被车机浮层盖住"的开关：
        //   OWN_CONTENT_ONLY —— 只显示 owner 启动的内容（不镜像主屏）；
        //   OWN_DISPLAY_GROUP —— 让这块屏自成 display group，车机 SystemUI 的
        //       "给每块屏挂装饰/媒体卡片"逻辑便不会跟过来（它的全屏 APPLICATION_OVERLAY
        //       就是那把画面盖死、点击吞掉的元凶）；
        //   不再要 PUBLIC —— public 屏对全系统开放，任何应用都能往上摆窗口。
        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        try {
            vd = dm.createVirtualDisplay("PipDesk-" + index, w, h, density(), surface, baseFlags | FLAG_TRUSTED);
        } catch (Throwable t1) {
            Log.w(TAG, "私有屏(trusted)失败，退回不带 trusted", t1);
            try {
                vd = dm.createVirtualDisplay("PipDesk-" + index, w, h, density(), surface, baseFlags);
            } catch (Throwable t2) {
                // 最后兜底：老配置（public）。public 屏会被车机 SystemUI 挂全屏浮层，
                // 属于退化路径，日志里留一条便于现场判断。
                Log.w(TAG, "私有屏建不出来，退回 public（可能被系统浮层覆盖）", t2);
                int pub = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                try {
                    vd = dm.createVirtualDisplay("PipDesk-" + index, w, h, density(), surface, pub | FLAG_TRUSTED);
                } catch (Throwable t3) {
                    try {
                        vd = dm.createVirtualDisplay("PipDesk-" + index, w, h, density(), surface, pub);
                    } catch (Throwable t4) {
                        Log.w(TAG, "createVirtualDisplay 失败", t4);
                        vd = null;
                    }
                }
            }
        }
        // 虚拟屏已持有 producer 引用，本地 Surface 可以立即释放
        try {
            surface.release();
        } catch (Throwable ignored) { }
        displayId = (vd == null || vd.getDisplay() == null) ? -1 : vd.getDisplay().getDisplayId();
        displayW = w;
        displayH = h;
        Log.i(TAG, "pane " + index + " display=" + displayId + " " + w + "x" + h + " dpi=" + dpi);
        if (displayId >= 0) {
            PipDisplays.Slot s = new PipDisplays.Slot();
            s.vd = vd;
            s.w = w;
            s.h = h;
            s.dpi = dpi;
            s.scale = renderScale;      // 记下建屏比例：之后比例变了要换屏，不能 resize
            PipDisplays.putOwned(key(), s, ownerToken);
            ignoreOrientationRequest(displayId);
            syncAnchor();
        }
    }

    /** 本 pane 对应的槽位名（进程级虚拟屏按它复用） */
    private String key() {
        return "PipDesk-" + index;
    }

    /**
     * 让本屏的「焦点锚」跟随屏内应用（详见 {@link FocusAnchor}）。
     *
     * 只给系统蓝牙 / 系统音乐挂：它们会在音频焦点切换时重建自己的窗口，
     * 是这台车机上唯一能把两块屏一起卡死的源头。其它应用不挂 —— 锚是屏上最顶层的
     * 可聚焦窗口，挂着会让屏内应用收不到按键和输入法（实测波点搜索框打不进字）。
     */
    private void syncAnchor() {
        if (displayId < 0) return;
        FocusAnchor.setWanted(app, displayId, index, FocusAnchor.needsAnchor(pkg));
    }

    /**
     * 让这块虚拟屏「忽略应用的方向请求」。
     *
     * 竖屏锁定的应用（头条、QQ 这类）在横屏虚拟屏上会被系统 letterbox 成中间
     * 一条窄带（实测 1176 宽的屏只给 604 宽，两侧全是死区，点了没反应）。
     * Android 12+ 的 WindowManager 提供 per-display 的 ignoreOrientationRequest，
     * uid 1000 可以调；调不通就保持系统默认的 letterbox（不影响功能）。
     */
    private void ignoreOrientationRequest(int display) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            for (java.lang.reflect.Method m : wm.getClass().getMethods()) {
                if (!"setIgnoreOrientationRequest".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class) {   // 只接受 per-display 版本
                    m.invoke(wm, display, true);
                    Log.i(TAG, "pane " + index + " 已忽略方向请求 display=" + display);
                    return;
                }
            }
            Log.i(TAG, "pane " + index + " 无 per-display 忽略方向接口");
        } catch (Throwable t) {
            Log.i(TAG, "pane " + index + " 忽略方向请求失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 解绑（Activity 销毁 / 纹理销毁 / pane 关闭）：只把 surface 从虚拟屏上摘掉，**屏留着**。
     *
     * 以前这里 `vd.release()`：屏一销毁，屏内 task 就被系统丢回主屏，接着还得 restoreDesktop()
     * 把桌面拉回来，用户看到的就是"桌面闪一下 + 应用变全屏"。更要命的是下次 open 会建一块
     * **新 displayId** 的屏，而应用 task 还在旧 id 上 —— 新屏永远空白 = 卡死。
     */
    private void releaseDisplay() {
        PipDisplays.detach(key(), ownerToken);
        vd = null;
        displayId = -1;
        displayW = displayH = 0;
    }

    /**
     * 真销毁这块屏。只在两种"屏确实要换"的场合用：
     *   1) 减少屏数（槽位不再需要）；
     *   2) 用户显式改「屏内渲染分辨率」（要连带重启屏内应用，见 setRenderScale）。
     * 日常的重排/重建一律走 {@link #createDisplay()} 的复用分支，绝不动 displayId。
     */
    private void destroyDisplay() {
        boolean hadApp = livePkg != null || pkg != null;
        livePkg = null;
        // 带属主：旧实例退场时不能把新实例正在用的屏 release 掉
        PipDisplays.releaseOwned(key(), ownerToken);
        vd = null;
        displayId = -1;
        displayW = displayH = 0;
        // 屏被真销毁时 task 才会被系统搬回主屏，这时候才需要把桌面拉回前台
        if (hadApp) restoreDesktop();
    }

    /**
     * 把桌面拉回前台。设置页在前台时不抢焦点。
     *
     * ★ 只拉一次，且只在"桌面真的被盖住了"的时候拉。
     *   每次 startActivity(MainActivity) 都会让系统把桌面 task 前置/reparent，
     *   而 resume 一块屏上的 activity 会触发 `pauseBackTasks` ——
     *   这台 ROM 上它是**全屏生效**的，会把两块虚拟屏上的 activity 一起 pause：
     *     wm_set_resumed_activity [0,richdesk/.MainActivity,reparent]
     *     wm_pause_activity [com.tw.bt/.ATBluetoothActivity,pauseBackTasks]
     *     wm_pause_activity [cn.wenyu.bodian/.MainActivity,pauseBackTasks]
     *   画中画随之停帧。原来这里 delay{180,600} 拉两次 = 连打两轮 pause。
     */
    private void restoreDesktop() {
        // 注意：任务被搬到主屏的瞬间，系统会把桌面 pause 掉（MainActivity.mainResumed 变 false），
        // 所以这里必须在释放前就把"桌面当时是否在前台"记下来，不能等延迟任务里再判断。
        if (!AppState.mainResumed) return;
        main.postDelayed(() -> {
            if (!AppState.mainResumed) return;
            try {
                // 明确拉自己的桌面（singleTask，只会前置不会重建），
                // 用 CATEGORY_HOME 的话会被系统默认桌面接管
                Intent h = new Intent(app, MainActivity.class);
                h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(h);
            } catch (Throwable t) {
                Log.w(TAG, "拉回桌面失败", t);
            }
        }, 400);
    }

    private final Runnable recreateTask = this::recreateNow;

    /** surface 是否已就绪（单画首开链路的状态可见性用） */
    boolean isSurfaceReady() {
        return surfaceReady;
    }

    /**
     * 单画首开看门狗。
     *
     * 「点图标第一次，单画不显示，第二次才正常」的链路是：
     *   open（surface 未就绪，挂起）→ onSurfaceTextureAvailable → recreateNow
     *   → 200ms 后 createDisplay → relaunchAfterStop（force-stop → 300ms → launchOn）
     * 五步里任何一环的守护条件不满足就**静默 return**，用户看到的就是"点了没反应"，
     * 而第二次点的时候 surface/屏多半已就绪，走的是直通路径，所以"第二次才正常"。
     * 这里在 openSingle 后 3 秒做一次体检：没建屏就重建，建了屏没出帧就补一次拉起；
     * 全链路状态落日志，再复现时日志直接给出断点。
     */
    void firstOpenWatchdog(String target) {
        if (target == null || !target.equals(pkg)) return;
        boolean displayOk = vd != null && displayId >= 0;
        FileLog.append("单画看门狗：" + target + " surface=" + surfaceReady
                + " display=" + displayId + " 帧数=" + frames
                + " 纹理=" + texture.getWidth() + "x" + texture.getHeight()
                + " loading可见=" + (loading.getVisibility() == View.VISIBLE));
        if (!displayOk) {
            if (!surfaceReady && texture.isAvailable()) {
                surfaceReady = true;
                surfaceW = Math.max(1, texture.getWidth());
                surfaceH = Math.max(1, texture.getHeight());
            }
            FileLog.append("单画看门狗：虚拟屏未建成 → 重新走建屏+启动");
            recreateNow();
            return;
        }
        if (frames == 0) {
            // 屏在但应用没出帧：不做 force-stop（应用可能只是冷启动慢），补一次拉到前台
            FileLog.append("单画看门狗：屏在但无帧 → 补一次 launchOn");
            launchOn(target, displayId);
            ensureOnDisplay(target, displayId);
            showContentAfter(800);
        }
    }


    private void scheduleRecreate() {
        main.removeCallbacks(recreateTask);
        main.postDelayed(recreateTask, 250);
    }

    /**
     * 真·重建（**只有改 DPI / 渲染分辨率时才走这里**，日常重排绝不进）。
     *
     * 这类改动靠 resize 改不动（屏内应用不会重新测量，画面缩在左上角），必须换一块屏。
     * 换屏就意味着屏内 task 会被系统丢回主屏，而且新屏再 launchOn 也拉不回来
     * （activity 实例还在 → AMS 只做 START_DELIVERED_TO_TOP，不 reparent）。
     * 所以这里直接把屏内应用进程 force-stop 掉，让下一次启动建一个全新 task 落在新屏上。
     */
    private void recreateNow() {
        final String keep = pkg;
        // 旧屏不再需要：真释放（不带 clearDisplayApp —— 空白页在新屏上没意义）
        destroyDisplay();
        if (keep == null || !surfaceReady) {
            FileLog.append("recreateNow(" + keep + ") 挂起：surface=" + surfaceReady);
            return;
        }
        main.postDelayed(() -> {
            if (!keep.equals(pkg) || !surfaceReady) {
                FileLog.append("recreateNow 延迟任务放弃：" + keep
                        + " pkg=" + pkg + " surface=" + surfaceReady);
                return;
            }
            createDisplay();
            if (displayId < 0) {
                FileLog.append("recreateNow 建屏失败（" + keep + "），本轮不再重试");
                return;
            }
            livePkg = keep;
            relaunchAfterStop(keep, 300);
        }, 200);
    }

    // ------------------------------------------------------------ 启动应用

    private void launchOn(String targetPkg, int display) {
        Intent i = app.getPackageManager().getLaunchIntentForPackage(targetPkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(display);
            // 注：试过用 freeform（setLaunchBounds + windowingMode=5）消除竖屏应用的
            // letterbox，结果 MIUI 会把它当成浮动窗口（带黑色装饰边、还把窗口挪到主屏），
            // 观感更差，已回退。竖屏锁定应用在横屏虚拟屏上被系统 letterbox 是 Android
            // 的标准行为，内容区之外的窄边不属于应用，点了本来就不会有反应。
            app.startActivity(i, o.toBundle());
            return;
        } catch (Throwable t) {
            Log.w(TAG, "进程内启动到 display " + display + " 失败，改走 su", t);
        }
        ComponentName cn = i.getComponent();
        if (cn != null) {
            Shell.run("am start --user 0 --display " + display + " -n " + cn.flattenToString());
        }
    }


    /**
     * 兜底校验（需求 7）：个别应用（例如小米指南针，manifest 里标了不可 resize）被启动到虚拟屏后，
     * 系统的任务分配可能把它的 task 仍落在主屏 —— 表现出来的就是"画中画闪一下，应用跑到全屏"。
     * 启动 1.2s 后查一次该应用在主屏上的任务；有就搬到本面板的虚拟屏上。
     * 全程 try/catch：拿不到系统接口（非系统 uid / 高版本改了隐藏接口）时只记日志，不影响正常使用。
     */
    private void ensureOnDisplay(final String targetPkg, final int display) {
        if (display < 0) return;
        main.postDelayed(() -> {
            if (!targetPkg.equals(pkg)) return;
            // findTask（getRunningTasks）是 binder 调用：整段丢后台，别占主线程
            new Thread(() -> {
                int taskDisp = findTaskDisplay(targetPkg);
                if (taskDisp >= 0 && taskDisp != display) {
                    Log.w(TAG, "任务不在 display " + display + "（在 " + taskDisp
                            + "），搬回 (" + targetPkg + ")");
                    forceToDisplay(targetPkg, display);
                }
            }, "pipdesk-findtask").start();
        }, 1200);
    }

    /**
     * 把应用 task 弄回本面板的虚拟屏。
     *
     * **规则：task 已在本屏 → 只补 resume，绝不动它的位置；
     *          task 在别的屏 → 删掉重建到本屏，绝不用 move-stack。**
     *
     * 为什么彻底禁用跨屏搬 task（车机 UIS7862 全量日志实证）：
     *
     *   20:33:23.455  InputDispatcher: Found window Letterbox_right_ActivityRecord{...}
     *                 in display 0, but it should belong to display 85
     *   20:33:23.456  wm_pause_activity [cn.wenyu.bodian/.MainActivity, pauseBackTasks]
     *
     * 主屏是 1920x1200，虚拟屏是 629x1045 / 1259x1045。task 只要在 display 0 上
     * 出现过一次，WindowManager 就会给它挂一对 Letterbox 子窗口（因为尺寸不合）。
     * 用 move-stack 把它挪到虚拟屏时，**子窗口的输入通道仍然登记在 display 0**。
     * 而 WMS 与 InputDispatcher 正是靠这个归属来仲裁「哪块屏持有输入焦点」：
     *
     *   InputDispatcher: Focused display #84 does not have a focused window.
     *   InputDispatcher: But another display has a focused window
     *   InputDispatcher:   FocusedWindows: displayId=0, name='...richdesk/MainActivity'
     *
     * 两边对不上 → WMS 放弃，把虚拟屏的 mFocusedApp 清成 null →
     * 焦点回落主屏 home → home 被 reparent 成 resumed → `pauseBackTasks`
     * 把**所有屏**上 resumed 的 activity 一起 pause → 画中画只剩最后一帧。
     *
     * 所以每搬一次，都是在给下一轮焦点仲裁埋雷。删了重建虽然会重建一次 activity
     * （removeTask 只销毁 activity，进程和常驻 service 不动，蓝牙放歌不受影响），
     * 但新 task 从出生就带着虚拟屏的尺寸，不会在主屏产生 letterbox。
     */
    private void forceToDisplay(final String targetPkg, final int display) {
        new Thread(() -> {
            final int[] st = taskState(targetPkg);
            final int tid = findTaskId(targetPkg);
            final int at = (st == null) ? -1 : st[0];
            // ★ 关键：task 已经在本屏 → **绝对不能搬**。
            //   搬（move-stack）是跨屏 reparent，会在这台 ROM 上留下输入窗口归属错乱
            //   （InputDispatcher: Found window Letterbox_... in display 0, but it should
            //   belong to display 85），而 WMS 正是靠这个归属仲裁「哪块屏持有焦点」。
            //   归属一错，下一轮焦点重算就会把虚拟屏的 mFocusedApp 清成 null，
            //   焦点回落主屏 home，home 被 resume 就 pauseBackTasks 把两块屏一起 pause。
            //   这就是"越救越卡"的成因：我们的自愈本身在污染焦点系统。
            final boolean needRebuild = at != display;
            Log.i(TAG, "自愈动作：" + targetPkg + " task=" + tid + " 在屏" + at
                    + "，目标 " + display + (needRebuild ? " → 重建到本屏" : " → 只补 resume"));
            if (!needRebuild) {
                // 就在本屏：只补一次 startActivity 让它 resume，不碰 task 位置
                main.postDelayed(() -> {
                    if (!targetPkg.equals(pkg) || displayId != display) return;
                    livePkg = targetPkg;
                    launchOn(targetPkg, display);
                    showContentAfter(450);
                    final long base = frames;
                    main.postDelayed(() -> {
                        if (!targetPkg.equals(pkg) || displayId != display) return;
                        if (frames > base) return;
                        // resume 也救不回来（activity 被系统判成不该在这块屏）→ 重建
                        Log.w(TAG, "resume 后 1.5s 无新帧，改为删 task 重建：" + targetPkg);
                        rebuildOn(targetPkg, display);
                    }, 1500);
                }, 300);
                return;
            }
            // task 真的跑在别的屏（大多是系统把它拉回主屏了）。
            // 这里**不再 move-stack**：它会在 display 0 留下 letterbox 输入窗口残骸。
            // 直接删掉旧 task，在目标虚拟屏上重新建 —— 新 task 从出生就带着虚拟屏的
            // 尺寸/DPI，不会在主屏产生 letterbox，也就不会污染下一轮焦点仲裁。
            // removeTask 只销毁 activity，进程与常驻 service 不动，蓝牙放歌不受影响。
            rebuildOn(targetPkg, display);
        }, "pipdesk-forcedisp").start();
    }

    /** 删掉旧 task，在目标虚拟屏上重建（唯一允许的"换屏"手段，不用 move-stack） */
    private void rebuildOn(final String targetPkg, final int display) {
        new Thread(() -> {
            final int t = findTaskId(targetPkg);
            if (t >= 0) {
                boolean ok = removeTask(t);
                Log.i(TAG, "重建到本屏：移除旧 task " + t + "(" + targetPkg + ") ok=" + ok);
            }
            main.postDelayed(() -> {
                if (!targetPkg.equals(pkg) || displayId != display) return;
                Log.i(TAG, "重建到本屏：display " + display + " 新建 task " + targetPkg);
                livePkg = targetPkg;
                launchOn(targetPkg, display);
                showContentAfter(450);
            }, 260);
        }, "pipdesk-rebuild").start();
    }

    /**
     * 全局配置（夜间模式等）变化后的兜底校验。
     *
     * UiModeManager.setNightMode 是全局 uiMode 变更：屏内应用没声明 uiMode
     * configChanges 的会被销毁重建，这台 ROM 上重建后 task 偶尔被丢回主屏，
     * 表现就是「切个深浅色，画中画里的应用被切走了」。
     * 还在本屏 → 不动；落到别的屏 → 搬回；task 整个没了 → 重新拉起
     * （launchOn 带的是 launch intent，应用其实还活着时这就是一次带回前台，无害）。
     */
    public void reensureTask() {
        final String keep = pkg;
        final int disp = displayId;
        if (keep == null || disp < 0) return;
        new Thread(() -> {
            Object t = findTaskInfo(keep);
            if (t == null) {
                Log.w(TAG, "配置变更后任务消失，重新拉起 (" + keep + ")");
                main.post(() -> {
                    if (keep.equals(pkg) && displayId >= 0) launchOn(keep, displayId);
                });
                return;
            }
            Integer td = intField(t, "displayId");
            if (td == null) return;          // 查得到 task 但查不到所在屏：别瞎折腾
            if (td == disp) return;          // 就在本屏：正常
            Log.w(TAG, "配置变更后任务在 display " + td + "，不在本屏 " + disp
                    + "，搬回 (" + keep + ")");
            forceToDisplay(keep, disp);
        }, "pipdesk-reeensure").start();
    }

    // ------------------------------------------------- 全体冻结自愈

    /**
     * 全体画中画同时冻住时的恢复入口（由 {@link PaneGuard} 调用）。
     *
     * 「什么时候算冻住」由 PaneGuard 判定（它只看每块屏最后一帧的时刻，所有屏
     * 同时静止才开始探测），这里只负责动作，仍然复用 {@link #forceToDisplay} 的分流：
     *   · task 还在这块虚拟屏上、只是 activity 被系统 stop →
     *     只补一次 resume，画面从冻住那一帧继续（用户看不出中间断开过）；
     *   · task 已经被系统拽到主屏 →
     *     删 task 在本屏重建。不能搬：move-stack 会在 display 0 留下 letterbox
     *     输入窗口，污染 WMS 的焦点归属，反而把两块屏一起拖死（见 forceToDisplay）。
     */
    public void reviveAllFrozen(String why) {
        final String keep = pkg;
        final int disp = displayId;
        if (keep == null || disp < 0 || fullscreened) return;
        Log.w(TAG, "全体冻结自愈：pane " + index + " " + keep
                + " 静止 " + msSinceLastFrame() + "ms（" + why + "）");
        FileLog.append("REVIVE-ALL pane=" + index + " pkg=" + keep
                + " silent=" + msSinceLastFrame() + "ms why=" + why
                + " @ " + FileLog.nowStamp());
        forceToDisplay(keep, disp);
    }

    /**
     * 一次全系统 task 扫描，批量回答「这些包名各在哪块屏上」。
     *
     * 给 PaneGuard 用：冻结判定之后所有屏共用这一次扫描。老实现是每块屏各自
     * 每 1.2~30 秒扫一遍（车机日志实测 8 分钟 110 次全校举），本身就是负载。
     *
     * 查不到的包名不会出现在结果里 —— 但调用方**不能**据此认定 task 没了：
     * getRunningTasks 只返回前若干条，漏掉是常事（老代码也是「查不到就别动」）。
     */
    public static java.util.Map<String, Integer> taskDisplays(Context ctx,
                                                             java.util.Collection<String> pkgs) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        if (ctx == null || pkgs == null || pkgs.isEmpty()) return out;
        List<?> tasks = null;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) tasks = am.getRunningTasks(50);
        } catch (Throwable ignored) { }
        if (tasks == null) {
            try {
                Object atm = activityTaskManager();
                if (atm != null) {
                    for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                        if (!"getAllRootTaskInfos".equals(m.getName())) continue;
                        if (m.getParameterTypes().length != 0) continue;
                        tasks = unwrapSlice(invokeQuiet(m, atm));
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        if (tasks == null) return out;
        for (String pkg : pkgs) {
            if (pkg == null) continue;
            Object t = scanTask(tasks, pkg);
            if (t == null) continue;
            Integer d = intField(t, "displayId");
            if (d != null) out.put(pkg, d);
        }
        return out;
    }

    /**
     * 查 task 的 [displayId, numRunning]；任一读不到返回 null。
     * numRunning == 0 = task 在这块屏但里面的 activity 全被 stop 了。
     *
     * 为什么不用 visible 做判据：getRunningTasks() 在部分 ROM 上只填基础字段，
     * visible 恒为 false，拿它判定会不停把静止页面拉一遍。
     */
    private int[] taskState(String targetPkg) {
        Object t = findTaskInfo(targetPkg);
        if (t == null) return null;
        Integer disp = intField(t, "displayId");
        Integer running = intField(t, "numRunning");
        if (disp == null || running == null) return null;
        return new int[]{disp, running};
    }

    /**
     * 找应用当前所在 task 的 id；找不到返回 -1。
     *
     * 查找顺序（实测踩坑记录）：
     *   1) 公共 API ActivityManager.getRunningTasks() —— 本应用 uid 1000 + PRIVILEGED，
     *      能拿到全部 task（含虚拟屏）。零反射，优先用；
     *   2) getAllRootTaskInfos()（Android 12+，唯一确定覆盖所有 display 的口）；
     *   3) getTasks(maxNum)，任意形参数量都接受。
     *
     * 【重要】入口 binder 不能用 ActivityTaskManager.getService()：它在这台
     * HyperOS 的隐藏 API blocklist 上，反射直接 NoSuchMethodException（logcat 里有
     * "blocked, reflection, denied"）。必须走 ServiceManager + Stub.asInterface，
     * 这条链路实测可用（IWindowManager 同款写法已经跑通）。
     */
    private int findTaskId(String targetPkg) {
        Object t = findTaskInfo(targetPkg);
        if (t == null) return -1;
        Integer tid = intField(t, "taskId");
        return tid == null ? -1 : tid;
    }

    /**
     * 找应用当前 task 所在的 displayId（与 findTaskId 同一套扫描）；找不到返回 -1。
     * 注意：不能走 getTaskForTaskId 反射 —— 那条路在这台 HyperOS 上查不到，
     * 永远返回"已在屏上"，错位检测形同虚设；getRunningTasks 的 task 信息里
     * 自带 displayId，实测可用。
     */
    private int findTaskDisplay(String targetPkg) {
        Object t = findTaskInfo(targetPkg);
        if (t == null) return -1;
        Integer disp = intField(t, "displayId");
        return disp == null ? -1 : disp;
    }

    /**
     * 三级降级找应用的 task 信息对象（taskId + displayId 都在上面）。
     * 查找顺序（实测踩坑记录）：
     *   1) 公共 API ActivityManager.getRunningTasks() —— 本应用 uid 1000 + PRIVILEGED，
     *      能拿到全部 task（含虚拟屏）。零反射，优先用；
     *   2) getAllRootTaskInfos()（Android 12+，唯一确定覆盖所有 display 的口）；
     *   3) getTasks(maxNum)，任意形参数量都接受。
     *
     * 【重要】入口 binder 不能用 ActivityTaskManager.getService()：它在这台
     * HyperOS 的隐藏 API blocklist 上，反射直接 NoSuchMethodException（logcat 里有
     * "blocked, reflection, denied"）。必须走 ServiceManager + Stub.asInterface，
     * 这条链路实测可用（IWindowManager 同款写法已经跑通）。
     */
    private Object findTaskInfo(String targetPkg) {
        // 1) 公共 API，无隐藏 API 风险
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            Object found = scanTask(am.getRunningTasks(30), targetPkg);
            if (found != null) {
                Log.i(TAG, "findTask(" + targetPkg + ") 命中 (getRunningTasks)");
                return found;
            }
        } catch (Throwable t) {
            Log.i(TAG, "getRunningTasks 不可用：" + t.getClass().getSimpleName());
        }
        try {
            Object atm = activityTaskManager();
            if (atm != null) {
                Class<?> iface = atm.getClass();   // binder 代理，接口方法在代理类上可查
                // 2) getAllRootTaskInfos()（Android 12+，返回 ParceledListSlice）
                for (java.lang.reflect.Method m : iface.getMethods()) {
                    if ("getAllRootTaskInfos".equals(m.getName())
                            && m.getParameterTypes().length == 0) {
                        Object found = scanTask(unwrapSlice(invokeQuiet(m, atm)), targetPkg);
                        if (found != null) {
                            Log.i(TAG, "findTask(" + targetPkg + ") 命中 (rootTasks)");
                            return found;
                        }
                    }
                }
                // 3) getTasks(maxNum)：老版本签名不定，按第一个 int 形参填 30，其余填默认值
                for (java.lang.reflect.Method m : iface.getMethods()) {
                    if (!"getTasks".equals(m.getName())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length < 1 || pt[0] != int.class) continue;
                    Object[] args = defaultArgs(pt);
                    args[0] = 30;
                    Object found = scanTask(unwrapSlice(invokeQuiet(m, atm, args)), targetPkg);
                    if (found != null) {
                        Log.i(TAG, "findTask(" + targetPkg + ") 命中 (getTasks)");
                        return found;
                    }
                }
            }
            Log.i(TAG, "findTask(" + targetPkg + ") 未找到（接口全试过）");
        } catch (Throwable t) {
            Log.i(TAG, "查 task 跳过：" + t.getClass().getSimpleName());
        }
        return null;
    }

    /** ParceledListSlice → List；本来就是 List 就直接用，解不开返回 null */
    private static List<?> unwrapSlice(Object r) {
        if (r == null) return null;
        if (r instanceof List) return (List<?>) r;
        try {
            return (List<?>) r.getClass().getMethod("getList").invoke(r);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeQuiet(java.lang.reflect.Method m, Object obj, Object... args) {
        try {
            return m.invoke(obj, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按形参类型填默认值：int/long → 0，boolean → false，其余 → null */
    private static Object[] defaultArgs(Class<?>[] pt) {
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) {
            Class<?> t = pt[i];
            if (t == int.class || t == long.class || t == short.class || t == byte.class) args[i] = 0;
            else if (t == boolean.class) args[i] = false;
            else if (t == float.class) args[i] = 0f;
            else if (t == double.class) args[i] = 0d;
            else if (t == char.class) args[i] = (char) 0;
            else args[i] = null;
        }
        return args;
    }

    /**
     * 在 task 信息列表里找目标包名，返回命中的 task 信息对象本身（taskId/displayId
     * 都能从它身上取）。命中：topActivity 的包名，或 childTaskNames（根 task 的子任务名，
     * 形如 "com.xx.app/.ui.MainActivity"）里有该包 —— RootTaskInfo 的
     * topActivity 经常是 null，childTaskNames 是更稳的字段。
     * 多个命中时优先**非主屏**的那个（全屏打开要搬的就是虚拟屏上那个）。
     */
    private static Object scanTask(List<?> tasks, String targetPkg) {
        if (tasks == null) return null;
        Object fallback = null;
        for (Object t : tasks) {
            if (t == null) continue;
            Integer tid = intField(t, "taskId");
            if (tid == null || tid < 0) continue;
            Integer disp = intField(t, "displayId");
            boolean hit = false;
            Object top = objField(t, "topActivity");
            if (top instanceof ComponentName
                    && targetPkg.equals(((ComponentName) top).getPackageName())) hit = true;
            if (!hit) {
                Object names = objField(t, "childTaskNames");
                if (names instanceof String[]) {
                    for (String n : (String[]) names) {
                        if (n != null && n.startsWith(targetPkg + "/")) { hit = true; break; }
                    }
                }
            }
            if (!hit) continue;
            if (disp != null && disp != android.view.Display.DEFAULT_DISPLAY) return t;
            if (fallback == null) fallback = t;
        }
        return fallback;
    }

    private static Integer intField(Object o, String name) {
        try {
            return o.getClass().getField(name).getInt(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object objField(Object o, String name) {
        try {
            return o.getClass().getField(name).get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调试用：把系统任务表（taskId / displayId / base / top）打成一串，验证任务查询在本机能否用 */
    public static String debugDumpTasks(Context ctx, String pkg) {
        StringBuilder sb = new StringBuilder("tasks want=" + pkg + " ");
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> ts = am.getRunningTasks(30);
            sb.append("count=").append(ts == null ? -1 : ts.size()).append(" | ");
            if (ts != null) {
                for (ActivityManager.RunningTaskInfo t : ts) {
                    Object top = objField(t, "topActivity");
                    Object base = objField(t, "baseActivity");
                    Integer disp = intField(t, "displayId");
                    Integer id = intField(t, "taskId");
                    sb.append("#").append(id)
                            .append(" d=").append(disp)
                            .append(" base=").append(top == null ? "?" : shortComp(base))
                            .append(" top=").append(top == null ? "?" : shortComp(top))
                            .append(" || ");
                }
            }
        } catch (Throwable t) {
            sb.append("ERR ").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String shortComp(Object o) {
        if (!(o instanceof ComponentName)) return String.valueOf(o);
        ComponentName c = (ComponentName) o;
        return c.getPackageName() + "/" + c.getClassName();
    }

    /**
     * 拿系统任务栈服务的 binder 代理（IActivityTaskManager / 老 IActivityManager）。
     * 返回 null 表示全都没走通。
     *
     * 【为什么绕开 ActivityTaskManager.getService()】它在这台 HyperOS（Android 13）
     * 的隐藏 API blocklist 上，反射直接被拒（NoSuchMethodException）。而
     * ServiceManager.getService + Stub.asInterface 这条链路不被拦 —— IWindowManager
     * 同款写法已在 setIgnoreOrientationRequest 里跑通。
     */
    private static Object activityTaskManager() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getMethod("getService", String.class);
            // 12+ 服务名叫 "activity_task"，老版本叫 "activity"；接口对应两个 Stub
            String[] services = {"activity_task", "activity"};
            String[] stubs = {"android.app.IActivityTaskManager$Stub",
                    "android.app.IActivityManager$Stub"};
            for (String s : services) {
                Object b = invokeQuiet(getService, null, s);
                if (b == null) continue;
                for (String st : stubs) {
                    try {
                        Object atm = Class.forName(st)
                                .getMethod("asInterface", android.os.IBinder.class)
                                .invoke(null, b);
                        if (atm != null) return atm;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "binder 入口不可用：" + t.getClass().getSimpleName());
        }
        // 兜底：老入口（部分版本可行；被 blocklist 拦就返回 null）
        try {
            return Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * 把 task 搬到指定 display。moveTaskToDisplay 的形参在不同版本不一样：
     * 新版本是 (taskId, displayId, moveHomeAlongside)，老版本只有 (taskId, displayId)，
     * 两种都试，哪个匹配用哪个。
     */
    private static boolean moveTask(int taskId, int display) {
        try {
            Object atm = activityTaskManager();
            if (atm == null) {
                Log.i(TAG, "搬 task 失败：拿不到任务栈服务");
                return false;
            }
            // moveTaskToDisplay 的形参在不同版本不一样：新版本是
            // (taskId, displayId, moveHomeAlongside)，老版本只有 (taskId, displayId)
            for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                if (!"moveTaskToDisplay".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class) {
                    m.invoke(atm, taskId, display, pt[2] == boolean.class);
                    Log.i(TAG, "moveTask " + taskId + " -> display " + display);
                    return true;
                }
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                    m.invoke(atm, taskId, display);
                    Log.i(TAG, "moveTask " + taskId + " -> display " + display);
                    return true;
                }
            }
            Log.i(TAG, "moveTaskToDisplay 无匹配签名（可能被隐藏 API blocklist 过滤）");
        } catch (Throwable t) {
            Log.i(TAG, "搬 task 反射失败：" + t.getClass().getSimpleName());
        }
        // 反射走不通（MIUI 把 moveTaskToDisplay 也 blocklist 了）就用 shell 命令搬。
        // 命令在 system_server 里调同一个 binder，不受**本进程**隐藏 API 限制；
        // uid 1000 有 MANAGE_TASKS，过得了权限检查。实测（HyperOS V140 / A13）：
        //   - `am move-task <id> <display>`：已移除，报 Unknown command；
        //   - `am display move-stack <id> <display>`：可用 —— 画中画里的应用一定是
        //     虚拟屏上的根 task（rootOfTask），根 task 的 id 就是 stackId。
        if (Shell.runSync("am display move-stack " + taskId + " " + display)) {
            Log.i(TAG, "moveTask(shell move-stack) " + taskId + " -> display " + display);
            return true;
        }
        if (Shell.runSync("am move-task " + taskId + " " + display)) {
            Log.i(TAG, "moveTask(shell move-task) " + taskId + " -> display " + display);
            return true;
        }
        Log.i(TAG, "moveTask shell 命令全部失败 " + taskId + " -> display " + display);
        return false;
    }

    // ------------------------------------------------------------ 旧应用 task 回收

    /**
     * 换应用 / 关闭画中画之后，把**旧应用在本屏上的 task** 移除。
     *
     * 为什么必须做：虚拟屏是 ON 的，栈里那些"曾经打开过"的 task 会被系统当成
     * 可见任务 —— 进程常驻、surface 常驻，LMK 也不轻易回收。用户每换一次应用就
     * 往栈底压一个，三块屏一起攒，跑一阵子内存就被吃光，表现出来就是越来越卡、
     * 屏内应用还会被系统冻住（画面停在最后一帧、点了没反应）。
     *
     * 只移除 task（不 force-stop 进程），并且这个包名还在别的屏上跑时不动。
     */
    private void scheduleReap(final String targetPkg, long delayMs) {
        if (targetPkg == null) return;
        final int display = displayId;
        if (display < 0) return;
        main.postDelayed(() -> {
            if (targetPkg.equals(pkg)) return;              // 又切回来了
            if (cb != null && cb.isPkgUsedElsewhere(targetPkg, display)) {
                Log.i(TAG, "reap 跳过 " + targetPkg + "：还在别的屏上用着");
                return;
            }
            new Thread(() -> {
                int tid = findTaskIdOnDisplay(targetPkg, display);
                if (tid < 0) {
                    Log.i(TAG, "reap " + targetPkg + "：本屏已无 task（d=" + display + "）");
                    return;
                }
                if (removeTask(tid)) {
                    Log.i(TAG, "reap 已移除旧任务 " + targetPkg + " task=" + tid + " d=" + display);
                } else {
                    Log.i(TAG, "reap 失败（removeTask 不可用）" + targetPkg + " task=" + tid);
                }
            }, "pipdesk-reap").start();
        }, Math.max(300, delayMs));
    }

    /**
     * 一屏只留一个应用：把这块虚拟屏上**其余历史 task** 全移除（保留 keepPkg）。
     *
     * 不清理会怎样（实测）：同一块屏会攒下一串 task —— 我们只能关掉"这块屏记着的
     * 那个应用"，之前在这块屏上开过的（被 BlankActivity 顶到后台的）task 会一直躺在
     * 栈里。于是用户按返回键时，看到的不是"应用退出"，而是**上一个应用被翻出来**，
     * 有的人会以为返回键坏了。
     *
     * keepPkg = null 表示"这块屏现在空闲，除了桌面自己的空白页全清"。
     * 必须在后台线程调用（dumpsys + binder）。
     */
    public void trimTasks(final String keepPkg) {
        final int d = displayId;
        if (d < 0) return;
        new Thread(() -> {
            try {
                String cmd = "dumpsys activity activities 2>/dev/null | awk -v h='Display #" + d
                        + " (activities' 'index($0,h)==1{inb=1;next}"
                        + " inb&&substr($0,1,9)==\"Display #\"{inb=0}"
                        + " inb&&/\\* Task/{print}'";
                String out = Shell.capture(cmd);
                if (out == null) return;
                java.util.regex.Pattern pId = java.util.regex.Pattern.compile("#(\\d+)");
                java.util.regex.Pattern pPkg =
                        java.util.regex.Pattern.compile("A=\\d+:([A-Za-z0-9_.]+)");
                int removed = 0;
                for (String line : out.split("\n")) {
                    if (!line.contains("Task{")) continue;
                    java.util.regex.Matcher mi = pId.matcher(line);
                    java.util.regex.Matcher mp = pPkg.matcher(line);
                    if (!mi.find() || !mp.find()) continue;
                    String pkgOnTask = mp.group(1);
                    if (pkgOnTask == null) continue;
                    if (pkgOnTask.startsWith("com.cz.richdesk")) continue;  // 空白页/桌面自己
                    if (pkgOnTask.equals(keepPkg)) continue;
                    int tid = Integer.parseInt(mi.group(1));
                    if (removeTask(tid)) {
                        removed++;
                        Log.i(TAG, "trim 移除 d=" + d + " 上的历史任务 " + pkgOnTask + " task=" + tid);
                    }
                }
                if (removed > 0) {
                    FileLog.append("trim：display " + d + " 清掉 " + removed
                            + " 个历史任务（保留 " + (keepPkg == null ? "无" : keepPkg) + "）");
                }
            } catch (Throwable t) {
                Log.i(TAG, "trim 失败 d=" + d + " " + t.getClass().getSimpleName());
            }
        }, "pipdesk-trim").start();
    }

    /** 在指定屏上找某应用的 task（只看这块屏，避免误伤主屏/别的屏上的同一个应用） */
    private static int findTaskIdOnDisplay(String targetPkg, int display) {
        try {
            Object atm = activityTaskManager();
            if (atm != null) {
                for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                    if ("getAllRootTaskInfos".equals(m.getName())
                            && m.getParameterTypes().length == 0) {
                        int tid = scanTasksOnDisplay(unwrapSlice(invokeQuiet(m, atm)),
                                targetPkg, display);
                        if (tid >= 0) return tid;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    /** 在 task 列表里找「指定屏 + 指定包名」的 taskId */
    private static int scanTasksOnDisplay(List<?> tasks, String targetPkg, int display) {
        if (tasks == null) return -1;
        for (Object t : tasks) {
            if (t == null) continue;
            Integer disp = intField(t, "displayId");
            if (disp == null || disp != display) continue;
            Integer tid = intField(t, "taskId");
            if (tid == null || tid < 0) continue;
            Object top = objField(t, "topActivity");
            boolean hit = top instanceof ComponentName
                    && targetPkg.equals(((ComponentName) top).getPackageName());
            if (!hit) {
                Object names = objField(t, "childTaskNames");
                if (names instanceof String[]) {
                    for (String n : (String[]) names) {
                        if (n != null && n.startsWith(targetPkg + "/")) { hit = true; break; }
                    }
                }
            }
            if (hit) return tid;
        }
        return -1;
    }

    /** 移除一个 task（IActivityTaskManager.removeTask，签名随版本不同，两种都试） */
    private static boolean removeTask(int taskId) {
        try {
            Object atm = activityTaskManager();
            if (atm == null) return false;
            for (java.lang.reflect.Method m : atm.getClass().getMethods()) {
                if (!"removeTask".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == int.class) {
                    m.invoke(atm, taskId);
                    return true;
                }
                if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                    m.invoke(atm, taskId, false);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "removeTask 不可用：" + t.getClass().getSimpleName());
        }
        return false;
    }

    public void launchFullScreen(String targetPkg) {
        launchFullScreen(targetPkg, null);
    }

    /**
     * 全屏打开，可带一个「搬完并前置之后」的回调。
     *
     * 注意：**不关画中画、不清屏**。应用 task 被搬到主屏全屏显示，
     * 画中画这边保留 pkg（纹理停在最后一帧）；用户回到桌面时由
     * {@link #bringBackIfFullscreened()} 把应用搬回本屏继续显示。
     */
    public void launchFullScreen(String targetPkg, Runnable after) {
        Intent i = app.getPackageManager().getLaunchIntentForPackage(targetPkg);
        if (i == null) {
            Log.w(TAG, "全屏打开失败：没有启动 Intent " + targetPkg);
            if (after != null) main.post(after);
            return;
        }
        // 关键：应用 task 还挂在虚拟屏上时，直接 startActivity 到主屏只会把
        // 虚拟屏上的旧 task 顶到前台（看起来像"全屏打开没反应"）。
        // 必须先把 task 搬回主屏（display 0），再 startActivity 前置它。
        // findTaskId 是 binder 调用（getRunningTasks/反射遍历），连同搬 task
        // 一起丢后台执行，完成后回主线程前置 —— 旧实现在主线程查 task，会顿一下。
        final Intent intent = i;
        new Thread(() -> {
            int tid = findTaskId(targetPkg);
            if (tid < 0) {
                Log.w(TAG, "全屏打开 " + targetPkg + "：未找到 task，直接 startActivity 兜底");
                main.post(() -> startFullscreenIntent(intent, tid));
                if (after != null) main.postDelayed(after, 400);
                return;
            }
            // 标记：这块屏的应用去主屏全屏了（只有本屏的应用才标记，别的应用全屏与本屏无关）
            if (targetPkg.equals(pkg)) fullscreened = true;
            // 搬 task 在 MIUI 上要绕道 shell（`am display move-stack`），可能耗时几百毫秒
            boolean moved = moveTask(tid, android.view.Display.DEFAULT_DISPLAY);
            // 校验是否真的搬过去了：搬失败的话 startActivity 只会顶起虚拟屏上的
            // 旧 task（看起来"没反应"），日志里要能直接看出是哪一步断了
            Log.i(TAG, "全屏打开 " + targetPkg + " taskId=" + tid
                    + " move=" + moved
                    + " onDisplay0=" + (moved && findTaskDisplay(targetPkg)
                            == android.view.Display.DEFAULT_DISPLAY));
            main.post(() -> {
                startFullscreenIntent(intent, tid);
                // 等应用在主屏画好第一帧再收画中画，避免中间露出空白虚拟屏
                if (after != null) main.postDelayed(after, 500);
            });
        }, "pipdesk-fullscreen").start();
    }

    /**
     * 桌面回到前台时调用：把「全屏打开」过、现在还挂在主屏的应用
     * 搬回本虚拟屏继续显示（用户需求：返回虚拟屏后应用还在）。
     */
    public void bringBackIfFullscreened() {
        if (!fullscreened) return;
        fullscreened = false;
        final String p0 = pkg;
        if (p0 == null || displayId < 0) return;
        new Thread(() -> {
            int tid = findTaskId(p0);
            if (tid < 0) {
                // 应用在主屏上已经被关掉：画中画回到空闲态
                Log.i(TAG, "全屏应用已退出：" + p0 + "，画中画回空闲");
                main.post(this::resetAfterExternalClose);
                return;
            }
            // 同样不跨屏搬：跨屏 reparent = 配置变更 = activity 销毁重建，
            // 重建期间本屏没有可聚焦窗口，焦点会被交还 display 0，画面永久停在最后一帧。
            // 删掉主屏上的 task，让它在虚拟屏上重建。
            Log.i(TAG, "全屏返回：" + p0 + " task " + tid + " 从主屏重建到 display " + displayId);
            final int disp = displayId;
            main.post(() -> {
                if (p0.equals(pkg)) forceToDisplay(p0, disp);
            });
        }, "pipdesk-bringback").start();
    }

    /** 应用在外部被关掉/找不到 task：画中画回到空闲玻璃态 */
    public void resetAfterExternalClose() {
        pkg = null;
        livePkg = null;
        main.removeCallbacks(hideLoading);
        loading.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
        texture.setOpaque(false);
        texture.setAlpha(0f);
        if (cb != null) cb.onPaneClosed(this);
    }

    /** 本屏最后一次出帧距今多久（0 = 从未出帧）：排查"画面冻住"用 */
    /** 最后一帧的时刻（uptimeMillis）：PaneGuard 只靠它判断「所有屏是不是都静止了」 */
    public long lastFrameAt() {
        return lastFrameAt;
    }

    public long msSinceLastFrame() {
        long t = lastFrameAt;
        return t == 0 ? -1 : android.os.SystemClock.uptimeMillis() - t;
    }

    /** 前置已搬到主屏的 task（moveTask 完成/失败后都会走到这里兜底） */
    private void startFullscreenIntent(Intent i, int tid) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // 明确指定主屏：否则可能复用虚拟屏上的那个 task，看起来像"没反应"
        try {   // setLaunchDisplayId 是 @SystemApi，公开 SDK 里没有，反射调
            java.lang.reflect.Method m = Intent.class.getDeclaredMethod(
                    "setLaunchDisplayId", int.class);
            m.invoke(i, android.view.Display.DEFAULT_DISPLAY);
        } catch (Throwable ignored) { }
        try {
            app.startActivity(i);
            Log.i(TAG, "全屏打开 -> display 0 (taskId=" + tid + ")");
        } catch (Throwable t) {
            Log.w(TAG, "全屏打开异常 pkg=" + i.getPackage(), t);
        }
    }

    /** 关闭前先在虚拟屏上顶一个空白页，避免目标应用被弹回主屏 */
    private void clearDisplayApp(String targetPkg) {
        if (displayId < 0) return;
        try {
            Intent i = new Intent(app, BlankActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            app.startActivity(i, o.toBundle());
        } catch (Throwable ignored) { }
    }

    public TextureView textureView() {
        return texture;
    }

    public FrameLayout rootView() {
        return root;
    }
}
