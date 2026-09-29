package com.solosu.mtforum.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.TextUtils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Prepares user media for Discuz' roughly 1 MiB image-upload limit. */
public final class MediaUploadProcessor {
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024L - 16L * 1024L;
    private static final int MAX_IMAGE_EDGE = 2560;

    private MediaUploadProcessor() {}

    public static final class Result {
        public final File file;
        public final String mimeType;
        public final boolean compressed;
        public final boolean videoConverted;

        Result(File file, String mimeType, boolean compressed, boolean videoConverted) {
            this.file = file;
            this.mimeType = mimeType;
            this.compressed = compressed;
            this.videoConverted = videoConverted;
        }
    }

    public static Result prepare(Context context, Uri uri) throws Exception {
        String mime = context.getContentResolver().getType(uri);
        if (!TextUtils.isEmpty(mime) && mime.toLowerCase().startsWith("video/")) {
            return videoToGif(context, uri);
        }
        return imageUnderLimit(context, uri);
    }

    private static Result imageUnderLimit(Context context, Uri uri) throws Exception {
        String sourceMime = context.getContentResolver().getType(uri);
        String safeMime = supportedImageMime(sourceMime);
        File original = copy(context, uri, "original_" + System.nanoTime() + extensionFor(safeMime));
        // Preserve every byte (including metadata/animation) when the source already meets the limit.
        if (original.length() <= MAX_UPLOAD_BYTES && safeMime != null) {
            return new Result(original, safeMime, false, false);
        }
        // Android has no platform animated-GIF encoder. Flattening it would silently destroy animation.
        if ("image/gif".equalsIgnoreCase(sourceMime)) {
            original.delete();
            throw new IllegalArgumentException("动态 GIF 超过 1MB；请缩短动画或降低分辨率后重试");
        }
        original.delete();

        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int edge = Math.max(w, h);
            if (edge > MAX_IMAGE_EDGE) {
                float ratio = MAX_IMAGE_EDGE / (float) edge;
                decoder.setTargetSize(Math.max(1, Math.round(w * ratio)),
                        Math.max(1, Math.round(h * ratio)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        if (decoded == null) throw new IllegalArgumentException("无法解码图片");
        Bitmap flattened = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(decoded, 0, 0, null);
        if (decoded != flattened) decoded.recycle();

        byte[] best = null;
        Bitmap working = flattened;
        // Quality is reduced first; dimensions are reduced only when quality alone cannot meet the limit.
        for (int scalePass = 0; scalePass < 6; scalePass++) {
            int low = 45, high = 95;
            while (low <= high) {
                int quality = (low + high) / 2;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                working.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
                byte[] candidate = bytes.toByteArray();
                if (candidate.length <= MAX_UPLOAD_BYTES) {
                    best = candidate;
                    low = quality + 1;
                } else {
                    high = quality - 1;
                }
            }
            if (best != null) break;
            int nw = Math.max(1, Math.round(working.getWidth() * 0.82f));
            int nh = Math.max(1, Math.round(working.getHeight() * 0.82f));
            Bitmap smaller = Bitmap.createScaledBitmap(working, nw, nh, true);
            if (working != flattened) working.recycle();
            working = smaller;
        }
        if (best == null) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            working.compress(Bitmap.CompressFormat.JPEG, 38, bytes);
            best = bytes.toByteArray();
        }
        if (working != flattened) working.recycle();
        flattened.recycle();
        if (best.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("图片内容过于复杂，无法压缩到 1MB 以下");
        File target = cacheFile(context, "upload_" + System.nanoTime() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(best); }
        return new Result(target, "image/jpeg", true, false);
    }

    private static Result videoToGif(Context context, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context, uri);
        long durationMs;
        try {
            durationMs = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
            durationMs = 0;
        }
        if (durationMs <= 0) throw new IllegalArgumentException("无法读取视频时长");
        // GIF is intended for short forum animations. Sampling is capped to avoid OOM and huge output.
        long usedMs = Math.min(durationMs, 12_000L);
        File target = null;
        int[] edges = {480, 400, 320, 256, 200};
        int[] frameDelays = {200, 250, 330, 400, 500};
        for (int pass = 0; pass < edges.length; pass++) {
            List<Bitmap> frames = new ArrayList<>();
            int delay = frameDelays[pass];
            for (long ms = 0; ms < usedMs; ms += delay) {
                Bitmap frame = retriever.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                int edge = Math.max(frame.getWidth(), frame.getHeight());
                if (edge > edges[pass]) {
                    float ratio = edges[pass] / (float) edge;
                    Bitmap scaled = Bitmap.createScaledBitmap(frame,
                            Math.max(1, Math.round(frame.getWidth() * ratio)),
                            Math.max(1, Math.round(frame.getHeight() * ratio)), true);
                    frame.recycle();
                    frame = scaled;
                }
                frames.add(frame);
            }
            if (frames.isEmpty()) continue;
            target = cacheFile(context, "video_" + System.nanoTime() + ".gif");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                FixedPaletteGif.write(out, frames, delay);
            } finally {
                for (Bitmap frame : frames) frame.recycle();
            }
            if (target.length() <= MAX_UPLOAD_BYTES) break;
            target.delete();
            target = null;
        }
        retriever.release();
        if (target == null) throw new IllegalArgumentException("视频转 GIF 后仍超过 1MB，请选择更短的视频（建议 6 秒内）");
        return new Result(target, "image/gif", true, true);
    }

    private static String supportedImageMime(String mime) {
        if (mime == null) return null;
        String lower = mime.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("image/jpeg") || lower.equals("image/png") || lower.equals("image/webp")
                || lower.equals("image/gif") || lower.equals("image/bmp")) return lower;
        return null;
    }

    private static String extensionFor(String mime) {
        if ("image/png".equals(mime)) return ".png";
        if ("image/webp".equals(mime)) return ".webp";
        if ("image/gif".equals(mime)) return ".gif";
        if ("image/bmp".equals(mime)) return ".bmp";
        return ".jpg";
    }

    private static File copy(Context context, Uri uri, String name) throws Exception {
        File target = cacheFile(context, name);
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalArgumentException("无法读取文件");
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        return target;
    }

    private static File cacheFile(Context context, String name) {
        File dir = new File(context.getCacheDir(), "media_uploads");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    /** Small GIF89a encoder using a fixed RGB332 palette and standard GIF LZW. */
    private static final class FixedPaletteGif {
        static void write(OutputStream out, List<Bitmap> frames, int delayMs) throws Exception {
            Bitmap first = frames.get(0);
            writeAscii(out, "GIF89a");
            writeShort(out, first.getWidth()); writeShort(out, first.getHeight());
            out.write(0xF7); out.write(0); out.write(0);
            for (int i = 0; i < 256; i++) {
                out.write(((i >> 5) & 7) * 255 / 7);
                out.write(((i >> 2) & 7) * 255 / 7);
                out.write((i & 3) * 255 / 3);
            }
            out.write(new byte[]{0x21, (byte) 0xFF, 0x0B});
            writeAscii(out, "NETSCAPE2.0"); out.write(new byte[]{3, 1, 0, 0, 0});
            for (Bitmap source : frames) {
                Bitmap frame = source;
                if (source.getWidth() != first.getWidth() || source.getHeight() != first.getHeight())
                    frame = Bitmap.createScaledBitmap(source, first.getWidth(), first.getHeight(), true);
                out.write(new byte[]{0x21, (byte) 0xF9, 4, 0});
                writeShort(out, Math.max(2, delayMs / 10)); out.write(0); out.write(0);
                out.write(0x2C); writeShort(out, 0); writeShort(out, 0);
                writeShort(out, first.getWidth()); writeShort(out, first.getHeight()); out.write(0);
                int[] pixels = new int[first.getWidth() * first.getHeight()];
                frame.getPixels(pixels, 0, first.getWidth(), 0, 0, first.getWidth(), first.getHeight());
                byte[] indexed = new byte[pixels.length];
                for (int p = 0; p < pixels.length; p++) {
                    int c = pixels[p];
                    indexed[p] = (byte) (((Color.red(c) >> 5) << 5)
                            | ((Color.green(c) >> 5) << 2) | (Color.blue(c) >> 6));
                }
                if (frame != source) frame.recycle();
                out.write(8);
                byte[] lzw = lzw(indexed);
                for (int offset = 0; offset < lzw.length; offset += 255) {
                    int count = Math.min(255, lzw.length - offset);
                    out.write(count); out.write(lzw, offset, count);
                }
                out.write(0);
            }
            out.write(0x3B);
        }

        private static byte[] lzw(byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BitWriter bits = new BitWriter(out);
            final int clear = 256, end = 257;
            Map<String, Integer> dict = initialDictionary();
            int next = 258, width = 9;
            bits.write(clear, width);
            if (data.length == 0) { bits.write(end, width); bits.flush(); return out.toByteArray(); }
            String w = one(data[0]);
            for (int i = 1; i < data.length; i++) {
                char k = (char) (data[i] & 255);
                String wk = w + k;
                Integer code = dict.get(wk);
                if (code != null) { w = wk; continue; }
                bits.write(dict.get(w), width);
                if (next < 4096) {
                    dict.put(wk, next++);
                    if (next == (1 << width) && width < 12) width++;
                } else {
                    bits.write(clear, width);
                    dict = initialDictionary(); next = 258; width = 9;
                }
                w = String.valueOf(k);
            }
            bits.write(dict.get(w), width); bits.write(end, width); bits.flush();
            return out.toByteArray();
        }

        private static Map<String, Integer> initialDictionary() {
            Map<String, Integer> d = new HashMap<>(4096);
            for (int i = 0; i < 256; i++) d.put(String.valueOf((char) i), i);
            return d;
        }
        private static String one(byte b) { return String.valueOf((char) (b & 255)); }
        private static void writeAscii(OutputStream out, String s) throws Exception { out.write(s.getBytes("US-ASCII")); }
        private static void writeShort(OutputStream out, int n) throws Exception { out.write(n & 255); out.write((n >> 8) & 255); }

        private static final class BitWriter {
            final OutputStream out; int value, count;
            BitWriter(OutputStream out) { this.out = out; }
            void write(int code, int width) {
                value |= code << count; count += width;
                while (count >= 8) { outWrite(value & 255); value >>>= 8; count -= 8; }
            }
            void flush() { if (count > 0) outWrite(value & 255); }
            void outWrite(int b) { try { out.write(b); } catch (Exception e) { throw new RuntimeException(e); } }
        }
    }
}
