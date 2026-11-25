package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.gogogo.model.RouteNode;
import com.zcshou.gogogo.util.GaitSpec;
import com.zcshou.gogogo.util.RouteDraftStore;
import com.zcshou.joystick.JoyStick;

import java.util.ArrayList;
import java.util.List;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    // 回放线程写入、定位线程读取，volatile 保证跨线程可见；原注入逻辑保持不变
    private volatile double mCurLat = DEFAULT_LAT;
    private volatile double mCurLng = DEFAULT_LNG;
    private volatile double mCurAlt = DEFAULT_ALT;
    private volatile float mCurBea = DEFAULT_BEA;

    // P1 增量 R-02：运动参数（服务每次模拟全新启动，onCreate 读取一次）
    private static final String KEY_MOVE_DIRECTION = "setting_move_direction";
    private static final String KEY_LOCATION_ACCURACY = "setting_location_accuracy";
    private static final String KEY_UPDATE_INTERVAL = "setting_update_interval";
    private static final float DEFAULT_ACCURACY_METERS = 5f;
    private static final long DEFAULT_UPDATE_INTERVAL_MS = 1000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 50L;
    private Float mFixedBearing = null;                       // null = auto（沿用摇杆 mCurBea）
    private float mAccuracyMeters = DEFAULT_ACCURACY_METERS;
    private long mIntervalMs = DEFAULT_UPDATE_INTERVAL_MS;

    private volatile double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息：只保留前台服务常驻通知，显示/隐藏摇杆动作已移除
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    // 摇杆相关
    private JoyStick mJoyStick;

    // 线路回放相关：独立线程插值，不触碰 mLocHandler 消息队列
    private RoutePlaybackController mPlayback;
    private volatile boolean mPlaybackActive = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private List<RouteNode> mRouteNodes = new ArrayList<>();
    // 位置页推送的闭环标记与回放圈数（仅闭环且圈数>1 时启用多圈）
    private volatile boolean mRouteClosed = false;
    private volatile int mRouteLoops = 1;
    private RouteDraftStore mDraftStore;
    private static final double DEFAULT_WALK_SPEED_MPS = 1.2D;
    // 悬浮窗当前速度档（<0 表示未选档，回退步行设置）
    private volatile double mSelectedSpeedMps = -1.0D;
    // 悬浮窗当前步态：非 null=走/跑按步推进；null=骑行连续。mSpeedModeSelected 区分"未选档"
    private volatile GaitSpec mSelectedGait = null;
    private volatile boolean mSpeedModeSelected = false;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        // P1 R-02：必须在定位线程启动前读取方向/精度/间隔
        loadMotionParams();

        initGoLocation();

        initNotification();

        mDraftStore = new RouteDraftStore(this);

        initJoyStick();

        initPlayback();
    }

    /** 初始化回放控制器；帧落点只更新坐标字段，摇杆同步切回主线程执行。 */
    private void initPlayback() {
        mPlayback = new RoutePlaybackController(new RoutePlaybackController.FrameCallback() {
            @Override
            public void onFrame(double lng, double lat, double alt, double bearing) {
                applyPlaybackFrame(lng, lat, alt, bearing);
            }

            @Override
            public void onFinished() {
                mPlaybackActive = false;
                syncPlaybackUi(false);
                XLog.i("ServiceGo: playback finished");
            }
        });
    }

    /** 回放状态切回主线程同步到悬浮窗按钮（回放线程禁止直接操作 View）。 */
    private void syncPlaybackUi(final boolean active) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mJoyStick != null) {
                    mJoyStick.setPlaybackActive(active);
                }
            }
        });
    }

    /**
     * 回放帧落点：只写坐标/方向/速度字段并同步摇杆位置。
     *
     * <p>严禁在此调用 setPosition —— 它会 removeMessages/sendEmptyMessage，
     * 打乱定位线程按 mIntervalMs 的独立注入节奏。</p>
     */
    public void applyPlaybackFrame(double lng, double lat, double alt, double bearing) {
        mCurLng = lng;
        mCurLat = lat;
        mCurAlt = alt;
        mCurBea = (float) bearing;
        if (mPlayback != null) {
            mSpeed = mPlayback.getSpeedMps();
        }
        if (mJoyStick != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mJoyStick != null) {
                        mJoyStick.setCurrentPosition(lng, lat, alt);
                    }
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        if (mPlayback != null) {
            mPlayback.stop();
        }
        mPlaybackActive = false;
        mMainHandler.removeCallbacksAndMessages(null);
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        if (mJoyStick != null) {
            mJoyStick.destroy();
        }

        removeTestProviderNetwork();
        removeTestProviderGPS();

        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    /**
     * 从默认 SharedPreferences 读取运动参数。
     * 所有解析均在内部 try/catch，保证 NumberFormatException 不会冒泡到定位线程；
     * 仅记录一行脱敏日志（来源与数值），不打印任何 AK。
     */
    private void loadMotionParams() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);

            // 固定方向：仅白名单 0/90/180/270 生效；auto / 非法值 → null（跟随摇杆）
            mFixedBearing = null;
            String direction = sp.getString(KEY_MOVE_DIRECTION, "auto");
            if (direction != null) {
                String value = direction.trim();
                if ("0".equals(value) || "90".equals(value)
                        || "180".equals(value) || "270".equals(value)) {
                    mFixedBearing = Float.parseFloat(value);
                }
            }

            // 定位精度（米）：解析失败 / NaN / <=0 → 5f
            mAccuracyMeters = DEFAULT_ACCURACY_METERS;
            try {
                float accuracy = Float.parseFloat(
                        sp.getString(KEY_LOCATION_ACCURACY, String.valueOf(DEFAULT_ACCURACY_METERS)));
                if (!Float.isNaN(accuracy) && !Float.isInfinite(accuracy) && accuracy > 0f) {
                    mAccuracyMeters = accuracy;
                }
            } catch (NumberFormatException e) {
                mAccuracyMeters = DEFAULT_ACCURACY_METERS;
            }

            // 更新间隔（毫秒）：解析失败 / <50 → 50L（不设上限）
            mIntervalMs = DEFAULT_UPDATE_INTERVAL_MS;
            try {
                long interval = Long.parseLong(
                        sp.getString(KEY_UPDATE_INTERVAL, String.valueOf(DEFAULT_UPDATE_INTERVAL_MS)));
                mIntervalMs = interval >= MIN_UPDATE_INTERVAL_MS ? interval : MIN_UPDATE_INTERVAL_MS;
            } catch (NumberFormatException e) {
                mIntervalMs = MIN_UPDATE_INTERVAL_MS;
            }
        } catch (Exception e) {
            // SP 不可用等极端情况：保持最小安全值，绝不让异常进入定位线程
            mFixedBearing = null;
            mAccuracyMeters = DEFAULT_ACCURACY_METERS;
            mIntervalMs = MIN_UPDATE_INTERVAL_MS;
        }
        XLog.i("ServiceGo motion params: bearing=" + (mFixedBearing == null ? "auto" : mFixedBearing)
                + ", accuracy=" + mAccuracyMeters + "m, interval=" + mIntervalMs + "ms");
    }

    private void initNotification() {
        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        // 仅保留点击回位置页的意图；显示/隐藏摇杆广播与动作已移除
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                if (mPlaybackActive) {
                    return;
                }
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                if (mPlaybackActive) {
                    return;
                }
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }

            @Override
            public void onStartPlaybackRequested() {
                startRoutePlayback();
            }

            @Override
            public void onStopPlaybackRequested() {
                stopRoutePlayback();
            }

            @Override
            public void onSpeedModeChanged(GaitSpec gait, double speedMps) {
                mSelectedSpeedMps = speedMps;
                mSelectedGait = gait;
                mSpeedModeSelected = true;
                // 回放中切档立即生效，不打断当前行程
                if (mPlayback != null) {
                    if (gait != null) {
                        mPlayback.setGait(gait);
                    } else {
                        mPlayback.setGait(null);
                        mPlayback.setSpeedMps(speedMps);
                    }
                }
            }
        });
        // 服务启动即显示悬浮窗，跨应用常驻
        mJoyStick.show();
    }

    /**
     * 从悬浮窗发起回放：节点取 Binder 推送的点集，为空时读 SP 草稿兜底。
     * 速度只在此刻读取一次，回放中由 RoutePlaybackController 自行插值。
     */
    public void startRoutePlayback() {
        if (mRouteNodes == null || mRouteNodes.isEmpty()) {
            if (mDraftStore != null) {
                mRouteNodes = mDraftStore.load();
                // 服务重启后从草稿恢复闭环/圈数，保证多圈回放不丢设置
                mRouteClosed = mDraftStore.loadClosed();
                mRouteLoops = mDraftStore.loadLoops();
            }
        }
        if (mRouteNodes == null || mRouteNodes.isEmpty()) {
            XLog.i("ServiceGo: no route nodes, playback ignored");
            mPlaybackActive = false;
            syncPlaybackUi(false);
            return;
        }

        double speedMps = mSelectedSpeedMps > 0.0D ? mSelectedSpeedMps : readWalkSpeedMps();
        mPlaybackActive = true;
        if (mPlayback != null) {
            // 仅闭环且用户设置圈数>1 时走多圈，否则保持单程行为
            int loops = (mRouteClosed ? mRouteLoops : 1);
            mPlayback.start(mRouteNodes, speedMps, loops);
            // 走/跑按步推进；未选档或骑行档为连续推进
            mPlayback.setGait(mSpeedModeSelected ? mSelectedGait : readWalkGait());
        }
        syncPlaybackUi(true);
        XLog.i("ServiceGo: route playback started, nodes=" + mRouteNodes.size()
                + ", speed=" + speedMps + "m/s, loops=" + (mRouteClosed ? mRouteLoops : 1));
    }

    /** 手动结束回放：坐标停在最后一帧，恢复摇杆响应。 */
    public void stopRoutePlayback() {
        if (mPlayback != null) {
            mPlayback.stop();
        }
        mPlaybackActive = false;
        syncPlaybackUi(false);
        XLog.i("ServiceGo: route playback stopped");
    }

    /** 读取设置页步行步频+步幅构造步态，非法值回退到步行默认（100 步/分 × 0.72 米）。 */
    private GaitSpec readWalkGait() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        return GaitSpec.fromStrings(
                sp.getString("setting_walk_cadence", getString(R.string.setting_walk_cadence_default)),
                sp.getString("setting_walk_stride", getString(R.string.setting_walk_stride_default)),
                GaitSpec.DEFAULT_WALK_CADENCE, GaitSpec.DEFAULT_WALK_STRIDE);
    }

    /** 读取步行步态的等效速度作为回放兜底速度。 */
    private double readWalkSpeedMps() {
        return readWalkGait().getSpeedMps();
    }

    /** 深拷贝点集，避免主线程列表变更影响回放。 */
    private static List<RouteNode> copyNodes(List<RouteNode> source) {
        List<RouteNode> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (RouteNode node : source) {
            if (node == null) {
                continue;
            }
            copy.add(new RouteNode(node.getId(), node.getName(), node.getOrder(),
                    node.getLat(), node.getLng(), node.getAlt()));
        }
        return copy;
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(mIntervalMs);

                    if (!isStop) {
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(mAccuracyMeters);           // 设定此位置的估计水平精度，以米为单位（P1 R-02 注入）
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mFixedBearing != null ? mFixedBearing : mCurBea);   // 方向（度）：固定值优先，auto 跟随摇杆
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(mAccuracyMeters);           // 设定此位置的估计水平精度，以米为单位（P1 R-02 注入）
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mFixedBearing != null ? mFixedBearing : mCurBea);   // 方向（度）：固定值优先，auto 跟随摇杆
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        /** 由位置页推送当前画布点集（WGS84），深拷贝保存供悬浮窗发起回放。 */
        public void setRoute(List<RouteNode> nodes) {
            setRoute(nodes, false, 1);
        }

        /** 推送点集 + 闭环/圈数，闭环且圈数>1 时回放走满多圈。 */
        public void setRoute(List<RouteNode> nodes, boolean closed, int loops) {
            mRouteNodes = copyNodes(nodes);
            mRouteClosed = closed;
            mRouteLoops = Math.max(1, Math.min(99, loops));
            XLog.i("ServiceGo: setRoute nodes=" + mRouteNodes.size()
                    + ", closed=" + closed + ", loops=" + mRouteLoops);
        }

        /** 开始线路回放：开启摇杆门禁后交给独立 tick 线程插值。 */
        public void startPlayback(List<RouteNode> nodes, double speedMps) {
            mPlaybackActive = true;
            if (mPlayback != null) {
                mPlayback.start(nodes, speedMps);
            }
            syncPlaybackUi(true);
        }

        /** 暂停回放：坐标冻结，摇杆门禁保持开启。 */
        public void pausePlayback() {
            if (mPlayback != null) {
                mPlayback.pause();
            }
        }

        /** 继续回放：从暂停点继续推进。 */
        public void resumePlayback() {
            if (mPlayback != null) {
                mPlayback.resume();
            }
        }

        /** 停止回放：停 tick 并恢复摇杆响应，坐标停在最后一帧。 */
        public void stopPlayback() {
            if (mPlayback != null) {
                mPlayback.stop();
            }
            mPlaybackActive = false;
            syncPlaybackUi(false);
        }
    }
}
