package com.solosu.mtforum.ui.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import androidx.cardview.widget.CardView;

/**
 * 毛玻璃背景统一入口:
 * 所有卡片统一使用 FrostedGlassDrawable(半透明渐变 + 高光 + 细边框),
 * 与主界面导航栏保持一致的视觉风格,自动适配亮色/暗色主题。
 */
public final class FrostedGlassHelper {

    private FrostedGlassHelper() {
    }

    /** 递归处理 root 下的所有 CardView(包含 root 自身)。 */
    public static void applyToCardViews(View root, Context context) {
        applyToCardViews(root, context, FrostedGlassDrawable.LEVEL_CARD);
    }

    /** build63: 带不透明度档位的版本，对话框传 LEVEL_DIALOG */
    public static void applyToCardViews(View root, Context context, int level) {
        if (root == null || context == null) return;
        if (root instanceof CardView) {
            applyToCard((CardView) root, context, level);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToCardViews(group.getChildAt(i), context, level);
            }
        }
    }

    /** 给单个卡片设置毛玻璃背景(与列表项一致的轻量样式)。 */
    public static void applyToCard(CardView card, Context context) {
        applyToCard(card, context, FrostedGlassDrawable.LEVEL_CARD);
    }

    public static void applyToCard(CardView card, Context context, int level) {
        if (card == null || context == null) return;
        boolean isDark = isDarkMode(context);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = card.getRadius();
        if (radius <= 0f) radius = 12f * density;
        card.setCardBackgroundColor(Color.TRANSPARENT);
        FrostedGlassDrawable bg = new FrostedGlassDrawable(
                isDark ? 0xFF1E1E1E : 0xFFFFFFFF, radius, density);
        bg.setLevelPreset(level);
        card.setBackground(bg);
    }

    /** RecyclerView 卡片创建时使用。 */
    public static void applyToItem(View root, Context context) {
        applyToCardViews(root, context);
    }

    /** 兼容旧接口:无操作。 */
    public static void setVisible(View root, boolean visible) {
        // no-op
    }

    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
