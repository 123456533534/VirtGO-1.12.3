package com.zcshou.gogogo.util;

import com.zcshou.gogogo.model.RouteLine;
import com.zcshou.gogogo.model.RouteNode;
import com.zcshou.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 位置页多点路线的唯一内存真相。
 *
 * <p>节点始终以 WGS84 语义保存（与 {@code ServiceGo.setPosition} 的注入约定一致）。
 * 落点入口接收百度地图 BD09 坐标，内部经 {@link MapUtils#bd2wgs(double, double)} 转换后入库；
 * 标记、折线、距离、撤销、收藏与推送服务全部从本类的节点列表派生。</p>
 */
public class RoutePlanner {

    private final List<RouteNode> nodes = new ArrayList<>();
    private int nextNodeId = 1;
    private final double defaultAlt;

    // 画模式：闭环标记（显示层首尾相连）与闭环回放圈数（1-99）
    private boolean closed = false;
    private int loopCount = 1;

    /**
     * @param defaultAlt 新增节点的默认海拔（米）
     */
    public RoutePlanner(double defaultAlt) {
        this.defaultAlt = defaultAlt;
    }

    /**
     * 追加一个地图落点。
     *
     * @param lat  百度地图 BD09 纬度
     * @param lng  百度地图 BD09 经度
     * @param name 节点名称，null/空白时给出默认名称
     * @return 新入库的 WGS84 节点
     */
    public RouteNode addBd09Point(double lat, double lng, String name) {
        double[] wgs = MapUtils.bd2wgs(lng, lat);
        String safeName = name == null || name.trim().isEmpty() ? "节点 " + (nodes.size() + 1) : name;
        RouteNode node = new RouteNode(nextNodeId++, safeName, nodes.size() + 1,
                wgs[1], wgs[0], defaultAlt);
        nodes.add(node);
        closed = false;
        return node;
    }

    /**
     * 仅当与末点距离不小于阈值时追加一个 BD09 落点（画模式采样去重）。
     *
     * @return 实际入库返回节点，被距离阈值忽略返回 null
     */
    public RouteNode addBd09PointIfFarEnough(double lat, double lng, String name, double minMeters) {
        if (!nodes.isEmpty()) {
            RouteNode last = nodes.get(nodes.size() - 1);
            double[] wgs = MapUtils.bd2wgs(lng, lat);
            double dist = RouteInterpolator.distanceMeters(last.getLat(), last.getLng(), wgs[1], wgs[0]);
            if (dist < minMeters) {
                return null;
            }
        }
        return addBd09Point(lat, lng, name);
    }

    /**
     * 首尾点是否足够接近以形成闭环（WGS84 语义，阈值由调用方给出）。
     *
     * @param maxMeters 首尾距离阈值（米）
     * @return 至少 2 点且首尾距离小于阈值返回 true
     */
    public boolean isCloseLoopWithin(double maxMeters) {
        if (nodes.size() < 2) {
            return false;
        }
        RouteNode first = nodes.get(0);
        RouteNode last = nodes.get(nodes.size() - 1);
        double dist = RouteInterpolator.distanceMeters(first.getLat(), first.getLng(), last.getLat(), last.getLng());
        return dist <= maxMeters;
    }

    /** 标记/取消闭环（显示层首尾相连，不改变节点数据）。 */
    public void setClosed(boolean value) {
        this.closed = value && nodes.size() >= 2;
    }

    /** @return 是否闭环 */
    public boolean isClosed() {
        return closed;
    }

    /** @param loopCount 闭环回放圈数，钳制到 [1,99] */
    public void setLoopCount(int loopCount) {
        this.loopCount = Math.max(1, Math.min(99, loopCount));
    }

    /** @return 闭环回放圈数 */
    public int getLoopCount() {
        return loopCount;
    }

    /**
     * 撤销末点并重排序号，保证编号连续。
     *
     * @return 有节点被移除返回 true
     */
    public boolean undo() {
        if (nodes.isEmpty()) {
            return false;
        }
        nodes.remove(nodes.size() - 1);
        reindex();
        closed = false;
        return true;
    }

    /** 清空全部节点，编号从 1 重新开始。 */
    public void clear() {
        nodes.clear();
        nextNodeId = 1;
        closed = false;
    }

    /**
     * 从一条收藏线路回填，深拷贝节点；null 安全。
     *
     * @param line 收藏线路，可为 null
     */
    public void load(RouteLine line) {
        nodes.clear();
        nextNodeId = 1;
        if (line == null) {
            return;
        }

        int maxId = 0;
        for (RouteNode source : line.getNodes()) {
            if (source == null) {
                continue;
            }
            RouteNode node = new RouteNode(source.getId(), source.getName(), source.getOrder(),
                    source.getLat(), source.getLng(), source.getAlt());
            nodes.add(node);
            if (node.getId() > maxId) {
                maxId = node.getId();
            }
        }
        reindex();
        nextNodeId = maxId + 1;
    }

    /** @return 内部节点列表（只读语义，调用方不得修改结构） */
    public List<RouteNode> getNodes() {
        return nodes;
    }

    /** @return 深拷贝节点列表，可安全交给 Binder / Store */
    public List<RouteNode> copyNodes() {
        List<RouteNode> copy = new ArrayList<>();
        for (RouteNode node : nodes) {
            if (node == null) {
                continue;
            }
            copy.add(new RouteNode(node.getId(), node.getName(), node.getOrder(),
                    node.getLat(), node.getLng(), node.getAlt()));
        }
        return copy;
    }

    /** @return 当前节点数 */
    public int size() {
        return nodes.size();
    }

    /** @return 线路总里程（米），复用现有插值工具 */
    public double totalMeters() {
        return RouteInterpolator.totalMeters(nodes);
    }

    /** 重排 order 为 1..N。 */
    private void reindex() {
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode node = nodes.get(i);
            if (node != null) {
                node.setOrder(i + 1);
            }
        }
    }
}
