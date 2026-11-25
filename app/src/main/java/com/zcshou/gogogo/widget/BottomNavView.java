package com.zcshou.gogogo.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.zcshou.gogogo.R;

/**
 * 全局胶囊底部导航：3 个 Tab（位置/收藏/设置）的选中态与点击分发。
 *
 * <p>不取代现有 DrawerLayout 抽屉；仅作为复合控件嵌入各页面底部。</p>
 */
public class BottomNavView extends LinearLayout {

    /** Tab 点击回调；未设置时走 {@link NavRouter#open(Context, String)} 默认跳转。 */
    public interface OnTabClickListener {
        void onTabClick(String tabKey);
    }

    private static final String[] TAB_KEYS = {
            NavConstants.TAB_LOCATION,
            NavConstants.TAB_FAVORITE,
            NavConstants.TAB_SETTINGS
    };

    private final View[] tabItems = new View[TAB_KEYS.length];
    private final ImageView[] tabIcons = new ImageView[TAB_KEYS.length];
    private final TextView[] tabLabels = new TextView[TAB_KEYS.length];

    private String currentTab = NavConstants.TAB_LOCATION;
    private OnTabClickListener tabClickListener = null;

    public BottomNavView(Context context) {
        super(context);
        bindViews();
    }

    public BottomNavView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        bindViews();
    }

    public BottomNavView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        bindViews();
    }

    /** inflate 布局、绑定 3 个 Tab 的图标/文字与点击事件。 */
    private void bindViews() {
        setOrientation(LinearLayout.HORIZONTAL);
        LayoutInflater.from(getContext()).inflate(R.layout.bottom_nav_view, this, true);

        int[] itemIds = {R.id.nav_location, R.id.nav_favorite, R.id.nav_settings};
        int[] iconIds = {
                R.id.nav_location_icon,
                R.id.nav_favorite_icon, R.id.nav_settings_icon
        };
        int[] labelIds = {
                R.id.nav_location_label,
                R.id.nav_favorite_label, R.id.nav_settings_label
        };

        for (int i = 0; i < TAB_KEYS.length; i++) {
            tabItems[i] = findViewById(itemIds[i]);
            tabIcons[i] = findViewById(iconIds[i]);
            tabLabels[i] = findViewById(labelIds[i]);

            final String tabKey = TAB_KEYS[i];
            if (tabItems[i] != null) {
                tabItems[i].setOnClickListener(v -> onTabClick(tabKey));
            }
        }

        setContentDescription("底部导航");
        setSelectedTab(currentTab);
    }

    /**
     * 切换选中态。ViewGroup 的 selected 状态不一定传播到子 View，
     * 因此这里对容器/图标/文字逐一设置，保证 color selector 生效。
     *
     * @param tabKey {@link NavConstants} 中的 Tab 常量
     */
    public void setSelectedTab(String tabKey) {
        String key = NavConstants.isKnownTab(tabKey) ? tabKey : NavConstants.TAB_LOCATION;
        currentTab = key;

        for (int i = 0; i < TAB_KEYS.length; i++) {
            boolean selected = TAB_KEYS[i].equals(key);
            if (tabItems[i] != null) {
                tabItems[i].setSelected(selected);
            }
            if (tabIcons[i] != null) {
                tabIcons[i].setSelected(selected);
            }
            if (tabLabels[i] != null) {
                tabLabels[i].setSelected(selected);
            }
        }
    }

    /** @return 当前选中的 Tab。 */
    public String getSelectedTab() {
        return currentTab;
    }

    /** 设置点击回调；为 null 时走默认路由。 */
    public void setOnTabClickListener(OnTabClickListener listener) {
        this.tabClickListener = listener;
    }

    /** 点击当前 Tab 不跳转；其他 Tab 交给回调或默认路由。 */
    private void onTabClick(String tabKey) {
        if (tabKey.equals(currentTab)) {
            return;
        }

        setSelectedTab(tabKey);
        if (tabClickListener != null) {
            tabClickListener.onTabClick(tabKey);
        } else {
            NavRouter.open(getContext(), tabKey);
        }
    }
}
