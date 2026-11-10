package com.cz.richdesk;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.Scroller;

/**
 * 极简双页横向滑动容器（替代 ViewPager2，纯 Java 无依赖）：
 * 第 0 页 = 首页，第 1 页 = 应用列表，左滑切换。
 * 子页面内的横向滚动（如 Dock）可通过 requestDisallowInterceptTouchEvent 排除。
 */
public class SimplePager extends ViewGroup {

    private final Scroller scroller;
    private final int touchSlop;
    private VelocityTracker velocity;

    private float downX, downY, lastX, lastY;
    private boolean dragging;
    private boolean directionDecided;
    private boolean horizontalIntent;
    private int currentPage;

    private static final int FLING_MIN = 800; // px/s

    /** 翻页闸门：由外面决定「从某页、朝某方向、起手在某坐标」能不能翻 */
    public interface SwipeGate {
        /** @param toNext true = 手指左滑（翻向下一页） */
        boolean allowSwipe(int fromPage, boolean toNext, float downX, float downY);

        /**
         * 起手位置的「严格度」：1 = 普通；越大越难翻（位移门槛和速度门槛都乘它）。
         * Dock 这种手指常驻、稍微一划就想干别的区域，调大一点能压掉误触。
         */
        default float strictness(float downX, float downY) {
            return 1f;
        }
    }

    private SwipeGate gate;

    /** 本次手势的严格度（DOWN 时按起手位置算一次） */
    private float strict = 1f;

    public void setSwipeGate(SwipeGate g) {
        gate = g;
    }

    /** 本次手势是否允许翻页（不许翻时吞掉翻页意图，子 View 照常处理） */
    private boolean gateOk(float x, float y) {
        if (gate == null) return true;
        boolean toNext = x < downX;
        if (!gate.allowSwipe(currentPage, toNext, downX, downY)) {
            horizontalIntent = false;
            return false;
        }
        return true;
    }

    public SimplePager(Context context) {
        this(context, null);
    }

    public SimplePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(i * w, 0, (i + 1) * w, child.getMeasuredHeight());
        }
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int page) {
        currentPage = Math.max(0, Math.min(getChildCount() - 1, page));
        int w = getWidth();
        scrollTo(currentPage * w, 0);
    }

    /** 平滑切页（点击应用后回到首页看画中画） */
    public void setCurrentPageSmooth(int page) {
        currentPage = Math.max(0, Math.min(getChildCount() - 1, page));
        smoothTo(currentPage * getWidth());
    }

    // ------------------------------------------------------------ 触摸处理

    /**
     * 横向滑动阈值：比系统 touchSlop 大一截。
     *
     * 真实原因：车机/电阻屏点按时手指会轻微横向抖动，用系统 slop 判定时
     * 一次"点击"会被这里当成翻页手势接管，子 View（画中画里的应用）只收到
     * DOWN + CANCEL —— 表现就是"点了半天没反应"。翻页动作位移都远大于这个值，
     * 提高阈值不会影响正常滑动，但能把误判压下去。
     */
    private float dragSlop() {
        return touchSlop * 2.2f * strict;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                directionDecided = false;
                horizontalIntent = false;
                dragging = false;
                strict = gate == null ? 1f
                        : Math.max(1f, gate.strictness(ev.getX(), ev.getY()));
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float slop = dragSlop();
                if (!directionDecided) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx > slop || dy > slop) {
                        directionDecided = true;
                        // 横向要明显大于纵向才算翻页（1.6 倍），斜着滑交给子 View
                        horizontalIntent = dx > dy * 1.6f;
                    }
                }
                if (horizontalIntent && !dragging
                        && Math.abs(ev.getX() - downX) > slop) {
                    if (!gateOk(ev.getX(), downY)) return false;   // 闸门不放行：该区域/方向不能翻页
                    dragging = true;
                    return true; // 接管横向滑动
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (velocity == null) {
            velocity = VelocityTracker.obtain();
        }
        velocity.addMovement(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                strict = gate == null ? 1f
                        : Math.max(1f, gate.strictness(ev.getX(), ev.getY()));
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!dragging && !directionDecided) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (dx > dragSlop() || dy > dragSlop()) {
                        directionDecided = true;
                        horizontalIntent = dx > dy * 1.6f;
                    }
                }
                if (horizontalIntent && !dragging
                        && Math.abs(ev.getX() - downX) > dragSlop()) {
                    if (gateOk(ev.getX(), downY)) {
                        dragging = true;
                    }
                }
                if (dragging) {
                    float delta = lastX - ev.getX();
                    float x = getScrollX() + delta;
                    int max = (getChildCount() - 1) * getWidth();
                    x = Math.max(0, Math.min(max, x));
                    scrollTo((int) x, 0);
                }
                lastX = ev.getX();
                lastY = ev.getY();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    int w = getWidth();
                    int target = currentPage;
                    if (Math.abs(vx) > FLING_MIN * strict) {
                        // 快速滑动：直接翻页（Dock 区门槛更高，轻扫不翻）
                        target = vx < 0 ? currentPage + 1 : currentPage - 1;
                    } else {
                        // 慢速：看滑过了多少
                        target = Math.round(getScrollX() / (float) w);
                    }
                    target = Math.max(0, Math.min(getChildCount() - 1, target));
                    currentPage = target;
                    smoothTo(currentPage * w);
                }
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                dragging = false;
                directionDecided = false;
                break;
        }
        return true;
    }

    private void smoothTo(int x) {
        scroller.startScroll(getScrollX(), 0, x - getScrollX(), 0);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }
}
