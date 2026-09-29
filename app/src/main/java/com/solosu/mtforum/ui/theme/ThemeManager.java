package com.solosu.mtforum.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.solosu.mtforum.R;

/**
 * 应用主题管理（build66 新增）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>深色模式</b>：跟随系统 / 浅色 / 深色，走 {@link AppCompatDelegate} 的
 *       NightMode，配合 {@code values-night/} 资源自动换色</li>
 *   <li><b>主题色</b>：工程里绝大多数控件写死的是 {@code @color/primary} 这个<b>资源</b>
 *       而不是 {@code ?attr/colorPrimary} 属性，资源没法在运行时改。
 *       所以这里用「遍历视图树、把原本等于默认主色的地方换成所选主色」的办法，
 *       在不重写全部布局的前提下拿到真实可见的换肤效果。</li>
 * </ol>
 */
public final class ThemeManager {

    private static final String PREF = "app_settings";
    private static final String KEY_NIGHT = "theme_night_mode";
    private static final String KEY_ACCENT = "theme_accent";

    /** 深色模式取值 */
    public static final int NIGHT_SYSTEM = 0;
    public static final int NIGHT_LIGHT = 1;
    public static final int NIGHT_DARK = 2;

    /** 主题色预设（名称 + 浅色值 + 深色值） */
    public static final String[] ACCENT_NAMES = {
            "科技蓝", "墨绿", "曜紫", "赤橙", "玫红", "石墨"
    };
    private static final int[] ACCENT_LIGHT = {
            0xFF1A73E8, 0xFF16A34A, 0xFF7C3AED, 0xFFEA580C, 0xFFDB2777, 0xFF475569
    };
    private static final int[] ACCENT_DARK = {
            0xFF60A5FA, 0xFF4ADE80, 0xFFA78BFA, 0xFFFB923C, 0xFFF472B6, 0xFF94A3B8
    };

    private ThemeManager() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ==================== 深色模式 ====================

    public static int getNightMode(Context c) {
        return sp(c).getInt(KEY_NIGHT, NIGHT_SYSTEM);
    }

    public static void setNightMode(Context c, int mode) {
        sp(c).edit().putInt(KEY_NIGHT, mode).apply();
        apply(mode);
    }

    /** 把设置好的深色模式真正生效，Application.onCreate 里调一次 */
    public static void applySaved(Context c) {
        apply(getNightMode(c));
    }

    private static void apply(int mode) {
        switch (mode) {
            case NIGHT_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case NIGHT_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public static String nightModeName(Context c) {
        switch (getNightMode(c)) {
            case NIGHT_LIGHT: return "浅色";
            case NIGHT_DARK: return "深色";
            default: return "跟随系统";
        }
    }

    // ==================== 主题色 ====================

    public static int getAccentIndex(Context c) {
        int i = sp(c).getInt(KEY_ACCENT, 0);
        return (i >= 0 && i < ACCENT_LIGHT.length) ? i : 0;
    }

    public static void setAccentIndex(Context c, int index) {
        sp(c).edit().putInt(KEY_ACCENT, index).apply();
    }

    public static String accentName(Context c) {
        return ACCENT_NAMES[getAccentIndex(c)];
    }

    /** 当前主题色（自动按明暗取对应亮度的那一档） */
    public static int accent(Context c) {
        int i = getAccentIndex(c);
        return isNight(c) ? ACCENT_DARK[i] : ACCENT_LIGHT[i];
    }

    /** 预览用：取指定档位的颜色 */
    public static int accentAt(Context c, int index) {
        if (index < 0 || index >= ACCENT_LIGHT.length) index = 0;
        return isNight(c) ? ACCENT_DARK[index] : ACCENT_LIGHT[index];
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 主题是否被改过（默认蓝色时可以整棵树都不用遍历） */
    public static boolean isAccentCustomised(Context c) {
        return getAccentIndex(c) != 0;
    }

    // ==================== 运行时换肤 ====================

    /**
     * 递归把视图树里「原本是默认主色」的地方换成当前主题色。
     *
     * <p>覆盖：ImageView 的 tint、TextView 的文字色、MaterialButton 的
     * backgroundTint / strokeColor、以及 backgroundTintList。
     *
     * <p>只在用户选了非默认主题色时才走，默认蓝色直接返回，零开销。
     */
    public static void applyAccent(View root, Context ctx) {
        if (root == null || ctx == null) return;
        if (!isAccentCustomised(ctx)) return;
        int from = ctx.getResources().getColor(R.color.primary, ctx.getTheme());
        int to = accent(ctx);
        if (from == to) return;
        recolor(root, from, to);
    }

    private static void recolor(View v, int from, int to) {
        if (v instanceof MaterialButton) {
            MaterialButton b = (MaterialButton) v;
            if (matches(b.getBackgroundTintList(), from)) {
                b.setBackgroundTintList(ColorStateList.valueOf(to));
            }
            if (matches(b.getStrokeColor(), from)) {
                b.setStrokeColor(ColorStateList.valueOf(to));
            }
            if (matches(b.getTextColors(), from)) {
                b.setTextColor(to);
            }
        } else if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            if (matches(iv.getImageTintList(), from)) {
                iv.setImageTintList(ColorStateList.valueOf(to));
            }
        } else if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (matches(tv.getTextColors(), from)) {
                tv.setTextColor(to);
            }
        }
        if (matches(v.getBackgroundTintList(), from)) {
            v.setBackgroundTintList(ColorStateList.valueOf(to));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                recolor(g.getChildAt(i), from, to);
            }
        }
    }

    private static boolean matches(ColorStateList csl, int color) {
        return csl != null && csl.getDefaultColor() == color;
    }

    /** 带透明度的主题色，用于淡底 */
    public static int accentAlpha(Context c, int alpha) {
        int a = accent(c);
        return Color.argb(alpha, Color.red(a), Color.green(a), Color.blue(a));
    }
}
