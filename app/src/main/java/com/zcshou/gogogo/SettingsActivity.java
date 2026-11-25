package com.zcshou.gogogo;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;

import com.zcshou.gogogo.widget.BottomNavView;
import com.zcshou.gogogo.widget.NavConstants;

public class SettingsActivity extends BaseActivity {

    private BottomNavView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 为了启动欢迎页全屏，状态栏被设置了透明，但是会导致其他页面状态栏空白
         * 这里设计如下：
         * 1. 除了 WelcomeActivity 之外的所有 Activity 均继承 BaseActivity
         * 2. WelcomeActivity 单独处理，其他 Activity 手动填充 StatusBar
         * */
        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));

        setContentView(R.layout.activity_settings);
        
        if (savedInstanceState == null) {
            getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.settings, new FragmentSettings())
            .commit();
        }

        initBottomNav();

        /* 获取默认的顶部的标题栏（安卓称为 ActionBar）*/
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    /** 绑定底部导航；从抽屉进入（无 extra）时默认选中“设置”。 */
    private void initBottomNav() {
        bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            String tab = getIntent().hasExtra(NavConstants.EXTRA_NAV_TAB)
                    ? NavConstants.tabFromIntent(getIntent())
                    : NavConstants.TAB_SETTINGS;
            bottomNav.setSelectedTab(tab);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            this.finish(); // back button
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}