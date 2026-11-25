package com.zcshou.gogogo.widget;

import android.content.Context;

/**
 * 导航路由的显式入口，保留独立类型便于后续扩展转场动画与埋点。
 * 实际跳转逻辑由 {@link NavConstants#open(android.content.Context, String)} 承担。
 */
public final class NavRouter {

    private NavRouter() {
        // 工具类禁止实例化
    }

    /**
     * 打开 tabKey 指定的页面。
     *
     * @param context 当前页面上下文
     * @param tabKey  {@link NavConstants} 中定义的 Tab 常量
     */
    public static void open(Context context, String tabKey) {
        NavConstants.open(context, tabKey);
    }
}
