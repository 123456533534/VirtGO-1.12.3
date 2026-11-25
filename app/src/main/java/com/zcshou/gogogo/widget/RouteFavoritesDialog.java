package com.zcshou.gogogo.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.zcshou.gogogo.R;
import com.zcshou.gogogo.model.RouteLine;
import com.zcshou.utils.GoUtils;
import com.zcshou.gogogo.util.RouteStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏线路列表弹窗：单击载入、长按重命名/删除。
 */
public class RouteFavoritesDialog {

    /** 选中一条线路后的回调。 */
    public interface Callback {
        /**
         * @param line 用户点击的线路
         */
        void onPick(RouteLine line);
    }

    private final Context context;
    private final RouteStore store;
    private final Callback callback;
    private final List<RouteLine> lines = new ArrayList<>();

    private AlertDialog dialog;
    private FavoritesAdapter adapter;

    /**
     * @param context  上下文
     * @param store    收藏存储
     * @param callback 载入回调，可为 null
     */
    public RouteFavoritesDialog(Context context, RouteStore store, Callback callback) {
        this.context = context;
        this.store = store;
        this.callback = callback;
    }

    /** 构建并显示弹窗。 */
    public void show() {
        if (context == null || store == null) {
            return;
        }

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_route_favorites, null);
        ListView listView = content.findViewById(R.id.lv_route_favorites);
        adapter = new FavoritesAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onPick(position);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showItemMenu(view, position);
                return true;
            }
        });

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.route_favorites)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();

        refresh();
    }

    /** 从存储重新读取并刷新列表。 */
    public void refresh() {
        if (adapter == null) {
            return;
        }
        lines.clear();
        lines.addAll(store.listAll());
        adapter.notifyDataSetChanged();
        if (lines.isEmpty()) {
            GoUtils.DisplayToast(context, context.getString(R.string.route_fav_empty));
        }
    }

    /** 单击载入一条线路。 */
    private void onPick(int position) {
        if (position < 0 || position >= lines.size()) {
            return;
        }
        RouteLine picked = lines.get(position);
        if (callback != null) {
            callback.onPick(picked);
        }
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    /** 长按弹出重命名/删除菜单。 */
    private void showItemMenu(View anchor, final int position) {
        if (position < 0 || position >= lines.size()) {
            return;
        }
        final RouteLine line = lines.get(position);

        PopupMenu menu = new PopupMenu(context, anchor);
        menu.setGravity(Gravity.END | Gravity.BOTTOM);
        menu.getMenu().add(context.getString(R.string.route_fav_rename));
        menu.getMenu().add(context.getString(R.string.route_fav_delete));
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (context.getString(R.string.route_fav_rename).equals(title)) {
                showRenameDialog(line);
                return true;
            }
            if (context.getString(R.string.route_fav_delete).equals(title)) {
                showDeleteDialog(line);
                return true;
            }
            return false;
        });
        menu.show();
    }

    /** 重命名确认弹窗。 */
    private void showRenameDialog(final RouteLine line) {
        final EditText input = new EditText(context);
        input.setHint(R.string.route_save_name_hint);
        input.setText(line.getName());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(context)
                .setTitle(R.string.route_fav_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog123, which) -> {
                    String name = input.getText().toString();
                    if (!name.trim().isEmpty()) {
                        store.rename(line.getId(), name);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 删除确认弹窗。 */
    private void showDeleteDialog(final RouteLine line) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.route_fav_delete)
                .setMessage(R.string.route_fav_delete_confirm)
                .setPositiveButton(android.R.string.ok, (dialog12, which) -> {
                    store.delete(line.getId());
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 收藏行适配器。 */
    private class FavoritesAdapter extends ArrayAdapter<String> {
        FavoritesAdapter() {
            super(context, R.layout.route_favorite_item, new ArrayList<String>());
        }

        @Override
        public int getCount() {
            return lines.size();
        }

        @Override
        public String getItem(int position) {
            return lines.get(position).getName();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(context).inflate(R.layout.route_favorite_item, parent, false);
            }

            RouteLine line = lines.get(position);
            TextView name = view.findViewById(R.id.tv_fav_name);
            TextView time = view.findViewById(R.id.tv_fav_time);
            TextView count = view.findViewById(R.id.tv_fav_count);

            if (name != null) {
                name.setText(line.getName());
            }
            if (time != null) {
                time.setText(GoUtils.timeStamp2Date(String.valueOf(line.getUpdatedAt() / 1000L)));
            }
            if (count != null) {
                count.setText(context.getString(R.string.route_fav_count, line.getNodes().size()));
            }
            return view;
        }
    }
}
