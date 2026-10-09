package com.solosu.mtforum.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.solosu.mtforum.model.Message;

import org.junit.Test;

import java.util.List;

public class ForumParserWallTest {

    @Test
    public void parsesWallEntryMetadataBodyQuoteAndActions() {
        String html = "<dl id='comment_20112_li'>"
                + "<dt><a class='rzlist_tximg'><img src='/uc_server/avatar.php?uid=123'></a>"
                + "<h2><a class='top_user' href='home.php?mod=space&amp;uid=123'>Alice</a></h2>"
                + "<span class='top_time'>2026-10-09 12:03</span>"
                + "<a id='comment_20112_edit' href='home.php?mod=spacecp&amp;ac=comment&amp;op=edit&amp;cid=20112'>edit</a>"
                + "<a id='comment_20112_delete' href='home.php?mod=spacecp&amp;ac=comment&amp;op=delete&amp;cid=20112'>delete</a>"
                + "<a id='comment_20112_reply' href='home.php?mod=spacecp&amp;ac=comment&amp;op=reply&amp;cid=20112'>reply</a></dt>"
                + "<dd class='plface'>hello<br>world"
                + "<div class='comiis_quote'><a class='top_user' href='home.php?uid=456'>Bob</a>quoted text</div>"
                + "</dd></dl>";

        List<Message> messages = ForumParser.parseWallMessages(html);
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        assertEquals("20112", message.getPmid());
        assertEquals("Alice", message.getAuthor());
        assertEquals("123", message.getAuthorUid());
        assertEquals("2026-10-09 12:03", message.getTime());
        assertEquals("hello\nworld", message.getSummary());
        assertEquals("quoted text", message.getQuotedContent());
        assertTrue(message.getAvatarUrl().startsWith("https://bbs.binmt.cc/"));
        assertTrue(message.getWallEditUrl().contains("cid=20112"));
        assertTrue(message.getWallDeleteUrl().contains("cid=20112"));
        assertTrue(message.getWallReplyUrl().contains("cid=20112"));
    }

    @Test
    public void parsesMaximumPageAndDefaultsForEmptyPage() {
        String html = "<div class='pg'><a href='home.php?mod=space&amp;do=wall&amp;page=4'>4</a>"
                + "<span>共 4 页</span></div>";
        assertEquals(4, ForumParser.parseWallMaxPage(html));
        assertEquals(1, ForumParser.parseWallMaxPage(""));
    }
}
