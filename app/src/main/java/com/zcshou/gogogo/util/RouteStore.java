package com.zcshou.gogogo.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.model.RouteLine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多线路收藏存储：默认 SharedPreferences + org.json，按更新时间倒序返回。
 *
 * <p>不建 SQLite 表、不新增 Gradle 依赖；日志只记录条数与错误，不打印任何 AK/凭据。</p>
 */
public class RouteStore {

    /** 收藏列表在默认 SharedPreferences 中的 key。 */
    public static final String KEY_ROUTES = "route_favorites_json";

    private static final String EMPTY_JSON_ARRAY = "[]";

    private final SharedPreferences sp;

    /**
     * @param context 上下文，内部使用 applicationContext 避免持有 Activity
     */
    public RouteStore(Context context) {
        this.sp = PreferenceManager.getDefaultSharedPreferences(
                context == null ? null : context.getApplicationContext());
    }

    /**
     * 读取全部收藏，按更新时间倒序。
     *
     * @return 非空列表（解析异常时返回空列表）
     */
    public List<RouteLine> listAll() {
        List<RouteLine> result = new ArrayList<>();
        if (sp == null) {
            return result;
        }

        String raw = sp.getString(KEY_ROUTES, EMPTY_JSON_ARRAY);
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                try {
                    result.add(RouteLine.fromJson(item));
                } catch (JSONException e) {
                    XLog.e("RouteStore: skip malformed route at index " + i);
                }
            }
            Collections.sort(result, new Comparator<RouteLine>() {
                @Override
                public int compare(RouteLine lhs, RouteLine rhs) {
                    return Long.compare(rhs.getUpdatedAt(), lhs.getUpdatedAt());
                }
            });
        } catch (JSONException e) {
            XLog.e("RouteStore: parse favorites failed");
            result.clear();
        }
        return result;
    }

    /**
     * 按 id 覆盖或追加一条线路，写回使用 apply() 异步落盘。
     *
     * @param line 待保存线路，null 时安全忽略
     */
    public void saveOrUpdate(RouteLine line) {
        if (line == null || sp == null) {
            return;
        }

        List<RouteLine> all = listAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (line.getId().equals(all.get(i).getId())) {
                all.set(i, line);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(line);
        }
        writeAll(all);
        XLog.i("RouteStore: saved route, total=" + all.size());
    }

    /**
     * 按 id 删除一条线路。
     *
     * @param routeId 线路 id
     */
    public void delete(String routeId) {
        if (routeId == null || sp == null) {
            return;
        }

        List<RouteLine> all = listAll();
        List<RouteLine> kept = new ArrayList<>();
        for (RouteLine line : all) {
            if (!routeId.equals(line.getId())) {
                kept.add(line);
            }
        }
        writeAll(kept);
        XLog.i("RouteStore: deleted route, total=" + kept.size());
    }

    /**
     * 重命名一条线路并刷新更新时间。
     *
     * @param routeId 线路 id
     * @param name    新名称，null/空白时忽略
     */
    public void rename(String routeId, String name) {
        if (routeId == null || name == null || name.trim().isEmpty() || sp == null) {
            return;
        }

        List<RouteLine> all = listAll();
        for (RouteLine line : all) {
            if (routeId.equals(line.getId())) {
                line.setName(name.trim());
                line.setUpdatedAt(System.currentTimeMillis());
                break;
            }
        }
        writeAll(all);
        XLog.i("RouteStore: renamed route");
    }

    /**
     * 按 id 查询单条线路。
     *
     * @param routeId 线路 id
     * @return 命中的线路，未命中返回 null
     */
    public RouteLine get(String routeId) {
        if (routeId == null) {
            return null;
        }
        for (RouteLine line : listAll()) {
            if (routeId.equals(line.getId())) {
                return line;
            }
        }
        return null;
    }

    /**
     * 生成线路 id。常规情况为 "r_" + 时间戳；同毫秒重复时追加递增后缀避免互相覆盖。
     *
     * @return 当前存储内唯一的线路 id
     */
    public String newId() {
        long timestamp = System.currentTimeMillis();
        String candidate = "r_" + timestamp;

        Set<String> existing = new HashSet<>();
        for (RouteLine line : listAll()) {
            existing.add(line.getId());
        }

        int suffix = 1;
        while (existing.contains(candidate)) {
            candidate = "r_" + timestamp + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    /** 将列表序列化后写入 SP；任何序列化异常都记录日志并放弃本次写入。 */
    private void writeAll(List<RouteLine> lines) {
        try {
            JSONArray array = new JSONArray();
            for (RouteLine line : lines) {
                array.put(line.toJson());
            }
            sp.edit().putString(KEY_ROUTES, array.toString()).apply();
        } catch (JSONException e) {
            XLog.e("RouteStore: serialize favorites failed");
        }
    }
}
