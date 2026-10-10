package com.solosu.mtforum.util;

/**
 * 帖子正文（WebView 原帖渲染）里「代码块一键复制」的注入脚本（build98）。
 *
 * <h3>为什么单独拆一个类</h3>
 * 这段 JS 是纯字符串、零 Android 依赖，单独放出来有两个好处：
 * <ol>
 *   <li>能被 JVM 单测直接断言（选择器、转义、按钮文案，以及「点一下必须回桥」）</li>
 *   <li>出问题时可以在桌面浏览器 / Node 里拿真实页面结构直接跑，不用装 APK</li>
 * </ol>
 *
 * <h3>两层，主次分明</h3>
 * <ol>
 *   <li><b>主路径</b>：{@link PostCodeRender} 已经在 HTML 里放好了
 *       {@code div.mt-code} 卡片（头部一个 {@code span.mt-code-btn} 复制按钮）。
 *       这里只负责把点击接上 {@code PostBody.copyText} —— 结构是我们自己生成的，
 *       不存在「猜不到站点结构」这件事。</li>
 *   <li><b>兜底</b>：万一某段代码块没被 {@link PostCodeRender} 替换掉（比如它是
 *       JS 动态插进来的），再按站点结构扫一遍，给它挂一个浮动按钮。</li>
 * </ol>
 */
public final class PostWebCodeCopyScript {

    private PostWebCodeCopyScript() {
    }

    /** 站点侧的代码块选择器（兜底那一层用） */
    public static final String SITE_SELECTOR =
            "pre,div.blockcode,div.blk_code,div.comiis_blockcode,div[class*=blockcode]";

    /** 卡片 class 的本地别名（下面拼 JS 时用，避免长表达式把字符串引号拼错） */
    private static final String MARK = PostCodeRender.MARK;

    /** 复制按钮的类名（取代码文本时要排掉它） */
    public static final String BUTTON_CLASS = "mt-fallback-copybtn";

    /**
     * 生成注入脚本。调用方拿到后直接 {@code WebView.evaluateJavascript(js, null)}。
     *
     * <p>幂等：重复调用只给新出现的块补按钮，不会重复挂。
     */
    public static String js() {
        return "(function(){try{"
                + "var BTN='" + PostCodeRender.MARK_BTN + "';"
                + "var FB='" + BUTTON_CLASS + "';"
                + "var SEL='" + SITE_SELECTOR + "';"
                + "function flash(el,ok){try{var old=el.textContent;"
                + "el.textContent=ok?'已复制':'复制失败';"
                + "el.style.background=ok?'rgba(46,160,67,.9)':'rgba(200,60,60,.9)';"
                + "setTimeout(function(){el.textContent=old;el.style.background='';},1200);"
                + "}catch(e){}}"
                + "function copy(el,txt){try{"
                + "if(!txt){flash(el,false);return;}"
                + "if(window.PostBody&&window.PostBody.copyText){PostBody.copyText(txt);flash(el,true);}"
                + "else{flash(el,false);}}catch(e){flash(el,false);}}"
                // 主路径：我们自己的卡片。代码在卡的 .mt-code-bd 里，行号/按钮都不在里面。
                + "function wireCards(){try{var cards=document.querySelectorAll('."
                + MARK + "');"
                + "for(var i=0;i<cards.length;i++){(function(card){"
                + "if(card.getAttribute('data-mt-copy')==='1')return;"
                + "var body=card.querySelector('." + PostCodeRender.MARK_BODY + "');"
                + "if(!body)return;"
                + "card.setAttribute('data-mt-copy','1');"
                + "var text=(body.innerText||body.textContent||'').replace(/^\\s+|\\s+$/g,'');"
                + "var btn=card.querySelector('." + PostCodeRender.MARK_BTN + "');"
                + "if(btn){btn.style.cursor='pointer';"
                + "btn.addEventListener('click',function(ev){try{ev.preventDefault();"
                + "ev.stopPropagation();}catch(e){}copy(btn,text);},true);"
                + "btn.addEventListener('touchend',function(ev){try{ev.preventDefault();"
                + "ev.stopPropagation();}catch(e){}copy(btn,text);},true);}"
                // 头部空白处也能点：手机上一根手指的靶区比一个小按钮大多了
                + "var hd=card.querySelector('." + PostCodeRender.MARK_HD + "');"
                + "if(hd&&hd!==btn){hd.style.cursor='pointer';"
                + "hd.addEventListener('click',function(ev){if(ev.target===btn)return;"
                + "copy(btn||hd,text);},false);}"
                + "})(cards[i]);}}catch(e){}}"
                // 兜底：站点结构里没被替换掉的块（动态插入的），给个浮动按钮
                + "function outer(b){try{var a=b.parentElement;while(a){"
                + "if(a.matches&&a.matches(SEL))return false;a=a.parentElement;}return true;}"
                + "catch(e){return true;}}"
                + "function textOf(box){try{var c=box.cloneNode(true);"
                + "var kill=c.querySelectorAll('.'+FB+',span.mt-ln,.mt-ln');"
                + "for(var i=0;i<kill.length;i++){if(kill[i].parentNode)"
                + "kill[i].parentNode.removeChild(kill[i]);}"
                + "var t=c.innerText||c.textContent||'';"
                + "return t.replace(/^[\\s\\u00a0]+|[\\s\\u00a0]+$/g,'');}catch(e){return '';}}"
                + "function wireSite(){try{var list=document.querySelectorAll(SEL);"
                + "for(var i=0;i<list.length;i++){(function(box){"
                + "if(!box||box.getAttribute('data-mt-fb')==='1')return;"
                + "if(box.closest&&box.closest('." + MARK + "'))return;"
                + "if(!outer(box))return;var t=textOf(box);if(!t)return;"
                + "box.setAttribute('data-mt-fb','1');"
                + "try{if(getComputedStyle(box).position==='static')box.style.position='relative';}"
                + "catch(e){}"
                + "var b=document.createElement('div');b.className=FB;b.textContent='复制';"
                + "b.style.cssText='position:absolute;top:6px;right:6px;z-index:99;padding:3px 10px;"
                + "font-size:11px;line-height:18px;border-radius:4px;background:rgba(0,0,0,.55);"
                + "color:#fff;cursor:pointer;font-family:sans-serif;user-select:none;"
                + "-webkit-user-select:none;-webkit-tap-highlight-color:transparent;';"
                + "function hit(ev){try{ev.preventDefault();ev.stopPropagation();}catch(e){}"
                + "copy(b,t);}"
                + "b.addEventListener('click',hit,true);"
                + "b.addEventListener('touchend',hit,true);"
                + "box.appendChild(b);"
                + "})(list[i]);}}catch(e){}}"
                + "function scan(){wireCards();wireSite();}"
                + "scan();"
                // 站点会懒加载/二次渲染，也可能有动态插进来的代码块
                + "try{if(window.__mtCodeObs!==1){window.__mtCodeObs=1;"
                + "var mo=new MutationObserver(function(){scan();});"
                + "mo.observe(document.body,{childList:true,subtree:true});"
                + "setTimeout(scan,800);setTimeout(scan,2000);}}catch(e){}"
                + "}catch(x){}})();";
    }
}
