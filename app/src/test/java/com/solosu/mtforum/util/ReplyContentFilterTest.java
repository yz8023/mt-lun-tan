package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Arrays;

import org.junit.Test;

public class ReplyContentFilterTest {

    @Test
    public void blacklistAlwaysUsesKeywordContainsMatching() {
        assertTrue(ReplyContentFilter.matchesBlacklist("看看", Collections.singletonList("看看")));
        assertTrue(ReplyContentFilter.matchesBlacklist("来看看", Collections.singletonList("看看")));
        assertTrue(ReplyContentFilter.matchesBlacklist("看看隐藏", Collections.singletonList("看看")));
        assertFalse(ReplyContentFilter.matchesBlacklist("感谢分享", Collections.singletonList("看看")));
    }

    @Test
    public void keywordMatchingIgnoresWhitespaceAndLatinCaseButKeepsPunctuation() {
        assertTrue(ReplyContentFilter.matchesBlacklist("正文里有 看 看\n隐藏", Collections.singletonList("看看")));
        assertTrue(ReplyContentFilter.matchesBlacklist("PLEASE  HELP", Collections.singletonList("please help")));
        assertFalse(ReplyContentFilter.matchesBlacklist("感谢分享！", Collections.singletonList("感谢分享，")));
    }

    @Test
    public void matchesSeveralDefaultStyleKeywordsInsideSubstantiveText() {
        assertTrue(ReplyContentFilter.matchesBlacklist("我看了，看看隐藏内容在这里", Arrays.asList("看看隐藏", "感谢分享")));
        assertTrue(ReplyContentFilter.matchesBlacklist("这个资源正需要这个，已经装好了", Collections.singletonList("正需要这个")));
    }

    @Test
    public void detectsPureFillerPhrasesAndTheirCombinations() {
        assertTrue(ReplyContentFilter.isSpamReply("看看", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享！", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享，支持一下", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("看看看看", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("正需要这个！", "帖子标题"));
    }

    @Test
    public void detectsBuiltInAutoUnlockTemplatesWithTitleKeyword() {
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享「教程主题」，正需要这个，回复支持一下", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "「教程主题」看着不错，谢谢分享，下来试试", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "支持「教程主题」，感谢分享，先回复看看", "教程主题"));
        assertTrue(ReplyContentFilter.isSpamReply(
                "感谢分享「这是一个超过十个字符的教程标题」，正好用得上",
                "这是一个超过十个字符的教程标题"));
    }

    @Test
    public void detectsTitleFollowedOrPrecededByCourtesyTemplate() {
        assertTrue(ReplyContentFilter.isSpamReply("帖子标题，感谢楼主分享", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享：帖子标题", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("帖子标题支持一下", "帖子标题"));
    }

    @Test
    public void keepsRepliesWithSubstantiveTextFromTemplateOnlyMatcher() {
        assertFalse(ReplyContentFilter.isSpamReply("看看隐藏内容在哪里", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("感谢分享，我按步骤安装后已经能正常使用", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("帖子标题写得不错，我补充一下实际测试结果", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("帖子标题", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("", "帖子标题"));
    }

    @Test
    public void titleAloneIsNotAReplyTemplate() {
        assertFalse(ReplyContentFilter.isSpamReply("这个帖子标题有一点问题", "帖子标题"));
    }
}
