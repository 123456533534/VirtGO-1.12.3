package com.zcshou.gogogo.util;

import com.zcshou.gogogo.model.RouteNode;

import java.util.List;

/**
 * 线路距离、ETA 与按里程线性插值的纯静态工具。
 *
 * <p>坐标换算复用 ServiceGo 中的约定：纬度 1°≈110574m；
 * 经度 1°≈111320×cos(midLat) m。</p>
 */
public final class RouteInterpolator {

    private static final double METERS_PER_LAT_DEGREE = 110574.0D;
    private static final double METERS_PER_LNG_DEGREE = 111320.0D;

    private RouteInterpolator() {
        // 工具类禁止实例化
    }

    /**
     * 两点球面近似距离（米）。
     *
     * @param lat1 起点纬度
     * @param lng1 起点经度
     * @param lat2 终点纬度
     * @param lng2 终点经度
     * @return 距离（米），永远不会为负
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double midLat = Math.abs((lat1 + lat2) / 2.0D);
        double dLat = (lat2 - lat1) * METERS_PER_LAT_DEGREE;
        double dLng = (lng2 - lng1) * METERS_PER_LNG_DEGREE
                * Math.cos(midLat * Math.PI / 180.0D);
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    /**
     * 线路总里程（米）。少于两个节点时返回 0。
     *
     * @param nodes 有序节点列表
     * @return 总里程（米）
     */
    public static double totalMeters(List<RouteNode> nodes) {
        if (nodes == null || nodes.size() < 2) {
            return 0.0D;
        }

        double total = 0.0D;
        for (int i = 1; i < nodes.size(); i++) {
            RouteNode prev = nodes.get(i - 1);
            RouteNode current = nodes.get(i);
            if (prev == null || current == null) {
                continue;
            }
            total += distanceMeters(prev.getLat(), prev.getLng(), current.getLat(), current.getLng());
        }
        return total;
    }

    /**
     * 按速度推算预计耗时（秒）。速度非法时返回 0，避免除零。
     *
     * @param meters   里程（米）
     * @param speedMps 速度（米/秒）
     * @return 预计耗时（秒），向上取整
     */
    public static long etaSeconds(double meters, double speedMps) {
        if (speedMps <= 0.0D || Double.isNaN(speedMps) || Double.isInfinite(speedMps) || meters <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(meters / speedMps);
    }

    /**
     * 按已走里程在线路上取插值点。
     *
     * @param nodes  有序节点列表
     * @param meters 从头累计已走里程（米）
     * @return {@code {lng, lat, bearing}}；节点不足时返回 {@code {0, 0, 0}}
     */
    public static double[] pointAtMeters(List<RouteNode> nodes, double meters) {
        if (nodes == null || nodes.isEmpty()) {
            return new double[] {0.0D, 0.0D, 0.0D};
        }

        RouteNode first = nodes.get(0);
        if (first == null) {
            return new double[] {0.0D, 0.0D, 0.0D};
        }
        if (nodes.size() == 1) {
            return new double[] {first.getLng(), first.getLat(), 0.0D};
        }

        double target = Math.max(0.0D, meters);
        double accumulated = 0.0D;
        for (int i = 1; i < nodes.size(); i++) {
            RouteNode prev = nodes.get(i - 1);
            RouteNode next = nodes.get(i);
            if (prev == null || next == null) {
                continue;
            }

            double segment = distanceMeters(prev.getLat(), prev.getLng(), next.getLat(), next.getLng());
            if (segment <= 0.0D) {
                continue;
            }

            if (target <= accumulated + segment) {
                double ratio = (target - accumulated) / segment;
                double lng = prev.getLng() + (next.getLng() - prev.getLng()) * ratio;
                double lat = prev.getLat() + (next.getLat() - prev.getLat()) * ratio;
                return new double[] {lng, lat, bearingDegrees(prev, next)};
            }
            accumulated += segment;
        }

        RouteNode last = nodes.get(nodes.size() - 1);
        if (last == null) {
            return new double[] {first.getLng(), first.getLat(), 0.0D};
        }
        RouteNode beforeLast = nodes.get(nodes.size() - 2);
        double bearing = beforeLast == null ? 0.0D : bearingDegrees(beforeLast, last);
        return new double[] {last.getLng(), last.getLat(), bearing};
    }

    /**
     * 按已走里程线性插值海拔（米），与 pointAtMeters 使用相同的分段比例。
     *
     * @param nodes  有序节点列表
     * @param meters 已走里程（米）
     * @return 海拔；节点不足时返回默认海拔
     */
    public static double altitudeAtMeters(List<RouteNode> nodes, double meters) {
        if (nodes == null || nodes.isEmpty()) {
            return RouteNode.DEFAULT_ALT;
        }
        RouteNode first = nodes.get(0);
        if (first == null) {
            return RouteNode.DEFAULT_ALT;
        }
        if (nodes.size() == 1) {
            return first.getAlt();
        }

        double target = Math.max(0.0D, meters);
        double accumulated = 0.0D;
        for (int i = 1; i < nodes.size(); i++) {
            RouteNode prev = nodes.get(i - 1);
            RouteNode next = nodes.get(i);
            if (prev == null || next == null) {
                continue;
            }

            double segment = distanceMeters(prev.getLat(), prev.getLng(), next.getLat(), next.getLng());
            if (segment <= 0.0D) {
                continue;
            }
            if (target <= accumulated + segment) {
                double ratio = (target - accumulated) / segment;
                return prev.getAlt() + (next.getAlt() - prev.getAlt()) * ratio;
            }
            accumulated += segment;
        }

        RouteNode last = nodes.get(nodes.size() - 1);
        return last == null ? first.getAlt() : last.getAlt();
    }

    /** 计算 prev→next 的方位角（正北 0°，顺时针 0..360）。 */
    private static double bearingDegrees(RouteNode prev, RouteNode next) {
        double midLat = Math.abs((prev.getLat() + next.getLat()) / 2.0D);
        double east = (next.getLng() - prev.getLng()) * METERS_PER_LNG_DEGREE
                * Math.cos(midLat * Math.PI / 180.0D);
        double north = (next.getLat() - prev.getLat()) * METERS_PER_LAT_DEGREE;
        double bearing = Math.toDegrees(Math.atan2(east, north));
        if (bearing < 0.0D) {
            bearing += 360.0D;
        }
        return bearing;
    }
}
