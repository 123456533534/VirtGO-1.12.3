package com.zcshou.gogogo.widget;

import android.content.Context;
import android.content.Intent;

/**
 * 底部胶囊导航的 tabKey 常量与轻量路由。
 *
 * <p>目标 Activity 用全限定类名字符串引用而非编译期 Class 引用，便于本组件在页面
 * 调整阶段独立编译；运行时通过 setClassName 解析，行为与直接 Class 引用一致。</p>
 */
public final class NavConstants {

    /** Intent extra key，三个 Tab 的选中态来源。 */
    public static final String EXTRA_NAV_TAB = "com.zcshou.gogogo.extra.NAV_TAB";

    public static final String TAB_LOCATION = "location";
    public static final String TAB_FAVORITE = "favorite";
    public static final String TAB_SETTINGS = "settings";

    private static final String APP_PACKAGE = "com.zcshou.gogogo";
    private static final String CLASS_MAIN = APP_PACKAGE + ".MainActivity";
    private static final String CLASS_FAVORITE = APP_PACKAGE + ".HistoryActivity";
    private static final String CLASS_SETTINGS = APP_PACKAGE + ".SettingsActivity";

    private NavConstants() {
        // 工具类禁止实例化
    }

    /**
     * 按 tabKey 打开对应页面。
     *
     * <p>回主屏时带 CLEAR_TOP | SINGLE_TOP，触发 MainActivity.onNewIntent 只刷新选中态，
     * 避免 singleInstance 的主屏被重建导致 MapView 与静态 showLocation 失效。</p>
     *
     * @param context 当前页面上下文
     * @param tabKey  目标 Tab 常量
     */
    public static void open(Context context, String tabKey) {
        if (context == null || tabKey == null) {
            return;
        }

        String targetClass = targetClassName(tabKey);
        if (targetClass == null || targetClass.equals(context.getClass().getName())) {
            return;
        }

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), targetClass);
        intent.putExtra(EXTRA_NAV_TAB, tabKey);

        if (TAB_LOCATION.equals(tabKey)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }

        context.startActivity(intent);
    }

    /**
     * 从 Intent 中读取 Tab；没有 extra 时返回默认的位置页。
     *
     * @param intent 页面 Intent，可为 null
     * @return 合法的 tabKey
     */
    public static String tabFromIntent(Intent intent) {
        if (intent == null) {
            return TAB_LOCATION;
        }

        String tab = intent.getStringExtra(EXTRA_NAV_TAB);
        return isKnownTab(tab) ? tab : TAB_LOCATION;
    }

    /** 判断是否为已约定的 Tab。 */
    public static boolean isKnownTab(String tabKey) {
        return TAB_LOCATION.equals(tabKey)
                || TAB_FAVORITE.equals(tabKey)
                || TAB_SETTINGS.equals(tabKey);
    }

    private static String targetClassName(String tabKey) {
        if (TAB_LOCATION.equals(tabKey)) {
            return CLASS_MAIN;
        }
        if (TAB_FAVORITE.equals(tabKey)) {
            return CLASS_FAVORITE;
        }
        if (TAB_SETTINGS.equals(tabKey)) {
            return CLASS_SETTINGS;
        }
        return null;
    }
}
