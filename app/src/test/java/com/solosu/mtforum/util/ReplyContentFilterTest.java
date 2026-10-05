package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ReplyContentFilterTest {

    @Test
    public void customKeywordFilterMatchesAnywhereInReply() {
        assertTrue(ReplyContentFilter.matchesKeyword("来看看隐藏内容", Collections.singletonList("看看隐藏")));
        assertTrue(ReplyContentFilter.matchesKeyword("正文中间有 关 键 词。", Collections.singletonList("关键词")));
        assertTrue(ReplyContentFilter.matchesKeyword("PLEASE  HELP", Collections.singletonList("please help")));
        assertFalse(ReplyContentFilter.matchesKeyword("感谢分享", Collections.singletonList("看看隐藏")));
    }

    @Test
    public void spamPhraseFilterRequiresTheWholeReplyToMatch() {
        assertTrue(ReplyContentFilter.matchesExactPhrase("感谢分享", Collections.singletonList("感谢分享")));
        assertTrue(ReplyContentFilter.matchesExactPhrase("  看 看\n隐藏 ", Collections.singletonList("看看隐藏")));
        assertFalse(ReplyContentFilter.matchesExactPhrase(
                "感谢分享，我已测试并补充说明", Collections.singletonList("感谢分享")));
        assertFalse(ReplyContentFilter.matchesExactPhrase(
                "感谢分享！", Collections.singletonList("感谢分享")));
    }

    @Test
    public void exactSpamListKeepsPunctuationAndFullReplySemantics() {
        assertTrue(ReplyContentFilter.matchesExactPhrase(
                "正需要这个！", Arrays.asList("看看隐藏", "正需要这个！")));
        assertFalse(ReplyContentFilter.matchesExactPhrase(
                "这个资源正需要这个！", Collections.singletonList("正需要这个！")));
    }

    @Test
    public void detectsOnlyCompleteBuiltInUnlockTemplates() {
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享，内容看着不错，回复支持一下", ""));
        assertTrue(ReplyContentFilter.isSpamReply(
                "谢谢分享，正需要这个，先回复看看", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("正需要这个！", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply(
                "感谢分享，我按步骤安装后已经能正常使用", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply(
                "我很感谢分享这个资源，已经补充了测试结果", "帖子标题"));
    }

    @Test
    public void titleTemplatedAutoUnlockRepliesNeedAnExactFullMatch() {
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享「教程主题」，正需要这个，回复支持一下", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "「教程主题」看着不错，谢谢分享，下来试试", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "支持「教程主题」，感谢分享，先回复看看", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享「教程主题」", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享「这是一个超过十个字符的教程标题」，正好用得上",
                "这是一个超过十个字符的教程标题"));
        assertFalse(ReplyContentFilter.isSpamReply(
                "感谢分享「教程主题」，正需要这个，回复支持一下。我另外补充了完整测试步骤。",
                "教程主题"));
        assertFalse(ReplyContentFilter.isSpamReply("教程主题写得不错，我补充一下实际测试结果", "教程主题"));
    }
}
