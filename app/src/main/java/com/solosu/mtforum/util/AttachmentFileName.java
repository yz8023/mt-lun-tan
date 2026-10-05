package com.solosu.mtforum.util;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 统一解析论坛附件名和 HTTP 下载响应里的文件名，保留中文与扩展名并清理非法路径字符。 */
public final class AttachmentFileName {

    private static final Pattern FILENAME_STAR = Pattern.compile(
            "(?i)(?:^|;)\\s*filename\\*\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))");
    private static final Pattern FILENAME = Pattern.compile(
            "(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]+))");
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.([a-z0-9]{1,10})$");
    private static final Pattern ENCODED_WORD = Pattern.compile(
            "=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=");

    private AttachmentFileName() {
    }

    /**
     * 从附件 HTML 的可见名称、download/name 参数等推断保存名。
     * 不请求附件地址，避免在展示确认框前消耗论坛积分。
     */
    public static String fromAttachmentMarkup(String label, String href, String aid) {
        String candidate = chooseUseful(decodeName(label));
        if (candidate == null) candidate = chooseUseful(extractNamedQueryValue(href));
        if (candidate == null) candidate = chooseUseful(extractPathName(href));
        if (candidate == null) candidate = "MT论坛附件";

        String extension = extensionOf(candidate);
        if (extension == null) extension = extensionFromUrl(href);
        if (extension != null && extensionOf(candidate) == null) {
            candidate = candidate + "." + extension;
        }
        return sanitize(candidate);
    }

    /** 从浏览器下载回调的 Content-Disposition / URL / MIME 推断可读文件名。 */
    public static String fromResponse(String url, String contentDisposition, String mimeType) {
        String candidate = chooseUseful(decodeContentDisposition(contentDisposition));
        if (candidate == null) candidate = chooseUseful(extractNamedQueryValue(url));
        if (candidate == null) candidate = chooseUseful(extractPathName(url));
        if (candidate == null) candidate = "MT论坛下载";

        String extension = extensionOf(candidate);
        if (extension == null) extension = extensionFromUrl(url);
        if (extension == null) extension = extensionFromMime(mimeType);
        if (extension != null && extensionOf(candidate) == null) {
            candidate = candidate + "." + extension;
        }
        return sanitize(candidate);
    }

    /** 清除路径穿越、控制字符和文件系统不允许的字符，且限制过长文件名。 */
    public static String sanitize(String source) {
        String value = decodeName(source);
        if (value == null) value = "";
        value = value.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);

        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x7f) continue;
            if (cp == '/' || cp == '\\' || cp == ':' || cp == '*' || cp == '?'
                    || cp == '"' || cp == '<' || cp == '>' || cp == '|') {
                clean.append('_');
            } else {
                clean.appendCodePoint(cp);
            }
        }
        value = clean.toString().trim();
        while (value.startsWith(".")) value = value.substring(1);
        while (value.endsWith(".") || value.endsWith(" ")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) value = "MT论坛附件";

        int count = value.codePointCount(0, value.length());
        if (count > 120) {
            String extension = extensionOf(value);
            String suffix = extension == null ? "" : "." + extension;
            int keep = Math.max(1, 120 - suffix.codePointCount(0, suffix.length()));
            int end = value.offsetByCodePoints(0, keep);
            value = value.substring(0, end);
            if (!suffix.isEmpty()) value += suffix;
        }
        return value;
    }

    private static String decodeContentDisposition(String disposition) {
        if (disposition == null || disposition.trim().isEmpty()) return null;
        Matcher extended = FILENAME_STAR.matcher(disposition);
        if (extended.find()) {
            String raw = firstNonEmpty(extended.group(1), extended.group(2));
            String decoded = decodeRfc5987(raw);
            if (chooseUseful(decoded) != null) return decoded;
        }
        Matcher ordinary = FILENAME.matcher(disposition);
        if (ordinary.find()) {
            String raw = firstNonEmpty(ordinary.group(1), ordinary.group(2));
            if (raw != null) raw = raw.replace("\\\"", "\"");
            String encodedWord = decodeEncodedWord(raw);
            String decoded = decodeName(encodedWord == null ? raw : encodedWord);
            if (chooseUseful(decoded) != null) return decoded;
        }
        return null;
    }

    private static String decodeRfc5987(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        String charsetName = "UTF-8";
        int firstQuote = value.indexOf('\'');
        int secondQuote = firstQuote < 0 ? -1 : value.indexOf('\'', firstQuote + 1);
        if (firstQuote >= 0 && secondQuote >= 0) {
            charsetName = value.substring(0, firstQuote).trim();
            value = value.substring(secondQuote + 1);
        }
        try {
            Charset charset = charsetName.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
            return decodePercent(value, charset);
        } catch (Exception ignored) {
            return decodePercent(value, StandardCharsets.UTF_8);
        }
    }

    private static String decodeEncodedWord(String value) {
        if (value == null) return null;
        Matcher matcher = ENCODED_WORD.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            Charset charset = Charset.forName(matcher.group(1));
            String encoding = matcher.group(2).toUpperCase(Locale.ROOT);
            byte[] bytes;
            if ("B".equals(encoding)) {
                bytes = java.util.Base64.getDecoder().decode(matcher.group(3));
            } else {
                String encoded = matcher.group(3).replace('_', ' ');
                ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length());
                for (int i = 0; i < encoded.length();) {
                    char ch = encoded.charAt(i++);
                    if (ch == '=' && i + 1 < encoded.length()) {
                        int high = Character.digit(encoded.charAt(i++), 16);
                        int low = Character.digit(encoded.charAt(i++), 16);
                        if (high < 0 || low < 0) return null;
                        out.write((high << 4) | low);
                    } else {
                        byte[] part = String.valueOf(ch).getBytes(charset);
                        out.write(part, 0, part.length);
                    }
                }
                bytes = out.toByteArray();
            }
            return new String(bytes, charset);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decodeName(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String result = value.trim();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1);
        }
        result = decodePercent(result, StandardCharsets.UTF_8);
        result = repairMojibake(result);
        return result == null ? null : result.trim();
    }

    private static String decodePercent(String value, Charset charset) {
        if (value == null || value.indexOf('%') < 0) return value;
        try {
            // 文件名里的 + 通常是字面字符，不按表单空格处理。
            return URLDecoder.decode(value.replace("+", "%2B"), charset.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String repairMojibake(String source) {
        if (source == null || source.isEmpty()) return source;
        String best = source;
        if (containsOnlyLatin1(source) && looksLikeLatin1Mojibake(source)) {
            try {
                String candidate = new String(source.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                if (filenameQuality(candidate) > filenameQuality(best)) best = candidate;
            } catch (Exception ignored) {
            }
        }
        if (looksLikeGbkMojibake(source)) {
            try {
                Charset gbk = Charset.forName("GB18030");
                String candidate = new String(source.getBytes(gbk), StandardCharsets.UTF_8);
                if (filenameQuality(candidate) > filenameQuality(best)) best = candidate;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static boolean containsOnlyLatin1(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0xFF) return false;
        return true;
    }

    private static boolean looksLikeLatin1Mojibake(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x80 && c <= 0x9F) return true;
        }
        return value.contains("Ã") || value.contains("Â") || value.contains("ä")
                || value.contains("å") || value.contains("æ") || value.contains("ç");
    }

    private static boolean looksLikeGbkMojibake(String value) {
        return value.contains("涓") || value.contains("锟") || value.contains("鐨")
                || value.contains("鏂") || value.contains("鎴") || value.contains("绉")
                || value.contains("宸") || value.contains("绔") || value.contains("浠")
                || value.contains("涔") || value.contains("璇") || value.contains("€");
    }

    private static int filenameQuality(String value) {
        if (value == null || value.isEmpty()) return Integer.MIN_VALUE / 2;
        int score = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0xFFFD) score -= 8;
            else if (Character.isLetterOrDigit(cp)) score += isCjk(cp) ? 3 : 1;
        }
        String[] suspicious = {"涓", "锟", "鐨", "鏂", "鎴", "绉", "宸", "绔", "浠", "涔", "璇"};
        for (String token : suspicious) if (value.contains(token)) score -= 2;
        return score;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    private static String chooseUseful(String value) {
        String candidate = decodeName(value);
        if (candidate == null || candidate.isEmpty() || candidate.length() > 240) return null;
        String lower = candidate.toLowerCase(Locale.ROOT);
        if ("forum.php".equals(lower) || "index.php".equals(lower)
                || "attachment".equals(lower) || "download".equals(lower)
                || "下载".equals(candidate) || "下载附件".equals(candidate)
                || "附件".equals(candidate) || "文件".equals(candidate)) return null;
        String stem = extensionOf(candidate) == null ? candidate : candidate.substring(0,
                candidate.length() - extensionOf(candidate).length() - 1);
        if (stem.matches("(?i)[a-z0-9_+/=-]{32,}")) return null;
        if (filenameQuality(candidate) < 0) return null;
        return candidate;
    }

    private static String extractNamedQueryValue(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            String query = uri.getRawQuery();
            if (query == null) return null;
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = decodePercent(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (!("filename".equalsIgnoreCase(key) || "file".equalsIgnoreCase(key)
                        || "name".equalsIgnoreCase(key) || "download".equalsIgnoreCase(key)
                        || "attname".equalsIgnoreCase(key))) continue;
                return decodeName(pair.substring(eq + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String extractPathName(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) return null;
            int slash = path.lastIndexOf('/');
            String name = decodeName(path.substring(slash + 1));
            if (name == null || "forum.php".equalsIgnoreCase(name) || "index.php".equalsIgnoreCase(name)) {
                return null;
            }
            return name;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extensionFromUrl(String url) {
        String named = extractNamedQueryValue(url);
        String extension = extensionOf(named);
        if (extension != null) return extension;
        return extensionOf(extractPathName(url));
    }

    private static String extensionFromMime(String mimeType) {
        if (mimeType == null) return null;
        String mime = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if ("application/vnd.android.package-archive".equals(mime)
                || "application/x-android-package-archive".equals(mime)) return "apk";
        if ("application/pdf".equals(mime)) return "pdf";
        if ("application/zip".equals(mime) || "application/x-zip-compressed".equals(mime)) return "zip";
        if ("application/x-rar-compressed".equals(mime)) return "rar";
        if ("application/x-7z-compressed".equals(mime)) return "7z";
        if ("image/jpeg".equals(mime)) return "jpg";
        if ("image/png".equals(mime)) return "png";
        if ("image/gif".equals(mime)) return "gif";
        if ("image/webp".equals(mime)) return "webp";
        if ("text/plain".equals(mime)) return "txt";
        return null;
    }

    public static boolean hasExtension(String name) {
        return extensionOf(name) != null;
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        Matcher matcher = EXTENSION.matcher(name.trim());
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null || second.trim().isEmpty() ? null : second.trim();
    }
}
