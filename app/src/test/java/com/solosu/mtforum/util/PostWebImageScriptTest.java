package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * build106: 正文 WebView 图片失败兜底脚本的 JVM 单测。
 *
 * <p>守住这几条不变量：
 * <ol>
 *   <li>每张图都会打上 {@code data-mtimg-i} 索引，原生按索引回填；</li>
 *   <li>监听挂上之前就已经失败的图（{@code complete && naturalWidth===0}）
 *       也会被处理 —— Chromium 不会为已失败的 img 补发 error 事件；</li>
 *   <li>一张图最多重试一次（{@code data-mt-retried} 守卫），失败转可视样式；</li>
 *   <li>回填脚本按索引精确定位，且 data: URI 中的特殊字符被转义。</li>
 * </ol>
 */
public class PostWebImageScriptTest {

    private static final String IDX_3 = "img[data-mtimg-i=" + '"' + "3" + '"' + "]";
    private static final String IDX_7 = "img[data-mtimg-i=" + '"' + "7" + '"' + "]";

    @Test
    public void bindErrors_marksEachImageAndListensForErrors() {
        String js = PostWebImageScript.bindErrorsJs();
        assertTrue("应给图片打索引属性", js.contains("data-mtimg-i"));
        assertTrue("应挂 error 监听", js.contains("addEventListener"));
        assertTrue("error 监听要走捕获阶段", js.contains(",true)"));
        assertTrue("应回调原生 imgFailed", js.contains("PostBody.imgFailed"));
        assertTrue("应处理挂监听前已失败的图", js.contains("naturalWidth===0"));
        assertTrue("重试守卫", js.contains("data-mt-retried"));
    }

    @Test
    public void setImgDataUri_targetsIndexedImageAndMarksRetried() {
        String js = PostWebImageScript.setImgDataUriJs(3, "data:image/png;base64,AAA");
        assertTrue("按索引定位图片", js.contains(IDX_3));
        assertTrue("标记已重试", js.contains("data-mt-retried"));
        assertTrue("回填 data URI", js.contains("data:image/png;base64,AAA"));
        assertTrue("清除失败样式", js.contains("mt-img-failed"));
    }

    @Test
    public void setImgDataUri_escapesHostileValues() {
        // 单引号、反斜杠、尖括号都必须转义，否则脚本被截断/注入
        String hostile = "data:image/png;base64," + "'" + "\\" + "</script>";
        String js = PostWebImageScript.setImgDataUriJs(0, hostile);
        assertTrue("单引号要转义", js.contains("\\'"));
        assertTrue("反斜杠要转义", js.contains("\\\\"));
        assertTrue("尖括号要转义", js.contains("\\x3c"));
        assertFalse("原始裸单引号不能进入字面量",
                js.contains("base64," + "'"));
    }

    @Test
    public void markImgFailed_addsVisibleFailureStyle() {
        String js = PostWebImageScript.markImgFailedJs(7);
        assertTrue(js.contains(IDX_7));
        assertTrue(js.contains("mt-img-failed"));
        assertTrue(js.contains("data-mt-retried"));
    }

    /**
     * build107: 统计「真正加载成功」的图片数，用于判断要不要上图廊兜底。
     *
     * <p>判定必须是 {@code complete && naturalWidth > 0}：站点把需要登录的附件换成
     * HTML 提示页时，{@code <img>} 照样会触发 load 完成事件，只是尺寸为 0 ——
     * 那种图在用户眼里就是一片空白，绝不能算「加载成功」，否则兜底永远不会触发。
     */
    @Test
    public void countLoadedImages_onlyCountsImagesWithRealPixels() {
        String js = PostWebImageScript.countLoadedImagesJs();
        assertTrue("必须按 naturalWidth 判定，不是只看 complete",
                js.contains("naturalWidth>0"));
        assertTrue("必须同时要求 complete", js.contains("e.complete&&e.naturalWidth>0"));
        assertTrue("遍历所有 img", js.contains("getElementsByTagName('img')"));
        assertTrue("出错要返回 -1，让调用方当未知处理而不是『一张没有』",
                js.contains("return -1"));
    }
}
