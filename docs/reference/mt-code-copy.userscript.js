// ==UserScript==
// @name         [MT论坛]“借鉴一下” by：青春向上
// @namespace    http://tampermonkey.net/
// @version      2025-11-22
// @description  利用正则表达式将html解析为bbcode，可供论坛发帖时布局参考。通过伪元素无侵入式添加代码复制功能。
// @author       青春向上
// @match        *://bbs.binmt.cc/forum.php?*tid=*
// @match        *://bbs.binmt.cc/*thread-*.html*
// @grant        none
// ==/UserScript==

(function () {
    'use strict';

    let isLoading = false;

    const tid = getTidByUrl();
    //不是帖子，停止执行
    if (!tid) return;

    const selectContent = 'div.comiis_messages.comiis_aimg_show.cl'

    function getTidByUrl() {
        const url = new URL(window.location.href);
        const regex = /thread-(\d+)-/;
        const match = url.pathname.match(regex);
        return url.searchParams.get('tid') || (match ? match[1] : null); // 匹配成功返回156601，失败返回null
    }

    // 复制代码块精简版：伪元素添加前置文字 + 点击复制（自动适配所有 .comiis_blockcode，含动态新增）
    (function () {
        // 1. 注入核心样式（伪元素文字 + 点击区域）
        const style = document.createElement('style');
        style.textContent = `
        .comiis_blockcode {
            position: relative !important;
            pointer-events: none !important; /* 禁止点击代码块 */
        }
        /* 前置文字（伪元素，无真实DOM） */
        .comiis_blockcode::before {
            content: "复制"; /* 前置文字 */
            position: absolute !important;
            right: 10px !important;
            top: 1em !important;
            transform: translateY(-50%) !important;
            color: #42b983 !important;
            font-size: 14px !important;
            font-weight: bold !important;
            pointer-events: auto !important; /* 只点击文字触发复制 */
        }
    `;
        document.head.appendChild(style);

        // 2. 事件委托：点击代码块（或前置文字）触发复制
        document.addEventListener('click', (e) => {
            const codeBlock = e.target.closest('.comiis_blockcode');
            if (!codeBlock) return;

            // 复制代码块的 textContent（纯文本，不含伪元素内容）
            const codeText = codeBlock.textContent.trim();
            previewDia(codeText)
        });
    })();

    // 提取处理单个元素的逻辑为独立函数
    function processElement(div) {
        try {
            const h2Element = div.querySelector('div.comiis_postli_top.bg_f h2');
            const pid = div.id.match(/\d+/)[0];

            // 检查是否已经添加过span，避免重复添加
            if (!h2Element.querySelector('span[data-action="reference"]')) {
                // 创建要添加的span元素
                const span = document.createElement('span');
                span.dataset.action = "reference"; // 添加标识，用于检查重复
                span.textContent = ' [借鉴一下]';
                span.style.cursor = 'pointer';
                span.style.color = '#0066cc';
                span.style.marginLeft = '8px';

                // 为span添加点击事件
                span.addEventListener('click', async () => {
                    // 查找对应div中的内容元素
                    const contentElement = div.querySelector(selectContent);

                    if (contentElement) {
                        // 输出innerHTML到控制台
                        if (!isLoading) {
                            const content = await getLatest(pid, contentElement.innerHTML)
                            previewDia(html2bbcode(content), null, 'ubb代码预览：');;
                        }
                    } else {
                        console.log(`未找到class为"${selectContent}"的元素`);
                    }
                });

                // 将span添加到h2元素的末尾
                h2Element.appendChild(span);
            }


            const timesElement = div.querySelector('div.comiis_postli_times.bg_f');
            //需判断，例如帖子没有此元素
            if (timesElement) {
                const bottomZhanSpan = timesElement.querySelector('span.bottom_zhan.y');
                if (bottomZhanSpan) {
                    // 检查是否已经添加过share span，避免重复添加
                    if (!timesElement.querySelector('span[data-action="share"]')) {
                        // 创建share span标签
                        const shareSpan = document.createElement('span');
                        shareSpan.dataset.action = "share"; // 添加标识，用于检查重复
                        shareSpan.className = 'y';
                        shareSpan.textContent = 'share';
                        shareSpan.style.marginLeft = '8px';

                        // 为share span添加点击事件
                        shareSpan.addEventListener('click', () => {
                            const link = `https://bbs.binmt.cc/forum.php?mod=redirect&goto=findpost&ptid=${tid}&pid=${pid}`;
                            previewDia(link);
                        });

                        // 将share span添加到bottomZhanSpan的后面
                        bottomZhanSpan.parentNode.insertBefore(shareSpan, bottomZhanSpan.nextSibling);
                    }
                }
            }


        } catch (error) {
            console.error(div, '处理元素时发生错误:', error);
        }
    }

    // 处理初始存在的元素
    const targetSelector = 'div.comiis_postli.comiis_list_readimgs.nfqsqi';
    document.querySelectorAll(targetSelector).forEach(processElement);

    // 创建 MutationObserver 实例
    const observer = new MutationObserver((mutationsList) => {
        // 存储已处理的元素（避免重复处理）
        const processedElements = new WeakSet();

        for (const mutation of mutationsList) {
            // 场景1：childList 变化（innerHTML 替换子节点时触发）
            // 场景2：characterData 变化（innerHTML 设为纯文本时触发，如 el.innerHTML = '新文本'）
            // 场景3：subtree: true 监听子树变化（目标元素是子元素时也能捕获）
            if (
                (mutation.type === 'childList' && mutation.addedNodes.length > 0) ||
                (mutation.type === 'characterData' && mutation.target.parentElement)
            ) {
                // 确定当前变化的关联元素（childList 取当前节点，characterData 取父元素）
                const currentElement = mutation.type === 'childList'
                    ? mutation.target
                    : mutation.target.parentElement;

                // 1. 若当前元素就是目标元素，且未处理过
                if (currentElement.matches(targetSelector) && !processedElements.has(currentElement)) {
                    processElement(currentElement);
                    processedElements.add(currentElement);
                }

                // 2. 若当前元素包含目标子元素，遍历处理（避免重复处理）
                currentElement.querySelectorAll(targetSelector).forEach(element => {
                    if (!processedElements.has(element)) {
                        processElement(element);
                        processedElements.add(element);
                    }
                });
            }
        }
    });

    // 开始观察目标节点
    observer.observe(document.body, {
        childList: true,        // 监听子节点增减（innerHTML 替换节点时触发）
        characterData: true,    // 监听文本节点变化（innerHTML 设纯文本时触发）
        subtree: true,          // 监听子树所有节点（目标元素是子元素时生效）
        attributes: false,      // 关闭属性监听（innerHTML 不触发属性变化，提升性能）
        characterDataOldValue: false // 不需要旧值，关闭节省内存
    });


    // 如果需要停止观察，可以调用：
    // observer.disconnect();

    //核心解析函数
    function html2bbcode(html) {
        let bbcode = html;
        const codeBlocks = [];
        let codeIndex = 0;

        // 提取代码块并替换为占位符（逻辑不变）
        bbcode = bbcode.replace(
            /<div class="comiis_blockcode[^>]*><div class="bg_f b_l"><ol>([\s\S]*?)<\/ol><\/div><\/div>/gi,
            (match, olContent) => {
                let codeContent = olContent
                    .replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, '$1\n')
                    .replace(/^\s*\n/gm, '');

                const codeEntities = {
                    '&nbsp;': ' ',
                    '&lt;': '<',
                    '&gt;': '>',
                    '&amp;': '&'
                };
                Object.entries(codeEntities).forEach(([entity, char]) => {
                    codeContent = codeContent.replace(new RegExp(entity, 'gi'), char);
                });

                codeContent = codeContent.replace(/<br[^>]*>/gi, '\n')
                    .replace(/<[a-zA-Z][^>]*>/gi, '')
                    .replace(/<\/[a-zA-Z][^>]*>/gi, '');

                const placeholder = `__CODE_BLOCK_${codeIndex}__`;
                codeBlocks.push(`[code]${codeContent.trim()}[/code]`);
                codeIndex++;
                return placeholder;
            }
        );

        // 处理非代码块部分的标签，先转义文本中的特殊字符，再处理标签
        // 将非标签内的 < > & 转义为占位符，避免与HTML标签混淆
        const tempPlaceholderMap = {
            '<': '__TEMP_LT__',
            '>': '__TEMP_GT__',
            '&': '__TEMP_AMP__'
        };
        // 正则匹配非标签内的 < > &（负向断言确保不在 <...> 内部）
        const textSpecialCharRegex = /(?<!<[^>]*)([<>\\&])(?!.*?>)/gi;
        bbcode = bbcode.replace(textSpecialCharRegex, match => tempPlaceholderMap[match] || match);

        // 处理引用 [quote]
        bbcode = bbcode.replace(
            /<div class="comiis_quote bg_h b_dashed f_c"><blockquote><font[^>]*>回复<\/font> ([\s\S]*?)<\/blockquote><\/div>/gi,
            '[quote]$1[/quote]'
        );

        // 处理隐藏内容 [hide]
        bbcode = bbcode.replace(
            /<div class="comiis_quote bg_h f_c">以下内容需要积分高于 ([\d]+) 才可浏览<\/div>/gi,
            '[hide=,$1][/hide]'
        );
        bbcode = bbcode.replace(
            /<div class="comiis_quote bg_h f_c"><h2[^>]*>本帖隐藏的内容: <\/h2>([\s\S]*?)<\/div>/gi,
            '[hide]$1[/hide]'
        );

        // 处理免费内容 [free]
        bbcode = bbcode.replace(
            /<div class="comiis_quote bg_h f_c"><blockquote>([\s\S]*?)<\/blockquote><\/div>/gi,
            '[free]$1[/free]'
        );

        // 处理表情标签
        const smileyMap = new Map();
        Object.keys(smilies_type).forEach(typeKey => {
            const [, dir] = smilies_type[typeKey];
            const typeId = typeKey.replace('_', '');
            const arrays = smilies_array[typeId];
            if (arrays) {
                Object.keys(arrays).forEach(pageKey => {
                    arrays[pageKey].forEach(item => {
                        const [, tag, filename] = item;
                        const fullPath = `https://cdn-bbs.mt2.cn/static/image/smiley/${dir}/${filename}`;
                        smileyMap.set(fullPath, tag);
                    });
                });
            }
        });
        bbcode = bbcode.replace(
            /<img[^>]*src="([^"]+)"[^>]*>/gi,
            (match, src) => smileyMap.get(src) || match
        );

        // 处理普通图片 [img]
        bbcode = bbcode.replace(
            /<img[^>]*src="([^"]+)"[^>]*width="([^"]*)"[^>]*height="([^"]*)"[^>]*>/gi,
            (_, src, w, h) => `[img=${w || ''},${h || ''}]${src}[/img]`
        );
        bbcode = bbcode.replace(
            /<img[^>]*src="([^"]+)"[^>]*>/gi,
            '[img]$1[/img]'
        );

        // 处理QQ标签 [qq]
        bbcode = bbcode.replace(
            /<a[^>]*href="http:\/\/wpa\.qq\.com\/msgrd\?[^>]*uin=([^&]+)[^>]*>([\s\S]*?)<\/a>/gi,
            '[qq]$1[/qq]'
        );

        // 处理电子邮件 [email]
        bbcode = bbcode.replace(
            /<a[^>]*href="mailto:([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi,
            (_, email, text) => {
                const cleanEmail = email.trim();
                const cleanText = text.trim();
                return cleanEmail === cleanText ? `[email]${cleanEmail}[/email]` : `[email=${cleanEmail}]${cleanText}[/email]`;
            }
        );

        // 处理链接 [url]
        bbcode = bbcode.replace(
            /<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi,
            (_, href, text) => {
                const cleanHref = href.trim();
                const cleanText = text.trim();
                return cleanHref === cleanText ? `[url]${cleanHref}[/url]` : `[url=${cleanHref}]${cleanText}[/url]`;
            }
        );

        // 处理文本样式标签
        bbcode = bbcode.replace(/<strong>([\s\S]*?)<\/strong>/gi, '[b]$1[/b]');
        bbcode = bbcode.replace(/<i>([\s\S]*?)<\/i>/gi, '[i]$1[/i]');
        bbcode = bbcode.replace(/<u>([\s\S]*?)<\/u>/gi, '[u]$1[/u]');
        bbcode = bbcode.replace(/<strike>([\s\S]*?)<\/strike>/gi, '[s]$1[/s]');

        // 处理颜色和背景色
        bbcode = bbcode.replace(
            /<font[^>]*color="([^"]+)"[^>]*>([\s\S]*?)<\/font>/gi,
            '[color=$1]$2[/color]'
        );
        bbcode = bbcode.replace(
            /<font[^>]*style="background-color:([^;]+);?"[^>]*>([\s\S]*?)<\/font>/gi,
            '[backcolor=$1]$2[/backcolor]'
        );

        // 处理字号 [size]
        bbcode = bbcode.replace(
            /<font[^>]*size="([^"]+)"[^>]*>([\s\S]*?)<\/font>/gi,
            '[size=$1]$2[/size]'
        );

        // 处理对齐 [align]
        bbcode = bbcode.replace(
            /<div[^>]*align="([^"]+)"[^>]*>([\s\S]*?)<\/div>/gi,
            '[align=$1]$2[/align]'
        );

        // 处理媒体标签 [media]
        bbcode = bbcode.replace(
            /<ignore_js_op>[\s\S]*?<iframe[^>]*src="([^"]+)"[^>]*>[\s\S]*?<\/ignore_js_op>/gi,
            '[media=x,500,375]$1[/media]'
        );

        // 处理表格标签
        bbcode = bbcode.replace(/<td[^>]*>([\s\S]*?)<\/td>/gi, '[td]$1[/td]');
        bbcode = bbcode.replace(/<tr[^>]*>([\s\S]*?)<\/tr>/gi, '[tr]$1[/tr]');
        bbcode = bbcode.replace(/<table[^>]*>([\s\S]*?)<\/table>/gi, '[table]$1[/table]');

        // 处理列表标签 [list]
        bbcode = bbcode.replace(
            /<ul[^>]*type="([^"]+)"[^>]*class="([^"]+)"[^>]*>([\s\S]*?)<\/ul>/gi,
            (_, type, cls, content) => {
                const listContent = content.replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, '[*]$1');
                return `[list=${type}]${listContent}[/list]`;
            }
        );
        bbcode = bbcode.replace(
            /<ul[^>]*>([\s\S]*?)<\/ul>/gi,
            (_, content) => {
                const listContent = content.replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, '[*]$1');
                return `[list]${listContent}[/list]`;
            }
        );

        // 处理水平线 [hr]
        bbcode = bbcode.replace(/<hr[^>]*class="l"[^>]*>/gi, '[hr]');

        bbcode = bbcode.replace(/&#(\d+);/gi, (_, code) => String.fromCharCode(code));
        bbcode = bbcode.replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)));

        // 处理换行
        bbcode = bbcode.replace(/<br[^>]*>/gi, '\n');

        // 清理非代码块部分的HTML标签（只匹配真正的HTML标签）
        bbcode = bbcode.replace(/<[a-zA-Z][^>]*>/gi, '')
            .replace(/<\/[a-zA-Z][^>]*>/gi, '');

        //还原文本中的特殊字符占位符
        const reversePlaceholderMap = Object.fromEntries(
            Object.entries(tempPlaceholderMap).map(([char, placeholder]) => [placeholder, char])
        );
        Object.entries(reversePlaceholderMap).forEach(([placeholder, char]) => {
            bbcode = bbcode.replace(new RegExp(placeholder, 'gi'), char);
        });

        // 3. 还原代码块（替换占位符）
        codeBlocks.forEach((code, index) => {
            bbcode = bbcode.replace(`__CODE_BLOCK_${index}__`, code);
        });
        // 处理HTML实体
        const htmlEntities = {
            '&nbsp;': ' ',
            '&amp;': '',
            //直接删除尖括号，避免被误认为html标签
            '&lt;': '<',
            '&gt;': '>',
            '&quot;': '"',
            '&#32;': ' ',
            '&#160;': ' ',
            '&#xa0;': ' '
        };
        Object.entries(htmlEntities).forEach(([entity, char]) => {
            bbcode = bbcode.replace(new RegExp(entity, 'gi'), char);
        });
        // 4. 最终格式整理
        return bbcode
            .replace(/\n{3,}/g, '\n\n')
            .replace(/^\s+|\s+$/g, '');
    }

    //优先使用最新数据解析，若帖子已被删除(404)或网络异常，再使用本地html解析，可以避免因修改本地html而造成解析数据有误
    // 函数本身标记为 async
    async function getLatest(pid, html) {
        isLoading = true;
        console.log(tid, pid);

        try {
            // 使用 await 等待 fetch 请求完成
            const response = await fetch(`https://bbs.binmt.cc/forum.php?mod=viewthread&tid=${tid}&viewpid=${pid}&mobile=2&inajax=1`, {
                method: 'GET',
                timeout: 3000
            });

            if (!response.ok) {
                // 如果 HTTP 错误，抛出异常，会被 catch 捕获
                throw new Error('网络请求失败');
            }

            // 使用 await 等待文本内容解析
            const xmlText = await response.text();
            const parser = new DOMParser();
            const xmlDoc = parser.parseFromString(xmlText, 'text/xml');

            //处理可能的XML解析错误
            if (xmlDoc.querySelector('parsererror')) {
                throw new Error('XML解析失败');
            }

            //获取目标节点值
            const root = xmlDoc.lastChild?.firstChild?.nodeValue;
            if (typeof root === 'undefined' || root === null) {
                throw new Error('返回数据格式不支持，改为解析本地');
            }

            //创建临时元素解析网页内容
            const tempDiv = document.createElement('div');
            tempDiv.innerHTML = root;
            const contentElement = tempDiv.querySelector(selectContent);
            tempDiv.remove();

            const content = contentElement ? contentElement.innerHTML : '';

            if (!content) {
                throw new Error(`返回数据为空，改为解析本地`);
            }

            // 成功时，返回 content
            return content;

        } catch (e) {
            // 捕获所有前面抛出的异常或网络错误
            console.log(e.message);
            // 失败时，返回备用的 html
            return html;

        } finally {
            // 无论成功失败，最后都执行
            isLoading = false;
        }
    }

    //预览对话框
    function previewDia(text, callback, title) {
        if (typeof text === 'object') text = JSON.stringify(text)
        // HTML转义函数
        function escapeHtml(unsafe) {
            return unsafe ? unsafe.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;") : '';
        }

        // 创建遮罩层
        const mask = document.createElement('div');
        mask.style = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:10002';
        mask.onclick = () => mask.remove();

        // 判断是否为输入模式（有回调）
        const isInputMode = typeof callback === 'function';

        // 构建弹窗HTML（动态切换按钮和内容区域属性）
        mask.innerHTML = `
<div style="position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:#fff;padding:15px;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,0.15);z-index:10000;width:80%;max-height:70%;min-height:200px;box-sizing:border-box;display:flex;flex-direction:column;">
  <span style="position:absolute;top:10px;right:15px;font-size:18px;cursor:pointer;color:#999" onclick="this.closest('div').parentNode.remove()">×</span>
  <label style="display:block;margin:10px 0 8px;font-size:16px;font-weight:bold;color:#333">${title ? title : '预览：'}</label>
  <div id="contentArea" style="overflow-y:auto;min-height:80px;white-space:pre-wrap;word-wrap:break-word;flex:1;${isInputMode ? 'user-modify: read-write; -webkit-user-modify: read-write; outline: none; cursor: text;' : ''}">${escapeHtml(text)}</div>
  <div style="margin-top:10px;text-align:right;">
    <button style="padding:5px 10px;border:1px solid #ccc;border-radius:4px;background:#fff;cursor:pointer;margin-right:5px" onclick="this.closest('div').parentNode.parentNode.remove()">取消</button>
    <button id="actionBtn" style="padding:5px 10px;border:1px solid #ccc;border-radius:4px;background:#fff;cursor:pointer">${isInputMode ? '确定' : '复制'}</button>
  </div>
</div>
`;
        document.body.appendChild(mask);

        // 获取核心元素
        const contentArea = mask.querySelector('#contentArea');
        const actionBtn = mask.querySelector('#actionBtn');

        // 阻止弹窗内部点击冒泡
        mask.querySelector('div').addEventListener('click', e => e.stopPropagation());
        actionBtn.addEventListener('click', () => {
            // 绑定按钮事件
            if (isInputMode) {
                // 输入模式：确定按钮
                const result = contentArea.textContent;
                callback(result); // 执行回调并传入内容
            } else {
                copyText(text);
            }
            mask.remove();
        });

    }

    //复制文本，兼容新旧浏览器
    function copyText(text) {
        // 创建临时文本框
        const textarea = document.createElement('textarea');

        // 隐藏文本框（不影响页面布局）
        textarea.style.position = 'absolute';
        textarea.style.left = '-9999px';
        textarea.style.top = '-9999px';
        textarea.readOnly = true; // 防止编辑

        // 处理文本（替换非换行空格）
        text = text.replace(/\xA0/g, ' ');
        textarea.value = text;

        // 添加到页面并选中内容
        document.body.appendChild(textarea);
        textarea.select();
        textarea.setSelectionRange(0, text.length); // 兼容移动设备

        let success = false;
        try {
            // 执行复制命令
            success = document.execCommand('copy');
        } catch (e) {
            success = false;
        }

        // 清理临时元素
        document.body.removeChild(textarea);
        return success;
    }

})();
