package com.zcshou.joystick;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.zcshou.gogogo.R;
import com.zcshou.gogogo.util.GaitSpec;
import com.zcshou.utils.GoUtils;

/**
 * 悬浮窗：手动摇杆 + 速度档（步行/跑步/骑行）+ 开始模拟/结束模拟按钮。
 *
 * <p>历史点、小地图已移除，本类不再创建第二个 MapView。窗口类型、flags 保持原样，
 * 服务启动即 {@link #show()} 常驻。速度档同时驱动摇杆手动移动与线路回放速度。</p>
 *
 * <p>新增：按住面板任意非摇杆区域可拖动（由 {@link DragFrameLayout} 用 touch slop 区分点击/拖动），
 * 拖动与收起/展开后均做屏幕边界钳制；面板可收起为入口按钮，点击入口重新展开。</p>
 */
public class JoyStick extends View {

    private static final int DIV_GO = 1000;    /* 骑行单次点击推进的时间基准，单位 ms */
    private static final long MOVE_TICK_MS = 50L; /* 自动推进的固定 tick，步点由累计时间判定 */
    private static final double DEFAULT_SPEED_MPS = 1.2D;
    private static final double DEFAULT_BIKE_SPEED_MPS = 10.0D;
    private static final int SPEED_MODE_WALK = 0;
    private static final int SPEED_MODE_RUN = 1;
    private static final int SPEED_MODE_BIKE = 2;

    private Context mContext;
    private LayoutInflater mInflater;
    private SharedPreferences mSharedPreferences;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowParamCurrent;
    private View mJoystickLayout;
    private GoUtils.TimeCount mTimer;
    private JoyStickClickListener mListener;

    private boolean isMove = false;
    private double mSpeed = DEFAULT_SPEED_MPS;
    // 当前步态：走路/跑步按步频×步幅推进；骑行档为 null（走原来的连续速度）
    private GaitSpec mGait = GaitSpec.walkDefault();
    private double mAltitude = 55.0D;
    private double mAngle = 0;
    private double mR = 0;
    private double disLng = 0;
    private double disLat = 0;
    private long mStepElapsedMs = 0;   /* 步态累计时间，达到步间隔即推进一步 */

    private boolean mPlaybackActive = false;
    private ImageButton mBtnStartSimulate;
    private ImageButton mBtnStopSimulate;
    private ImageButton mBtnWalk;
    private ImageButton mBtnRun;
    private ImageButton mBtnBike;
    private int mSpeedMode = SPEED_MODE_WALK;

    private View mPanelView;
    private View mEntryView;
    private boolean mCollapsed = false;

    public JoyStick(Context context) {
        super(context);
        init(context);
    }

    public JoyStick(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public JoyStick(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    /** 统一初始化：窗口参数 + 单一悬浮窗布局。 */
    private void init(Context context) {
        mContext = context;
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        initWindowManager();
        mInflater = LayoutInflater.from(mContext);
        initJoyStickView();
    }

    /**
     * 同步回放状态：模拟中关闭摇杆门禁、禁用开始按钮、启用结束按钮。
     *
     * @param active true 表示正在模拟
     */
    public void setPlaybackActive(boolean active) {
        mPlaybackActive = active;
        if (active) {
            mTimer.cancel();
            isMove = false;
        }
        int accent = ContextCompat.getColor(mContext, R.color.dark_accent);
        int muted = ContextCompat.getColor(mContext, R.color.text_secondary);
        if (mBtnStartSimulate != null) {
            mBtnStartSimulate.setEnabled(!active);
            mBtnStartSimulate.setColorFilter(active ? muted : accent);
        }
        if (mBtnStopSimulate != null) {
            mBtnStopSimulate.setEnabled(active);
            mBtnStopSimulate.setColorFilter(active ? accent : muted);
        }
    }

    /**
     * 回放帧同步当前位置；小地图已删除，这里只需保留海拔供后续定位注入。
     *
     * @param lng 经度（WGS84）
     * @param lat 纬度（WGS84）
     * @param alt 海拔（米）
     */
    public void setCurrentPosition(double lng, double lat, double alt) {
        mAltitude = alt;
    }

    /** 服务启动即显示悬浮窗；重复调用不会重复 addView。 */
    public void show() {
        if (mJoystickLayout == null || mWindowManager == null) {
            return;
        }
        if (mJoystickLayout.getParent() == null) {
            mWindowManager.addView(mJoystickLayout, mWindowParamCurrent);
            // 首帧测量完成后把初始位置钳制在屏幕内
            mJoystickLayout.post(this::clampPosition);
        }
    }

    /**
     * 按位移拖动窗口，并实时钳制到屏幕可视范围内。
     *
     * <p>gravity=START|TOP，x/y 为窗口左上角相对屏幕的偏移；用真实屏幕尺寸保证右下不越界。</p>
     */
    private void dragBy(int dx, int dy) {
        mWindowParamCurrent.x += dx;
        mWindowParamCurrent.y += dy;
        clampPosition();
        mWindowManager.updateViewLayout(mJoystickLayout, mWindowParamCurrent);
    }

    /** 把窗口左上角限制在 [0, 屏幕尺寸 - 窗口尺寸] 内。 */
    @SuppressWarnings("deprecation")
    private void clampPosition() {
        if (mWindowManager == null || mJoystickLayout == null) {
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        int viewW = mJoystickLayout.getWidth();
        int viewH = mJoystickLayout.getHeight();
        int maxX = Math.max(0, metrics.widthPixels - viewW);
        int maxY = Math.max(0, metrics.heightPixels - viewH);
        mWindowParamCurrent.x = Math.max(0, Math.min(mWindowParamCurrent.x, maxX));
        mWindowParamCurrent.y = Math.max(0, Math.min(mWindowParamCurrent.y, maxY));
    }

    /** 收起为入口按钮：面板 GONE、入口 VISIBLE，保持同一窗口与位置。 */
    private void collapse() {
        if (mPanelView == null || mEntryView == null || mCollapsed) {
            return;
        }
        mCollapsed = true;
        mPanelView.setVisibility(GONE);
        mEntryView.setVisibility(VISIBLE);
        relayoutAfterStateChange();
    }

    /** 从入口按钮展开面板：入口 GONE、面板 VISIBLE。 */
    private void expand() {
        if (mPanelView == null || mEntryView == null || !mCollapsed) {
            return;
        }
        mCollapsed = false;
        mEntryView.setVisibility(GONE);
        mPanelView.setVisibility(VISIBLE);
        relayoutAfterStateChange();
    }

    /** 可见性切换会改变 WRAP_CONTENT 窗口尺寸，测量完成后重算边界并刷新布局。 */
    private void relayoutAfterStateChange() {
        if (mJoystickLayout == null || mWindowManager == null) {
            return;
        }
        mJoystickLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mJoystickLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        clampPosition();
                        mWindowManager.updateViewLayout(mJoystickLayout, mWindowParamCurrent);
                    }
                });
        mJoystickLayout.requestLayout();
    }

    /** 从窗口移除悬浮窗（不清空布局，后续可再次 show）。 */
    public void hide() {
        if (mJoystickLayout != null && mJoystickLayout.getParent() != null && mWindowManager != null) {
            mWindowManager.removeViewImmediate(mJoystickLayout);
        }
    }

    /** 销毁悬浮窗并停止移动计时器。 */
    public void destroy() {
        hide();
        if (mTimer != null) {
            mTimer.cancel();
        }
        isMove = false;
    }

    /** @param listener 摇杆/回放按钮回调 */
    public void setListener(JoyStickClickListener listener) {
        mListener = listener;
    }

    /** 窗口类型、flags 与拖动参数保持原样。 */
    private void initWindowManager() {
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowParamCurrent = new WindowManager.LayoutParams();
        mWindowParamCurrent.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWindowParamCurrent.format = PixelFormat.RGBA_8888;
        mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowParamCurrent.gravity = Gravity.START | Gravity.TOP;
        mWindowParamCurrent.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.x = 300;
        mWindowParamCurrent.y = 300;
    }

    /** inflate 极简布局，绑定摇杆、方向按钮与两个模拟按钮。 */
    @SuppressLint("InflateParams")
    private void initJoyStickView() {
        mTimer = new GoUtils.TimeCount(MOVE_TICK_MS, MOVE_TICK_MS);
        mTimer.setListener(new GoUtils.TimeCount.TimeCountListener() {
            @Override
            public void onTick(long millisUntilFinished) {
                // 本实现不使用 tick 回调，推进统一在 onFinish 中按固定 tick 处理
            }

            @Override
            public void onFinish() {
                if (mPlaybackActive) {
                    return;
                }
                // 按固定 tick 累计时间；走/跑达到步间隔才推进一步，骑行每 tick 连续推进
                if (mGait != null) {
                    mStepElapsedMs += MOVE_TICK_MS;
                    long interval = mGait.getStepIntervalMs();
                    if (mStepElapsedMs < interval) {
                        mTimer.start();
                        return;
                    }
                    mStepElapsedMs -= interval;
                    double stepMeters = mGait.getStepMeters();
                    disLng = stepMeters * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;
                    disLat = stepMeters * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;
                } else {
                    // 骑行档：沿用原来的连续推进（每 tick 按等效速度推进一小段）
                    double tickSeconds = MOVE_TICK_MS / 1000.0D;
                    disLng = mSpeed * tickSeconds * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;
                    disLat = mSpeed * tickSeconds * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;
                }
                if (mListener != null) {
                    mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F - mAngle);
                }
                mTimer.start();
            }
        });

        applySpeedMode(SPEED_MODE_WALK, false);

        mJoystickLayout = mInflater.inflate(R.layout.joystick, null);
        if (mJoystickLayout instanceof DragFrameLayout) {
            ((DragFrameLayout) mJoystickLayout).setDragListener(new DragFrameLayout.DragListener() {
                @Override
                public void onDrag(int dx, int dy) {
                    dragBy(dx, dy);
                }

                @Override
                public void onDragEnd() {
                    // 位置在每次 onDrag 已更新，无需额外处理
                }
            });
        }

        mPanelView = mJoystickLayout.findViewById(R.id.joystick_panel);
        mEntryView = mJoystickLayout.findViewById(R.id.joystick_entry);
        ImageButton btnCollapse = mJoystickLayout.findViewById(R.id.joystick_collapse);
        if (btnCollapse != null) {
            // app:tint 对系统 ImageButton 不生效，统一用代码染色
            btnCollapse.setColorFilter(ContextCompat.getColor(mContext, R.color.text_secondary));
            btnCollapse.setOnClickListener(v -> collapse());
        }
        if (mEntryView instanceof ImageButton) {
            ((ImageButton) mEntryView).setColorFilter(ContextCompat.getColor(mContext, R.color.dark_accent));
        }
        if (mEntryView != null) {
            mEntryView.setOnClickListener(v -> expand());
        }

        RockerView rockerView = mJoystickLayout.findViewById(R.id.joystick_rocker);
        if (rockerView != null) {
            rockerView.setListener(this::processDirection);
        }

        ButtonView buttonView = mJoystickLayout.findViewById(R.id.joystick_button);
        if (buttonView != null) {
            buttonView.setListener(this::processDirection);
        }

        // 沿用既有摇杆类型设置：0=圆盘摇杆，其他=方向按钮
        if ("0".equals(mSharedPreferences.getString("setting_joystick_type", "0"))) {
            if (rockerView != null) {
                rockerView.setVisibility(VISIBLE);
            }
            if (buttonView != null) {
                buttonView.setVisibility(GONE);
            }
        } else {
            if (rockerView != null) {
                rockerView.setVisibility(GONE);
            }
            if (buttonView != null) {
                buttonView.setVisibility(VISIBLE);
            }
        }

        mBtnStartSimulate = mJoystickLayout.findViewById(R.id.joystick_start_simulate);
        if (mBtnStartSimulate != null) {
            mBtnStartSimulate.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onStartPlaybackRequested();
                }
            });
        }

        mBtnStopSimulate = mJoystickLayout.findViewById(R.id.joystick_stop_simulate);
        if (mBtnStopSimulate != null) {
            mBtnStopSimulate.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onStopPlaybackRequested();
                }
            });
        }

        // 速度档：步行 / 跑步 / 骑行，默认步行，选中态用墨青高亮
        mBtnWalk = mJoystickLayout.findViewById(R.id.joystick_walk);
        mBtnRun = mJoystickLayout.findViewById(R.id.joystick_run);
        mBtnBike = mJoystickLayout.findViewById(R.id.joystick_bike);
        applySpeedMode(SPEED_MODE_WALK, false);
        if (mBtnWalk != null) {
            mBtnWalk.setOnClickListener(v -> applySpeedMode(SPEED_MODE_WALK, true));
        }
        if (mBtnRun != null) {
            mBtnRun.setOnClickListener(v -> applySpeedMode(SPEED_MODE_RUN, true));
        }
        if (mBtnBike != null) {
            mBtnBike.setOnClickListener(v -> applySpeedMode(SPEED_MODE_BIKE, true));
        }

        setPlaybackActive(false);
    }

    /** 切换速度档：同步手动移动步态、按钮选中态，并通知服务用于线路回放。 */
    private void applySpeedMode(int mode, boolean notify) {
        mSpeedMode = mode;
        mStepElapsedMs = 0;
        switch (mode) {
            case SPEED_MODE_RUN:
                mGait = readGait("setting_run_cadence", R.string.setting_run_cadence_default,
                        "setting_run_stride", R.string.setting_run_stride_default, GaitSpec.runDefault());
                mSpeed = mGait.getSpeedMps();
                break;
            case SPEED_MODE_BIKE:
                // 骑行不走步态，保持原来的连续速度
                mGait = null;
                mSpeed = readSpeed("setting_bike", R.string.setting_bike_default, DEFAULT_BIKE_SPEED_MPS);
                break;
            case SPEED_MODE_WALK:
            default:
                mGait = readGait("setting_walk_cadence", R.string.setting_walk_cadence_default,
                        "setting_walk_stride", R.string.setting_walk_stride_default, GaitSpec.walkDefault());
                mSpeed = mGait.getSpeedMps();
                break;
        }
        int accent = ContextCompat.getColor(mContext, R.color.dark_accent);
        int normal = ContextCompat.getColor(mContext, R.color.text_secondary);
        if (mBtnWalk != null) {
            mBtnWalk.setColorFilter(mode == SPEED_MODE_WALK ? accent : normal);
        }
        if (mBtnRun != null) {
            mBtnRun.setColorFilter(mode == SPEED_MODE_RUN ? accent : normal);
        }
        if (mBtnBike != null) {
            mBtnBike.setColorFilter(mode == SPEED_MODE_BIKE ? accent : normal);
        }
        if (notify && mListener != null) {
            // 骑行档 gait 为 null 表示连续速度；走/跑传步态供回放按步推进
            mListener.onSpeedModeChanged(mGait, mSpeed);
        }
    }

    /** 读设置页步频+步幅，构造步态；非法值逐项回退默认。 */
    private GaitSpec readGait(String cadenceKey, int cadenceDefaultRes,
                              String strideKey, int strideDefaultRes, GaitSpec fallback) {
        String cadenceRaw = mSharedPreferences.getString(cadenceKey,
                getResources().getString(cadenceDefaultRes));
        String strideRaw = mSharedPreferences.getString(strideKey,
                getResources().getString(strideDefaultRes));
        try {
            return GaitSpec.fromStrings(cadenceRaw, strideRaw,
                    fallback.getCadencePerMin(), fallback.getStrideMeters());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** 读设置页速度（骑行档用），非法值回退默认档位速度。 */
    private double readSpeed(String key, int defaultRes, double fallback) {
        try {
            double value = Double.parseDouble(mSharedPreferences.getString(
                    key, getResources().getString(defaultRes)).trim());
            if (value > 0.0D && !Double.isNaN(value) && !Double.isInfinite(value)) {
                return value;
            }
        } catch (Exception ignored) {
            // 解析失败使用档位默认值
        }
        return fallback;
    }

    /** 处理摇杆方向输入；模拟中直接门禁。 */
    private void processDirection(boolean auto, double angle, double r) {
        if (mPlaybackActive) {
            return;
        }
        if (r <= 0) {
            mTimer.cancel();
            isMove = false;
            return;
        }

        mAngle = angle;
        mR = r;
        if (auto) {
            if (!isMove) {
                mTimer.start();
                isMove = true;
            }
        } else {
            mTimer.cancel();
            isMove = false;
            // 单次点击：走/跑推进一步，骑行仍按 1 秒推进
            double meters = (mGait != null) ? mGait.getStepMeters() : mSpeed * (DIV_GO / 1000.0D);
            disLng = meters * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;
            disLat = meters * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;
            if (mListener != null) {
                mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F - mAngle);
            }
        }
    }

    /** 悬浮窗回调；两个回放请求由 ServiceGo 实现。 */
    public interface JoyStickClickListener {
        void onMoveInfo(double speed, double disLng, double disLat, double angle);

        void onPositionInfo(double lng, double lat, double alt);

        /** 悬浮窗“开始模拟” */
        void onStartPlaybackRequested();

        /** 悬浮窗“结束模拟” */
        void onStopPlaybackRequested();

        /**
         * 悬浮窗切换速度档。
         *
         * @param gait     走/跑档的步态（步频+步幅）；骑行档为 null，表示用连续速度
         * @param speedMps 等效速度（米/秒），供定位速度字段与连续档使用
         */
        void onSpeedModeChanged(GaitSpec gait, double speedMps);
    }
}
