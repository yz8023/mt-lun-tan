package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AttachmentFileNameTest {

    @Test
    public void decodesPercentEncodedChineseAndPreservesApkExtension() {
        assertEquals("中文资源.apk", AttachmentFileName.fromAttachmentMarkup(
                "%E4%B8%AD%E6%96%87%E8%B5%84%E6%BA%90.apk", "forum.php?mod=attachment&aid=abc", "abc"));
    }

    @Test
    public void repairsUtf8ThatWasMisreadAsLatin1() {
        String mojibake = "\u00e4\u00b8\u00ad\u00e6\u0096\u0087.apk";
        assertEquals("中文.apk", AttachmentFileName.fromAttachmentMarkup(mojibake, "", ""));
    }

    @Test
    public void parsesRfc5987ContentDispositionAndAddsMimeExtension() {
        assertEquals("测试安装包.apk", AttachmentFileName.fromResponse(
                "https://example.invalid/download",
                "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E5%AE%89%E8%A3%85%E5%8C%85.apk",
                "application/octet-stream"));
        assertEquals("MT论坛下载.apk", AttachmentFileName.fromResponse(
                "https://example.invalid/download", null,
                "application/vnd.android.package-archive"));
    }

    @Test
    public void rejectsOpaqueAidAsFilenameAndRemovesUnsafePathCharacters() {
        String opaque = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/==";
        assertEquals("MT论坛附件", AttachmentFileName.fromAttachmentMarkup(
                opaque, "forum.php?mod=attachment&aid=" + opaque, opaque));
        assertEquals("我的_应用.apk", AttachmentFileName.sanitize("../我的:应用.apk"));
    }
}
