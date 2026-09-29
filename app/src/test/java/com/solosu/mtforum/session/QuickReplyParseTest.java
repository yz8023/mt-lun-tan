package com.solosu.mtforum.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 快捷回复短语的多行文本解析（build63）。
 * 编辑器是「一行一条」，空行和首尾空格必须被吃掉，否则会冒出空按钮。
 */
public class QuickReplyParseTest {

    @Test
    public void parsesLinesAndTrims() {
        List<String> r = QuickReplyManager.parseLines("感谢分享\n  支持一下  \n\n学习了\n");
        assertEquals(Arrays.asList("感谢分享", "支持一下", "学习了"), r);
    }

    @Test
    public void handlesCrlf() {
        List<String> r = QuickReplyManager.parseLines("A\r\nB\r\n");
        assertEquals(Arrays.asList("A", "B"), r);
    }

    @Test
    public void emptyInputGivesEmptyList() {
        assertTrue(QuickReplyManager.parseLines(null).isEmpty());
        assertTrue(QuickReplyManager.parseLines("").isEmpty());
        assertTrue(QuickReplyManager.parseLines("\n\n  \n").isEmpty());
    }

    @Test
    public void roundTripThroughLines() {
        List<String> items = Arrays.asList("沙发", "顶一个", "已下载，回来反馈");
        assertEquals(items, QuickReplyManager.parseLines(QuickReplyManager.toLines(items)));
    }

    @Test
    public void defaultsAreNonEmptyAndWithinLimits() {
        assertTrue(!QuickReplyManager.DEFAULTS.isEmpty());
        assertTrue(QuickReplyManager.DEFAULTS.size() <= QuickReplyManager.MAX_COUNT);
        for (String s : QuickReplyManager.DEFAULTS) {
            assertTrue(s.length() <= QuickReplyManager.MAX_LENGTH);
        }
    }
}
