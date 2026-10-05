package com.solosu.mtforum.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 配合 Glide 异步加载的占位 Drawable（用于 Html.ImageGetter）
 * Html.fromHtml 同步调用 getDrawable 时先返回本占位对象，
 * Glide 加载完成后回调 setReal() 填充真实图并触发宿主 TextView 重绘。
 * ImageSpan 持有的是本对象引用，draw() 委托内部 real，实现异步回显。
 */
public class UrlDrawable extends ColorDrawable {

    private Drawable real;
    private View target;

    public UrlDrawable(View targetView, int sizePx) {
        super(0x00000000);
        this.target = targetView;
        setBounds(0, 0, sizePx, sizePx);
    }

    /** Glide 加载完成回填真实图，并请求宿主重绘 */
    public void setReal(Drawable drawable, View targetView) {
        this.real = drawable;
        this.target = targetView;
        if (drawable != null) {
            // ImageGetter 先用小占位图创建 ImageSpan；必须把占位 Drawable 自身的 bounds
            // 同步到加载后计算出的真实显示尺寸，否则 ImageSpan 仍会按初始占位尺寸排版。
            android.graphics.Rect loadedBounds = drawable.getBounds();
            if (loadedBounds.width() > 0 && loadedBounds.height() > 0) {
                setBounds(0, 0, loadedBounds.width(), loadedBounds.height());
            }
            drawable.setBounds(getBounds());
        }
        if (targetView instanceof android.widget.TextView) {
            // 图片尺寸变化后必须重新创建文本布局。setText(tv.getText()) 可能因同一对象
            // 被判定为无变化而直接返回，因此复制 Spanned，并且仅在本图片仍属于当前行时重排，
            // 避免 RecyclerView 复用后旧图片回调触碰到新内容。
            final android.widget.TextView tv = (android.widget.TextView) targetView;
            tv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        CharSequence current = tv.getText();
                        if (current instanceof android.text.Spanned) {
                            android.text.Spanned spanned = (android.text.Spanned) current;
                            android.text.style.ImageSpan[] spans = spanned.getSpans(
                                    0, spanned.length(), android.text.style.ImageSpan.class);
                            boolean stillAttached = false;
                            for (android.text.style.ImageSpan span : spans) {
                                if (span.getDrawable() == UrlDrawable.this) {
                                    stillAttached = true;
                                    break;
                                }
                            }
                            if (!stillAttached) return;
                            tv.setText(new android.text.SpannableString(current));
                        }
                        tv.requestLayout();
                        tv.invalidate();
                    } catch (Exception ignore) {
                    }
                }
            });
        } else if (targetView != null) {
            targetView.postInvalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (real != null) {
            real.setBounds(getBounds());
            real.draw(canvas);
        } else {
            super.draw(canvas);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return real != null ? real.getIntrinsicWidth() : getBounds().width();
    }

    @Override
    public int getIntrinsicHeight() {
        return real != null ? real.getIntrinsicHeight() : getBounds().height();
    }

    @Override
    public void setAlpha(int alpha) {
        if (real != null) {
            real.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (real != null) {
            real.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return real != null ? real.getOpacity() : PixelFormat.TRANSPARENT;
    }
}
