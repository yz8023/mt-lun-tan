package com.solosu.mtforum.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 签到解析规则的 JVM 单元测试（build60 新增）。
 *
 * <p>重点守住两条最容易翻车的线：
 * <ol>
 *   <li>游客页不能被判成已登录（否则会一直"签到失败"却不触发自动重登）</li>
 *   <li>已登录页不能被判成游客（否则每次都白白重登一遍，徒增风控风险）</li>
 * </ol>
 */
public class SignParserTest {

    /** 实测抓取的 bbs.binmt.cc 游客签到页片段 */
    private String guestPage() throws IOException {
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream("guest_sign_page.html");
        assertTrue("缺少测试资源 guest_sign_page.html", in != null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ==================== 登录态判定 ====================

    @Test
    public void guestSignPageIsDetectedAsLoggedOut() throws IOException {
        String html = guestPage();
        // 该页面同时含有 k_misign 和 spacecp —— 早期实现拿它们当"已登录标记"会误判
        assertTrue("fixture 应含 k_misign", html.contains("k_misign"));
        assertTrue("fixture 应含 spacecp", html.contains("spacecp"));
        assertFalse("游客页不应含 formhash", html.contains("formhash"));
        assertTrue("游客页必须判定为未登录", SignParser.looksLoggedOut(html));
    }

    @Test
    public void loggedInSignPageIsNotLoggedOut() {
        String html = "<html><body>"
                + "<input type=\"hidden\" name=\"formhash\" value=\"a1b2c3d4\" />"
                + "<div class=\"qiandao\"><a id=\"midaben_sign\">点击签到</a></div>"
                + "<div>您的签到排名：123</div>"
                + "</body></html>";
        assertFalse(SignParser.looksLoggedOut(html));
    }

    @Test
    public void emptyHtmlIsLoggedOut() {
        assertTrue(SignParser.looksLoggedOut(null));
        assertTrue(SignParser.looksLoggedOut("   "));
    }

    @Test
    public void blockedPageIsDetected() {
        assertTrue(SignParser.isBlocked("<html><body>403 Forbidden</body></html>"));
        assertTrue(SignParser.isBlocked("Sorry, you have been blocked"));
        assertTrue(SignParser.isBlocked("<script>var acw_sc__v2='challenge';document.cookie='acw_tc=x'</script>"));
        assertTrue(SignParser.isBlocked("<html><body>请完成人机验证</body></html>"));
        assertFalse(SignParser.isBlocked("<html><input name=\"formhash\"><script>var acw_sc__v2='old'</script></html>"));
        assertFalse(SignParser.isBlocked("<html>正常页面</html>"));
    }

    // ==================== 字段提取 ====================

    @Test
    public void extractFormhashHandlesAttributeOrders() {
        assertEquals("abc123",
                SignParser.extractFormhash("<input type=\"hidden\" name=\"formhash\" value=\"abc123\" />"));
        assertEquals("def456",
                SignParser.extractFormhash("<input value=\"def456\" name=\"formhash\">"));
        assertEquals("ghi789",
                SignParser.extractFormhash("<a href=\"plugin.php?id=k_misign:sign&formhash=ghi789\">签到</a>"));
    }

    @Test
    public void extractRankingWorks() {
        assertEquals("328",
                SignParser.extractRanking("<div class=\"rank\">您的签到排名：328</div>"));
        assertEquals("", SignParser.extractRanking("<div>没有排名信息</div>"));
    }

    @Test
    public void extractRewardFromPageAndText() {
        assertEquals("6", SignParser.extractReward("<input id=\"lxreward\" value=\"6\" type=\"hidden\">"));
        assertEquals("6", SignParser.extractReward("<input value=\"6\" id=\"lxreward\">"));
        assertEquals("0", SignParser.extractReward("<div>没有奖励字段</div>"));

        assertEquals("5", SignParser.extractRewardFromText("签到成功，获得 5 金币"));
        assertEquals("12", SignParser.extractRewardFromText("恭喜，奖励12威望"));
        assertEquals("9", SignParser.extractRewardFromText("本次签到获得随机奖励 9 金币"));
        assertEquals("3", SignParser.extractRewardFromText("签到后增加 3 积分"));
        assertEquals("7", SignParser.extractReward("<div>本次签到获得 7 金币</div>"));
        assertEquals("0", SignParser.extractRewardFromText("签到成功"));
    }

    @Test
    public void extractNicknameFromLoginResponse() {
        assertEquals("测试用户",
                SignParser.extractNickname("<root>欢迎您回来，测试用户，现在将转入登录前页面</root>"));
    }

    // ==================== 接口返回解析 ====================

    @Test
    public void parseCdataResponse() {
        assertEquals("签到成功，获得随机奖励 5 金币",
                SignParser.parseSignResponse(
                        "<?xml version=\"1.0\"?><root><![CDATA[签到成功，获得随机奖励 5 金币]]></root>"));
    }

    @Test
    public void parseFullHtmlResponseReturnsEmpty() {
        String page = "<!DOCTYPE html><html><head><title>MT论坛</title></head>"
                + "<body>一大堆无关内容</body></html>";
        assertEquals("", SignParser.parseSignResponse(page));
    }

    @Test
    public void parsePlainShortTextResponse() {
        assertEquals("succeed", SignParser.parseSignResponse("succeed"));
    }

    @Test
    public void alreadySignedKeywordsAreRecognised() {
        assertTrue(SignParser.isAlreadySigned("您今天已经签到过了"));
        assertTrue(SignParser.isAlreadySigned("<div>今日已签</div>"));
        assertFalse(SignParser.isAlreadySigned("点击签到"));
    }

    @Test
    public void successAndFailureTexts() {
        assertTrue(SignParser.isSuccessText("签到成功"));
        assertTrue(SignParser.isSuccessText("SUCCEED"));
        assertTrue(SignParser.isSuccessText("今日已签"));
        assertFalse(SignParser.isSuccessText(""));

        assertTrue(SignParser.isFailureText("请先登录"));
        assertTrue(SignParser.isFailureText("非法操作"));
        assertFalse(SignParser.isFailureText("签到成功"));
    }

    // ==================== 登录结果 ====================

    @Test
    public void loginSuccessDetection() {
        assertTrue(SignParser.isLoginSuccess("<root><![CDATA[欢迎您回来，张三，现在]]></root>"));
        assertTrue(SignParser.isLoginSuccess("succeed"));
        assertFalse(SignParser.isLoginSuccess("登录失败，密码错误"));
    }

    @Test
    public void loginErrorMessagesAreSpecific() {
        assertEquals("密码错误", SignParser.loginErrorMessage("抱歉，您的密码错误，请重新输入"));
        assertEquals("用户名不存在", SignParser.loginErrorMessage("抱歉，该用户名不存在"));
        assertEquals("登录错误次数过多，已临时锁定",
                SignParser.loginErrorMessage("密码错误次数过多，请 15 分钟后再试"));
        assertEquals("被站点防护拦截(403)，请求太频繁，稍后再试",
                SignParser.loginErrorMessage("403 Forbidden"));
        assertEquals("登录失败，服务器无响应", SignParser.loginErrorMessage(""));
    }

    // ==================== Cookie ====================

    @Test
    public void bareAuthTokenIsRejected() {
        assertTrue(SignParser.isBareAuthToken("cEbe_2132_auth=abcdefg"));
        assertFalse(SignParser.isBareAuthToken(
                "cEbe_2132_auth=abcdefg; cEbe_2132_saltkey=xy12"));
        assertFalse(SignParser.isBareAuthToken(""));
    }

    @Test
    public void saltkeyDetection() {
        assertTrue(SignParser.hasSaltkey("cEbe_2132_auth=a; cEbe_2132_saltkey=b"));
        assertTrue(SignParser.hasSaltkey("saltkey=b"));
        assertFalse(SignParser.hasSaltkey("cEbe_2132_auth=a"));
    }

    /**
     * build107: 站点部分页面用<b>单引号</b>包属性。
     *
     * <p>实测：用移动版 UA 请求 {@code member.php?mod=logging&action=login}，
     * 返回的登录页里是 {@code <input type="hidden" name="formhash" id="formhash"
     * value='a3906715' />} —— 属性值是单引号。原来只认双引号，formhash 解析为空，
     * 上层就报「登录页解析失败（可能被风控）」：看着像站点风控，其实是自己的正则
     * 漏了一种写法。两种引号都必须认。
     */
    @Test
    public void extractFormhash_acceptsSingleQuotedAttributes() {
        String single = "<form id=\"loginform\" method=\"post\" action=\"member.php?mod=logging&amp;"
                + "action=login&amp;loginsubmit=yes&amp;loginhash=LD3qp\">"
                + "<input type=\"hidden\" name=\"formhash\" id=\"formhash\" value='a3906715' /></form>";
        assertEquals("单引号包属性也要能解析出 formhash", "a3906715", SignParser.extractFormhash(single));

        String doubleQuoted = "<input type=\"hidden\" name=\"formhash\" id=\"formhash\" value=\"a3906715\" />";
        assertEquals("双引号写法不能被改坏", "a3906715", SignParser.extractFormhash(doubleQuoted));

        String reversed = "<input type=\"hidden\" value='bb11cc22' name=\"formhash\" />";
        assertEquals("属性顺序反过来也要认", "bb11cc22", SignParser.extractFormhash(reversed));

        assertEquals("loginhash 照常解析", "LD3qp", SignParser.extractLoginhash(single));
    }
}
