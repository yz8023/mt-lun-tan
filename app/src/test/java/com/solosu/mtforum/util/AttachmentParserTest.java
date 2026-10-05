package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class AttachmentParserTest {

    @Test
    public void recognizesApkAttachmentAndKeepsItsExtension() {
        String html = "<ignore_js_op><span id='attach_7'>"
                + "<a href='forum.php?mod=attachment&amp;aid=signedAid123' title='应用包'>RikkaHub-release.apk</a>"
                + "<em class='xg1'>(12.4 MB, 下载次数: 8)</em>"
                + "</span></ignore_js_op>";
        List<AttachmentParser.Attachment> attachments = AttachmentParser.parse(html);
        assertEquals(1, attachments.size());
        assertEquals("RikkaHub-release.apk", attachments.get(0).name);
        assertTrue(attachments.get(0).isApk());
        assertEquals("https://bbs.binmt.cc/forum.php?mod=attachment&aid=signedAid123",
                attachments.get(0).downloadUrl());
    }

    @Test
    public void doesNotMistakeGenericAttachmentLinkForAnImage() {
        String html = "<ignore_js_op><a href='forum.php?mod=attachment&amp;aid=123'>文件</a></ignore_js_op>";
        List<AttachmentParser.Attachment> attachments = AttachmentParser.parse(html);
        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertEquals("MT论坛附件", attachments.get(0).name);
    }
}
