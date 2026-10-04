package com.solosu.mtforum.util;

import android.text.Layout;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/**
 * build97: 给 TextView 里图文混排的图片挂「点击看大图」。
 *
 * <p>主楼（{@code ThreadDetailActivity}）和评论区（{@code ReplyAdapter}）都用
 * 同一套 ImageGetter + UrlDrawable 做内联渲染，但以前只有主楼挂了点击 ——
 * 评论区的图点下去毫无反应，用户报的就是「评论区图片无法显示原图」。
 *
 * <p>用触摸命中测试而不是 MovementMethod：{@code setupClickableLinks} 会把
 * {@code textIsSelectable} 设为 true，选择模式会吞掉 ClickableSpan 的点击。
 */
public final class InlineImageClicks {

    public interface Opener { void open(String url); }

    private InlineImageClicks() {}

    /** 给 tv 挂图片点击。opener 收到的是命中的图片地址（不含表情/站点静态图）。 */
    public static void attach(final TextView tv, final Opener opener) {
        if (tv == null || opener == null) return;
        tv.setOnTouchListener(new View.OnTouchListener() {
            private String pendingUrl;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pendingUrl = imageUrlAt(tv, event);
                    // 命中图片才拦截，否则放行给文本选择/链接
                    return pendingUrl != null;
                }
                if (action == MotionEvent.ACTION_UP && pendingUrl != null) {
                    String url = pendingUrl;
                    pendingUrl = null;
                    opener.open(url);
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    pendingUrl = null;
                }
                return false;
            }
        });
    }

    /** 触点处是否有内容图片，有则返回地址，没有返回 null */
    public static String imageUrlAt(TextView tv, MotionEvent event) {
        CharSequence cs = tv.getText();
        if (!(cs instanceof Spanned)) return null;
        Layout layout = tv.getLayout();
        if (layout == null) return null;

        int x = (int) event.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
        int y = (int) event.getY() - tv.getTotalPaddingTop() + tv.getScrollY();
        int line = layout.getLineForVertical(y);
        int offset = layout.getOffsetForHorizontal(line, x);

        Spanned sp = (Spanned) cs;
        ImageSpan[] spans = sp.getSpans(offset, offset, ImageSpan.class);
        if (spans == null || spans.length == 0) {
            // 触点可能落在图片字符的右半边，向前退一格再试
            if (offset > 0) {
                spans = sp.getSpans(offset - 1, offset - 1, ImageSpan.class);
            }
            if (spans == null || spans.length == 0) return null;
        }
        String url = spans[0].getSource();
        if (url == null || url.isEmpty()) return null;
        // 表情和站点静态图不放大
        if (url.contains("/smiley/") || url.contains("/static/image/")) return null;
        return url;
    }
}
