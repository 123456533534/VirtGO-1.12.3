package com.zcshou.gogogo.model;

/**
 * 线路节点数据模型。
 *
 * <p>经纬度按 WGS84 语义使用，与 {@code ServiceGo.setPosition} 的注入约定一致。
 * 本类是原 RouteActivity 内部静态类的公共升级版，供存储、插值与对话框复用。</p>
 */
public class RouteNode {

    /** 未设置节点时的默认海拔（米）。 */
    public static final double DEFAULT_ALT = 55.0D;

    private int id;
    private String name;
    private int order;
    private double lat;
    private double lng;
    private double alt;

    /** 默认构造：JSON 反序列化与列表适配器使用。 */
    public RouteNode() {
        this(0, "", 1, 0.0D, 0.0D, DEFAULT_ALT);
    }

    /**
     * 全参构造。
     *
     * @param id    节点本地唯一标识
     * @param name  节点名称
     * @param order 1..N 的顺序号，第一个为起点、最后一个为终点
     * @param lat   纬度（WGS84）
     * @param lng   经度（WGS84）
     * @param alt   海拔（米）
     */
    public RouteNode(int id, String name, int order, double lat, double lng, double alt) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.order = order;
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
    }

    /** @return 节点本地唯一标识 */
    public int getId() {
        return id;
    }

    /** @param id 节点本地唯一标识 */
    public void setId(int id) {
        this.id = id;
    }

    /** @return 节点名称 */
    public String getName() {
        return name;
    }

    /** @param name 节点名称 */
    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /** @return 1..N 的顺序号 */
    public int getOrder() {
        return order;
    }

    /** @param order 1..N 的顺序号 */
    public void setOrder(int order) {
        this.order = order;
    }

    /** @return 纬度（WGS84） */
    public double getLat() {
        return lat;
    }

    /** @param lat 纬度（WGS84） */
    public void setLat(double lat) {
        this.lat = lat;
    }

    /** @return 经度（WGS84） */
    public double getLng() {
        return lng;
    }

    /** @param lng 经度（WGS84） */
    public void setLng(double lng) {
        this.lng = lng;
    }

    /** @return 海拔（米） */
    public double getAlt() {
        return alt;
    }

    /** @param alt 海拔（米） */
    public void setAlt(double alt) {
        this.alt = alt;
    }

    /**
     * 合法性校验：名称非空、经纬度在合法范围、序号从 1 开始。
     *
     * @return true 表示节点可用于插值与存储
     */
    public boolean isValid() {
        return name != null
                && !name.trim().isEmpty()
                && order >= 1
                && lat >= -90.0D && lat <= 90.0D
                && lng >= -180.0D && lng <= 180.0D
                && !Double.isNaN(lat) && !Double.isNaN(lng)
                && !Double.isInfinite(lat) && !Double.isInfinite(lng);
    }
}
