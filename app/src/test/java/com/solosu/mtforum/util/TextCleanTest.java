package com.solosu.mtforum.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 抓取文本清洗测试（build65）。
 *
 * <p>论坛楼层结构是 {@code <span class="f_d y">\n21<sup>#</sup></span>}。
 * Jsoup 的 {@code text()} 本身能给出干净的 "21#"，但不同模板/插件可能在
 * 数字与 {@code #} 之间塞零宽字符或控制符，渲染出来就是「异常符号」，
 * 而在日志里完全看不出来 —— 所以统一过一遍清洗。
 */
public class TextCleanTest {

    @Test
    public void numericFloorNormalised() {
        assertEquals("21#", TextClean.floorLabel("21#"));
        assertEquals("21#", TextClean.floorLabel("\n21#"));
        assertEquals("21#", TextClean.floorLabel(" 21 # "));
        assertEquals("393#", TextClean.floorLabel("393#"));
        // 只有数字没有 # 时补上
        assertEquals("21#", TextClean.floorLabel("21"));
        // 重复 # 归一
        assertEquals("21#", TextClean.floorLabel("21##"));
    }

    @Test
    public void invisibleCharactersRemoved() {
        // 零宽空格 / 零宽连字 / BOM / 软连字符 —— 都会渲染成方块或问号
        assertEquals("21#", TextClean.floorLabel("21\u200B#"));
        assertEquals("21#", TextClean.floorLabel("\uFEFF21#"));
        assertEquals("21#", TextClean.floorLabel("2\u00AD1#"));
        assertEquals("21#", TextClean.floorLabel("21\u200D#"));
        assertEquals("21#", TextClean.floorLabel("\u202A21#\u202C"));
    }

    @Test
    public void controlCharactersRemoved() {
        assertEquals("21#", TextClean.floorLabel("21\u0000#"));
        assertEquals("21#", TextClean.floorLabel("21\u001F#"));
        assertEquals("21#", TextClean.floorLabel("21\u007F#"));
    }

    @Test
    public void textualFloorsKept() {
        assertEquals("沙发", TextClean.floorLabel("\n沙发"));
        assertEquals("下水道", TextClean.floorLabel("下水道"));
        assertEquals("地下室", TextClean.floorLabel("\u200B地下室\uFEFF"));
    }

    @Test
    public void nullAndEmpty() {
        assertEquals(null, TextClean.floorLabel(null));
        assertEquals("", TextClean.floorLabel(""));
        assertEquals("", TextClean.floorLabel("   \n  "));
    }

    @Test
    public void genericCleanKeepsNewlines() {
        assertEquals("a\nb", TextClean.clean("a\nb"));
        assertEquals("a b", TextClean.clean("a\u00a0b"));
        assertEquals("ab", TextClean.clean("a\u200Bb"));
    }
}
