package com.solosu.mtforum.session;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 签到相关的纯文本解析（build60 新增）。
 *
 * <p>刻意不引用任何 android.* 类，这样这些规则可以跑 JVM 单元测试
 * （见 {@code app/src/test/java/.../SignParserTest.java}）。
 * 签到链路最容易出错的就是"把游客页当成已登录"这类判断，必须能测。
 *
 * <p>判定依据来自 bbs.binmt.cc 实测：
 * <ul>
 *   <li>游客访问 {@code k_misign-sign.html}：出现"您需要先登录 / 立即登录"，
 *       且整页 {@code formhash} 出现 <b>0</b> 次</li>
 *   <li>页面里 {@code k_misign}、{@code spacecp} 这些字样游客也看得到
 *       （侧边栏推广链接），<b>不能</b>拿来当登录态标记</li>
 * </ul>
 */
public final class SignParser {

    /** 已签到判定关键词 */
    static final String[] ALREADY_SIGNED = {
            "今日已签", "今日已经签到", "您今天已经签到过了", "您已经签到过了",
            "已经签到过", "已经签到", "重复签到", "请勿重复", "请不要重复", "签到过了"
    };

    /** 登录成功标志（AJAX 返回 succeed，非 AJAX 返回 欢迎您回来） */
    static final String[] LOGIN_SUCCESS = {
            "succeed", "欢迎您回来", "login_reult_1"
    };

    /** 明确的未登录提示 */
    static final String[] LOGGED_OUT_HINTS = {
            "您需要先登录", "请先登录", "立即登录", "请登录后再试"
    };

    private SignParser() {
    }

    // ==================== 登录态 ====================

    /**
     * 判断签到页是否处于未登录状态。
     *
     * <p>核心依据：Discuz 只给已认证会话输出 {@code formhash}。
     * 实测游客访问签到页时整页没有 formhash，而已登录时签到按钮必带 formhash。
     */
    public static boolean looksLoggedOut(String html) {
        if (isBlank(html)) return true;
        // 没有 formhash 基本可以断定是游客页
        if (!html.contains("formhash")) return true;
        // 有 formhash 但同时挂着明确的未登录提示，且没有签到操作入口 → 仍按未登录处理
        return containsAny(html, LOGGED_OUT_HINTS)
                && !html.contains("qiandao")
                && !html.contains("midaben_sign");
    }

    /** 站点防护拦截（阿里云 ESA / Cloudflare 之类） */
    public static boolean isBlocked(String html) {
        if (isBlank(html)) return false;
        String lower = html.toLowerCase();
        boolean forum = lower.contains("discuz_uid") || lower.contains("comiis_")
                || lower.contains("discuz_tips") || lower.contains("formhash");
        boolean challenge = lower.contains("acw_sc__v2") || lower.contains("aliyungf_tc")
                || lower.contains("__jsl_clearance") || lower.contains("window._config_")
                || lower.contains("waf challenge") || lower.contains("esa challenge")
                || html.contains("人机验证") || html.contains("安全验证")
                || (lower.contains("document.cookie") && (lower.contains("challenge") || lower.contains("arg1")));
        return lower.contains("you have been blocked")
                || lower.contains("403 forbidden")
                || lower.contains("access denied")
                || lower.contains("attention required")
                || (challenge && !forum);
    }

    public static boolean isLoginSuccess(String body) {
        return containsAny(body, LOGIN_SUCCESS);
    }

    public static String loginErrorMessage(String body) {
        if (isBlank(body)) return "登录失败，服务器无响应";
        if (isBlocked(body)) return "被站点防护拦截(403)，请求太频繁，稍后再试";
        // 注意顺序：限流提示里同样含"密码错误"四个字，必须先判次数，
        // 否则被临时锁定的用户会看到"密码错误"，白白去改密码。
        if (body.contains("次数过多") || body.contains("次数超")) return "登录错误次数过多，已临时锁定";
        if (body.contains("密码错误") || (body.contains("密码") && body.contains("错误"))) return "密码错误";
        if (body.contains("用户名") && body.contains("不存在")) return "用户名不存在";
        if (body.contains("验证码") || body.contains("seccode")) return "需要验证码，请在 App 内手动登录一次";
        if (body.contains("禁止访问") || body.contains("禁止登录")) return "账号被封禁";
        return "登录失败，请检查账号密码";
    }

    // ==================== 签到状态 ====================

    public static boolean isAlreadySigned(String html) {
        return containsAny(html, ALREADY_SIGNED);
    }

    public static boolean isSuccessText(String text) {
        if (isBlank(text)) return false;
        String lower = text.toLowerCase();
        return text.contains("签到成功") || text.contains("恭喜") || isAlreadySigned(text)
                || lower.contains("succeed") || lower.contains("success");
    }

    public static boolean isFailureText(String text) {
        if (isBlank(text)) return false;
        return text.contains("请先登录") || text.contains("您需要先登录")
                || text.contains("没有权限") || text.contains("非法操作")
                || text.contains("请重新登录");
    }

    /**
     * 解析 k_misign 签到接口返回。
     *
     * <p>正常是 {@code <root><![CDATA[...]]></root>} 或一句短文本；
     * 若返回整页 HTML（formhash 过期 / 伪静态被拦 / 风控页）则返回空串，
     * 交由上层重抓签到页以"页面是否显示已签"为准。
     */
    public static String parseSignResponse(String raw) {
        if (isBlank(raw)) return "";
        String v = extract(raw, "(?s)<root><!\\[CDATA\\[(.*?)\\]\\]></root>");
        if (!isBlank(v)) return stripTags(v);
        v = extract(raw, "(?s)<root>(.*?)</root>");
        if (!isBlank(v)) return stripTags(v);
        String trimmed = raw.trim();
        if (!trimmed.isEmpty() && trimmed.length() <= 200
                && !trimmed.startsWith("<!DOCTYPE")
                && !trimmed.startsWith("<html")
                && !trimmed.contains("<body")) {
            return stripTags(trimmed);
        }
        return "";
    }

    // ==================== 字段提取 ====================

    /** Discuz formhash：优先取表单隐藏域，退而取 URL 参数 */
    /**
     * 站点对部分页面（实测：移动版 UA 拿到的登录页）用<b>单引号</b>包属性：
     * {@code <input type="hidden" name="formhash" id="formhash" value='a3906715' />}。
     * 只认双引号的话 formhash 解析为空，上层就直接报「登录页解析失败（可能被风控）」——
     * 看起来像站点风控，其实是自己的正则漏了一种写法。
     */
    public static String extractFormhash(String html) {
        String v = extract(html, "name=[\"']formhash[\"'][^>]*?value=[\"']([a-zA-Z0-9]+)[\"']");
        if (!isBlank(v)) return v;
        v = extract(html, "value=[\"']([a-zA-Z0-9]+)[\"'][^>]*?name=[\"']formhash[\"']");
        if (!isBlank(v)) return v;
        v = extract(html, "formhash[\"']\\s*value=[\"']([a-zA-Z0-9]+)[\"']");
        if (!isBlank(v)) return v;
        return orEmpty(extract(html, "formhash=([a-zA-Z0-9]+)"));
    }

    public static String extractLoginhash(String html) {
        return orEmpty(extract(html, "loginhash=([a-zA-Z0-9]+)"));
    }

    /**
     * 签到排名，归一成<b>纯数字字符串</b>。
     *
     * <p>build80 修一个真 bug：原来的第一条正则把
     * {@code 您的签到排名：(.*?)</div>} 整段抓下来，只做了 stripTags，
     * 于是返回「您的签到排名：123」而不是「123」。上层
     * {@code SignResult.display()} 直接拼成「· 排名 您的签到排名：123」，
     * 用户看到的就是一句重复的废话。
     *
     * <p>k_misign 的排名真实来源是隐藏域 {@code qiandaobtnnum}，
     * 先取它，再退文案。
     */
    public static String extractRanking(String html) {
        if (isBlank(html)) return "";
        String v = extract(html, "id=\"qiandaobtnnum\"[^>]*value=\"([0-9]+)\"");
        if (isBlank(v)) v = extract(html, "name=\"qiandaobtnnum\"[^>]*value=\"([0-9]+)\"");
        if (isBlank(v)) v = extract(html, "value=\"([0-9]+)\"[^>]*(?:id|name)=\"qiandaobtnnum\"");
        if (isBlank(v)) v = extract(html, "您的签到排名[：:]?\\s*([0-9]+)");
        if (isBlank(v)) v = extract(html, "签到排名[：:]?\\s*([0-9]+)");
        if (isBlank(v)) {
            // 最后兜底：从带前缀的文案里抠出数字
            String raw = extract(html, "您的签到排名：(.*?)</div>");
            if (!isBlank(raw)) {
                Matcher m = Pattern.compile("([0-9]+)").matcher(stripTags(raw));
                if (m.find()) v = m.group(1);
            }
        }
        return isBlank(v) ? "" : v.trim();
    }

    /** 连续签到天数 —— 来源是隐藏域 {@code lxdays} */
    public static String extractContinuousDays(String html) {
        return firstHiddenNumber(html, "lxdays");
    }

    /** 累计签到天数 —— 来源是隐藏域 {@code lxtdays} */
    public static String extractTotalDays(String html) {
        return firstHiddenNumber(html, "lxtdays");
    }

    /** 签到等级 —— 隐藏域 {@code lxlevel} */
    public static String extractLevel(String html) {
        if (isBlank(html)) return "";
        String v = extract(html, "id=\"lxlevel\"[^>]*value=\"([^\"]+)\"");
        if (isBlank(v)) v = extract(html, "name=\"lxlevel\"[^>]*value=\"([^\"]+)\"");
        if (isBlank(v)) v = extract(html, "value=\"([^\"]+)\"[^>]*(?:id|name)=\"lxlevel\"");
        return isBlank(v) ? "" : v.trim();
    }

    /**
     * 签到时间。
     *
     * <p>注意：k_misign <b>没有</b>公认的 {@code lxtime} 隐藏域，
     * 网上能查到的只有 lxdays/lxtdays/lxlevel/lxreward/qiandaobtnnum。
     * 所以这里只做文案兜底，拿不到就返回空串 —— 不编造。
     */
    public static String extractSignTime(String html) {
        if (isBlank(html)) return "";
        String v = extract(html, "签到时间[：:]?\\s*([0-9]{1,4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}"
                + "(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?)");
        if (isBlank(v)) v = extract(html, "([0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)\\s*签到");
        if (isBlank(v)) v = extract(html, "lastsign[^0-9]{0,40}?([0-9]{1,4}-[0-9]{1,2}-[0-9]{1,2})");
        return isBlank(v) ? "" : v.trim();
    }

    /** 取 {@code <input type="hidden" id="xxx" value="N">} 这类隐藏域的数字值 */
    private static String firstHiddenNumber(String html, String id) {
        if (isBlank(html)) return "";
        String[] patterns = {
                "id=\"" + id + "\"[^>]*value=\"([0-9]+)\"",
                "name=\"" + id + "\"[^>]*value=\"([0-9]+)\"",
                "value=\"([0-9]+)\"[^>]*(?:id|name)=\"" + id + "\""
        };
        for (String p : patterns) {
            String v = extract(html, p);
            if (!isBlank(v)) return v.trim();
        }
        return "";
    }

    public static String extractNickname(String loginResponse) {
        return orEmpty(extract(loginResponse, "欢迎您回来，(.*?)，现在"));
    }

    /** 从签到页提取奖励（lxreward 隐藏域，模板属性顺序不固定所以多重回退） */
    public static String extractReward(String html) {
        if (isBlank(html)) return "0";
        String[] patterns = {
                "id=\"lxreward\"[^>]*value=\"(.*?)\"",
                "name=\"lxreward\"[^>]*value=\"(.*?)\"",
                "value=\"(.*?)\"[^>]*id=\"lxreward\"",
                "value=\"(.*?)\"[^>]*name=\"lxreward\"",
                "lxreward[^>]*?value=\"(.*?)\""
        };
        for (String p : patterns) {
            String v = extract(html, p);
            if (!isBlank(v)) return v.trim();
        }
        // 已签到页面经常移除 lxreward 隐藏域，只留下结果文案。
        String fromResult = extractRewardFromText(stripTags(html));
        return isBlank(fromResult) ? "0" : fromResult;
    }

    /**
     * 从签到接口当次返回里抓奖励数量（页面兜底常常拿不到）。
     *
     * <p>build80 修一个真 bug：原来的模式全是「<b>数字 币种</b>」顺序
     * （{@code 奖励 8 金币}），而 k_misign 的真实文案是
     * 「<b>币种 数字</b>」顺序（{@code 金币 8} / {@code 奖励金币 8}）。
     * 于是 {@link #extractReward} 的页面兜底<b>永远返回 0</b>，
     * 用户在签到结果里从来看不到奖励数量。
     *
     * <p>两种顺序都收，币种在前的优先。
     */
    public static String extractRewardFromText(String text) {
        if (isBlank(text)) return "0";
        String[] patterns = {
                // 币种在前：金币 8 / 奖励金币 8 / 获得 MT币 12
                "(?:金币|威望|贡献|积分|金钱|MT币)\\s*([0-9]+)",
                "奖励\\s*(?:金币|威望|贡献|积分|金钱|MT币)\\s*([0-9]+)",
                "获得(?:随机)?(?:奖励)?\\s*(?:金币|威望|贡献|积分|金钱|MT币)\\s*([0-9]+)",
                // 数字在前：奖励 8 金币 / +8 金币
                "奖励\\s*([0-9]+)\\s*(?:金币|威望|贡献|积分|金钱|MT币)",
                "奖励\\s*([0-9]+)",
                "获得(?:随机)?(?:奖励)?\\s*([0-9]+)\\s*(?:金币|威望|贡献|积分|金钱|MT币)",
                "获得(?:随机)?(?:奖励)?\\s*([0-9]+)",
                "增加\\s*([0-9]+)\\s*(?:金币|威望|贡献|积分|金钱|MT币)?",
                "本次签到.{0,40}?([0-9]+)\\s*(?:金币|威望|贡献|积分|金钱|MT币)",
                "\\+\\s*([0-9]+)\\s*(?:金币|威望|贡献|积分|金钱|MT币)"
        };
        for (String p : patterns) {
            String v = extract(text, p);
            if (!isBlank(v)) return v;
        }
        return "0";
    }

    // ==================== Cookie ====================

    /**
     * 只有一个 {@code xxx_auth}、缺配套 saltkey 的裸 token。
     * Discuz 的 auth 必须和签发它的 saltkey 一起用，否则服务端重算 saltkey 会解密失败判游客。
     */
    public static boolean isBareAuthToken(String cookie) {
        if (isBlank(cookie)) return false;
        int valid = 0;
        boolean onlyAuth = true;
        for (String p : cookie.split(";")) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            valid++;
            if (!p.substring(0, eq).trim().endsWith("_auth")) onlyAuth = false;
        }
        return valid == 1 && onlyAuth;
    }

    /** Cookie 串里是否带了 saltkey */
    public static boolean hasSaltkey(String cookie) {
        if (isBlank(cookie)) return false;
        for (String p : cookie.split(";")) {
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            if (name.equals("saltkey") || name.endsWith("_saltkey")) return true;
        }
        return false;
    }

    // ==================== 小工具 ====================

    public static String extract(String text, String regex) {
        if (isBlank(text)) return null;
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean containsAny(String text, String[] keywords) {
        if (isBlank(text)) return false;
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    public static String stripTags(String raw) {
        if (isBlank(raw)) return "";
        return raw.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
