package com.solosu.mtforum.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MarkdownBbcodeConverterTest {
    @Test public void convertsCommonInlineMarkdown() {
        assertEquals("[b]bold[/b] and [i]soft[/i] [s]gone[/s]",
                MarkdownBbcodeConverter.convert("**bold** and _soft_ ~~gone~~"));
    }

    @Test public void convertsLinksImagesHeadingsAndQuotes() {
        String converted = MarkdownBbcodeConverter.convert(
                "# Title\n[forum](https://example.test)\n![photo](https://img.test/a.png)\n> quoted");
        assertTrue(converted.contains("[size=5][b]Title[/b][/size]"));
        assertTrue(converted.contains("[url=https://example.test]forum[/url]"));
        assertTrue(converted.contains("[img]https://img.test/a.png[/img]"));
        assertTrue(converted.contains("[quote]quoted[/quote]"));
    }

    @Test public void keepsFencedCodeLiteralAndSupportsUnclosedFence() {
        String converted = MarkdownBbcodeConverter.convert(
                "```java\n**not bold**\n```\n`inline`\n~~~\n**also literal**");
        assertTrue(converted.contains("[code]**not bold**\n[/code]"));
        assertTrue(converted.contains("[code]inline[/code]"));
        assertTrue(converted.contains("[code]**also literal**\n[/code]"));
    }

    @Test public void preservesExistingBbcodeWhileConvertingNearbyMarkdown() {
        String converted = MarkdownBbcodeConverter.convert(
                "[b]already bold[/b] **new bold** [qq]12345[/qq]");
        assertEquals("[b]already bold[/b] [b]new bold[/b] [qq]12345[/qq]", converted);
    }

    @Test public void convertsListsAndNormalizesNewlines() {
        String converted = MarkdownBbcodeConverter.convert("- one\r\n- two\r\n\r\n1. first\r\n2. second");
        assertTrue(converted.contains("[list]\n[*]one\n[*]two\n[/list]"));
        assertTrue(converted.contains("[list=1]\n[*]first\n[*]second\n[/list]"));
    }
}
