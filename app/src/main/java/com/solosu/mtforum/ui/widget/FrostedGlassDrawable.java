package com.solosu.mtforum.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;


/**
 * 轻量玻璃背景(用于列表项/导航栏等高频刷新场景):
 * 半透明渐变底 + 顶部高光 + 细边框,简洁通透,绘制开销极小。

 */
public class FrostedGlassDrawable extends Drawable {

    // ==================== 不透明度档位（build63） ====================
    // 原实现固定 62%→50%，叠在帖子列表这种深浅混杂的背景上时文字几乎读不清。
    // 拆成两档：卡片保留一点点通透，对话框基本不透明（对话框本来就该压住背景）。

    /** 卡片 / 列表项：顶部 96% → 底部 93%，仍有玻璃感但不影响阅读 */
    public static final int LEVEL_CARD = 0;
    /** 对话框 / 底部弹窗：顶部 99% → 底部 97%，保证长文可读 */
    public static final int LEVEL_DIALOG = 1;

    private static final int[][] ALPHA = {
            {245, 237},  // LEVEL_CARD
            {252, 248},  // LEVEL_DIALOG
    };

    /**
     * 暗色感知工厂:按当前主题自动选择填充色,radiusDp 为圆角半径(dp)。
     */
    public static FrostedGlassDrawable create(android.content.Context context, float radiusDp) {
        return create(context, radiusDp, LEVEL_CARD);
    }

    /** 对话框专用：几乎不透明，避免背景文字透上来干扰阅读 */
    public static FrostedGlassDrawable createDialog(android.content.Context context, float radiusDp) {
        return create(context, radiusDp, LEVEL_DIALOG);
    }

    public static FrostedGlassDrawable create(android.content.Context context, float radiusDp, int level) {
        return createWithOpacity(context, radiusDp, level);
    }

    /** build73: 不透明度改为读用户在设置里调的值 */
    public static FrostedGlassDrawable createWithOpacity(android.content.Context context,
                                                          float radiusDp, int level) {
        boolean isDark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        float density = context.getResources().getDisplayMetrics().density;
        FrostedGlassDrawable d = new FrostedGlassDrawable(
                isDark ? 0xFF1E1E1E : 0xFFFFFFFF,
                radiusDp * density, density);
        d.setLevelPreset(level);
        try {
            int pct = (level == LEVEL_DIALOG)
                    ? com.solosu.mtforum.ui.theme.ThemeManager.dialogOpacity(context)
                    : com.solosu.mtforum.ui.theme.ThemeManager.cardOpacity(context);
            d.setOpacityPercent(pct);
        } catch (Throwable ignored) {
        }
        return d;
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float density;
    private int fillColor;
    private int levelPreset = LEVEL_CARD;

    /** build73: 直接按百分比设置不透明度（顶部略亮、底部略暗，保留一点玻璃质感） */
    private int opacityPercent = -1;

    public void setOpacityPercent(int percent) {
        this.opacityPercent = percent;
        updateShaders();
        invalidateSelf();
    }

    /** 切换不透明度档位 */
    public void setLevelPreset(int level) {
        this.levelPreset = (level >= 0 && level < ALPHA.length) ? level : LEVEL_CARD;
        updateShaders();
        invalidateSelf();
    }

    /**
     * @param fillColor 基础填充色(实际不透明度由档位决定，见 {@link #setLevelPreset(int)})
     * @param radius    圆角半径(px)
     * @param density   屏幕密度(用于高光/边框尺寸)
     */
    public FrostedGlassDrawable(int fillColor, float radius, float density) {
        this.radius = radius;
        this.density = density;
        this.fillColor = fillColor;

        bgPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(0.5f, 0.75f * density));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
        updateShaders();
    }

    private void updateShaders() {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        int r = Color.red(fillColor);
        int g = Color.green(fillColor);
        int b = Color.blue(fillColor);
        // build63: 不透明度按档位取（原来写死 158→128 即 62%→50%，文字读不清）
        int top, bottom;
        if (opacityPercent >= 0) {
            top = Math.max(0, Math.min(255, Math.round(opacityPercent * 2.55f)));
            bottom = Math.max(0, top - 8);       // 底部略透，保留玻璃层次
        } else {
            int[] a = ALPHA[levelPreset];
            top = a[0];
            bottom = a[1];
        }
        bgPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                Color.argb(top, r, g, b),
                Color.argb(bottom, r, g, b),
                Shader.TileMode.CLAMP));
        // 边框:按底色明暗自适应 — 浅底配深灰细边框(日间可见),深底配白色细边框(夜间)
        boolean lightBg = (r * 299 + g * 587 + b * 114) / 1000 > 128;
        borderPaint.setShader(null);
        borderPaint.setColor(lightBg
                ? Color.argb(45, 0, 0, 0)
                : Color.argb(70, 255, 255, 255));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
        borderPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
