package com.solosu.mtforum.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 快捷回复的「勾选启用 + 变量占位符」纯 Java 测试（build98 · 用户反馈第 2 条）。
 *
 * <p>用户要的是：条目能用<b>勾选</b>的方式取舍，能编辑 / 删除，还要支持
 * {@code {title}} 这类<b>快捷键（占位符）</b>。这些逻辑全在 {@link QuickReplyManager} 里，
 * 不碰 Android API，所以可以直接在 JVM 上测。
 *
 * <p>重点守两件事：
 * <ol>
 *   <li><b>老数据不能丢</b>：build63~98 存的是纯字符串数组
 *       {@code ["感谢分享"]}，升级后必须还能读出来，而且默认是勾选状态</li>
 *   <li><b>只替换带花括号的变量</b>：正文里恰好出现单词 {@code title} 时不能被误替换</li>
 * </ol>
 */
public class QuickReplyItemsTest {

    @Test
    public void readsLegacyStringArray() {
        List<QuickReplyManager.Item> items =
                QuickReplyManager.parseStored("[\"感谢分享\",\"支持一下\"]");
        assertEquals(2, items.size());
        assertEquals("感谢分享", items.get(0).text);
        assertTrue("老条目默认勾选", items.get(0).enabled);
        assertTrue(items.get(1).enabled);
    }

    @Test
    public void readsNewObjectArrayWithEnabledFlag() {
        List<QuickReplyManager.Item> items = QuickReplyManager.parseStored(
                "[{\"t\":\"感谢分享\",\"on\":true},{\"t\":\"沙发\",\"on\":false}]");
        assertEquals(2, items.size());
        assertTrue(items.get(0).enabled);
        assertFalse("on=false 的就是没勾的，不该出现在快捷条上", items.get(1).enabled);
        assertEquals("沙发", items.get(1).text);
    }

    @Test
    public void roundTripKeepsTextAndFlag() {
        List<QuickReplyManager.Item> src = new ArrayList<>();
        src.add(new QuickReplyManager.Item("含 \"引号\" 和 \\ 反斜杠", true));
        src.add(new QuickReplyManager.Item("{author} 太强了", false));

        List<QuickReplyManager.Item> back =
                QuickReplyManager.parseStored(QuickReplyManager.serialize(src));
        assertEquals(2, back.size());
        assertEquals(src.get(0).text, back.get(0).text);
        assertTrue(back.get(0).enabled);
        assertEquals(src.get(1).text, back.get(1).text);
        assertFalse(back.get(1).enabled);
    }

    @Test
    public void brokenJsonDoesNotExplode() {
        assertTrue(QuickReplyManager.parseStored("").isEmpty());
        assertTrue(QuickReplyManager.parseStored("not json").isEmpty());
        assertTrue(QuickReplyManager.parseStored("[{\"t\":").isEmpty());
        assertTrue(QuickReplyManager.parseStored(null).isEmpty());
    }

    @Test
    public void countIsCappedAtMax() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < QuickReplyManager.MAX_COUNT + 5; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(i).append('"');
        }
        sb.append(']');
        assertEquals(QuickReplyManager.MAX_COUNT,
                QuickReplyManager.parseStored(sb.toString()).size());
    }

    @Test
    public void tooLongTextIsClamped() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < QuickReplyManager.MAX_LENGTH + 20; i++) longText.append('字');
        List<QuickReplyManager.Item> items =
                QuickReplyManager.parseStored("[\"" + longText + "\"]");
        assertEquals(QuickReplyManager.MAX_LENGTH, items.get(0).text.length());
    }

    @Test
    public void replacesKnownVariablesOnly() {
        Map<String, String> vars = QuickReplyManager.newVarMap();
        vars.put("{title}", "怎么脱壳");
        vars.put("{author}", "张三");

        assertEquals("张三 太强了，怎么脱壳 已收藏",
                QuickReplyManager.applyVars("{author} 太强了，{title} 已收藏", vars));
    }

    @Test
    public void leavesUnknownPlaceholderAlone() {
        Map<String, String> vars = QuickReplyManager.newVarMap();
        vars.put("{title}", "标题");
        // {nobody} 没给值：原样留着，让用户自己删，而不是悄悄变空
        assertEquals("标题 / {nobody}", QuickReplyManager.applyVars("{title} / {nobody}", vars));
    }

    @Test
    public void doesNotTouchBareWordMatchingVariableName() {
        Map<String, String> vars = QuickReplyManager.newVarMap();
        vars.put("{title}", "标题");
        // 没带花括号的普通英文单词不能被替换（正文里出现 title 很常见）
        assertEquals("the title is fine",
                QuickReplyManager.applyVars("the title is fine", vars));
        // 带花括号的要换
        assertEquals("the title is 标题",
                QuickReplyManager.applyVars("the title is {title}", vars));
    }

    @Test
    public void emptyValueKeepsPlaceholder() {
        Map<String, String> vars = QuickReplyManager.newVarMap();
        vars.put("{floor}", "");
        assertEquals("{floor} 楼说得对",
                QuickReplyManager.applyVars("{floor} 楼说得对", vars));
    }

    @Test
    public void variableTableIsWellFormed() {
        assertTrue(QuickReplyManager.VARIABLES.length >= 5);
        for (String[] v : QuickReplyManager.VARIABLES) {
            assertEquals(2, v.length);
            assertTrue("占位符要带花括号：" + v[0],
                    v[0].startsWith("{") && v[0].endsWith("}"));
            assertTrue("说明不能为空", v[1].trim().length() > 0);
        }
    }

    @Test
    public void defaultItemsAreAllEnabled() {
        for (QuickReplyManager.Item it : QuickReplyManager.defaultItems()) {
            assertTrue("默认条目默认全部勾选", it.enabled);
            assertFalse(it.text.trim().isEmpty());
        }
    }
}
