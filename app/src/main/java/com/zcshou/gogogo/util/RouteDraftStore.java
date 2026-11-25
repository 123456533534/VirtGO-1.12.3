package com.zcshou.gogogo.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.zcshou.gogogo.model.RouteLine;
import com.zcshou.gogogo.model.RouteNode;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前画布点集的 SharedPreferences 草稿持久化。
 *
 * <p>位置页每次落点/撤销/清空/回填都会写入；ServiceGo 在 Binder 未推送或进程重启时读取兜底。
 * 序列化复用系统 org.json 与既有 {@link RouteLine}，不新增任何依赖与存储表。</p>
 */
public class RouteDraftStore {

    /** 草稿在默认 SharedPreferences 中的 key。 */
    public static final String KEY_ROUTE_DRAFT = "route_draft_json";
    /** 闭环标记与回放圈数的 key（服务重启兜底需要）。 */
    public static final String KEY_ROUTE_CLOSED = "route_draft_closed";
    public static final String KEY_ROUTE_LOOPS = "route_draft_loops";

    private static final String DRAFT_ID = "draft";
    private static final String DRAFT_NAME = "草稿线路";

    private final SharedPreferences sp;

    /**
     * @param context 上下文，内部使用 applicationContext 避免持有 Activity
     */
    public RouteDraftStore(Context context) {
        this.sp = PreferenceManager.getDefaultSharedPreferences(
                context == null ? null : context.getApplicationContext());
    }

    /**
     * 保存点集草稿。
     *
     * @param nodes 当前节点列表，null 时写空草稿
     */
    public void save(List<RouteNode> nodes) {
        if (sp == null) {
            return;
        }
        List<RouteNode> safeNodes = nodes == null ? new ArrayList<RouteNode>() : nodes;
        RouteLine line = new RouteLine(DRAFT_ID, DRAFT_NAME, System.currentTimeMillis(), safeNodes);
        try {
            sp.edit().putString(KEY_ROUTE_DRAFT, line.toJson().toString()).apply();
        } catch (Exception e) {
            // 序列化失败保留上一份草稿，不阻塞落点流程
        }
    }

    /**
     * 读取点集草稿。
     *
     * @return 非空列表；解析失败返回空列表
     */
    public List<RouteNode> load() {
        List<RouteNode> result = new ArrayList<>();
        if (sp == null) {
            return result;
        }

        String raw = sp.getString(KEY_ROUTE_DRAFT, "");
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }

        try {
            RouteLine line = RouteLine.fromJson(new JSONObject(raw));
            for (RouteNode node : line.getNodes()) {
                if (node != null) {
                    result.add(node);
                }
            }
        } catch (Exception e) {
            result.clear();
        }
        return result;
    }

    /** 保存闭环标记与回放圈数。 */
    public void saveLoopInfo(boolean closed, int loops) {
        if (sp == null) {
            return;
        }
        sp.edit()
                .putBoolean(KEY_ROUTE_CLOSED, closed)
                .putInt(KEY_ROUTE_LOOPS, Math.max(1, Math.min(99, loops)))
                .apply();
    }

    /** @return 草稿是否闭环 */
    public boolean loadClosed() {
        return sp != null && sp.getBoolean(KEY_ROUTE_CLOSED, false);
    }

    /** @return 草稿回放圈数，钳制到 [1,99] */
    public int loadLoops() {
        if (sp == null) {
            return 1;
        }
        return Math.max(1, Math.min(99, sp.getInt(KEY_ROUTE_LOOPS, 1)));
    }

    /** 清除草稿。 */
    public void clear() {
        if (sp != null) {
            sp.edit().remove(KEY_ROUTE_DRAFT)
                    .remove(KEY_ROUTE_CLOSED)
                    .remove(KEY_ROUTE_LOOPS)
                    .apply();
        }
    }
}
