package com.zcshou.service;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.model.RouteNode;
import com.zcshou.gogogo.util.GaitSpec;
import com.zcshou.gogogo.util.RouteInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 线路插值回放控制器：独立 HandlerThread 以 100ms tick 推进里程并回调帧数据。
 *
 * <p>本类只做纯计算，不接触 ServiceGo 的定位注入消息队列，也不触碰 UI。
 * 每一帧调用 {@link FrameCallback#onFrame}，到终点后调用 {@link FrameCallback#onFinished}。</p>
 */
public class RoutePlaybackController {

    /** 回放三态。 */
    public enum PlayState {
        RUNNING,
        PAUSED,
        STOPPED
    }

    /** 回放帧回调，运行在回放线程上。 */
    public interface FrameCallback {
        /**
         * 一帧插值结果。
         *
         * @param lng     经度（WGS84）
         * @param lat     纬度（WGS84）
         * @param alt     海拔（米）
         * @param bearing 方位角（度）
         */
        void onFrame(double lng, double lat, double alt, double bearing);

        /** 已到达线路终点。 */
        void onFinished();
    }

    private static final long TICK_MS = 100L;
    private static final double TICK_SECONDS = TICK_MS / 1000.0D;
    private static final String THREAD_NAME = "RoutePlayback";

    private final FrameCallback callback;

    private volatile List<RouteNode> nodes = new ArrayList<>();
    private volatile double speedMps = 1.2D;
    // 非 null 表示按步推进（走/跑）：每步间隔走一个步幅；null 表示连续推进（骑行/旧调用）
    private volatile GaitSpec gait = null;
    private long stepElapsedMs = 0L;
    private volatile double traveledMeters = 0.0D;
    private volatile PlayState state = PlayState.STOPPED;
    // 闭环回放圈数（1 = 单程/普通闭环一圈）；回放节点末尾会追加起点副本保证首尾连续
    private volatile int loopCount = 1;
    private volatile int completedLoops = 0;

    private HandlerThread thread;
    private Handler tickHandler;

    /**
     * @param callback 帧回调，允许为 null（仅做里程推进、无外部副作用）
     */
    public RoutePlaybackController(FrameCallback callback) {
        this.callback = callback;
    }

    /**
     * 从起点开始（或重新开始）回放。会先清理上一次的线程与状态。
     *
     * @param nodes    有序节点列表，至少需要一个节点
     * @param speedMps 回放速度（米/秒），必须大于 0
     */
    public void start(List<RouteNode> nodes, double speedMps) {
        start(nodes, speedMps, 1);
    }

    /**
     * 从起点开始回放，支持闭环多圈。
     *
     * @param nodes    有序节点列表
     * @param speedMps 速度（米/秒）
     * @param loops    圈数（<=1 视为单程）
     */
    public void start(List<RouteNode> nodes, double speedMps, int loops) {
        List<RouteNode> copy = copyNodes(nodes);
        if (copy.isEmpty() || speedMps <= 0.0D || Double.isNaN(speedMps) || Double.isInfinite(speedMps)) {
            XLog.i("RoutePlayback: invalid start params, ignored");
            return;
        }

        stop();

        this.loopCount = Math.max(1, loops);
        this.completedLoops = 0;
        // 多圈闭环：在回放副本末尾补一个起点，使里程归零（下一圈）时首尾位置连续
        if (this.loopCount > 1 && copy.size() >= 2) {
            RouteNode first = copy.get(0);
            copy.add(new RouteNode(first.getId(), first.getName(), copy.size() + 1,
                    first.getLat(), first.getLng(), first.getAlt()));
        }

        this.nodes = copy;
        this.speedMps = speedMps;
        this.traveledMeters = 0.0D;
        this.stepElapsedMs = 0L;
        this.state = PlayState.RUNNING;

        thread = new HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        tickHandler = new Handler(thread.getLooper());

        // 起点立即落一帧，回放期间位置先回到线路起点
        emitFrame(0.0D);
        scheduleTick();
        XLog.i("RoutePlayback: started nodes=" + copy.size() + ", speed=" + speedMps
                + "m/s, loops=" + this.loopCount);
    }

    /** 暂停：冻结里程，不再回调帧。 */
    public void pause() {
        if (state == PlayState.RUNNING) {
            state = PlayState.PAUSED;
            XLog.i("RoutePlayback: paused");
        }
    }

    /** 继续：从冻结里程恢复推进。 */
    public void resume() {
        if (state == PlayState.PAUSED) {
            state = PlayState.RUNNING;
            XLog.i("RoutePlayback: resumed");
        }
    }

    /** 停止：终止 tick 线程并清空里程，坐标停在最后一帧。 */
    public void stop() {
        state = PlayState.STOPPED;
        if (tickHandler != null) {
            tickHandler.removeCallbacksAndMessages(null);
            tickHandler = null;
        }
        if (thread != null) {
            thread.quit();
            thread = null;
        }
        traveledMeters = 0.0D;
    }

    /** @return 当前回放状态 */
    public PlayState getState() {
        return state;
    }

    /** @return 当前速度（米/秒） */
    public double getSpeedMps() {
        return speedMps;
    }

    /** 回放中切换速度档（骑行），非法值忽略，下一 tick 立即生效。 */
    public void setSpeedMps(double speedMps) {
        if (speedMps > 0.0D && !Double.isNaN(speedMps) && !Double.isInfinite(speedMps)) {
            this.speedMps = speedMps;
        }
    }

    /**
     * 回放中切换步态（步行/跑步），null 表示切回连续推进（骑行）。
     * 切档时同步等效速度并复位步累计，下一 tick 用新节拍推进。
     */
    public void setGait(GaitSpec gait) {
        this.gait = gait;
        this.stepElapsedMs = 0L;
        if (gait != null) {
            this.speedMps = gait.getSpeedMps();
        }
    }

    /** @return 已推进里程（米） */
    public double getTraveledMeters() {
        return traveledMeters;
    }

    /** 一个 tick：推进里程、插值落点、判断终点、预约下一 tick。 */
    private void tick() {
        if (state == PlayState.STOPPED || tickHandler == null) {
            return;
        }

        if (state == PlayState.RUNNING) {
            double total = RouteInterpolator.totalMeters(nodes);

            if (gait != null) {
                // 走/跑：按步频节拍推进，每隔一个步间隔走一个步幅；未到步点则保持当前帧不动
                stepElapsedMs += TICK_MS;
                long interval = gait.getStepIntervalMs();
                if (stepElapsedMs >= interval) {
                    stepElapsedMs -= interval;
                    traveledMeters += gait.getStepMeters();
                } else {
                    scheduleTick();
                    return;
                }
            } else {
                // 骑行/旧调用：连续推进
                traveledMeters += speedMps * TICK_SECONDS;
            }

            if (total <= 0.0D || traveledMeters >= total) {
                double finalMeters = total > 0.0D ? total : 0.0D;
                emitFrame(finalMeters);
                // 多圈闭环：未跑满圈数则从起点继续下一圈；副本末尾已补起点，归零不跳变
                if (loopCount > 1 && total > 0.0D && completedLoops + 1 < loopCount) {
                    completedLoops++;
                    traveledMeters = 0.0D;
                    emitFrame(0.0D);
                    XLog.i("RoutePlayback: loop " + (completedLoops + 1) + "/" + loopCount);
                    scheduleTick();
                    return;
                }
                if (callback != null) {
                    callback.onFinished();
                }
                stop();
                XLog.i("RoutePlayback: finished, loops=" + (completedLoops + 1));
                return;
            }
            emitFrame(traveledMeters);
        }

        scheduleTick();
    }

    /** 预约下一 tick（由回放线程的 Handler 驱动，不占主线程）。 */
    private void scheduleTick() {
        if (tickHandler != null && state != PlayState.STOPPED) {
            tickHandler.postDelayed(this::tick, TICK_MS);
        }
    }

    /** 按里程插值并向外抛出一帧。 */
    private void emitFrame(double meters) {
        if (callback == null || nodes.isEmpty()) {
            return;
        }
        double[] point = RouteInterpolator.pointAtMeters(nodes, meters);
        if (point.length < 3) {
            return;
        }
        double alt = RouteInterpolator.altitudeAtMeters(nodes, meters);
        callback.onFrame(point[0], point[1], alt, point[2]);
    }

    /** 深拷贝节点，避免编辑页在回放中修改列表导致并发问题。 */
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
}
