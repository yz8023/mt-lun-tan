package com.solosu.mtforum.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Cookie 核心/临时分类的 JVM 单元测试（build79 新增）。
 *
 * <p>守住 docs/07 #73 那条死锁：把防护 cookie（{@code acw_tc}、{@code acw_sc__v2}）
 * 当账号身份持久化，服务端就只肯回挑战页。
 */
public class CookieSyncTest {

    /** 实测 MT 论坛（bbs.binmt.cc）登录态的 cookie 前缀 */
    private static final String PREFIX = "cQWy_2132_";

    // ==================== 前缀推断 ====================

    @Test
    public void infersPrefixFromAuthCookie() {
        assertEquals("cQWy_2132_", CookieSync.inferCookiePrefix(
                Arrays.asList("cQWy_2132_auth", "cQWy_2132_saltkey", "cQWy_2132_lastact")));
        assertEquals("a1b2_", CookieSync.inferCookiePrefix(
                Arrays.asList("a1b2_auth", "a1b2_saltkey")));
    }

    @Test
    public void fallsBackToMostCommonUnderscorePrefix() {
        // 没有 auth 时按出现次数猜；只出现 1 次不猜（宁可全留）
        assertEquals("x_", CookieSync.inferCookiePrefix(
                Arrays.asList("x_a", "x_b", "x_c")));
        assertEquals("", CookieSync.inferCookiePrefix(
                Arrays.asList("x_a", "y_b")));
    }

    @Test
    public void returnsEmptyWhenNothingInferable() {
        assertEquals("", CookieSync.inferCookiePrefix(Collections.emptyList()));
        assertEquals("", CookieSync.inferCookiePrefix(
                Collections.singletonList("auth")));   // 长度不足
        assertEquals("", CookieSync.inferCookiePrefix(
                Arrays.asList("nosuffix", "also_nothing")));
    }

    @Test
    public void ignoresBlankNames() {
        assertEquals("p_", CookieSync.inferCookiePrefix(
                Arrays.asList("p_auth", "", null, "p_b")));
    }

    // ==================== 核心 / 临时分类 ====================

    @Test
    public void coreCookiesDropWafCookies() {
        // 真实形态：核心登录态 + 两条边缘下发的防护 cookie
        String raw = "cQWy_2132_auth=abc; cQWy_2132_saltkey=def; "
                + "acw_tc=1a2b3c; acw_sc__v2=deadbeef";
        assertEquals("cQWy_2132_auth=abc; cQWy_2132_saltkey=def",
                CookieSync.coreCookiesOf(raw));
    }

    @Test
    public void coreCookiesKeepsEverythingWhenPrefixUnknown() {
        // 推不出前缀 → 不筛选（保持旧行为，避免把登录态筛空）
        String raw = "acw_tc=1; acw_sc__v2=2";
        assertEquals(raw, CookieSync.coreCookiesOf(raw));
    }

    @Test
    public void coreCookiesHandlesBlankInput() {
        assertEquals("", CookieSync.coreCookiesOf(""));
        assertEquals(null, CookieSync.coreCookiesOf(null));
    }

    @Test
    public void isCoreCookieWithUnknownPrefixKeepsAll() {
        assertTrue(CookieSync.isCoreCookie("acw_tc", ""));
        assertTrue(CookieSync.isCoreCookie("anything", null));
        assertFalse(CookieSync.isCoreCookie("acw_tc", "cQWy_2132_"));
        assertTrue(CookieSync.isCoreCookie("cQWy_2132_auth", "cQWy_2132_"));
    }

    // ==================== 裸 auth token ====================

    @Test
    public void bareAuthTokenDetection() {
        assertTrue(CookieSync.isBareAuthToken("cQWy_2132_auth=abc"));
        assertFalse(CookieSync.isBareAuthToken("cQWy_2132_auth=abc; cQWy_2132_saltkey=def"));
        assertFalse(CookieSync.isBareAuthToken(""));
        assertFalse(CookieSync.isBareAuthToken(null));
    }
}
