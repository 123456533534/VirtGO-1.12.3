package com.zcshou.joystick;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 悬浮窗根容器：负责“按住拖动”手势。
 *
 * <p>用 {@link ViewConfiguration#getScaledTouchSlop()} 区分点击与拖动：
 * 手指移动未超过阈值时不拦截，子控件（摇杆、速度档、开始/结束按钮）点击照常生效；
 * 一旦超过阈值即拦截并把拖动位移回调给 {@link DragListener}，由外部更新 WindowManager 位置。</p>
 */
public class DragFrameLayout extends FrameLayout {

    /** 拖动回调：由持有 WindowManager 的一方完成位置更新与边界钳制。 */
    public interface DragListener {
        /** @param dx 相对上一帧的横向位移（像素） @param dy 纵向位移（像素） */
        void onDrag(int dx, int dy);

        void onDragEnd();
    }

    private DragListener mDragListener;
    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    private boolean mDragging = false;

    public DragFrameLayout(Context context) {
        this(context, null);
    }

    public DragFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DragFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
    }

    public void setDragListener(DragListener listener) {
        mDragListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = mLastX = ev.getRawX();
                mDownY = mLastY = ev.getRawY();
                mDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    float dx = ev.getRawX() - mDownX;
                    float dy = ev.getRawY() - mDownY;
                    if (Math.abs(dx) >= mTouchSlop || Math.abs(dy) >= mTouchSlop) {
                        mDragging = true;
                        // 从当前帧开始算位移，避免拦截瞬间跳动
                        mLastX = ev.getRawX();
                        mLastY = ev.getRawY();
                        return true;
                    }
                } else {
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                int dx = (int) (ev.getRawX() - mLastX);
                int dy = (int) (ev.getRawY() - mLastY);
                mLastX = ev.getRawX();
                mLastY = ev.getRawY();
                if (mDragListener != null) {
                    mDragListener.onDrag(dx, dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                if (mDragListener != null) {
                    mDragListener.onDragEnd();
                }
                return true;
            default:
                return true;
        }
    }
}
