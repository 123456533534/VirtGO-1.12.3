package com.zcshou.gogogo.util;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.Overlay;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.zcshou.gogogo.R;
import com.zcshou.gogogo.model.RouteNode;
import com.zcshou.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 WGS84 的 {@link RouteNode} 列表渲染为百度编号标记 + 绿色折线。
 *
 * <p>编号图标由 {@link BitmapDescriptorFactory#fromView(View)} 代码生成，不新增 drawable 资源；
 * 折线颜色复用现有令牌 {@code @color/mediumseagreen}（#3CB371）。</p>
 */
public class RouteMapOverlay {

    private final Context context;
    private final List<Overlay> ownOverlays = new ArrayList<>();
    /** 节点数超过该阈值时跳过编号 marker，避免大量标记图标拖慢渲染；折线始终绘制。 */
    private static final int MAX_NUMBERED_MARKERS = 30;

    /**
     * @param context 上下文，用于布局 inflate 与颜色读取
     */
    public RouteMapOverlay(Context context) {
        this.context = context;
    }

    /**
     * 清空旧叠加并重绘整条路线。
     *
     * <p>这里调用 {@code map.clear()} 是为了移除 markMap() 刚加入的默认针脚，
     * 避免与最新点的编号标记重叠；路线数据本身由 RoutePlanner 持有，不受影响。</p>
     *
     * @param map        百度地图对象
     * @param nodesWgs84 WGS84 节点列表
     */
    public void redraw(BaiduMap map, List<RouteNode> nodesWgs84) {
        redraw(map, nodesWgs84, false);
    }

    /**
     * 清空旧叠加并重绘整条路线（支持闭环）。
     *
     * @param map        百度地图对象
     * @param nodesWgs84 WGS84 节点列表
     * @param closed     是否闭环：为 true 时折线末尾补首点形成闭合圈
     */
    public void redraw(BaiduMap map, List<RouteNode> nodesWgs84, boolean closed) {
        if (map == null || context == null) {
            return;
        }
        clearAll(map);
        if (nodesWgs84 == null || nodesWgs84.isEmpty()) {
            return;
        }

        List<LatLng> bdPoints = toBd09(nodesWgs84);
        // 节点过多时跳过编号 marker 以减轻渲染负担，折线仍按原逻辑始终绘制。
        if (nodesWgs84.size() <= MAX_NUMBERED_MARKERS) {
            for (int i = 0; i < nodesWgs84.size() && i < bdPoints.size(); i++) {
                RouteNode node = nodesWgs84.get(i);
                if (node == null) {
                    continue;
                }
                int order = node.getOrder() > 0 ? node.getOrder() : i + 1;
                BitmapDescriptor icon = numberIcon(order);
                MarkerOptions option = new MarkerOptions()
                        .position(bdPoints.get(i))
                        .icon(icon)
                        .anchor(0.5f, 1.0f);
                Overlay overlay = map.addOverlay(option);
                if (overlay != null) {
                    ownOverlays.add(overlay);
                }
            }
        }

        if (bdPoints.size() >= 2) {
            // 闭环：折线末尾补首点，使首尾相连形成闭合圈（不改节点数据）
            List<LatLng> linePoints = bdPoints;
            if (closed && bdPoints.size() >= 3) {
                linePoints = new ArrayList<>(bdPoints);
                linePoints.add(bdPoints.get(0));
            }
            PolylineOptions line = new PolylineOptions()
                    .points(linePoints)
                    .color(ContextCompat.getColor(context, R.color.mediumseagreen))
                    .width(6);
            Overlay overlay = map.addOverlay(line);
            if (overlay != null) {
                ownOverlays.add(overlay);
            }
        }
    }

    /**
     * 清除本类加过的叠加，并清掉地图上其他针脚。
     *
     * @param map 百度地图对象
     */
    public void clearAll(BaiduMap map) {
        if (map == null) {
            return;
        }
        map.clear();
        ownOverlays.clear();
    }

    /**
     * 生成一个圆形编号图标：墨青底 + 白字，锚点为圆心正下方。
     *
     * @param order 1..N 的顺序号
     * @return 供 MarkerOptions 使用的图标
     */
    private BitmapDescriptor numberIcon(int order) {
        View view = LayoutInflater.from(context).inflate(R.layout.route_marker, null);
        TextView number = view.findViewById(R.id.tv_route_marker_no);
        if (number != null) {
            number.setText(String.valueOf(order));
        }
        view.setContentDescription(context.getString(R.string.route_marker_desc) + " " + order);

        // bg_circle_btn 在白色系主题下是白底，这里用代码生成墨青圆底，避免白字不可见。
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(context, R.color.dark_accent));
        circle.setStroke(1, ContextCompat.getColor(context, R.color.white));
        view.setBackground(circle);

        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(unspecified, unspecified);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        return BitmapDescriptorFactory.fromView(view);
    }

    /**
     * WGS84 节点转百度 BD09 点列，供标记与折线绘制使用。
     *
     * @param nodes WGS84 节点列表
     * @return 与入参等长的 BD09 点列
     */
    private List<LatLng> toBd09(List<RouteNode> nodes) {
        List<LatLng> points = new ArrayList<>();
        for (RouteNode node : nodes) {
            if (node == null) {
                points.add(null);
                continue;
            }
            double[] bd = MapUtils.wgs2bd09(node.getLng(), node.getLat());
            points.add(new LatLng(bd[1], bd[0]));
        }
        return points;
    }
}
