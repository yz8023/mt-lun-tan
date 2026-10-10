package com.solosu.mtforum.util;

/**
 * 正文 WebView 的图片失败兜底脚本（build106）。
 *
 * <p>背景：正文按原帖 HTML 渲染，{@code forum.php?mod=attachment&aid=…} 这类
 * 附件图必须带登录 Cookie 才能取到。一旦 WebView 的 CookieManager 缺会话
 * （单账号从不切号、新装、系统清了 WebView 存储），站点就把附件请求按游客
 * 处理，返回「该附件无法读取」的提示页 —— 对 {@code <img>} 来说就是永远
 * 加载不出来的空白块（用户报告 tid=160198 整帖图片空白）。
 *
 * <p>本脚本给每张图挂 error 兜底：首次失败时通过 JS 桥回调原生，让原生用
 * 带会话的 OkHttp 重新取图并回填 data: URI；重试仍失败就打上可视的失败样式，
 * 绝不静默留白。纯字符串类，无 Android 依赖，可在 JVM 单测里守护不变量。
 */
public final class PostWebImageScript {

    private PostWebImageScript() {}

    /**
     * 挂错误监听。必须在正文图片插入后执行（onPageFinished）。
     *
     * <p>两个入口：
     * <ul>
     *   <li>尚未失败的图：挂捕获阶段 error 监听；</li>
     *   <li>监听挂上之前就已经失败的图：{@code e.complete && naturalWidth===0}
     *       直接走同一条回调 —— Chromium 不会为已失败的 img 补发 error 事件。</li>
     * </ul>
     * 每张图带 {@code data-mtimg-i} 索引，原生回填时按索引精确定位；
     * {@code data-mt-retried} 保证一张图只重试一次，重试失败转可视样式。
     * build117: 该标记改为<b>发起重试前</b>写入 —— 见 {@link #bindErrorsJs()} 内注释。
     */
    public static String bindErrorsJs() {
        return "(function(){try{"
                + "var a=document.getElementsByTagName('img');"
                + "function fail(e,idx){"
                + "if(e.getAttribute('data-mt-retried')){"
                + "if(e.classList)e.classList.add('mt-img-failed');"
                + "return;}"
                // build117: 标记必须在发起重试之前就打上，不能等重试跑完。
                //
                // 站点对部分 CDN（icdn.binmt.cc）上的图会返回「指向自身」的 307
                // 无限重定向（x-tengine-error: denied by http_custom）。Chromium
                // 跟到 20 跳后放弃并抛 error —— 而且会持续反复抛。原来的实现是
                // 等原生重取结束、markImgFailedJs 落地时才写 data-mt-retried，
                // 于是这段窗口里每一个 error 都会再开一条重取线程（每条都要跟着
                // 绕 20 跳，几十秒才失败）；而重取一旦成功 setImgDataUriJs 又会
                // remove('mt-img-failed')，失败再 add 回去 ——
                // 图片框在 56px 与 auto 之间反复跳变，紧随其后的文字被顶得上下抽动，
                // 用户看到的就是「那张图和后面那段话一直疯狂闪」。
                //
                // 这里在回调原生之前先占位，同一张图就只会重试一次，闪烁随即止住。
                + "e.setAttribute('data-mt-retried','1');"
                + "if(window.PostBody&&window.PostBody.imgFailed){"
                + "PostBody.imgFailed(String(idx),e.currentSrc||e.src||'');}}"
                + "for(var i=0;i<a.length;i++){(function(e,idx){"
                + "if(e.getAttribute('data-mtimg-i'))return;"
                + "e.setAttribute('data-mtimg-i',String(idx));"
                + "var s=e.currentSrc||e.src||'';"
                + "if(s&&e.complete&&e.naturalWidth===0){fail(e,idx);return;}"
                + "e.addEventListener('error',function(){fail(e,idx);},true);"
                + "})(a[i],i);}"
                + "}catch(x){}})();";
    }

    /** 把原生取回的图片字节以 data: URI 回填到指定索引的 img，并标记已重试。 */
    public static String setImgDataUriJs(int index, String dataUri) {
        return "(function(){try{"
                + "var e=document.querySelector('img[data-mtimg-i=\"" + index + "\"]');"
                + "if(e){e.setAttribute('data-mt-retried','1');"
                + "if(e.classList)e.classList.remove('mt-img-failed');"
                + "e.src=" + jsString(dataUri) + ";}"
                + "}catch(x){}})();";
    }

    /**
     * 统计正文中<b>真正加载成功</b>的图片数量（build107）。
     *
     * <p>判定标准必须是 {@code complete && naturalWidth > 0}：站点把「需要登录」
     * 的附件换成 HTML 提示页时，{@code <img>} 照样会触发 load 完成事件，
     * 只是解码出来尺寸为 0 —— 那种图在用户眼里就是一片空白。
     *
     * <p>返回值交给 {@code evaluateJavascript} 的回调；出错时返回 {@code -1}，
     * 调用方把它当「未知」处理，不要误判成「一张都没有」。
     */
    public static String countLoadedImagesJs() {
        return "(function(){try{"
                + "var a=document.getElementsByTagName('img'),n=0;"
                + "for(var i=0;i<a.length;i++){"
                + "var e=a[i];if(e.complete&&e.naturalWidth>0)n++;}"
                + "return n;"
                + "}catch(x){return -1;}})()";
    }

    /**
     * build118: 让指定 img 重新按原地址加载一次。
     *
     * <p>用于「原生重取拿到了图，但图太大不适合塞成 data: URI」的情况
     * （实测 tid=170823 那张是 1329x2822、1.7MB 的 PNG，base64 后约 2.3MB）。
     * 把 2MB 级的字符串经 evaluateJavascript 塞进 WebView 再解码，开销极大，
     * 正是正文持续闪烁的放大器。此时 Cookie 已同步到图片所在域，
     * 让 WebView 自己带会话重取一次即可 —— 该图已带 data-mt-retried，
     * 即便再失败也不会重复走这条链路。
     */
    public static String reloadImgSrcJs(int index, String url) {
        return "(function(){try{"
                + "var e=document.querySelector('img[data-mtimg-i=\\\"" + index + "\\\"]');"
                + "if(e){e.setAttribute('data-mt-retried','1');"
                + "if(e.classList)e.classList.remove('mt-img-failed');"
                + "e.src=" + jsString(url) + ";}"
                + "}catch(x){}})();";
    }

    /** 重试也失败时给图打上可视的失败样式，替代静默空白。 */
    public static String markImgFailedJs(int index) {
        return "(function(){try{"
                + "var e=document.querySelector('img[data-mtimg-i=\"" + index + "\"]');"
                + "if(e){e.setAttribute('data-mt-retried','1');"
                + "if(e.classList)e.classList.add('mt-img-failed');}"
                + "}catch(x){}})();";
    }

    /** 生成 JS 字符串字面量（转义反斜杠、引号与控制字符）。 */
    private static String jsString(String value) {
        if (value == null) return "''";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<': sb.append("\\x3c"); break;
                case '>': sb.append("\\x3e"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\x%02x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
