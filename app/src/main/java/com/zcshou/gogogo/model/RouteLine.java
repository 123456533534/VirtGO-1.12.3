package com.zcshou.gogogo.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条完整的多途经点线路，含名称、更新时间与有序节点序列。
 *
 * <p>序列化使用 Android 系统内置的 org.json，不引入任何第三方依赖。</p>
 */
public class RouteLine {

    private String id;
    private String name;
    private long updatedAt;
    private List<RouteNode> nodes;
    // 画模式闭环属性（向后兼容：老 JSON 缺失时默认 false / 1）
    private boolean closed = false;
    private int loopCount = 1;

    /** 默认构造：JSON 反序列化与收藏列表使用。 */
    public RouteLine() {
        this("", "", 0L, new ArrayList<RouteNode>());
    }

    /**
     * 全参构造。
     *
     * @param id        线路唯一标识，"r_" + 时间戳
     * @param name      线路名称
     * @param updatedAt 更新时间戳（毫秒）
     * @param nodes     有序节点列表
     */
    public RouteLine(String id, String name, long updatedAt, List<RouteNode> nodes) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.updatedAt = updatedAt;
        this.nodes = nodes == null ? new ArrayList<RouteNode>() : nodes;
    }

    /** @return 线路唯一标识 */
    public String getId() {
        return id;
    }

    /** @param id 线路唯一标识 */
    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    /** @return 线路名称 */
    public String getName() {
        return name;
    }

    /** @param name 线路名称 */
    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /** @return 更新时间戳（毫秒） */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt 更新时间戳（毫秒） */
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** @return 有序节点列表（非 null） */
    public List<RouteNode> getNodes() {
        return nodes;
    }

    /** @param nodes 有序节点列表，null 时自动转为空列表 */
    public void setNodes(List<RouteNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<RouteNode>() : nodes;
    }

    /** @return 是否闭环线路 */
    public boolean isClosed() {
        return closed;
    }

    /** @param closed 是否闭环线路 */
    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    /** @return 闭环回放圈数 */
    public int getLoopCount() {
        return loopCount;
    }

    /** @param loopCount 闭环回放圈数，钳制到 [1,99] */
    public void setLoopCount(int loopCount) {
        this.loopCount = Math.max(1, Math.min(99, loopCount));
    }

    /**
     * 序列化为架构约定的 JSON 对象。
     *
     * @return 含 id/name/updatedAt/nodes 的 JSONObject
     * @throws JSONException 序列化失败时抛出
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("updatedAt", updatedAt);
        json.put("closed", closed);
        json.put("loopCount", loopCount);

        JSONArray nodeArray = new JSONArray();
        for (RouteNode node : nodes) {
            if (node == null) {
                continue;
            }
            JSONObject nodeJson = new JSONObject();
            nodeJson.put("id", node.getId());
            nodeJson.put("name", node.getName());
            nodeJson.put("order", node.getOrder());
            nodeJson.put("lat", node.getLat());
            nodeJson.put("lng", node.getLng());
            nodeJson.put("alt", node.getAlt());
            nodeArray.put(nodeJson);
        }
        json.put("nodes", nodeArray);
        return json;
    }

    /**
     * 从 JSON 对象反序列化；缺失字段使用安全默认值，坏节点跳过而不是整体失败。
     *
     * @param json 线路 JSON 对象
     * @return 还原后的 RouteLine
     * @throws JSONException 顶层结构非法时抛出
     */
    public static RouteLine fromJson(JSONObject json) throws JSONException {
        RouteLine line = new RouteLine();
        if (json == null) {
            return line;
        }

        line.setId(json.optString("id", ""));
        line.setName(json.optString("name", ""));
        line.setUpdatedAt(json.optLong("updatedAt", 0L));
        line.setClosed(json.optBoolean("closed", false));
        line.setLoopCount(json.optInt("loopCount", 1));

        List<RouteNode> restored = new ArrayList<>();
        JSONArray nodeArray = json.optJSONArray("nodes");
        if (nodeArray != null) {
            for (int i = 0; i < nodeArray.length(); i++) {
                JSONObject nodeJson = nodeArray.optJSONObject(i);
                if (nodeJson == null) {
                    continue;
                }
                RouteNode node = new RouteNode();
                node.setId(nodeJson.optInt("id", i + 1));
                node.setName(nodeJson.optString("name", ""));
                node.setOrder(nodeJson.optInt("order", i + 1));
                node.setLat(safeDouble(nodeJson.optDouble("lat", 0.0D), 0.0D));
                node.setLng(safeDouble(nodeJson.optDouble("lng", 0.0D), 0.0D));
                node.setAlt(safeDouble(nodeJson.optDouble("alt", RouteNode.DEFAULT_ALT),
                        RouteNode.DEFAULT_ALT));
                restored.add(node);
            }
        }
        line.setNodes(restored);
        return line;
    }

    /** org.json 的 optDouble 在类型不匹配时返回 NaN，这里统一回退到安全值。 */
    private static double safeDouble(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : value;
    }
}
