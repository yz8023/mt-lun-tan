package com.solosu.mtforum.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class ReplyContentFilterTest {

    @Test
    public void exactModeRequiresWholeReplyToEqualTerm() {
        assertTrue(ReplyContentFilter.matchesBlacklist("看看", Collections.singletonList("看看"), false));
        assertFalse(ReplyContentFilter.matchesBlacklist("来看看", Collections.singletonList("看看"), false));
        assertFalse(ReplyContentFilter.matchesBlacklist("看看隐藏", Collections.singletonList("看看"), false));
    }

    @Test
    public void fuzzyModeMatchesContainedTerm() {
        assertTrue(ReplyContentFilter.matchesBlacklist("来看看", Collections.singletonList("看看"), true));
        assertTrue(ReplyContentFilter.matchesBlacklist("看看隐藏", Collections.singletonList("看看"), true));
        assertFalse(ReplyContentFilter.matchesBlacklist("感谢分享", Collections.singletonList("看看"), true));
    }

    @Test
    public void matchingIgnoresWhitespaceButKeepsExactPunctuationAndLatinCaseRules() {
        assertTrue(ReplyContentFilter.matchesBlacklist("  看 看 \n", Collections.singletonList("看看"), false));
        assertFalse(ReplyContentFilter.matchesBlacklist("看看！", Collections.singletonList("看看"), false));
        assertTrue(ReplyContentFilter.matchesBlacklist("PLEASE  HELP", Collections.singletonList("please help"), false));
    }

    @Test
    public void detectsPureFillerPhrasesAndTheirCombinations() {
        assertTrue(ReplyContentFilter.isSpamReply("看看", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享！", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享，支持一下", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("看看看看", "帖子标题"));
    }

    @Test
    public void detectsTitleFollowedByCourtesyTemplate() {
        assertTrue(ReplyContentFilter.isSpamReply("帖子标题，感谢楼主分享", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("感谢分享：帖子标题", "帖子标题"));
        assertTrue(ReplyContentFilter.isSpamReply("帖子标题支持一下", "帖子标题"));
    }

    @Test
    public void keepsRepliesWithSubstantiveText() {
        assertFalse(ReplyContentFilter.isSpamReply("看看隐藏内容在哪里", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("感谢分享，我按步骤安装后已经能正常使用", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("帖子标题写得不错，我补充一下实际测试结果", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("帖子标题", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("", "帖子标题"));
    }

    @Test
    public void titleOnlyHidesWhenRemainingTextIsCourtesyTemplate() {
        assertTrue(ReplyContentFilter.isSpamReply("帖子标题谢谢分享", "帖子标题"));
        assertFalse(ReplyContentFilter.isSpamReply("这个帖子标题有一点问题", "帖子标题"));
    }
}
