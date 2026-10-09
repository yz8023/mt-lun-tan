package com.solosu.mtforum.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ForumIdParserTest {
    @Test public void acceptsPlainPositiveIdsAndNormalizesLeadingZeroes() {
        assertEquals("173642", ForumIdParser.parseTid("173642"));
        assertEquals("42", ForumIdParser.parseUid("00042"));
    }

    @Test public void readsThreadQueryParameterWithoutPickingEarlierNumbers() {
        assertEquals("876", ForumIdParser.parseTid(
                "https://bbs.binmt.cc/forum.php?authorid=42&page=2&tid=876&extra=1"));
        assertEquals("876", ForumIdParser.parseTid("tid=876"));
        assertEquals("876", ForumIdParser.parseTid("https://bbs.binmt.cc/?THREADID=876"));
    }

    @Test public void readsPrettyThreadLinks() {
        assertEquals("876", ForumIdParser.parseTid(
                "https://bbs.binmt.cc/thread-876-1-1.html"));
        assertEquals("876", ForumIdParser.parseTid(
                "https://bbs.binmt.cc/user-42/thread-876-2-1.html"));
    }

    @Test public void readsUserProfileLinks() {
        assertEquals("250", ForumIdParser.parseUid(
                "https://bbs.binmt.cc/home.php?mod=space&uid=250&do=profile"));
        assertEquals("250", ForumIdParser.parseUid(
                "https://bbs.binmt.cc/space-uid-250.html"));
    }

    @Test public void rejectsUnrelatedNumbersAndZero() {
        assertNull(ForumIdParser.parseTid("https://bbs.binmt.cc/forum.php?fid=14&page=2"));
        assertNull(ForumIdParser.parseTid("thread-0-1-1.html"));
        assertNull(ForumIdParser.parseTid("topic 876 please"));
        assertNull(ForumIdParser.parseUid("https://bbs.binmt.cc/forum.php?fid=14"));
    }
}
