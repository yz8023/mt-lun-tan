package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * build106: 裸 [attach]/[attachimg] 的渲染守门测试。
 *
 * <p>背景（tid=160198 整帖图片空白）：旧实现把裸 aid 拼成
 * {@code forum.php?mod=image&aid=…&key=} —— Discuz 的 mod=image 需要服务端
 * 签发的 key，空 key 实测一律 HTTP 500，等于每张图都是加载不出来的空白块。
 * 现在改为可视占位文案：站点没把附件内联渲染出来时，用户至少看得见原因，
 * 而不是对着白块猜。
 */
public class BBCodeAttachRenderTest {

    @Test
    public void attach_bbcode_renders_visible_placeholder_not_fake_image() {
        String html = BBCodeUtil.convertBBCodeToHtml("演示 [attach]347054[/attach] 截图");
        assertTrue("应有可视占位", html.contains("mt-attach-ph"));
        assertTrue("占位文案", html.contains("[图片附件]"));
        assertFalse("不得再伪造 mod=image 地址", html.contains("mod=image"));
        assertFalse("不得再带空 key 参数", html.contains("key="));
        assertFalse("不得残留裸 bbcode", html.contains("[attach]347054"));
    }

    @Test
    public void attachimg_bbcode_also_renders_placeholder() {
        String html = BBCodeUtil.convertBBCodeToHtml("[attachimg]998877[/attachimg]");
        assertTrue(html.contains("mt-attach-ph"));
        assertFalse(html.contains("mod=image"));
        assertFalse(html.contains("[attachimg]"));
    }

    @Test
    public void normal_img_bbcode_unaffected() {
        String html = BBCodeUtil.convertBBCodeToHtml(
                "[img]https://cdn.binmt.cc/forum/2025/12/11/x.png[/img]");
        assertTrue("普通 [img] 仍转成 img 标签",
                html.contains("<img src=\"https://cdn.binmt.cc/forum/2025/12/11/x.png\">"));
        assertFalse(html.contains("mt-attach-ph"));
    }
}
