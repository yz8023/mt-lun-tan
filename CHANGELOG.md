# 更新日志

# 更新日志

## v5.35 (build118) — 修复 CDN 大图带来的持续正文闪烁

> v5.34（局部图框闪烁）与本版是两个叠加的问题，都源自「图拿不到」，
> 但表现不同：前者保证死循环不炸，后者解决「原生能取到图、WebView 却拿不到」。

### 现象
帖子 170823（长图文）一直疯狂闪，重启 App / axcee 同样如此。

### 根因
1. `syncToCookieManager()` 只把 Cookie 写给 `bbs.binmt.cc`，
   而正文配图常挂在其它子域（`icdn.binmt.cc`、`oos.binmt.cc`）。Cookie 默认 host-only，
   WebView 请求这些图等于「裸奔」，站点 WAF 返回 307 指向自身的无限重定向。
2. 原生 `retryPostWebImage` 带 Cookie 能拿到图（该图 1.7MB PNG），
   随后把 1.7MB 字节流 base64 成约 2.3MB 的 data URI 回塞 WebView。
   大块 base64 + 解码 + 布局重算组合起来，就把正文高度持续拉高拉低，形成闪烁。

### 修复
- `HttpClient.syncCookiesToHosts(Collection<String>)`：向指定主机写会话 Cookie；
- `PostImageHtml.extractImageHosts(String)`：从正文 HTML 中抽出 img/src/file/...
  里出现的所有图片主机；
- `ThreadDetailActivity.renderContentInWeb`：渲染前先扫 HTML，
  把 Cookie 同步到正文图片域；
- `ThreadDetailActivity.retryPostWebImage`：字节数 > 256 KB 时不回塞 data URI，
  改为 `reloadImgSrcJs`，让 WebView 自己带 Cookie 重拉一次。

### 备注
- 提取主机用 Jsoup 手动手写解析，不依赖额外 jsoup
- 所有新增代码避开外部依赖，保证编译通过
## v5.34 (build117) — 局部疯狂闪烁修复

> 与 v5.33（整篇重排型闪烁）是**两个独立问题**，不要混为一谈。

### 现象
帖子 170823 中「一个完整的 CocoStudio UI JSON，完美解出。」这段及其前一张图持续疯狂闪烁，无错误日志。

### 根因
该图位于 `icdn.binmt.cc`，被 WAF 拦截，返回**指向自身的 307 无限重定向**：

```
HTTP/2 307
x-tengine-error: denied by http_custom
location: /2608/6a797e4f48063.png    ← 指向自己
```

1. Chromium 跟随至上限（约 20 跳）后中止，抛 `error`，且**持续反复抛**；
2. `fail()` 回调原生 `imgFailed` → `retryPostWebImage` → OkHttp 亦跟随重定向，
   需绕完 20 跳、耗时数十秒才失败；
3. `data-mt-retried`（「一张图只重试一次」标记）**原在重取结束后**才由
   `markImgFailedJs` / `setImgDataUriJs` 写入 —— 在这段长窗口内，
   每一个新的 `error` 事件都会再开启一条重取线程；
4. 重取成功时 `setImgDataUriJs` 执行 `classList.remove('mt-img-failed')`，
   失败时 `markImgFailedJs` 再 `add` 回去 —— 图片框在 `height:56px !important`
   与 `height:auto !important` 之间反复跳变，其后文字随之上下抽动，即「疯狂闪」。

### 修复
- `PostWebImageScript.bindErrorsJs()`：在调用 `PostBody.imgFailed` **之前**
  先写入 `data-mt-retried='1'`，使同一张图只可能进入一次重取流程。
- `ThreadDetailActivity` 新增 `postWebImgRetrying`（同步 `HashSet<Integer>`）：
  重取入口去重，杜绝同一索引被并发开多条线程；`renderContentInWeb` 内每次重渲染清空。

### 备注
- 未改动 `HttpClient` 的共享重定向策略：去重后每张坏图最多一次慢请求，
  风险低于改动全局网络行为。

## v5.33 (build116) — 正文闪烁修复

### 修复
- **正文加载到某位置后持续快速闪烁**：`measurePostWebHeight()` 调用点极多
  （`onPageFinished`、`onReady`、每张图 `retryPostWebImage` / `markPostWebImageFailed`、
  `maybeFallbackToGallery`），且原实现无条件执行 `web.setLayoutParams(lp)`。
  即便测得高度与当前一致，也会触发 `requestLayout()`，强制整棵子树
  measure/layout 并让 WebView 重绘；长帖累积数十次即为肉眼可见的持续闪烁，
  且在图片与文字交界处最明显（该处高度变化最剧烈）。
  - 合并：以 `pendingWebHeightMeasure` 持有待执行 runnable，新测量先 `removeCallbacks` 再 post，
    一堆排队任务塌缩为最后一次。
  - 阈值：`lp.height > 0 && Math.abs(lp.height - newHeight) <= 2` 时直接 return，
    容差用于规避亚像素取整导致的 1px 抖动。
- **兜底轮询无上限**：`maybeFallbackToGallery()` 在 `postWebImgPending > 0` 时
  每 600ms 自我重排，原无次数上限；而 `postWebImgPending++` 位于 JavaBridge 线程
  （`imgFailed` 为 `@JavascriptInterface` 回调）、`--` 位于主线程，存在数据竞争。
  计数若卡住将永远轮询。新增 `fallbackGalleryPolls` 上限 10 次（约 6 秒）。

### 排查记录
- 已排除「图片无限重试」假设：`PostWebImageScript` 用 `data-mt-retried` 保证一张图只重试一次，
  `setImgDataUriJs` 先打标记再换 src，回填失败也不会再次触发。
- 已排除「onReady 重复触发」假设：`bindPostWebReady` 有 `done` 标志，只回调一次。

## v5.32 (build115) — 踢帖修复 · 预选理由

### 修复
- **踢帖成功却提示失败**：`submitKickRequest` 复用了 `isForumActionResponseSuccessful()`，
  该函数为评分/打赏设计，仅匹配 `succeedhandle_rate` / `rate_success` / `评分成功` / `打赏成功`，
  踢帖响应无法命中，导致一律判失败。新增 `isKickResponseSuccessful()`。
- **失败提示无信息量**：新增 `extractKickError()`，从 ajax CDATA 或 `messagetext` 节点抽取站点文案；
  失败提示改为「踢帖失败：＜站点原因＞」，取不到原因时才退回通用提示。

### 新增
- **踢帖预选理由**：`dialog_kick.xml` 增加 `chip_kick_presets`（ChipGroup），
  理由取自 `strings.xml` 的 `kick_reason_presets` 数组，与踢帖规则 5 条一一对应；
  点击填入输入框并清除错误态，仍可手动编辑。

## v5.31 (build114) — 相册图片修复

### 修复
- **相册版式的帖子正文图片整张丢失**：带多张截图的帖子，站点把图片放在 `ul.comiis_img_list`、
  单图帖放在 `ul.comiis_img_one`，这两个容器与正文 `div.comiis_message_table` 平级，
  而解析器只取正文容器，图片被整体丢掉（`ForumParser.appendAlbumImages`）。
- **评论区同样问题一并修复**：评论内容走同一个正文选择器（`div.comiis_message div.comiis_a.comiis_message_table.cl`），
  带相册的评论此前也会丢图。

### 验证
- 真实页面离线回放提取流程：`tid=174354` 正文图 0 → 3 张；`tid=174353` 0 → 1 张；
  对照帖 `tid=174306`（原本正常）2 → 2 张，无回归。
- 选择器改用 `ul[class*=comiis_img]` 属性包含匹配，覆盖多图/单图两种版式及后续可能的变体。

## v5.30 (build113) — 评论自动加载修复 · 移除账号名搜索

### 1. 修复：拖到底不自动加载更多评论

用户反馈：「拖到底但不是最后一条的时候还需要手动点击加载更多评论」。

`ThreadDetailActivity` 里负责自动加载的是 `nestedScroll` 的滚动监听，
原来的判断是：

```java
if (isNearBottom && this.binding.btnLoadMore.getVisibility() == 8 && this.postDetail != null) {
```

`8` 即 `View.GONE`。也就是说：**只有"加载更多"按钮处于隐藏状态时才会自动加载**。
而这个按钮恰恰在**还有下一页时是 VISIBLE** 的 —— 于是这个分支永远进不去，
滑到底也不会自动加载，只能手动点按钮。

改为只保留真正该有的判据：

```java
if ((isNearBottom || isAtBottom) && this.postDetail != null && !this.isLoadingMore) {
    if (this.postDetail.getCurrentPage() < this.postDetail.getTotalPages()) {
        loadMoreReplies();
    }
}
```

### 2. 预取可连续消费

「预加载无效」多半是同一个根因的连带现象：自动加载不跑，缓存自然没人消费。

另外补了一个二次卡顿：追加一页后，如果缓存里还有下一页、且用户仍停在底部
（新内容没把屏幕填满时很容易这样），就继续接上，
不再出现「加载一页后卡住、还得再滑一下或再点一次」。

新增 `isScrolledNearBottom()` 供自动加载与预取续接共用同一套判据。

### 3. 移除：账号名搜索用户（原第 7 项）

实测无效，按用户要求整体移除：
- 搜索页的「搜帖子 / 搜用户」切换控件（`MaterialButtonToggleGroup`）；
- `SearchActivity` 里的 `searchUserMode` 字段、切换监听、`performSearch` 分支；
- `searchUserByAccount()` 整个方法。

搜索恢复为只搜帖子。

### 构建

- versionCode **65** / versionName **5.30**（build113）
- 签名不变，可直接覆盖升级。

---

## v5.29 (build112) — 修复个人主页一律显示成自己

上一版（v5.28）修完搜索后，用户反馈「搜用户名还是跳到自己的主页」。
排查下来，**问题根本不在搜索，而在个人主页本身**。

### 真因

`UserProfileActivity` 抓的是：

    home.php?mod=space&uid=<uid>&mobile=2

而本站在**移动模板**（`&mobile=2`）下的空间页里，**根本不下发目标用户的数据**。
实测（目标 uid=157227「一束挽风」，我自己 uid=128752）：

| 页面 | 目标用户名出现 | 目标 uid 出现 | 我的 uid 出现 |
|---|---|---|---|
| `uid=X&mobile=2` | **0** | **0** | 4 |
| `uid=X&do=profile&mobile=2` | **0** | **0** | 4 |
| `uid=X`（桌面） | 5 | 25 | 4 |
| `uid=X&do=profile`（桌面） | 6 | 22 | 4 |

移动页里唯一的用户信息，是**全局头部当前登录用户**的那一份。

于是 `ForumParser.parseUserProfile` 的 Comiis 选择器（`.comiis_space_tx` / `h2.fyy` /
`.kmlevs`）一个都命中不了，全部落到通用兜底上。其中头像兜底是：

    doc.select("img[src*=avatar]").first()

页面上第一个头像是头部里**我自己**的小头像 —— 于是「不管点谁，都像在看自己的资料」。

补充：四个变体里 Comiis 那套类名的出现次数**全都是 0**，说明个人主页的解析
一直是靠兜底在撑着，这个问题不是新引入的。

### 改法

1. 新增 `ForumParser.parseUserProfileDesktop(html, uid)`：
   - 用户名：从 `<title>` 反推（「一束挽风的广播 - MT论坛」→ 去掉「 - 站名」
     和「的广播 / 的个人资料 / 的主页」等后缀）；
   - 头像：遍历 `<img>`，只认 `avatar.php` 且 `uid=` 与目标一致的那个，
     `size=small` 换成 `size=middle`。**按 uid 精确匹配**，避免抓到头部当前用户的小头像。
2. `UserProfileActivity`：移动模板解析不到用户名时，改抓桌面模板空间页
   （`home.php?mod=space&uid=X`，不带 `mobile=2`）并用新方法解析。

### 影响范围

**不只搜索。** 帖子里点任意作者名进个人主页，以前也是同一个毛病，一并修好。
搜索本身在 v5.28 已经是对的（6/6 命中正确 uid），这次修的是显示环节。

### 实测

登录真实账号，7 个 uid 逐个验证：

| uid | 期望用户名 | 解析结果 |
|---|---|---|
| 157227 | 一束挽风 | 一束挽风 ✅ |
| 120059 | 王傲辰 | 王傲辰 ✅ |
| 112076 | Android逆向 | Android逆向 ✅ |
| 157306 | heidong4686 | heidong4686 ✅ |
| 131142 | 吃橘子 | 吃橘子 ✅ |
| 145466 | Huaer | Huaer ✅ |
| 128752 | （自己） | 1058467230 ✅ |

用户名与头像 7/7 全部正确（含"看自己"的情况）。

### 构建

- versionCode **64** / versionName **5.29**（build112）
- 签名不变，可直接覆盖升级。

---

## v5.28 (build111) — 修复账号名搜索永远搜到自己

v5.27 刚加的「按账号名搜索用户」，实测下来搜任何人都返回自己。

### 根因

写法是 `home.php?mod=space&username=<名字>&mobile=2`。

**`&mobile=2` 是元凶。** 移动模板不认 `username=` 这个参数，而且它不会报错 ——
而是**静默回落成"我的空间"**。页面里自然满是我自己的 uid，
"取出现次数最多的 uid"也就永远取到自己。

桌面模板（不带 `mobile=2`）才认 `username=`，会直接跳转到目标用户的空间页。

### 实测对比（目标：uid=157227「一束挽风」，自己的 uid=128752）

| 请求 | 页面主导 uid | 结果 |
|---|---|---|
| `home.php?mod=space&username=X&mobile=2` | 128752 | ❌ 回落到自己 |
| `home.php?mod=space&username=X` | 157227 | ✅ 正确 |

### 顺带加固：不能只靠 uid 判断成功

这里还有第二层坑：**用户不存在时同样会回落到自己的空间**，
uid 依然取得到（就是自己的）。所以光拿到 uid 不能算成功。

现在多验一步：空间页的 `<title>` 形如「用户名的广播 - MT论坛」，
不存在的用户站点给的是「提示信息 - MT论坛」。
标题里没有搜的那个名字，就判定"没有找到该用户"。

### 验证

用真实账号登录，取帖子正文里出现的 6 个真实用户名逐个跑：

| 搜索词 | 期望 uid | 实际 uid |
|---|---|---|
| 一束挽风 | 157227 | 157227 ✅ |
| 王傲辰 | 120059 | 120059 ✅ |
| Android逆向 | 112076 | 112076 ✅ |
| heidong4686 | 157306 | 157306 ✅ |
| 吃橘子 | 131142 | 131142 ✅ |
| Huaer | 145466 | 145466 ✅ |
| zzz_不存在的人_9527 | 查不到 | 正确报未找到 ✅ |

7/7 通过。

### 教训

`&mobile=2` 是我从 `UserProfileActivity` 里照抄的 —— 那里是用 **uid** 打开空间，
加了 `mobile=2` 没问题。但按 **username** 查找时移动模板不支持，
而这个不支持的表现不是报错，是静默给错数据。
参数看起来无害，行为却完全不同，不能凭"别处这么写过"就照抄。

### 构建

- versionCode **63** / versionName **5.28**（build111）
- 签名不变，可直接覆盖升级。

---

## v5.27 (build110) — 反馈清单第 3~9 项

一次性做完用户列的 7 项（3~9）。

### 3. 评论预加载（最高 3 页，设置可自定义）

- 新增 `ReplyPrefetchPreferences`：默认 3 页，可在设置里 0~5 调，0 为关闭。
- `ThreadDetailActivity.scheduleReplyPrefetch()`：主楼渲染完就在后台顺序抓取
  `currentPage+1 .. currentPage+N`，每页间隔 250ms，存进内存 `prefetchedPages`。
- `loadMoreReplies()` 先查缓存，命中就零等待接上；没命中才走原来的网络分支。
- **刻意保持"不要预加载所有楼层"**：只往下取、有硬上限、单线程串行、可关闭。

### 4. 切换用户后消息界面不会更新

- 根因：`NoticeActivity.onResume()` 有个 60 秒节流（build68 加的，防 ESA 403）。
  切完账号马上进消息页，正好落在节流窗口里直接 `return`，于是继续显示上一个账号的数据。
- 数据层本来就是按 uid 隔离的（`NoticeBadgeManager.activeAccountUid`、
  `isCurrentBadgeRequest` 都会校验 uid），所以唯一要修的就是这个节流。
- 现在记录上次拉取时的 uid，**换号就无视节流立刻重拉**；同账号来回进出仍享受节流。

### 5. 下载弹窗美化 + 可改文件名

- 文件名从只读 `TextView` 换成可直接编辑的 `EditText`，加描边提示"这里能改"。
- 下方"保存到 Download/xxx"用 `TextWatcher` 跟随输入实时更新。
- 下载时取输入框里的名字，留空回落到论坛原名。

### 6. 帖子外可直接点图片预览 + 缩放优化

- `ThreadAdapter`：列表卡片上的缩略图（单图 / 双图网格）都可点开 `ImagePreviewActivity`，
  并把整张贴的图片列表一起传过去，进去后能左右翻。以前只有帖子详情里点正文的图才行。
- `ImagePreviewActivity`：`setOffscreenPageLimit(1)`，翻页不再从头加载。
- `ZoomableImageView`：
  - 放大后 `requestDisallowInterceptTouchEvent(true)`，横向拖动变成平移而不是翻页；
  - 双指缩放中一律禁止翻页；
  - 新增 `clampTranslation()`，把平移限制在图片范围内，不会把图拖出屏幕找不回来；
  - 回到原始大小就把翻页手势交还给 ViewPager2。

### 7. 账号名直接搜索用户

- 搜索页新增"搜帖子 / 搜用户"切换（`MaterialButtonToggleGroup`）。
- 搜用户走 `home.php?mod=space&username=xxx`，Discuz 会跳到该用户空间页，
  从中取出现次数最多的 `uid=nnn`，再交给已有的 `UserProfileActivity`。

### 8. 内置浏览器自定义 UA

- 新增 `UserAgentPreferences`：两条内置（移动版/电脑版，不可删）+ 用户自定义多条（长按可删）。
- 默认仍是内置移动版，与改动前行为一致。
- **只影响应用内浏览器**（`InAppBrowserActivity`）；论坛数据请求的 UA 一动不动 ——
  站点靠它区分移动/桌面模板，改了会直接把解析逻辑搞坏。

### 9. 评论窗口抬高 + 快捷代码双行

- 回复弹窗内容整体套进 `NestedScrollView`：原来内容一高就把发送按钮顶到屏幕外，
  而 BottomSheet 又关掉了拖拽手势，按钮根本点不到。
- 预览区高度 150dp → 110dp，输入框 `minLines` 4 → 3，给下面腾空间。
- `BBCodeEditor.buildToolbar()`：先把所有按钮收集起来再均分到两行，
  一次能看到约两倍，不用横着滑很久找标签。

### 验证

- 本地无 Android SDK，跑不了 Gradle，改用 javac 做语法校验：
  11 个改动文件过滤掉"包不存在/无法解析"后 **0 语法错误**；
  4 个改过的布局 XML 全部 `minidom` 解析通过。
- 真机行为待用户在 v5.27 上确认。

### 构建

- versionCode **62** / versionName **5.27**（build110）
- 签名不变，可直接覆盖升级。

---

## v5.26 (build109) — 帖子内图片白屏修复（真正原因：懒加载）

> 这一版推翻了 v5.25 的判断。v5.25 以为「附件需要登录态才可见」，
> 本版用账号登录实机验证后确认：**与登录态无关**，是懒加载没被改写。

### 真正的原因

站点移动模板的正文配图长这样：

    <img src="https://cdn.binmt.cc/template/comiis_app/pic/none.png"
         comiis_loadimages="https://oos.binmt.cc/forum/202610/09/175607....jpg"
         class="comiis_loadimages">

真图放在 **`comiis_loadimages`** 属性里，`src` 上挂的是一张 **500x320 的占位图**，
靠站点自己的 `$("img.comiis_loadimages").lazyload()` 在滚动到视口时才把真图写回 `src`。

而 `renderContentInWeb()` 为了防 XSS，第一步就把正文里的 `<script>` 全部剥掉了 ——
**那段懒加载脚本永远不会执行**，于是 WebView 里显示的永远是那张占位图，
用户看到的就是「整片空白」。

这也解释了两个现象：
- 为什么**只有部分帖子**白屏：老帖子的图直接写在 `src` 上，不走懒加载。
- 为什么**评论区图片正常**：`ReplyAdapter` 一直调了 `upgradeThumbnailsToFull()`。

### 修复

在 `renderContentInWeb()` 里、进 WebView 之前，补上
`PostImageHtml.upgradeThumbnailsToFull(body, HttpClient.BASE_URL)`，
把 `comiis_loadimages` 等懒加载属性的真实地址写回 `src`。该方法幂等。

### 实机验证（本轮用账号登录后才做得到）

- 登录卡在 ESA 挑战上：之前只给 GET 解了 WAF 挑战，**POST 没解**，
  所以 POST 一直被拦（返回挑战页，早先那个「非法字符」也是它）。
  补全后登录成功（站点支持手机号直接登录）。
- 4 个帖子（174306 / 174257 / 174255 / 174248）登录态下正文图共 14 张，
  全在 `oos.binmt.cc` 这个 CDN 上。
- **游客不带任何 cookie 也能 200 取到原图**（image/jpeg 282360 字节）→ 与登录态无关。
- 占位图实测 500x320 → `naturalWidth > 0` → **v5.25 那套「一张都没加载出来才兜底」的
  判据不会触发**，所以 v5.25 对这个白屏无效。

### 构建

- versionCode **61** / versionName **5.26**（build109）
- 签名不变，可直接覆盖升级。

---

## v5.25 (build108) — 帖子内图片白屏修复 · 图廊兜底

### 用户反馈（本轮）

1. 部分帖子（tid=174306 / 174257 / 174255 / 174248）图片无法加载，是白屏。

### 根因

- 四个帖子的图全是 **Discuz 附件**（页面提示「本帖子中包含更多资源，您需要登录后
  才能下载或查看附件」）。站点对没有登录态的请求返回的是 HTML 提示页 —— 对
  `<img>` 来说照样会触发 load 完成事件，只是解码出来尺寸为 0，用户眼前就是空白。
- v5.11（build92）起主楼默认走 `renderContentInWeb`，为了避免「一图两显」，
  这条路在渲染前**无条件**关掉底部图廊：`renderImageGallery(null)`。
  于是正文的附件图一旦没渲染出来，就是「正文没图 + 图廊也没图 = 一屏空白」。
- v5.23 加的原生重取兜底只覆盖「正文里 `<img>` 存在但加载失败」，
  覆盖不到「正文压根没解析出图」这种情况。

### ① 图廊兜底（主修复）

- 新增 `PostWebImageScript.countLoadedImagesJs()`：统计正文里**真正加载成功**
  的图片数，判定标准是 `complete && naturalWidth > 0`，零像素的提示页不算成功。
- 图片全部落地后（`onReady`，含失败、含原生重取结束）统计张数；为 0 且有候选
  地址时才启用底部图廊。正文正常出图时不介入，「不重复显示」的语义不变。
- 有原生重取在飞时不下结论（`postWebImgPending` 计数 + 延迟重试），
  避免把「正在抢救」误判成「没救了」而弹出重复图廊。

### ② 顺带修掉的两个静默失效

- `parseJsInt`：`evaluateJavascript` 的返回值是 JSON，字符串会带引号，
  而 `parseIntSafe` 只认裸数字会**永远解析失败** —— 这类兜底逻辑会静默失效。
  新增专用解析，带引号与不带引号都认。
- `SignParser.extractFormhash`：站点部分页面用**单引号**包属性
  （实测移动版 UA 的登录页是 `value='a3906715'`），原正则只认双引号，
  formhash 解析为空，上层就报「登录页解析失败（可能被风控）」——
  看着像站点风控，其实是自己的正则漏了一种写法。改为两种引号都认。

### 排查过程

本轮用仓库自带的 ESA 挑战解算器拿到了四个帖子的真实页面，并用仓库自己的
`ForumParser`（编译了 JVM 桩）跑过：四个帖子 `imageUrls` 均为 0、
`hasHiddenContent` 均为 false，图片确实全在登录可见的附件里。
手机站登录是 `comiis_sms` 短信验证码，PC 登录 POST 从本环境一律被 Discuz
判为「非法字符」拒绝，因此未能取得登录态页面做端到端复现 —— 结论由代码路径
与游客页结构推导得出，已由用户实机验证为准。

### 测试

- 新增 `PostWebImageScriptTest#countLoadedImages_onlyCountsImagesWithRealPixels`、
  `SignParserTest#extractFormhash_acceptsSingleQuotedAttributes`。
- 本地 37 项 JVM 单测全绿；`countLoadedImagesJs` 另用 jsdom 在真实 DOM 上验证过
  「零像素提示页不算成功、未加载完的也不算」。
- 改动前后 javac 错误数一致（8 个，均为既有代码在 JDK11 下的转义告警，
  CI 用 JDK17 不受影响）。

### 构建

- versionCode **60** / versionName **5.25**（build108）
- 签名不变，可直接覆盖升级。

---

## v5.24 (build107) — 帖子内纯文本链接自动识别为可点链接

### 用户反馈（本轮）

1. 帖子内链接无法自动识别为可直接点击打开的超链接。

### 根因

- v5.11（build92）起主楼默认走 `renderContentInWeb`：把站点下发的正文 HTML 原样丢进
  WebView，图才会落在作者插入的位置。但给 TextView 用的那套链接识别
  （`PlainTextUrlPattern` + `FixNestedScrollLinkMovementMethod.matcherLinkify`）
  只在 `if (!webRender)` 分支里被调用 —— WebView 这条路上一次都没跑过。
- 作者用 `[url]` BBCode 时 Discuz 会生成 `<a href>`，点击没问题；直接把网址贴进正文
  时不会，WebView 里就是一段普通文字：点不动，也不像链接。用户报的正是这一条。
- 顺带发现两处同类缺口：① `renderContentInWeb` 里 WebView 不可用、退回 TextView 的
  兜底分支也没调 `setupClickableLinks`；② 回复里的引用块（`tvReplyQuote`）同样没挂。

### ① 正文 WebView 自动补链接（主修复）

- 新增 `PostWebLinkifyScript`（纯字符串、零 Android 依赖，JVM 可测）：遍历正文文本节点，
  把纯文本网址包成 `<a href>`，在 `onPageFinished` 里注入。
- 覆盖 `https?://`、`//host`、`www.` 与常见裸域名（含 `.cn`），分别补成 `https://`；
  收尾的 `.,;:!?` 不吞进链接；邮箱（`user@example.com`）与文件名（`readme.html`）不误判。
- 跳过 `<a>`、`<pre>`、`<code>`、`<script>`、`<style>`、`<textarea>` 与自建的
  `.mt-code` 代码卡片：示例网址不被改坏，已有链接不重复包；脚本幂等，可反复执行。
- 只放行 http/https，不生成 `javascript:` 等其它 scheme 的链接。
- 链接数封顶 400，长帖不会把主线程拖死。
- 补出来的 `<a>` 不设 `target`：导航留在当前 WebView，`shouldOverrideUrlLoading`
  才能把它交给 `handlePostWebLink`，沿用统一的应用内/系统浏览器设置。

### ② 规则只留一份来源

- `PlainTextUrlPattern` 的 `URL_CHARS` 与 `TLD_ALTERNATION` 改为包内可见，
  `PostWebLinkifyScript` 直接用它们拼出 JS 侧正则；Java 正则里的两个后行断言
  在 JS 侧用等价的手写守卫替代（被挡掉时逐格前进，与正则引擎的推进方式一致）。
  两边从此不可能漂移成「TextView 里是链接、WebView 里不是」。

### ③ 另外两处缺口

- `renderContentInWeb` 的 TextView 兜底分支补上 `setupClickableLinks`。
- `ReplyAdapter` 引用块补上 `setupClickableLinks`（须在 `attachCopyOnLongClick` 之前调用）。

### 测试

- 新增 `PostWebLinkifyScriptTest`（9 项）：JS 侧正则与 `WEB_URL` 在 16 条语料上认出
  完全一致的链接；跳过名单、幂等、不设 target、只补 https、条数封顶等不变量。
- 另用 jsdom 对生成的脚本做了 30 项真实 DOM 端到端验证（链接生成、代码块不动、
  邮箱不误判、属性不被改、连跑三次幂等、900 个链接封顶、无 `javascript:` href）。
- `PlainTextUrlPatternTest` 5 项仍全绿（重构常量后行为不变）。

### 构建

- versionCode **59** / versionName **5.24**（build107）
- 签名不变，可直接覆盖升级。

---

## v5.23 (build106) — 帖子附件图整帖空白修复 · WebView 会话同步

### 用户反馈（本轮）

1. 帖子《【逆向工具】手机版新一代反汇编工具RzDroid发布》（tid=160198）正文图片全是空白不显示；已登录，其它帖子图片正常，使用默认的原帖渲染。

### 根因

- 该帖正文图全部是 Discuz `[attach]` 附件，地址走 `forum.php?mod=attachment&aid=…`。这类路由**必须带登录 Cookie**；站点把游客请求换成「该附件无法读取」提示页（HTTP 200 的 HTML），对 `<img>` 就是永远加载不出来的空白块。
- 本 App 登录走原生 OkHttp 表单，会话只存在 HttpClient 的 Cookie 罐里；此前**只有「切换账号」才会把会话推进 WebView CookieManager**。单账号、新装、清数据或 Cookie 登录后，正文 WebView 里没有登录态 —— 附件图全按游客处理，整帖空白。其它帖子的图多为 CDN 直链或带 key 的 `mod=image` 缩略图，不需要登录态，所以只有这类纯附件帖暴露问题。
- 附带发现两条次生缺陷：① `BBCodeUtil` 把残留的裸 `[attach]aid[/attach]` 拼成 `mod=image&aid=…&key=` 的缩略图地址，而 Discuz 的 `mod=image` 要求服务端签发的 key，空 key 实测一律 HTTP 500；② Glide 图片栈对「200 + HTML 提示页」不做内容类型校验，静默失败。

### ① WebView 会话同步（主修复）

- `HttpClient.init()`：启动恢复会话后把会话推进 WebView CookieManager。
- Cookie 登录、后台签到重登刷新的前台账号（`AccountManager.updateCookieHeader`）：同步推进。
- 正文 WebView 渲染前（`renderContentInWeb`）：再同步一次，确保任何进入路径下附件请求都带当前会话。

### ② 图片失败兜底：不再静默空白

- 正文 WebView 给每张图挂 error 兜底（含挂监听前已失败的图）：首次失败回调原生，用带完整论坛会话的 OkHttp 重取，成功以 data: URI 回填；重试仍失败打上可视的失败样式（灰底虚线框）。
- 新增 `PostWebImageScript`（纯字符串、JVM 可测）。
- `OkHttpStreamLoader`：附件/图片路由返回 `text/html`（站点提示页）时按加载失败处理，不再对 HTML 解码出空白。

### ③ 裸 [attach] 不再伪造必 500 的图片地址

- 站点没把附件内联渲染出来（游客/未回帖/无权限）时，残留的 `[attach]`/`[attachimg]` 渲染为可见占位「[图片附件]」，不再是空 key 的假缩略图。

### 测试与构建

- 新增 `PostWebImageScriptTest`、`BBCodeAttachRenderTest`（索引定位、重试守卫、特殊字符转义、占位不再含 mod=image）。
- versionCode **58** / versionName **5.23**（build106）
- 签名不变，可直接覆盖升级。

---

## v5.22 (build105) — Markdown 导入 · 用户留言板 · 本机 MCP · 通知与账号修复

### ① Markdown 转 Discuz BBCode

- 发帖、编辑和回复面板新增 Markdown 导入入口；支持标题、段落、列表、引用、代码块、链接、图片、粗体/斜体/删除线。
- 导入面板支持转换预览、复制、光标处插入或替换正文；首行 Markdown 标题可填入标题栏。
- 保留既有 `[qq]`、`[hr]`、`[media]`、`[email]`、`[url]` 等 BBCode，不改现有 BBCode 实时预览组件。

### ② 用户空间留言板

- 用户主页与个人中心均可进入留言板，支持分页查看、发布留言及在论坛提供相应操作链接时回复、编辑、删除。
- 解析作者、UID、头像、时间、引用内容和服务端权限链接；提交沿用论坛表单中的 `formhash`、`handlekey`、`referer` 等字段，不从页面外猜造编辑/删除权限。

### ③ 恢复本机只读 MCP（不恢复公网隧道）

- 默认关闭；启用后只绑定 `127.0.0.1:9797`，采用 Bearer Token 与常数时间比较校验，限制请求体、请求头、超时及客户端并发。
- 提供 12 个只读工具（帖子/版块/用户/搜索/通知/私信/收藏/好友等）；响应走白名单、限长及认证字段脱敏，无发帖、回复或其它写工具。
- 不恢复 `cloudflared`、Cloudflare 或任何公网/LAN 监听。MCP 工具会提示通知和私信可能包含当前账号的私人内容。

### ④ 通知角标、搜索与快速跳转

- 通知逐条维护已读状态；账号切换时用账号 UID 与请求代次隔离响应，避免旧请求污染新账号或覆盖“全部已读”状态。
- 增加可选“同帖通知批量标记已读”（默认关闭），保留逐条阅读语义；搜索历史可点选重搜、单项删除或清空。
- 快速跳转只接受数字 ID 或可解析的有效帖子/用户链接，错误类型会显示对应提示。

### ⑤ 多账号签到与签名材料

- 自动签到遵守“全部账号签到”开关；关闭时不再因账号库里有多条记录就无条件串行等待；已经完成今日签到的账号不再占用网络间隔。
- 签名材料重构：build.gradle 不再硬编码口令，改为环境变量或本机忽略的 `app/signing.properties` 注入；Release 签名优先由 GitHub Actions Secrets 提供。当前仓库的 Secrets 尚未配置，CI 暂时回退使用仓库内置的演示 keystore（口令与 v2.0~v5.21 公开历史一致）；配置 Secrets 后即可移除该回退文件。发布源码压缩包通过 `.gitattributes` export-ignore 永久排除 keystore 与签名属性；未配置任何签名材料时发布步骤会拒绝未签名 APK。

### 测试与构建

- 全量 JVM 单测：157 项通过（0 失败）；包含 Markdown 转换与留言板 DOM/分页解析测试。
- Debug 编译及签名 Release 构建通过；R8 优化成功，Release APK 签名验证通过。
- versionCode **57** / versionName **5.22**（build105）
- 签名材料仅保存在本地/CI Secret，不纳入源码包。

---

## v5.21 (build104) — 链接路由修复 · 下载确认 · 解锁随机池透明化

### 用户反馈（本轮）

1. 链接点开没有按设置留在应用内，普通 http/https/www/.cn 文本也有漏识别；直接文件下载没有确认弹窗。
2. 默认自动解锁回复模板藏在代码随机池里，希望默认内容也能逐条查看和编辑。

### ① 统一链接打开与纯文本识别

- 修复设置分流：`LinkRouter` 此前读取附件下载偏好，而正文开关写入的是 `UiSettings`；现在正文、回复和链接路由共用同一开关。应用内模式下普通网页进入应用内浏览器，站内帖子/用户页优先使用原生页面；系统浏览器模式下全部交给系统。
- 正文和回复共用 URL 模式，支持大写/小写 `http://`、`https://`、协议相对地址、`www.` 以及常见裸域名（包括 `.cn`）；中文句末标点不会再吞并相邻链接。
- 回复文本改用支持嵌套滚动的链接触摸处理，避免可见链接被父滚动容器抢走点击。

### ② 应用内下载确认

- 修复 `InAppBrowserActivity` 的 WebView 下载监听器此前直接加入系统下载队列的问题。
- 下载前展示推断文件名和文件大小，提供“复制直链 / 取消 / 下载”；确认后沿用 Cookie、User-Agent 和 Referer 发起系统下载器任务，APK 保留安装包类型。

### ③ 默认解锁回复随机池公开可编辑

- 把原来藏在 `AutoReplyEngine` 里的 9 条默认候选迁到配置管理器；解锁回复编辑器逐行列出默认内容，可查看、修改、删除或恢复默认。
- 每行作为一个随机候选；有帖子标题时优先从含 `{title}` 的候选中抽取，标题为空时优先用通用候选。旧版单条自定义模板保留兼容。

### 测试与构建

- 新增 `PlainTextUrlPatternTest` 覆盖 http/https、大小写、www.cn、常见裸域名、相邻链接、句末标点、邮箱和文件名边界；合计 30 个独立 JVM 单测通过。
- versionCode **56** / versionName **5.21**（build104）
- 签名不变，可直接覆盖升级。

---

## v5.20 (build103) — 他人主页提速 · 回复图片尺寸 · 过滤语义修正

### 用户反馈（本轮）

1. 打开其他用户主页要等待很久。
2. 评论区图片仍像缩略图，不能像正文图片一样正常显示。
3. 灌水回复的默认词条应按整条精准匹配；自定义关键词过滤才按“包含关键词”匹配，两者不能混成一个规则。

### ① 他人主页提速

- 慢的主要原因是资料解析后同步调用 `queryServerFollowState()`：它为了确认一个目标用户是否被关注，会串行拉取当前账号最多 50 页的完整关注列表，主页 UI 要等这些请求全部结束才显示。
- 移除主页打开时的全量关注列表查询，关注状态直接使用目标资料页 HTML 中已有的 `followmod` 状态；若服务端页面没有提供状态，就显示“状态未提供”，不再用多页抓取阻塞主页。
- 去除每次改变主页 URL 的 `_ts` 参数，保留稳定请求地址；前台请求节流标记放进 `finally` 清理，避免异常后残留。

### ② 回复图片按真实尺寸排版

- 修复 `UrlDrawable`：Glide 加载完成后，以计算出的实际宽高同步占位 Drawable 自身的 bounds，并复制当前行的 `Spanned` 触发真正的 TextView 重新排版（`setText(tv.getText())` 可能因对象相同直接无效）；旧图片回调若遇到 RecyclerView 已复用的行则不触碰新内容。此前 ImageSpan 可能仍按初始 24dp 占位框测量，导致回复图片即使加载完成也没有按正文宽度排版。
- 原图属性识别补充 `data-zoomfile`、`data-original-src`、`data-full-src`；保留“仅从已签名 aid 构造附件路由”的安全规则。

### ③ 分开灌水精准匹配与关键词包含匹配

- **屏蔽灌水回复（精准匹配）**：整条回复与词条相同才命中；默认短语可编辑/恢复默认，标点参与匹配。内置自动解锁模板也按完整句式及帖子标题精准识别。
- **关键词匹配（包含匹配）**：另设独立开关和词条列表；开启后，只要回复正文包含任一自定义关键词即屏蔽。新装默认列表为空，避免把“感谢分享”等灌水短语误当作子串规则。
- 默认精准短语包含 `看看隐藏`、`感谢分享`、`感谢分享，看看隐藏`、`去看看`、`11111`、`看看内容`、`学习学习`、`正需要这个`、`正需要这个！`。
- 从 v5.19 迁移时，旧列表中默认灌水短语留在精准过滤；用户新增的非默认词条迁入包含匹配列表并保留启用状态。作者黑名单仍独立。

### 测试与构建

- 更新 `ReplyContentFilterTest`（精准/包含差异、完整自动回复模板）和 `PostImageHtmlTest`（`data-zoomfile` 原图属性选择）；`UrlDrawable` 的 ImageSpan 尺寸重排尚未由 JVM 单测覆盖。
- versionCode **55** / versionName **5.20**（build103）
- 签名不变，可直接覆盖升级。

---

## v5.19 (build102) — 回复图片、关键词过滤与资源下载体验

### 用户反馈（本轮）

1. v5.18 后，thread 174005 的部分回复图片仍显示为缩略图。
2. 首页/列表预览裁切后只剩边角，单图预览太窄、内容不易辨认。
3. 移除回复黑名单的「精准匹配」模式，改为可编辑关键词包含匹配，并保留软件自动解锁话术识别。
4. thread 174008 的 Discuz 表情被当作正文大图放大；APK 附件需明确保留安装包类型。
5. 附件文件名可能乱码；下载前希望看到保存名，并能复制直链或直接下载。
6. 回复中的纯文本网址与 HTML 超链接应遵循应用内/系统浏览器偏好。

### ① 回复图片：补齐 Discuz 中尺寸变体与签名 aid 的解析

- 图片解析会比较 HTML 已提供的 Discuz 图片变体尺寸；即使链接指向 500×480 等中尺寸，也会继续检查元素/祖先上的有效签名 aid。
- 找到有效签名 aid 或明确的原始附件链接时，请求 `nothumb=yes`；只有裸数字 aid 时仍保留现有图片 URL，不猜造附件地址。
- 增补回复场景对应的单元测试，覆盖中尺寸变体、祖先签名 aid、无签名数字 aid等边界。

### ② 首页图片：固定尺寸下完整利用可用宽度

- 单张图片不再误落在双列网格的半宽区域，改为使用完整卡片宽度。
- 双图仍固定高度、居中裁切；首次布局宽度未就绪时会按真实容器宽度校准，避免第二张只露出边角。

### ③ 回复过滤：统一为关键词匹配

- 移除「精准匹配/模糊匹配」选择器；正文包含任一词条即命中。
- 新装默认词条：`看看隐藏`、`感谢分享`、`感谢分享，看看隐藏`、`去看看`、`11111`、`看看内容`、`学习学习`、`正需要这个`；每行可自定义，也可恢复默认。
- 「屏蔽灌水回复」开关统一控制自定义关键词与内置自动解锁模板识别；识别「感谢分享 + 帖子标题」以及软件自动解锁话术。过滤只影响本地列表，不删论坛回复。

### ④ 表情与 APK 附件

- WebView 正文对 Discuz 表情路径单独采用 24px 行内样式；回复文本中的已识别表情也保持小尺寸。
- `.apk` 附件作为普通可下载文件保留 `.apk` 扩展名并标注安装包 MIME 类型；下载器通知仍交由 Android 系统处理。

### ⑤ 下载文件名与液态确认面板

- 新增文件名解析：优先附件名称与 `download`/`filename` 信息，并处理 RFC 5987、百分号编码及常见 UTF-8/GBK 乱码；过滤签名 aid 等不适合作为文件名的长串。
- 下载前显示毛玻璃风格底部弹窗，展示预计文件名/扩展名及保存位置；左侧复制直链，右侧下载。仅点击下载才会发起请求，并提示论坛资源可能需要登录或扣除积分。
- 应用内浏览器触发的文件下载复用同一文件名解析逻辑。

### ⑥ 回复链接

- 回复纯文本网址支持 `www.` 与协议相对地址；HTML 超链接继续转换为可点击链接。
- 统一经链接路由器按「应用内打开 / 系统浏览器」偏好处理；应用内模式下论坛帖子与用户页保留原生跳转。

### 测试与构建

- 新增/更新 `ReplyContentFilterTest`、`PostImageHtmlTest`、`AttachmentFileNameTest`、`AttachmentParserTest`。
- versionCode **54** / versionName **5.19**（build102）
- 签名不变，可直接覆盖升级。

---

## v5.18 (versionCode 53) — 评论区图片原图链接解析

### 用户反馈（本轮）

- 评论区图片显示的是缩略图，要求正常显示。

---

## 评论区图片：使用 HTML 中的原图链接，并请求原始附件

`ForumParser` 保留回复正文的 HTML；`ReplyAdapter` 在渲染前调用共享的 `PostImageHtml`，再由 ImageGetter 按 TextView 可用宽度等比绘制。此前 helper 只看图片自身的 lazy-load 属性与 `src`，没有查看包住图片的直接附件链接；此外，Discuz 的 `mod=attachment` 需要签名 aid，不能把 `mod=image&aid=123` 的裸数字直接拼成附件地址。

本版调整：

- 原图属性优先；若图片位于直接附件链接内，改用该链接，并加 `nothumb=yes` 明确请求原始图片。
- 只有 `<img>` 上的 aid 确实符合 Discuz 签名格式时，才允许据此构造原图附件链接；不会把未签名的缩略图 aid 猜成下载地址。
- `size=500x480` 等较大 CDN 地址保持原样；若 HTML 确实只提供小尺寸缩略图且没有原图属性/链接，保留原地址而不构造已知无效的链接。

新增 `PostImageHtmlTest` 覆盖附件外链、签名 aid、`nothumb=yes`、显式原图属性、无签名缩略图不伪造附件链接，以及保留正常 CDN 地址等情况。

## 构建

- versionCode **53** / versionName **5.18**（build101）
- 签名不变，可直接覆盖升级

---

## v5.17 (versionCode 52) — 回复黑名单匹配模式 · 屏蔽模板化灌水回复

### 用户反馈（本轮）

1. 回复黑名单需要「精准匹配 / 模糊匹配」两种模式。
2. 增加「屏蔽灌水回复」开关，默认开启，隐藏纯填充词与标题加客套模板。

---

## ① 回复黑名单：精准匹配 / 模糊匹配

- 侧边栏「显示」区新增下拉选项：**精准匹配**、**模糊匹配**。
- 精准模式要求回复正文归一化后与词条完全相等；例如词条「看看」只隐藏回复「看看」，不匹配「来看看」或「看看隐藏」。
- 模糊模式下，回复正文包含任一词条即隐藏。
- 点旁边的「词条」按钮可编辑本机回复黑名单：每行一条，支持增删；空白行忽略、重复词条合并。默认没有自定义词条，默认匹配方式为精准，避免短词造成意外误屏蔽。
- 匹配时忽略空白与英文大小写，但标点仍参与黑名单精准匹配；灌水识别会忽略标点。不会把引用块内容算进回复正文。

## ② 屏蔽灌水回复（默认开启）

侧边栏「显示」区新增独立开关。开启后仅在帖子详情回复列表中隐藏：

- 「看看」「感谢分享」「支持一下」等纯填充/客套短语及其纯短语组合；
- 「帖子标题 + 感谢分享 / 谢谢楼主 / 支持一下」这类只套标题的模板回复。

采用保守判定：若同一条回复还有实际说明、问题或观点，就不按模板灌水隐藏。关闭开关只停用这一套灌水识别，不会关闭用户自己维护的回复黑名单。

**实现细节**

- 回复过滤只调整本地显示，不会删除论坛上的回复或改变服务器回复数。
- 初始页和「加载更多」都共用同一过滤函数；保留原始回复列表，切换只看楼主和追加分页时不会把过滤结果重复当作原数据。
- 新增纯 Java `ReplyContentFilter` 与单元测试，覆盖精准/模糊边界、标点归一、纯填充词、标题模板和实质内容保留。

## 构建

- versionCode **52** / versionName **5.17**（build100）
- 签名不变，可直接覆盖升级

---

## v5.16 (versionCode 51) — 安装包 20.8MB→3.8MB · 移除 MCP · 侧边栏折叠 · 首页图片固定尺寸 · 高刷申请

### 用户反馈（本轮）

1. 侧边栏功能进行折叠
2. 顶栏不再显示 MCP 功能
3. 去掉 MCP 功能，因为无效
4. 安装包体积过大
5. 为什么论坛 FPS 锁 60
6. 首页图片显示限制数量最大 2、裁切显示大小为固定尺寸

---

## ④ 安装包体积过大 → 20.81 MB 降到约 3.8 MB

### 先量了一遍

把 v5.15 的发布包拆开统计每个条目的压缩后大小：

```
lib/arm64-v8a/libcloudflared.so   17.02 MB   ← 占整个包的 82.2%
classes.dex                        2.72 MB   ← 13.1%
res/                               0.44 MB
resources.arsc                     0.44 MB
```

### 根因

`cloudflared` 一个文件就 17 MB，而它只服务于「公网 MCP 隧道」这一个功能。
更荒唐的是它是**构建时下载**的：GitHub Actions 的源码包里从来没有它，
release 工作流却把它塞进 APK —— 于是「源码自己构建出来的包」和「发布包」
体积差了 5 倍，用户拿到的一直是那个大的。

### 处理

- 删掉 `app/build.gradle` 里的 `fetchCloudflared` 任务与 jniLibs 目录
- 删掉 CI 里「下载并校验 cloudflared」那一步
- 顺手清掉两个已无用的东西：`android:extractNativeLibs="true"`
  （包里已经一个 native 库都没有了）与 `FOREGROUND_SERVICE_SPECIAL_USE`
  权限（MCP 是唯一用它的前台服务）

APK 里现在不含任何 native 二进制，体积与「源码构建」完全一致。

---

## ②③ 移除 MCP 功能

用户原话：「顶栏不再显示 mcp 功能」「去掉 mcp 功能，因为无效」。

整体删除，不留半截入口：

- `mcp/` 包五个类（`McpServer` / `McpService` / `McpPreferences` /
  `CloudflareTunnelManager` / `McpSettingsActivity`）全部删除
- 首页顶栏的 MCP 按钮（原 AI 助手图标位）删除，右上角只剩搜索
- 设置页「AI 与高级工具 → 只读 MCP 与公网隧道」卡片删除
- `AndroidManifest` 里的前台服务声明、设置 Activity 声明删除
- `MyApplication` 启动时不再拉起 MCP 服务

保留的部分：`ai/ForumTools` 是「AI 助手对话」用的本地工具集，和 MCP
无关，继续保留（总结帖子、读帖都还在用）。

---

## ① 侧边栏分区折叠

侧边栏一千多行、四大块，全展开要滚好几屏才能找到底部的设置 / 标签 / 运行日志。

现在四个分区标题都能点，点一下收起 / 展开，状态记在偏好里下次照旧：

| 分区 | 内容 |
| --- | --- |
| 账号 · 点头像卡片直接切换 | 账号列表、添加账号、账号与签到管理、立即签到 |
| AI 自动化 · 签到 / 自动回复 / 解锁 | 自动回复、自动解锁、解锁模板、演练、静默、立即执行、AI 总结、AI 配置、AI 助手 |
| 显示 · 阅读体验开关 | 图片原位显示、原帖排版渲染、FPS、高刷新率、链接打开方式、隐藏内容就地展开、滚动隐藏底栏 |
| 其他 · 工具入口 | 小黑屋、设置、ID 跳转、标签、已保存帖子、运行日志 |

- 标题右侧箭头：收起是 `›`，展开是 `⌄`
- **默认只展开「显示」** —— 那一块是日常真会动的开关，其余三块默认收起，
  一屏就能看到四个分区标题
- 折叠实现放在 `MainActivity.setupDrawerCollapse()`，偏好键
  `drawer_group_open_{0..3}`（见 `UiSettings`）

---

## ⑥ 首页图片：最多 2 张 + 固定尺寸裁切

数量本来就是 2（build86 从 4 降下来的），这版按用户要求把**显示方式**改成固定尺寸裁切：

- 两张图：各占半宽、高度 = 列宽 × 3/4（≈4:3），`CENTER_CROP` 居中裁切
- 单图封面：固定 150dp 高 + `CENTER_CROP`
- 都是圆角裁切（`clipToOutline`）

为什么又改回裁切：build95 为了「不裁长图」把高度改成 `wrap_content + fitCenter`，
结果每张卡片高度都不一样 —— 一张长截图能占掉大半屏，列表参差不齐。
用户这次明确要求「裁切显示大小为固定尺寸」，于是以整齐优先，
宁可裁掉边缘也不让长图撑高卡片（图片本身点开还是能看全的）。

---

## ⑤ 为什么论坛 FPS 锁 60

**不是 App 里写死了 60。** Android 上屏幕跑多少赫兹由三方决定：

1. **面板**：设备最高支持多少（`Display.getSupportedModes()`）
2. **系统的刷新率策略**：多数国产 ROM（MIUI / HyperOS / ColorOS…）
   对**没有主动申请的第三方 App 一律按 60Hz 合成**，只有白名单应用才给 120Hz
3. **App 有没有提出请求**：想跑高刷必须显式告诉系统
4. **省电模式 / 开发者选项里的「强制 60Hz」/ 系统设置里的刷新率**：
   系统级开关，App 无法覆盖

### 这一版做的事

- 新增 `util/RefreshRate`：在**每个 Activity 恢复时**申请设备支持的最高刷新率。
  两条窗口属性一起给 ——
  `WindowManager.LayoutParams.preferredRefreshRate`（通用）+ 
  `preferredDisplayModeId`（直接指名最高刷新率那个显示模式，
  只写前者在部分 ROM 上会被忽略）
- 侧边栏「显示」区新增开关 **请求高刷新率**（默认开，想省电可关），
  副标题实时显示「屏幕当前 60Hz / 设备最高 120Hz」，一眼能分清是谁在限
- **FPS 浮层改成同时显示两个数字**：`FPS 60 · 屏 120Hz`。
  屏 120 而 FPS 60 = App 渲染跟不上（或系统没放行）；
  两个都是 60 = 屏幕本来就在 60Hz，去找系统设置里的刷新率开关

需要说明：如果系统设置里本身就选了 60Hz、或者开了省电模式，
App 的申请会被系统忽略 —— 这种情况下 FPS 浮层里的「屏 xHz」会直接显示 60Hz，
用户就知道该去哪儿改了。

---

## 构建

- versionCode **51** / versionName **5.16**
- 签名不变，可直接覆盖升级
- APK 内已不含任何 native 二进制，目标体积约 **3.8 MB**（上一版 20.81 MB）

## v5.15 (versionCode 50) — 正文代码一键复制 · 快捷回复可勾选可编辑 · 发帖标签 · [code] 预览修复

### 用户反馈（本轮）

1. 帖子正文里面的，代码部分还是不能直接复制，就是注入复制按钮，直接复制代码内容
2. 默认回复增加一些快捷选择的词条，用勾选的方式，增加编辑或者删除，以及一些快捷键，比如已知的 `{title}`
3. 我说的标签就是这个（`misc.php?mod=tag&id=384&type=thread&mobile=2`），发帖要能快捷选择添加标签
4. 快捷键补上：`[qq]`、`[hr]`、`[media=x,500,375]…[/media]`、`[email=地址]名称[/email]`、
   `[url=地址]名字[/url]`（后两条要弹窗快速填充）
5. 快捷键用了 `[code][/code]` 会导致预览异常，之前的颜色全失效，直接
   把 `[color=#B30000]` 这样的颜色代码露出来

这一版把这五条全部处理。

---

## ① 正文代码块「复制」按钮（第二次修）

### 上一版为什么还是不行

v5.14 的修法是在 WebView 加载完之后，用 JS 去**猜**站点结构
（`pre` / `div.comiis_blockcode`…），猜中了再往里塞一个绝对定位的按钮。
这条路上要同时赌对三件事，任何一件不成立用户看到的就是「还是不能复制」：

1. 站点模板结构猜对（模板一换就废）
2. `onPageFinished` 时那个代码块已经存在（懒加载 / 二次渲染就错过）
3. 塞进去的按钮没被站点自己的 CSS 盖住、没被代码块的横向滚动带走

### 这一版的修法：不再猜，自己生成

在**把 HTML 交给 WebView 之前**就替换掉代码块 —— 新增
`util/PostCodeRender.java`，用 jsoup 把正文里的每个代码块换成 App 自己的卡片：

```html
<div class="mt-code">
  <div class="mt-code-hd"><span class="mt-code-lang">java</span>
    <span class="mt-code-btn">复制</span></div>
  <pre class="mt-code-bd">public class A { … }</pre>
</div>
```

卡片、语言标签、按钮的 class 全是写死的，按钮就在卡片头部的正常文档流里 ——
不可能被盖住、不可能飘走、不可能没挂上。JS 只剩一件事：把点击接到
`PostBody.copyText`（`util/PostWebCodeCopyScript.java`）。

替换时的三个细节：

- `span.mt-ln`（build64 加的行号）整段丢掉，复制出来是干净代码
- `<br>` 还原成换行、缩进保留，`< > &` 走 jsoup 转义，再怪的代码也不会破坏 HTML
- 只处理最外层块：模板里 `.comiis_blockcode` 常套着同 class 的子 div，
  不排掉就会「卡片套卡片」

还留了两条兜底：站点动态插进来的块按老办法挂浮动按钮；
「复制正文」长按现在是**面板** —— 可选纯文本，也可逐段复制第 N 段代码
（哪怕 WebView 里一个按钮都没点上，代码也拿得走）。

---

## ② 快捷回复：勾选启用 · 编辑 · 删除 · 变量占位符

`session/QuickReplyManager.java` 的存储从纯字符串数组升级成对象数组
（`[{"t":"…","on":true}]`），老数据仍能读（`["…"]` 全部视为已勾选，不会丢）。

新增「快捷回复管理」面板（`ui/widget/QuickReplyManagerSheet.java` +
`res/layout/dialog_quick_reply_manager.xml`），回复框里点「自定义」即开：

- 每行一个 **勾选框** —— 勾上的才显示在回复框的快捷条上（不想删也能临时收起）
- 每行 **编辑 / 删除**，删除带二次确认
- 底部：新增一条 / 全部勾选 / 全不勾选 / 恢复默认
- 编辑器里带「插入变量」按钮，点一下把占位符插进光标处

支持 11 个占位符（插入时按当前帖子展开）：

| 占位符 | 含义 |
| --- | --- |
| `{title}` | 当前帖子标题 |
| `{author}` | 楼主 |
| `{to}` | 正在回复的那个人的名字 |
| `{forum}` | 版块名 |
| `{floor}` | 正在回复的楼层 |
| `{url}` / `{tid}` | 帖子链接 / 帖子 ID |
| `{username}` / `{uid}` | 我自己 |
| `{date}` / `{time}` | 今天 / 现在 |

规则：只替换「带花括号」的写法，且**没给值的变量原样留着** ——
正文里恰好出现 `title` 这种普通单词不会被误替换，用户也能一眼看出哪个变量没取到值。

---

## ③ 发帖标签：能选、能搜、能自己加

### 为什么以前点了没反应

build96 只从**桌面版**发帖页里找 `name="tags"` 这样的 input，找不到就
把「标签」按钮禁掉、提示「该版块未启用标签功能」。但这个站点只有克米
mobile 模板（v5.10 已有实证结论），桌面 UA 拿回来的还是移动页 ——
于是这个判断几乎永远成立，用户永远选不了标签。

### 现在

- 字段名解析顺序：移动版发帖页 → 发帖页 → **Discuz 标准名 `tags` 兜底**。
  站点对不认识的 POST 字段是「忽略」而不是报错，所以兜底是安全的；
  反过来「选了标签一个都没带上」才是真的坏结果
- 标签选择器（`ui/tag/TagPickerSheet.java`）重做：
  - 顶部**真搜索框** —— 本地先筛（101 个标签瞬间出结果），回车再走站点搜索
    `misc.php?mod=tag&type=thread&name=XXX&mobile=2`
  - 「手动添加」—— 标签汇里没有的词也能自己填（Discuz 允许发帖时新建标签）
  - 已选标签显示成一排可点即删的胶囊，确认后按 `tags` 字段（逗号分隔）提交
  - 编辑帖子时自动带上该帖已有的标签
- 帖子详情页把站点的 `div.comiis_tags` 显示成一排 `#标签` 胶囊，
  点一下进标签页看同类帖子（就是用户给的那个 `misc.php?mod=tag&id=384…` 页面）

---

## ④ 快捷键补齐：`[qq]` `[hr]` `[media]` `[email]` `[url]`

`ui/widget/BBCodeEditor.java` 的工具栏现在分两段：

**弹窗填充型**（带参数、手写容易错，一律走弹窗）：
`链接 [url=地址]名字[/url]`、`QQ [qq]号[/qq]`、`邮箱 [email=地址]名称[/email]`、
`视频 [media=x,500,375]地址[/media]`、`图片 [img=宽,高]地址[/img]`

- 弹窗的第二个输入框会用**当前选中的文字**预填（选中「点这里」再点「链接」，
  名字那一栏已经是「点这里」）
- 确认后是**替换**选区，不再把整块内容套在选区外面
- 尺寸那类参数留空就用默认值（视频默认 `x,500,375`）

**直接插入型**（19 个）：加粗 / 斜体 / 下划线 / 删除线 / 字号 / 代码 / 引用 / 隐藏 /
免费 / 居中 / 居左右 / `[hr]` 分割线 / 列表 / 表格 / 背景色 …

`[code]` 的插入形式固定为

```
[code]
（光标在这里）
[/code]
```

---

## ⑤ `[code]` 用完之后颜色全变成字面量

### 根因（真机 + AOSP 源码定位）

`Html.fromHtml` 在收尾时会把结果里所有 ParagraphStyle span 重新
`setSpan(..., SPAN_PARAGRAPH)`，而 `SPAN_PARAGRAPH` 要求**起点落在段落边界**
（index 0，或前一个字符是 `\n`）。代码块的 TagHandler 给 `<pre>` 套的
`LineBackgroundSpan` 正好是 `ParagraphStyle` 的子接口 —— 于是只要 `<pre>`
前面还有同行内容（用户例子：`天天[color=#B30000]向上[/color]` 紧跟 `[code]`），
`setSpan` 就抛

```
RuntimeException: PARAGRAPH span must start at paragraph boundary (5 follows  )
```

整个 `Html.fromHtml` 失败 → 预览只能回退成原文 → 用户看到的满屏
`[color=#B30000]`，颜色全丢。

### 修法

还原代码块占位符前，先判断这段 HTML 渲染成文本后是否已经换行，没换就补一个 `<br>`：

```java
String lead = needsBreakBefore(result.substring(0, phM.start())) ? "<br>" : "";
```

判定规则（`BBCodeUtil.needsBreakBefore`）来自实测：

- 前缀为空、或以 `<br>` / `<hr>` / `</p>` / `</div>` / `</pre>` 等块级收尾标签结尾 → 安全
- 前缀是行内内容（文字、`</span>`、`&nbsp;`）→ 必须补 `<br>`
- **前缀以裸换行 `\n` 结尾也不行**：Html 会把源码里的 `\n` 变成「空格＋换行」，
  多出来的那个空格照样让段落边界判定失败 —— 这是第一版修法漏掉的地方

另外 `createTagHandler` 关标签时先复位 `inPre/preStart`，五处 `setSpan` 全部
try/catch（最坏只是少一层背景色，正文绝不会因为代码块整体渲染失败）。

---

## 构建

- versionCode **50** / versionName **5.15**
- 签名不变，可直接覆盖升级

## v5.14 (versionCode 49) — 评论区图片可点开看原图 · 代码块复制回归修复 · MCP 永久隧道

### 用户反馈（本轮）

1. 帖子内链接自动设置超链接可直接点击打开（跟随设置内部打开或者外部打开）
2. 可设置是否显示 FPS 在顶部
3. 切换账号后打开帖子还是在用原身份阅读帖子，没有更新身份
4. MCP 开启无效，要不是 429 要不是错误 1 直接结束。隧道不是有啥子临时隧道/永久隧道、
   协议/边缘 IP 版本这些配置吗
5. MT 论坛有一个标签功能，发帖可以快捷设置标签以及快速搜索标签
6. 顶部热门点开没有卡片详细信息显示，只有空白标题，完全看不到内容
7. 主页显示部分图片被使用长图导致显示异常
8. **评论区图片无法显示原图（像网页那样）** ← 新
9. **正文代码类型不能直接复制了** ← 新

1~7 已在 v5.12 / v5.13 修掉（切号身份同步、长图比例、正文缩略图、热门空白、
MCP 403 与错误信息、链接内外打开、FPS 开关、标签功能）。这一版修 8、9，
并把 4 的隧道配置补齐。

---

## ⑧ 评论区图片无法显示原图

### 根因：评论区的图根本没挂点击

评论区走的是 `tvContent` + ImageGetter 图文混排（`llReplyImages` 那条旧路
已经被隐藏）。而 `setImageClick()` **只服务旧方案** —— 它给
`llReplyImages` 里独立的 ImageView 挂点击，那条路现在
`setVisibility(GONE)`，一次都不会执行。

于是评论区的图能显示，但**点下去毫无反应**，看不到原图。主楼早就挂了
`attachInlineImageClicks`，评论区一直没有。

### 修法

把主楼那套触摸命中测试提取成公共工具
`util/InlineImageClicks.java`，评论区也挂上：

```java
InlineImageClicks.attach(tvContent, url -> {
    Intent it = new Intent(ctx, ImagePreviewActivity.class);
    it.putExtra("image_url", url);
    ctx.startActivity(it);
});
```

必须放在 `setupClickableLinks` **之后** —— 它会把 `textIsSelectable` 设为 true，
选择模式会吞掉 ClickableSpan 的点击，所以用触摸命中测试，不依赖 MovementMethod。

### 顺带修掉的一个真 bug

评论区的 `upgradeImageSources()` 原来是独立实现，只做
`ImageUrl.realUrl(img)` + `toAbsolute`，比主楼少了一层 `isUsableImageValue` 过滤。

站点 JS 拼出来的废值会漏过去。实测见过这种：

```html
<img src="' + IMGDIR + '/imageloading.gif" class="comiis_loading comiis_noloadimage">
```

挑中这种废值后 `img.attr("src", ...)` 被写坏，**图直接不显示**。

已统一委托 `util/PostImageHtml.upgradeThumbnailsToFull(html, base)`，
主楼和评论区走同一套真图挑选 + 值校验。

---

## ⑨ 正文代码不能直接复制 —— v5.11 的回归

v5.11 起主楼改用 WebView 原样渲染站点 HTML。当时为了避免「同一段代码出现两遍」，
在 `webRender` 分支里调了 `renderMainCodeBlocks(null)` 跳过代码块卡片 ——
**但 `CodeBlockView` 自带的「一键复制」按钮也跟着没了**。这就是回归来源。

### 修法

WebView 模式下给每个 `pre` / `.blk_code` / `.blockcode` / `.comiis_blockcode`
**注入一个绝对定位的「复制」按钮**：

```js
var sel='pre,.blk_code,.blockcode,.comiis_blockcode';
// 每个块右上角塞一个 div，onclick -> PostBody.copyText(box.innerText)
```

点击经 JS 桥回 native 写剪贴板（`ClipboardManager` + `ClipData`），
并 Toast「已复制」。用 `data-copybtn` 属性防重复注入。
JS 桥从原来的 2 个方法（`openImage` / `onReady`）扩到 3 个。

---

## ④ MCP 隧道配置补齐（临时/永久 + 协议 + 边缘 IP）

### 429 的真正来源

`CloudflareTunnelManager` 原来只有 **quick tunnel** 一条路。quick tunnel 是
**匿名注册**到 Cloudflare 的共享服务，**它有速率限制，重连几次就回 429** ——
这就是「开启无效，429」的主因。另外 `protocol: http2` 和
`edge-ip-version: "4"` 全是硬编码，IPv6 网络下连不上边缘节点也没法改。

### 修法

**新增永久隧道（named tunnel + Token）模式**：用 Cloudflare Dashboard 建好的
tunnel，`cloudflared tunnel run --token <TOKEN>` 直接认领，
**不走匿名注册，没有 429**，公网地址也是固定的（在 Dashboard 里配）。

`McpPreferences` 新增四项配置：

| 配置 | 取值 | 默认 |
|------|------|------|
| `tunnel_mode` | `quick` / `token` | `quick` |
| `tunnel_token` | Dashboard 给的 Token | 空 |
| `tunnel_protocol` | `http2` / `quic` | `http2` |
| `edge_ip_version` | `4` / `6` / `auto` | `4` |

`McpSettingsActivity` 加了对应的单选列表和 Token 输入框
（含焦点丢失与 apply 时双保险落盘）。IPv6 网络下用 4 连不上边缘节点时，
改成 `6` 或 `auto` 再试。

---

## 构建

- versionCode **49** / versionName **5.14**
- 签名 `9ce3aefa…15da`（不变，可覆盖升级）

## v5.13 (versionCode 48) — 标签功能（标签汇 / 快速搜索 / 发帖快捷标签）

> 上一版留的问题：MT 论坛有一个标签功能，发帖可以快捷设置标签以及快速搜索标签。

这一版不再问，直接去站点把标签系统摸清楚实现了。

---

## 一、先把站点标签系统摸清（全部实测）

| 用途 | URL | 实测结果 |
|------|-----|---------|
| 标签汇 | `misc.php?mod=tag&mobile=2` | 200 / 32300B，**101 个标签链接** |
| 标签项结构 | `<a href="misc.php?mod=tag&id=413&type=thread&mobile=2" title="剧情" class="color1">剧情</a>` | id 从链接拿，名字从 title 拿 |
| 按名搜标签 | `misc.php?mod=tag&name=剧情&type=thread&mobile=2` | **GET 可行**，返回「标签 : 剧情」页 |
| 标签详情 | `misc.php?mod=tag&id=413&type=thread&mobile=2` | 帖子卡片复用列表页的 `mmlist_li_box` 结构 |
| 帖子页标签容器 | `div.comiis_tags pb10 b_b cl` | 该帖没打标签时是空 div |
| 站点自己的搜索框 | `<form method="post" action="misc.php?mod=tag&type=thread">` + `<input name="name">` + `<input type="hidden" name="searchsubmit" value="yes">` | 就是 `name` 字段 |

标签汇里前几个：剧情(id=413)、薅羊毛(412)、奋斗(411)…

> 注：标签「剧情」下实测「没有相关内容」（站点自己显示这句），
> 说明该标签暂无关联帖子，不是解析问题。

---

## 二、做了什么

### ① 标签页 `TagActivity`

一个页面承担三件事，搜索框常驻顶部：

- **标签汇**：进页面默认加载全部 101 个标签
- **快速搜索标签**：输入关键字 → `misc.php?mod=tag&name=XXX&type=thread&mobile=2`
  （回车或点「搜索」即搜，和站点自己的 `tagssbox` 行为一致）
- **标签下帖子列表**：点任意标签 → `misc.php?mod=tag&id=XXX&type=thread&mobile=2`，
  帖子卡片直接复用 `ThreadAdapter`，点帖子照常进详情

从「标签下帖子」按返回键是回到标签列表，不是直接退出。

抽屉新增「**标签**」入口。

### ② 发帖「快捷标签」

`post_activity.xml` 匿名那一行后面加了「标签」按钮，点开是 `TagPickerSheet`
（BottomSheet 多选）：标签来源就是站点标签汇，**不用手打** ——
站点标签是 `misc.php?mod=tag&id=X&type=thread` 的固定集合，手打拼错站点直接不认。

### ③ 标签字段名：动态解析，绝不猜

这是这一版最关键的设计。`PostActivity` 本来就要抓**桌面版发帖页**取
`formhash` 和 `hash` 令牌（`getDesktop`），我在同一个地方加了：

```java
currentTagField = ForumParser.parseTagFieldName(desktopHtml);
```

`parseTagFieldName` 按优先级找：`name="tags"` → name 含 `tag` 的 input →
id 含 `tag` 的 input。**一个都找不到就返回 null。**

提交时：

```java
if (currentTagField != null && !currentTags.trim().isEmpty()) {
    params.put(currentTagField, currentTags.trim());
}
```

两个条件缺一个都不发这个字段。**发帖是核心功能，绝不允许因为标签拼错字段名
而整个发帖失败。** 站点这个版块没开标签时，点「标签」按钮会直接提示
「该版块未启用标签功能」，不让用户白选一堆。

---

## 构建

- versionCode **48** / versionName **5.13**
- 签名 `9ce3aefa…15da`（不变，可覆盖升级）

## v5.12 (versionCode 47) — 切号身份同步 · 图片比例修复 · 8 项反馈逐条处理

### 用户反馈（8 项）

1. 帖子内链接自动设置超链接可直接点击打开（跟随设置内部打开或者外部打开）
2. 可设置是否显示 FPS 在顶部
3. 切换账号后打开帖子还是在用原身份阅读帖子，没有更新身份
4. MCP 开启无效，要不是 429 要不是错误 1 直接结束
5. MT 论坛有一个标签功能，发帖可以快捷设置标签以及快速搜索标签
6. 顶部热门点开没有卡片详细信息显示，只有空白标题，完全看不到内容
7. 主页显示部分图片被使用长图导致显示异常
8. 主帖子正文没有正确显示图片（显示的是缩略图，不能像正常帖子那样正常显示）

---

## 一、逐项定位与修复

### ③ 切号后还是原身份 —— UserSessionManager 没跟着换

`AccountManager.switchTo()` 只写了自己的 `KEY_ACTIVE` 和 cookie 快照，
**没有同步 `UserSessionManager` 里的 session uid**。而
`ThreadDetailActivity.onResume()` 判「账号有没有变」用的是
`likeFavScope() -> UserSessionManager.getUid()` —— 它一直返回上一个账号的
uid，于是「没变」→ 不刷新，界面还挂着旧身份。点赞/收藏/关注态也按这个 uid
分桶，不同步就会串号。

**修法**：`switchTo()` 里补一段 `UserSessionManager.saveLoginInfo()`，
把 uid / username / avatar / level 一起写过去。

### ⑦ 主页长图显示异常 —— 封面图固定 160dp + centerCrop

`item_thread.xml` 的 `iv_thumbnail` 是 `layout_height="160dp"` +
`scaleType="centerCrop"`：竖长图被裁成中间一条，宽图被拉变形。

另外首页多图的 `GridLayout` 用 `params.width = 0` + `columnSpec` 权重均分列，
**权重测量的结果不会回灌给 ImageView 的 `onMeasure`** —— `adjustViewBounds`
拿不到最终宽度，算出来的高度是错的，竖长图照样被压扁。

**修法**：
- `iv_thumbnail` 改 `wrap_content` + `adjustViewBounds` + `fitCenter` +
  `maxHeight="320dp"`（兜住超长图，别把卡片撑到一屏）
- `Glide.centerCrop()` 改 `fitCenter()`
- `GridLayout` 按容器实际宽度**显式算死**每张图的列宽，不再依赖权重

### ⑧ 正文图显示成缩略图 —— CSS 没覆盖站点写死的 width

v5.11 的 `WEB_CONTENT_CSS` 只限了 `max-width:100%`，但站点 mobile 模板
（以及作者粘贴进来的内容）经常给 img 写死 `style="width:120px"` 或用 class
控成缩略图尺寸。**`max-width` 和 `width` 是两个属性，只限前者那张 `width`
照样生效**，图就一直是缩略图大小。

**修法**：
```css
img{width:auto !important; min-width:0 !important; max-width:100% !important;
    height:auto !important; max-height:none !important; ...}
```
把 width / min-width / height / max-height 全部打回 auto，再由
`max-width:100%` 撑到容器宽，高度按图片真实比例走。

同时把图片点击从 `e.onclick = fn` 改成
`addEventListener('click', fn, true)`（**捕获阶段**）：站点自己的脚本后面会给
img 挂委托监听，直接覆盖掉 `e.onclick`，图就点不动了。捕获阶段先跑，
`preventDefault + stopPropagation` 之后站点再也收不到。

### ⑥ 顶部热门点开是空白 —— 站点 mobile 端没有热帖列表

`forum.php?mod=guide&view=hot&mobile=2` 返回的**不是帖子列表，而是导读首页**
（每日签到 / 精华推荐 / 积分商城 + 一条滚动文字栏）。实测 48887 字节里只有
15 个 `thread-` 链接，且全在 `comiis_mh_kxtxt` 的 `<li><a>` 里，
**没有 `mmlist_li_box` / `comiis_pyqlist` 容器**，解析器 0 条 → 整页空白。

试过并确认无效的替代方案：去掉 `mobile=2`、加 `&type=hot`、
`forum.php?mod=forumdisplay&filter=heat&orderby=lastpost`（21651 字节、0 帖子）。

**修法**：移除「热门」chip。留着它只会给用户一个空页面。
保留「新帖 / 最新回复 / 精华」三个都有真实列表的视图。

### ④ MCP 开启无效 —— 隧道域名被自己 403 + 错误信息不可读

两个问题叠在一起：

1. `McpServer.isAllowedName()` 只放行 `localhost` / 开了 `lan` /
   `CloudflareTunnelManager.publicUrl()` 当前值。而**隧道注册是异步的**
   （`start()` 里另起线程），`publicUrl` 在注册完成前一直是空串 ——
   于是「开了 MCP + 隧道」的请求照样被 403 打回来。
   **修法**：按域名模式放行 `*.trycloudflare.com` / `*.cfargotunnel.com` /
   `*.cloudflareaccess.com`，不依赖注册时序。
2. `ServerSocket` 绑定失败时 `error = e.getMessage()`，而 `BindException` 的
   message 常常是 `bind failed: EADDRINUSE` 甚至空/数字 —— 用户看到的就是
   一句没头没尾的「错误1」。
   **修法**：捕获 `BindException` 换成「端口 X 已被占用，请在 MCP 设置里换一个」；
   启动失败时换一条带可读原因的**错误通知**，并 `stopForeground(true)` 不再挂前台。

### ① 帖子内链接可点 + 内外打开设置

v5.11 的 WebView 里链接已经是可点的（`WebViewClient` 自动处理 `<a>`），
这一版把它接上设置：抽屉新增「**正文链接打开方式**」开关，
`post_link_open` = `internal`(默认) / `external`。

- `internal`：站内 `thread-X-Y-Z.html` 走应用内详情页，其余（站外、站内非帖子页）
  交给系统浏览器 —— 避免正文 WebView 套娃
- `external`：一律交给系统浏览器

### ② 顶部 FPS 显示

新增 `util/FpsOverlay.java`：用 `Choreographer` 统计每 500ms 的帧回调数求平均
帧率，通过给 Activity 的 `DecorView` 加子 View 实现 —— **不占用
WindowManager 类型窗口的权限**，也不会跨 Activity 残留
（`onViewDetachedFromWindow` 时 `removeFrameCallback`）。

在 `MyApplication` 的 `ActivityLifecycleCallbacks.onActivityResumed` 里统一挂载，
抽屉开关「**顶部显示 FPS**」默认关。

---

## 二、⑤ 标签功能：这一版没做，需要你确认

你说「MT 论坛有一个标签功能，发帖可以快捷设置标签以及快速搜索标签」。

我没有实现它，原因是**抓不到站点的真实标签接口**。需要你补充：

1. 站点发帖页的标签输入框，提交时是哪个字段？（`tags` / `tag` / `k_tags`…）
2. 「快速搜索标签」是站点自带的联想接口，还是只是一个输入框？
3. 你希望「快速搜索」搜的是**标签库**还是**带该标签的帖子**？

这三个问题定了我再动手，避免又做一遍解析后位置不对的白工。

---

## 构建

- versionCode **47** / versionName **5.12**
- 单测 91/0/0
- 签名 `9ce3aefa…15da`（不变，可覆盖升级）

## v5.11 (versionCode 46) — 原帖内容直接用 WebView 渲染 · 逐功能审查

### 用户反馈

> 现在你转到审查的角度，挨个审查功能是否有缺陷，是否有漏洞？将其补齐修复。
> 已知问题进入帖子，解析后没有将图片正确的还原到原处，或者说，应该直接套用
> 网页原帖内容不要解析

两件事：① **图片不要再解析重组，直接套用网页原帖内容**；② 转到审查角度，
逐个功能找缺陷、找漏洞，补齐。

---

## 一、进帖图片：不再解析，直接套原帖

### 根因

之前所有版本都在做同一件事：把帖子 HTML 拆开，图片 URL 抽出来，正文转成
TextView 能显示的 Spanned。**站点模板里「插进正文的图」的位置信息只存在于
原始 HTML 中，一旦拆成纯文本 + URL 列表就必然丢失。**

站点（克米 mobile 模板）自己的选择器说得很清楚：

```
comiis_wx_img_obj = $(".comiis_flxx_style img[...], .comiis_messages img[...]")
```

- `.comiis_messages img` = 作者插进正文的图，位置就是作者插入的位置
- `.comiis_flxx_style img` = 只上传、没插进正文的图，站点自己也排在正文之后

所以**登录态下按原样渲染，图天然落在原位**。是我们自己解析才把它挪走了。

### 修法

`ThreadDetailActivity` 新增 `web_content`（WebView），正文容器 `frame_content`
里和 `tv_content` 并列。`bindData` 里默认走 WebView：

```java
if (webRender) {
    renderContentInWeb(strArrSplitEditFooter[0]);
}
```

`renderContentInWeb` 做的事：

1. **剥脚本**：`<script>` / `<iframe>` / `on*=` 全删，`javascript:` 换成 `#`。
   论坛正文是用户内容，不能让它执行脚本。
2. **套一层自己的样式**：克米的 class 在应用里没有对应 CSS，直接裸渲染会散架。
   用 `WEB_CONTENT_CSS` 给出 `img{max-width:100%;height:auto}`、代码块、
   引用块、表格的基本排版。
3. **缩略图升级 + 兜底图**：`upgradeThumbnailsToFull` 把懒加载属性换成真实
   CDN 地址；`injectFallbackImagesInline` 在正文无 `<img>` 时补列表页的图
   （游客态站点不下发附件图，这一条保留）。
4. **图片点击**：JS 注入 `onclick` → `PostBody.openImage(url)` → 全屏预览。
   JS 桥只暴露 `openImage` / `onReady` 两个方法，且只接受字符串。
5. **高度自适应**：WebView 嵌在 NestedScrollView 里，必须按内容实际高度撑开。
   `onPageFinished` 量一次，**图片全部 `load`/`error` 完再量一次**——
   图片没下载完时 `scrollHeight` 是按「图还没占位」算的，只量一次必然截断或留白。
6. **链接**：站内 `thread-\d+-\d+-\d+\.html` 走应用内跳转，其余交给系统浏览器，
   一律不让正文 WebView 自己导航（避免套娃）。
7. **`onDestroy` 显式 `destroy()`**：WebView 持有 Activity Context，详情页来回
   进出不释放就是一条内存泄漏链。

配套新增抽屉开关「**按原帖排版渲染**」（默认开，`post_web_render`），
关掉则退回旧的 TextView 解析渲染。`onResume` 检测开关变动即本地重渲。

---

## 二、审查出来的缺陷与漏洞（已修）

| # | 问题 | 严重度 | 修法 |
|---|------|--------|------|
| 1 | **原帖渲染下代码块渲染两遍** —— `extractCodeBlocks` 抽出来做成卡片，原始 HTML 里那份还在 WebView 里 | 高（直接可见） | `webRender` 时 `renderMainCodeBlocks(null)` 跳过 |
| 2 | **`renderContentInWeb` 早退时页面空白** —— `webContent == null` 直接 return，正文一个字都不显示 | 高 | 早退分支回落 TextView 渲染 |
| 3 | **主线程同步落盘**：`BlacklistManager.addLocal` 从「拉黑作者」对话框确定按钮调用，`.commit()` 在主线程 | 中（ANR 边缘） | 改 `.apply()` |
| 4 | **主线程同步落盘**：`DraftManager.saveDraft` 调用链是 `PostActivity.onPause → saveDraftNow`，`.commit()` 在主线程 | 中 | 改 `.apply()` |
| 5 | **图片 URL 转义不完整**：只做 `replace("&","&amp;")`，源串已是 `&amp;` 会被二次转义成 `&amp;amp;`，CDN 的 `?aid=X&size=Y&key=Z` 直接取不到图；且不转义 `"`，url 含引号会截断属性 | 中 | 新增 `escapeHtmlAttr`，实体感知 + 全字符转义 |
| 6 | **WebView 高度只量一次**，图片未加载完 → 内容截断或留白 | 中 | JS 等所有图片 `load`/`error` 后回调 `onReady` 重量（带 3s 兜底） |

### 审查过、确认没问题的部分

- **AndroidManifest**：除 `MainActivity`（LAUNCHER）外全部 `exported="false"`，
  无导出 Receiver/Provider/Service，无自定义 deeplink scheme。
- **Token 泄漏**：全仓 grep `ghp_…` 无命中，`.git/config` 不含 token，
  push 走命令行参数传入。
- **切号同步**：`AccountManager.switchTo` 清 HTTP cookie、`clearPendingCache`、
  图片磁盘缓存、`ListImageRegistry`，并 `SWITCH_EPOCH.incrementAndGet()`。
- **登录态**：`HttpClient` 与 `CookieManager` 双向同步，切号后 `restoreCookieStore`
  + `syncToCookieManager`。
- **图片地址**：`ForumParser` 的 `attachImageUrls` 取的是 `comiis_loadimages` /
  `file` 等懒加载真图地址，全屏翻页不是缩略图。
- **`injectFallbackImagesInline` 幂等**：正文已有 `<img>` 就不注入，
  WebView 里再调一次不会重复。

### 遗留（非缺陷，记录在案）

- `usesCleartextTraffic="true"`。全仓无 `http://` 硬编码基线（都是校验判断），
  站点/CDN 实测全走 https；暂不动，避免误伤某条图片跳转。
- `BlacklistManager` 服务端黑名单缓存、`McpPreferences` 退出登录清 token 仍用
  `.commit()` —— 前者在网络回调线程，后者要求落盘后才算退出，有意保留。

---

## 构建

- versionCode **46** / versionName **5.11**
- 单测 **91 通过 / 0 失败 / 0 错误**
- 签名 `9ce3aefa…15da`（与 v4.8 起一致，可覆盖升级）
- APK 3925169 字节

## v5.10 (versionCode 45) — 开关立刻生效 · 站点无 PC 模板的实证结论

### 用户反馈

> 还是不能显示到帖子的原本位置，底部确实是都能兜底了，开关也是没有作用

三条信息：① 原位还是没做到；② 底部兜底是好的；③ **开关拨了没反应**。

### 一、开关「没有作用」：不是开关坏了，是要下次进帖才生效

接线本身是好的（`setChecked` 读当前值 → `setOnCheckedChangeListener` 写偏好 →
`bindSwitchRow` 整行可点，行也没有被任何代码隐藏，全仓只有一处绑定）。
问题是 `ThreadDetailActivity` 只在 `bindData` 里读一次这个偏好——用户在抽屉里
拨开关时，面前那个帖子页已经渲染完了，**什么都不重渲**，看起来就是「没有作用」。

**修法**：新增 `lastImagesInline` 字段记录本次渲染用的排布方式，
`onResume()` 发现变了就用**已经拿到的 `postDetail` 本地重渲**（`bindData(postDetail, false)`），
不重新请求网络。现在拨完开关退回来，页面当场就变。

另外删掉 `MainActivity` 里一句 v5.5 留下的自相矛盾的注释：
「正文图片现在固定以原图在原位展示，旧版底部图廊开关不再适用」——
开关明明一直在，这句话会把人带偏。

### 二、为什么「原位」还是做不到：站点根本没有 PC 模板

这一版把可能性都试过了，结论是硬限制：

| 试的 URL | 结果 |
|---|---|
| `mod=viewthread&tid=X&mobile=2` | 克米 mobile 模板，游客无附件图 |
| `mod=viewthread&tid=X`（去掉 mobile=2） | **还是同一个克米 mobile 模板**（`comiis_app`），没有 PC 模板 |
| `?archiver=1` | SSL EOF |
| `archiver/?tid-X.html` | 1604 字节，空壳 |
| `api/mobile/index.php?module=viewthread&tid=X` | 33124 字节 WAP 页，无附件 |
| `forum.php?mod=post&action=reply...` 预载 | 需登录 |

PC 模板本来是有希望的一条路：Discuz 的 PC 模板把 `[attachimg]aid[/attachimg]`
渲染成**正文内联的 `<img>`**，位置就是作者插入的位置；而克米 mobile 模板把附件
整体挪到正文之后的块里（`.comiis_flxx_style`）。**但这个站强制走 mobile 模板，
PC 模板这条路不存在。**

站点自己的懒加载选择器也印证了 mobile 模板的行为：

```js
comiis_wx_img_obj = $(".comiis_flxx_style img[...], .comiis_messages img[...]")
```

- `.comiis_messages img` —— 作者**插进正文里**的图（有权限时内联，位置正确）
- `.comiis_flxx_style img` —— 只上传、没插进正文的图（排在正文之后的附件区）

**所以：只有 app 处于登录态、且站点愿意把 `.comiis_messages` 里的内联图下发时，
图才会落在原本的位置。** 游客态服务端连 `.comiis_messages` 里的 `<img>` 都不发
（tid=173937 实测：`comiis_loadimages="` 0 次、`aid=` 1 次且在 JS 里），
只能像现在这样兜底。

### 三、其它

- `versionCode` 44 → **45**，`versionName` 5.9 → **5.10**。

---

## v5.9 (versionCode 44) — 原位显示不再被设备上的旧偏好覆盖

### 用户反馈（与 v5.8 相同，原话重复）

> 显示确实是成功了，但是图片不在正文排版处正常显示而是全部解析到正文底部

v5.8 已经把默认值改成原位显示了，但反馈没变。这一版修一个**能让 v5.8 的改动
完全失效**的隐患。

### 一、`getBoolean` 的默认值不是「默认值」

```java
sp.getBoolean("post_images_inline", true)   // 默认 true
```

这个 `true` **只在这条偏好从来没被写过的时候生效**。而：

- v5.5 / v5.6 / v5.7 三版的默认值是 `false`
- v5.7 又把抽屉开关接上了，误触一下 `drawer_images_inline_row` 就会把
  `false` 落盘（`bindSwitchRow` 整行可点）

于是设备上很可能已经躺着一条 `post_images_inline=false`。v5.8 把代码里的
默认值改成 `true`，对那些设备**一行都不会生效**——用户照旧看到帖子底部
那个图廊。这就是「改了两版还是老样子」的原因。

### 二、修法：加一条「用户是否显式设置过」的标记

```java
private static final String KEY_IMAGES_INLINE_SET = "post_images_inline_user_set";

public static boolean isImagesInline(Context c) {
    SharedPreferences sp = sp(c);
    if (!sp.getBoolean(KEY_IMAGES_INLINE_SET, false)) return true;  // 没设置过 -> 新默认值
    return sp.getBoolean(KEY_IMAGES_INLINE, true);                  // 设置过 -> 听用户的
}

public static void setImagesInline(Context c, boolean v) {
    sp(c).edit().putBoolean(KEY_IMAGES_INLINE, v)
            .putBoolean(KEY_IMAGES_INLINE_SET, true).apply();
}
```

现在**只有用户本人在抽屉里拨过开关**才会用他选的值；否则一律原位显示。
旧设备上那条残留的 `false` 会被这条标记无条件盖掉。

### 三、本轮实证过的两个结论（排除法，供后续参考）

1. **站点把附件放在正文之后是设计如此**：游客态 tid=173937 详情页里，
   `div.comiis_a.comiis_message_table`（正文容器）在 15004 字符处结束，
   登录墙块 `div.comiis_noatt_ico` + 「本帖子中包含更多精彩资源」+
   「您需要 登录 才可以查看」在 16965 字符处，**在正文容器外面、之后**。
   所以兜底图追加在正文末尾，与站点自己的排版一致。
2. **正文里没有位置标记可用**：`[attachimg]` 在游客页只出现 1 次，
   且在上传器的 JS（`comiis_upload_success`）里，不是帖子内容；
   PC 模板对游客同样一张附件图都不下发（`mod=image` 4 次全在 JS 选择器串里）。
   所以游客态拿不到「图原本插在哪」这个信息，只能按站点自己的位置排在文末。

### 四、其它

- `versionCode` 43 → **44**，`versionName` 5.8 → **5.9**。

---

## v5.8 (versionCode 43) — 图片回到正文排版处显示（原位显示改为默认）

### 用户反馈

> 显示确实是成功了，但是图片不在正文排版处正常显示而是全部解析到正文底部

v5.7 把图接出来了，但走的是 v5.5 定下的「抽离 + 帖子底部横滑图廊」——
`isImagesInline()` 默认 `false`。用户要的是图片跟在正文排版里。

### 一、默认值改回原位显示

`UiSettings.isImagesInline()` 默认 `false` → **`true`**。

抽屉里「正文图片原位显示」那一项 v5.7 已经真的接上了，想用底部图廊仍然可以
手动关掉，两条路都在。

顺带修正 build87 那段只对了一半的判断：当时认为「原位这条路实际不出图」，
真正的原因是**站点对游客在详情页根本不下发附件 `<img>`**，跟选原位还是图廊
无关。兜底链路（build87）补上之后，两条路就都有图了。

### 二、游客态：把兜底图补写进正文，而不是塞进底部图廊

光改默认值对游客不够 —— 游客详情页正文里一张 `<img>` 都没有，原位模式下
`Html.fromHtml` 无从渲染，最后又只能退化成底部图廊。

新增 `injectFallbackImagesInline(String html)`：

- 仅当正文里**一张图都没有**时触发（登录态正常下发配图，不重复插）
- 把 `listImageFallback` 里的真实 CDN 图以 `<img>` 形式追加进正文 HTML，
  跟着同一个 TextView 排版、同样满宽
- 位置只能追加在正文末尾：列表页给得到图的地址，给不到它在原帖里的插入位置。
  但在正文里满宽排版，比抽到帖子底部那张独立卡片贴近「正文排版处」得多

同时给底部图廊的兜底加了 `!imagesInline` 守卫 —— 原位模式下兜底图已经进正文了，
再进图廊就是同一张图出现两次。

### 三、图片清晰度：列表页地址拿到的是原图

本来担心列表页的 `size=500x480` 是缩略图、满宽显示会糊，实测打消了这个顾虑：

| size 参数 | 结果 |
|---|---|
| `500x480` | **200 / 69303 字节 / 1080×690 原图** |
| `500x99999` | 跳 `none.gif`（43 字节） |
| `99999x99999` | 跳 `none.gif` |
| `1080x99999` | 跳 `none.gif` |

`size` 与 `key` 是绑定的，改大就退化，所以地址**原样用**；而 `500x480` 这一档
CDN 实际跳转到 OSS 上的原图（1080×690），满宽显示不糊。

### 四、其它

- `versionCode` 42 → **43**，`versionName` 5.7 → **5.8**。

---

## v5.7 (versionCode 42) — 修「原位显示」开关是死的 · 兜底图可翻页 · 清 5 个死文件

### 一、「正文图片原位显示」开关根本不存在（文档承诺了却没实现）

v5.5 把图廊开关改成真实读偏好，CHANGELOG 里写「设置里想用原位仍然可以手动打开」。
但 `MainActivity` 里同时留着一句：

```java
View imagesInlineRow = findViewById(R.id.drawer_images_inline_row);
if (imagesInlineRow != null) imagesInlineRow.setVisibility(View.GONE);
```

抽屉里那一行被整行藏了，开关还被设了 `clickable="false"`。**文档承诺的功能实际不存在**，
用户在抽屉里根本找不到这一项；`UiSettings.setImagesInline()` 也就成了全仓唯一
没有调用方的 setter——死 UI + 死 API 一对。

**修法**：删掉隐藏那行，按 `swHiddenInline` 的同一套模式把开关接上
（`setChecked` 读当前值 → `setOnCheckedChangeListener` 写偏好 → `bindSwitchRow`
整行可点）。现在这一项真的能用了。

顺带确认切到「原位显示」不会把图搞没：
`galleryUrls` 只在 `!imagesInline` 时填充，而 build87 的兜底条件是
`galleryUrls.isEmpty() && !listImageFallback.isEmpty()`。
登录态正文有图时 `arrayList` 非空、兜底不触发，不会一图两显；
游客态正文无图时兜底触发，图廊照旧出图。两条路都验过。

### 二、兜底图点开不能翻页

`currentImageList`（喂全屏翻页的图组）是在兜底合并**之前**赋值的：

```java
this.currentImageList = new ArrayList<>(arrayList);   // 只有正文解析出来的图
```

游客态 `arrayList` 是空的，于是点图廊里第 2 张时 `openImagePreview` 走的是
`!list.contains(url)` 分支，只把单张 url 传进去——左右翻页翻不动。

**修法**：`currentImageList` 为空时把兜底图补进去。

### 三、清理（5 个死文件 + 1 个死重载）

系统性扫了一遍「布局里有 id 但代码从不引用」：508 个 id 里 41 个真没引用，
逐个核完大部分是 `<include>` 外层或 TextInputLayout 包装的 id，无害；
真正成死链的是置顶帖那一套：

| 文件 | 判定 |
|---|---|
| `adapter/StickyThreadAdapter.java` | 无任何使用方 |
| `res/layout/layout_sticky_threads.xml` | 没有任何布局 include 它 |
| `res/layout/item_sticky_thread.xml` | 只被上面那个死Adapter inflate |
| `network/CookieSync.java` | 只有自己的测试在引用，生产代码零调用 |
| `app/src/test/.../CookieSyncTest.java` | 随上一条一起删 |

保留：`Thread.isSticky()/setSticky()` 和 `ForumParser.isStickyThread()`——
`ThreadAdapter` 第 273 行用它们显示置顶徽章，是活代码。
（`HttpClient` 里的 `android.webkit.CookieSyncManager` 是框架类，与删掉的
`network.CookieSync` 同名不同物，不受影响。）

另外删掉 `NavigationHelper` 里那个 4 参私有重载——build87 加了第 5 个参数后
它就只剩转发，没有任何调用方。

### 四、其它

- `versionCode` 41 → **42**，`versionName` 5.6 → **5.7**。
- 单测 **91 / 0 / 0**（删 `CookieSyncTest` 少 9 条，其余全绿）。

---

## v5.6 (versionCode 41) — 进帖图片兜底做到全链路 · 切号后详情页全同步

### 一、进帖不显示图：v5.5 的兜底只覆盖了「列表页直达」这一条路

v5.5 加了列表页图片兜底，用户实测**仍然不显示**。这次不再猜，直接抓包实证：

对 tid=173937（列表页明明有 2 张缩略图）拉取游客态详情页，67943 字符里：

| 探针 | 结果 |
|---|---|
| `comiis_loadimages="` | **0 次** |
| `aid=` | 1 次（且是 JS 里的懒加载选择器字符串，不是 `<img>`） |
| 正文容器 `div.comiis_a.comiis_message_table` | 只有文字和头像，**一条配图标记都没有** |
| 全部 `<img>` | 51 个头像，0 张附件图 |

**结论**：站点对**游客**在详情页根本不下发附件 `<img>`。v5.3/v5.4/v5.5 三版
改选择器、改懒加载属性、改图廊开关全是空转 —— 详情页压根没有图可解析。
**列表页是唯一能拿到真实 CDN 附件地址的地方**，兜底必须做成全链路。

同时复核了 `populateThreadImages` 的容器选择器
`.comiis_pyqlist_imgs img, .comiis_pyqlist_img img`：列表卡片真实 class 是
`comiis_pyqlist_imgs comiis_pyqlist_img2p` / `comiis_pyqlist_img`，**选择器命中正常**，
列表解析没问题。此前用 Python 正则端口得出的「大面积不匹配」是正则的局限，作废。

v5.5 兜底没生效的两个真实原因：

1. **只有「列表页直达」才带图**。`HomeFragment` / `ForumDetailActivity` 传的是
   `openThread(context, thread)`，但 `MainActivity`、`ReplyAdapter`、
   `ThreadDetailActivity`、`LogCenterActivity` 四处走的是 `openThread(tid)`，
   图片一个字节都没带过去。
2. **兜底写在 `if (!TextUtils.isEmpty(contentHtml))` 里面**。游客态正文解析
   结果为空时走的是 else 分支，那里**无条件** `cardImageGallery.setVisibility(GONE)`，
   兜底代码根本执行不到。

**修法**：

- 新增 `util/ListImageRegistry.java`：按 tid 登记列表页真实 CDN 配图地址的
  有界 LRU 表（上限 200，只存字符串不存 Bitmap）。
  `ForumParser.populateThreadImages` 解析到图就登记，
  **任意入口**（首页流、版块页、搜索、日志中心、引用回复、相关帖子）都能取回，
  不再依赖 Intent 有没有传 extra。
- `ThreadDetailActivity.onCreate`：Intent extra 为空时回落到登记表按 tid 查。
- 把图廊渲染从 `bindData` 抽成 `renderImageGallery(List<String>)`，
  **正文为空的 else 分支也调用它** —— 这是最后一环，以前这里直接隐藏图廊。
  正文为空时提示「本帖子资源需登录后查看，以下为列表页图片」。
- 切号时 `AccountManager.switchTo` 顺带清登记表，避免跨账号读到旧列表的图。

### 二、切号后详情页还是原账号信息

排查确认 `FollowStateManager` 早就按 uid 隔离（`accountKey` = 当前 uid），不是问题。
真正没隔离的是**点赞 / 收藏状态**：

`ThreadDetailActivity` 的 `PREF_LIKE_FAV` + `KEY_LIKED_PREFIX`/`KEY_FAVORITED_PREFIX`
**key 只拼 tid、不带账号**，切号后新账号一进帖子就看到旧账号点过的赞、收藏过的帖。

另外详情页**没有 `onResume`**：用户在账号管理里换了账号再退回已打开的帖子页，
界面一直挂着旧账号的点赞/收藏/关注态。

**修法**：

- 点赞/收藏 key 加上账号作用域 `likeFavScope()`（当前 uid，游客态落 `guest`）。
  不走 `loginUid()`——那个字段是懒初始化的，这里要的是「此刻」的账号。
- 新增 `onResume()`：发现账号变了就 `refreshPostDetail()` 整页重拉，
  配合 `bindData` 里记录的 `lastRenderUid`。

### 三、其它

- `versionCode` 40 → **41**，`versionName` 5.5 → **5.6**。
- 单测 **100 / 0 / 0**；release APK **3920650 B**，807 files。
- 签名钥匙不变（SHA-256 `9ce3aefa…15da`），可直接覆盖升级。

---

## v5.5 (versionCode 40) — 图片恢复 v2.2 的图廊显示 · 验证彻底不弹界面

### 一、进帖不显示图：build68 的「原位显示」把图廊一起关掉了

用户提供了 v2.2 源码，逐行比对后定位。两版的渲染流程差异：

| | v2.2（用户实测可用） | v5.4（坏了） |
|---|---|---|
| 抽离图片 | `extractAndSeparateImages(...)` **无条件调用** | 仅 `!imagesInline` 时才调 |
| 底部图廊 | `if (!arrayList.isEmpty())` **无条件显示** | 仅 `!imagesInline` 时才喂 `galleryUrls` |
| `isImagesInline` | 无此概念 | 硬编码 `return true` |

build68 引入「图片原位显示」时，除了把 `<img>` 留在正文里交给
`createInlineImageGetter` 图文混排，还把图廊的填充条件改成了 `!imagesInline`。
而 `isImagesInline()` 又被硬编码成 `return true`，于是**两条路同时断了**：
原位那条实际不出图，底部图廊又被一起禁用。

用户说的「可能与那个图片在原处显示有关」指的正是这里。

**修法**：

- `UiSettings.isImagesInline()` 改为真实读偏好，**默认 `false`**，即恢复 v2.2 的
  「抽离 + 底部横滑图廊」。设置里想用原位仍然可以手动打开。
- 附带确认：`UrlDrawable`、图廊渲染代码、`extractAndSeparateImages`、
  `pickRealImageUrl` 在 v2.2 与 v5.4 之间**完全一致**，没有其它损耗点。

### 二、列表页的图带进详情页兜底

实测 tid=173937：列表卡片有 2 张 `mod=image&aid=377307/377306`，帖子页（游客）
`mod=image` **0 次** —— 站点把附件换成了「您需要登录才可以查看」。

现在 `NavigationHelper.openThread(context, thread)` 会把列表已经拿到的真实 CDN 图
通过 Intent 带进 `ThreadDetailActivity`；帖子页解析不到任何图时，用它们填充图廊。
列表页能显示、进帖却什么都没有，是最扎眼的一种「图片不显示」。

### 三、验证不再跳浏览器：按 mtluntan 的窄判据，并且不再阻塞线程

v5.4 只把 `onChallengeDetected` 留作「解算值都算不出」时的兜底，但只要宽泛的
**结构判定**命中而本地解算器不认这个形态，就还是会弹。结构判定
（`InterstitialDetector.looksLikeInterstitialPage`：有 `<html>`、无论坛骨架、
可见正文 <256 字符即算拦截页）命中面远大于解算器能处理的形状 —— WAF 拦截页、
登录页、错误桩都会命中，`solve()` 返回 null 就弹 WebView。

更糟的是兜底路径 `awaitClearance(150_000)` 会把 **OkHttp 线程挂住最多 150 秒**
等用户手工验证，界面直接卡死。

参照项目 `yz8023/mtluntan` 的 `WafInterceptor` 判据是**窄**的：只有 body 同时含
`var arg1=` 和 `acw_sc__v2` 才当挑战，其余一律原样放行，**从不弹界面**。
本次按这个思路整改：

- **删掉自动弹 WebView 的整条兜底路径**（含 `onChallengeDetected` 调用与
  `awaitClearance(150_000)` 的阻塞等待）。本地解算现在是唯一自动路径，
  解算不了只记日志、原样放行。
- 确需人工验证时，设置页保留「手动打开站点验证」入口。
- `checkAuthFailure` 沿用 v5.4 的改动，仍然只记日志不弹界面。

---

# 更新日志

## v5.4 (versionCode 39) — 验证彻底不弹窗 · 去掉热帖排行 · 预览限两张图 · 帖子配图真相

### 一、验证「无感」：根因是解算出的 cookie 根本没发出去

上一版我说「验证不再跳前台」，用户实测**还在跳**。这轮找到真因，不是算法失效
（算法我用真实站点复验过，仍然有效：4321B 挑战页 → 173792B 真实页），
而是**解算结果压根没送到服务器**。

`recoverInterstitialIfNeeded` 原来是这么传 cookie 的：

```java
String merged = mergeCookie(request.header("Cookie"), solved);
Request replay = request.newBuilder().header("Cookie", merged).build();
```

问题出在 OkHttp 4.12 的 `BridgeInterceptor`（源码核实）：

```kotlin
val cookies = cookieJar.loadForRequest(userRequest.url)
if (cookies.isNotEmpty()) {
  requestBuilder.header("Cookie", cookieHeader(cookies))   // ← 无条件覆盖
}
```

cookie 罐非空时，它会**用罐子里的值覆盖调用方自设的 Cookie 头**。而本项目的 cookie
统一由罐子管理，请求对象上的 `Cookie` 头恒为 `null`，于是 `mergeCookie(null, solved)`
只剩 `acw_sc__v2` 一项；重放时它又被罐子里的会话 cookie 整个覆盖掉
→ 挑战照旧 → `isStillChallenge` 为 true → 落到 `onChallengeDetected` → **弹 WebView**。

**修法**：不再塞 header，改为把解算出的 `acw_sc__v2` **写进自己的 cookie 罐**
（`injectChallengeCookie`）。罐子自然带上它、不会被覆盖，而且后续所有请求都带着它，
不用每个请求重新解算一遍。

配套三项：

- **`checkAuthFailure` 永不再弹验证界面**。挑战页的处置权整体收敛到那一个拦截器
  （它挂在所有请求上、能读完整响应体、能解算、能重放、能写罐子）。以前这里也
  `onChallenge(url)`，造成「拦截器弹一次、调用方再判一次又弹一次」，而且是同步调用链，
  用户看到的就是「还是跳到验证页」。现在这里只记日志。
- **重试两轮**再放弃：挑战页有偶发服务端抖动，别一失败就退兜底。
- **`onChallengeDetected` 只在 `solve()` 返回 null 时才走**（即站点改了挑战算法、
  连解算值都算不出来）。解算值拿到了但重放仍未通过时，**不弹界面**，只记日志按原样返回。

### 二、首页去掉「热帖排行」

用户要求「帖子预览界面不要显示热帖排行」。`HomeFragment.loadHotBoards()` 会额外请求
`forum.php?mod=guide&view=hot`，解析带 `<em>排名</em>` 的链接，在列表头部渲染一个
带 1/2/3 排名徽章的「热帖排行」卡片。已整块删除（方法 + 调用点），顺带少发一个请求。

### 三、帖子预览最多两张图

`ThreadAdapter` 首页卡片原来是 `Math.min(4, …)`（2×2 满格），改为 `Math.min(2, …)`。
布局仍是 2 列 GridLayout，两张图正好一行；`adjustViewBounds + FIT_CENTER` 不变，不会变形。

### 四、进帖不显示图：不是识别错误，是站点对游客隐藏附件

用户问「进入帖子为什么不显示图片了，识别错误？」并让我参考
[`qcxs/mtbbs_app`](https://github.com/qcxs/mtbbs_app) 的帖子内解析。我读了它的
`viewthread/detail/parse.dart` 与 `core/parser/post_parser.dart`，然后拿真实页面逐一核对。

**结论：不是解析识别不到，是站点没把图发给游客。**

实测 tid=173937：

| 位置 | `mod=image&aid=` |
|---|---|
| 列表页卡片 | **2 张**（aid=377307 / 377306，带 key） |
| 帖子页（游客） | **0 张** |

帖子页里本应是附件的位置只有这段：

```html
<div class="comiis_quote bg_h f_c">游客，如果您要查看本帖隐藏内容请<a>回复</a></div>
<div class="comiis_noatt_ico bg_0 f_f"><i class="comiis_font">&#xe650;</i></div>
<h3 class="f_c">本帖子中包含更多精彩资源</h3>
<p>您需要 <a>登录</a> 才可以查看, 没帐号? <a>注册</a></p>
```

也就是说：**列表页对游客展示缩略图（服务端另外生成），进帖后附件区被换成登录墙**。
我方原来的解析器已经把懒加载属性处理得很全了
（`file → comiis_loadimages → data-original → data-src → data-file → src`，
站点 JS 也正是读 `attr('comiis_loadimages')` 优先），所以确实不是识别问题。

**这版的修法**：把这道墙**识别出来并明确告诉用户**，而不是静默显示一张图都没有。

- `PostDetail` 新增 `attachmentLoginWall`。
- `ForumParser` 识别 `div.comiis_noatt_ico`，或正文含「本帖子中包含更多精彩资源」/「登录才可以查看」。
- `ThreadDetailActivity`：正文确实没有图且命中登录墙时，在隐藏内容区给出说明
  ——「本帖配图/附件需要登录后查看。站点对游客隐藏附件（列表页那张缩略图是服务端另外生成的），登录后进来即可正常显示。」
- 只在真的没有正文图时提示，已登录能看到图时不弹这句。

参考 mtbbs_app 还确认了两件值得保留的做法（本次已按其思路核对，未改动）：
附件区 `div.pattl → ignore_js_op` 的解析我方走的是移动模板的 `div.comiis_messages`，
等登录态下附件真的出现在 HTML 里时再由同一套 `firstUsableImageAttr` 接管。

### 五、仓库分支清理

删除 `arena/01a0ef90-mt-lun-tan`，远端现在**只剩 `main`** 一个分支，默认分支即 `main`。

> 关于「限制不能创建分支」：GitHub 的 rulesets 与经典分支保护在**私有仓库的免费版**
> 都不可用（API 返回 403 `Upgrade to GitHub Pro or make this repository public`）。
> 需要彻底禁止建分支，要么升级 GitHub Pro，要么把仓库改为公开。本次已把分支清干净，
> 这一点需要你决定走哪条路。

---

# 更新日志

## v5.3 (versionCode 38) — 图片加载栈换成 OkHttp · 代码清理

### 一、图片不加载：根因是 Glide 的加载栈不带 User-Agent

v5.1/v5.2 反复改图片都没解决。这轮把整条链路逐一排除后才定位到根因，不是解析、不是缓存、不是布局，而是**加载栈**。

实测同一个图片地址，行为取决于 UA：

| UA | 结果 |
|---|---|
| 空 UA | `403` / 628 字节 |
| 任意非空 UA | `301` → `oss.binmt.cc` → `-L` 后 `200` / 146140 字节 |

而且**两级跳都要求非空 UA**：`cdn.binmt.cc` 拒绝空 UA 一次，重定向后的 `oss.binmt.cc` 还会再拒绝一次。Cookie 和 Referer 都无关，**只有 UA 是门槛**。

那为什么参照项目 `yz8023/mtluntan` 的图一直能显示？它用 Coil 2.5.0，底层是 OkHttp，而 **OkHttp 的 `BridgeInterceptor` 自带 `User-Agent: okhttp/<版本>`**（反编译 `okhttp-4.12.0.jar` 确认）。也就是说它从来没被这个问题困扰过，不是因为图片 URL 或解析更好，纯粹是因为换了个默认带 UA 的栈。

本项目的 Glide 4.16.0 走的是 `HttpURLFetcher` → `HttpURLConnection`，**没有任何地方设置 `http.agent`**。前面试过在 `LazyHeaders` 和 `GlideUrl` 上加 UA —— 这条路走不通：Glide 4.16 对带 header 的 `GlideUrl` 用的是签名化缓存键，改一次 header 缓存就失效一次；而且要看系统 `http.agent` 的脸色，行为不透明。

**所以这轮直接把 Glide 的加载栈换掉**，而不是继续打补丁：

- 新增 `util/OkHttpStreamLoader.java`：实现 Glide 的 `ModelLoader<GlideUrl, InputStream>`，内部用项目里已有的 OkHttp 发请求。请求构造交给 OkHttp 自己的 `BridgeInterceptor`，由它负责补 `User-Agent`（也兜底写一份 `ForumImageLoader.HTTP_AGENT`，保证即使是旧版本 OkHttp 也有非空 UA）。拦截器只保留「正常网络拦截器」（超时、重定向、日志），**不掺任何业务逻辑**。
- `MyApplication.onCreate()` 里用 `Glide.get(this).getRegistry().replace(...)` 运行时注册。

> 为什么是运行时注册而不是写一个 `@GlideModule` 的 `AppGlideModule`？因为 `libs.versions.toml` 里声明了 `glide-compiler`，但 `app/build.gradle` 从来没接注解处理器 —— 写 `@GlideModule` 根本不会被扫描到，属于白干。运行时注册不需要动构建配置，且失败时 try/catch 兜底，不影响启动。
>
> 注册放在 `onCreate()` 最前面，保证任何 Activity 出现之前图片栈就已经是 OkHttp 了。

顺带把重试也补上，对齐 mtluntan 的三态 UI：失败时 `attempt += 1` 并刷新一次（mtluntan 正是这么做的，它的 `#mtretry=` 挂载就是为这个服务的）。

### 二、清理不需要的东西

- `ImageUrl.toFullSize(...)`：全仓库无调用，连测试都只测它自己，删掉方法连同其测试。
- 目录树从 420 个源码文件扫出 41 个「主源码与测试里都没有调用」的 `public static` 方法。**这些没有删**：release 包本身由 R8 剥离，删了包体不会有任何变化，而按正则扫出来的名单有误报风险（反射、XML 引用都扫不到），动了反而可能弄坏功能。真正让包变大的是资源与二进制，那个在 v5.2 已经处理掉了。
- 仓库体积：这轮没有往仓库里塞新的二进制或资源。

### 三、其它

- `versionCode` 37 → 38，`versionName` 5.2 → 5.3。

---

# 更新日志

## v5.2 (versionCode 37) — 安装包从 20.8MB 降到约 4MB · 验证不再跳前台 · 列表图不再变形

### 一、安装包太大：cloudflared 出包（82% → 0）

`libcloudflared.so` 一个文件压缩后 **17.0MB**，而整个 APK 只有 20.8MB —— 它占 **81.8%**。去掉它，APK 约 **3.8MB**。

它只服务于「公网 MCP 隧道」这一个可选功能，却让所有用户为它付 17MB 下载。改为**首次启用隧道时按需下载**：

- `CloudflareTunnelManager.ensureBinary()`：优先用已下载到 `filesDir` 的，其次用安装包自带的（老版本升级兼容），都没有才现下载、`chmod 755`、校验大小与 ELF 魔数后才使用。下载失败给出明确错误，不影响 App 其它功能。
- `app/build.gradle`：**移除** `preBuild` 与 `JniLibFolders` 对 `fetchCloudflared` 的依赖。之前那么做导致任何构建都会自动把 37MB 二进制拉回 `jniLibs` 再打进包里 —— 这就是体积一直减不下来的直接原因。`fetchCloudflared` 任务保留，需要旧式「含 cloudflared」安装包时手动执行 `gradle fetchCloudflared :app:assembleRelease`。

### 二、验证不再跳到前台：本地解算 ESA 挑战

用户要求「验证不要跳到前台，自动验证」。参照 `yz8023/mtluntan` 的 `WafInterceptor` + `WafChallengeSolver` 做法，把整套机制移植过来。

ESA 的 `acw_sc__v2` 挑战其实是**固定算法**，不需要 JS 引擎、不需要 WebView、也不需要 cloudflared 隧道：

1. 从挑战页读 `arg1`（40 位十六进制种子）
2. 按脚本里的置换表 `m` 重排这 40 个字符
3. 每个字节与常量 `3000176000856006061501533003690027800375` 异或

结果就是服务端要的 cookie。新增 `util/WafChallengeSolver`（纯计算，可在 OkHttp 拦截器线程直接跑），`arg1` 和置换表都从实时脚本解析，脚本形状变了仍能工作。

接入点：`HttpClient.recoverInterstitialIfNeeded` 里检测到挑战页后，**先尝试本地解算并重放请求**，成功就直接返回真实响应，完全不弹界面；只有解算不出来才退回原来的 WebView 方案。合并 cookie 时只替换同名项，保留登录态（直接覆盖会把用户静默登出，这是 mtluntan 注释里特别踩过的坑）。

**已对 bbs.binmt.cc 端到端实测**（Java 解算器，非 mock）：
- 不带 cookie 请求 `forum.php?mod=guide&view=newthread&page=1&mobile=2` → 4321B 挑战页
- 解算出 `acw_sc__v2` 后重试同一 URL → **173867B 真实列表页**（`comiis_` 出现 1058 次、`mod=image` 68 次）

### 三、列表图片变形：去掉硬编码高度 + CENTER_CROP

参照项目 mtluntan 的 `PostImage` 注释里明确写了「图片显示异常」的根因：旧的 `fillMaxWidth()` 把所有图撑到满宽 + 硬编码高度导致变形。Java 版列表页正是这个问题：

```java
params.height = dp(104);                              // 硬编码高度
imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);  // 裁剪填充
```

每张图都被压成同一个 104dp 高的方块：竖图被裁掉大半、宽图被拉扁。改为 `WRAP_CONTENT` + `adjustViewBounds=true` + `FIT_CENTER`，宽度仍由列权重决定，高度按图片自身比例自适应，绝不变形。

---

## v5.1 (versionCode 36) — 修「图片依旧不显示」：27 处图片加载补上 UA/Referer/Cookie

**这是 v4.8 以来第 5 次尝试修图片，前 4 次都没修对地方。**

### 根因

v4.8 修的是"从 HTML 里挑出真实图片地址"这一层（Jsoup 属性链、`_icon.png` 被误杀、占位图黑名单、`upgradeThumbnailsToFull` 不再删图）。那一层确实有问题，但**不是用户看到"图片不显示"的主因**。

真正的主因在**加载层**：

- 本站配图是 `cdn.binmt.cc/forum.php?mod=image&aid=…&key=…`，实测 **UA 为空直接 403**；头像 `avatar.mt2.cn/uc_server/avatar.php` 同理会 403。
- 项目里早就有一个能用的封装 `util/ForumImageLoader.model(url)`，它把地址包成带 `User-Agent` + `Referer` + `Cookie` 的 `GlideUrl`。
- **但它只在 5 处被调用，全在 `ui/detail/` 里**。全项目 31 处 `.load()` 中另外 26 处是裸的 `Glide.with(ctx).load(url)`，一个 header 都不带。

后果：帖子正文的行内图（走 `ThreadDetailActivity` 的 ImageGetter 路径，恰好用了封装）能显示，而**论坛列表页的头像、封面图网格、缩略图**，以及**全 App 各处的头像**——全部 403，只看到 placeholder / error 占位图。

也就是说：v4.8 把"挑地址"修好了，但图片请求从来没带过 UA，列表页和头像从头到尾都是坏的。这解释了为什么用户反复反馈"图片依旧没修复"。

### 改法

把 27 处网络图片加载全部接上 `ForumImageLoader.model(...)`，覆盖 13 个文件：

| 位置 | 影响 |
|---|---|
| `adapter/ThreadAdapter`（3 处） | **列表页头像 / 封面图网格 / 缩略图** —— 用户看得最多的界面 |
| `adapter/ChatMessageAdapter`、`FriendAdapter`、`MessageAdapter`、`AccountAdapter`、`LikeUsersAdapter`、`ReplyAdapter`（各 1 处） | 私信 / 好友 / 账号管理 / 点赞用户 / 回帖的头像 |
| `adapter/ForumGridAdapter`、`ui/forum/ForumDetailActivity` | 版块图标 |
| `ui/detail/ThreadDetailActivity`（6 处） | 楼主头像、正文图廊、参与者头像、点赞者头像、打赏页头像 |
| `ui/profile/ProfileFragment`、`ui/space/UserProfileActivity`、`MainActivity`（2 处）、`ui/BlacklistActivity` | 个人页 / 抽屉 / 黑名单头像 |

顺带把 `ForumImageLoader.model()` 改成**只包 http(s) 地址**：本地路径（`/storage/…`、`file://`、`content://`）包成 `GlideUrl` 会让 Glide 走网络加载器、必然失败。改完之后调用方可以无脑全量使用，不会误伤本地图片（发帖页选图、拍照预览仍走原样路径，未改动）。

### 验证

- 逐处扫描全项目 `.load()`：31 处中 27 处已包裹，剩余 4 处经确认**应当**保持原样——2 处本地相机 `uri`、2 处本地文件路径 `af.path`、1 处是 `KeyStore.load(null)` 与 Glide 无关。
- `load(Object)` 重载在真实 Glide 4.16.0 上编译通过（该写法在 v5.0 里已有 5 处先例，非新引入）。
- 单测 **103 tests / 0 failures / 0 errors**。

---

## v5.1 附带：切号/登出清图片缓存

build65 在 HTTP 层已处理过"切号后新账号复用上一个账号的页面结果"（`switchTo` 清 `clearPendingCache()` + `clearCookies()`），**图片层被漏掉了**：Glide 磁盘缓存 key 只有 URL、不带 Cookie/UA，而本站配图 UA 为空就 403 —— "能不能看到图"取决于会话，"命不命中缓存"只取决于 URL。于是账号 A 读过的图，切到 B 或登出后照样从磁盘命中显示。

新增 `util/ImageCacheJanitor`（后台线程清 Glide 磁盘缓存，全程 try/catch，清理失败不影响登出主流程），接线两处：`UserSessionManager.clearLoginInfo()`（所有登出路径的共同收口）与 `AccountManager.switchTo()`。

---

## v5.0 (versionCode 35) — 回帖渲染跳过重复 HTML 解析

对照 `yz8023/mtluntan`（Kotlin + Jetpack Compose 重写版）逐项比对后的性能收尾。

**渲染热点**
- `ReplyAdapter.onBindViewHolder` 里对每条回帖调 `Html.fromHtml`，每次 bind 都完整解析 HTML 并重建整棵 span 树。列表每滚一屏，可见的十几段就全部重解析一遍。现在把解析结果缓存进 `ReplyItem.renderedText` / `renderedQuote`，纯文本回帖只在首次算一次。
- **只缓存「没有任何内联图片」的回帖**。原因：`Html.ImageGetter` 在解析时会捕获当时那个 TextView，Glide 加载完成后把图回填到那个 View 上；回帖 View 是被 RecyclerView 复用的，缓存带图的 Spanned 会让图片回填到错误的一行。用 `ImageSpan` 数量判定，有图就保持每次重新解析——正确性优先。
- **缓存存「未被污染的副本」，每次发副本**。`setupClickableLinks` 是就地改 span 的：`matcherLinkify` 往里加 ClickableSpan，又把 Html 产生的 URLSpan removeSpan 后换成自定义 ClickableSpan。如果缓存对象被直接复用，第二次 bind 时 `matcherLinkify` 会再叠一套 ClickableSpan——同一段文字挂两个点击处理器，点一下可能开两个页面。所以存原件、每次 `new SpannableString(...)` 发副本；`Html.fromHtml`（HTML 解析 + 建 span 树）才是真正贵的部分，副本只是字符和 span 的浅拷贝。

**与参照项目的差异结论（本轮未改）**
- 节流不是差异：`yz8023/mtluntan` 的 `RequestThrottle` 与本项目 build69 的双车道设计几乎一致（前台不等、后台令牌桶、约 85 请求/分钟）。
- 帖子详情请求数：参照项目就是 1 个请求后立即解析，无 enrich / 收藏同步 / 桌面版整页；本项目 v4.8 已把这两个附加请求挪到渲染后异步。
- 参照项目也有桌面模式开关（`desktopMode` + PC 版帖子 URL），说明 PC 模板分页更多这点同样被发现，但默认关闭。
- 参照项目用 `AnnotatedString` 原生渲染正文，本项目在 `onBindViewHolder` 跑 `Html.fromHtml` + `ImageGetter`（全项目 26 处）。这是更深一层的架构差异，改动渲染模型风险和工作量都大得多，本轮未动。

---

## v4.9 (versionCode 34) — 修 cookie 获取登录失效 · 评论区滚动卡顿

**一、修「cookie 获取登录失效」（build80 引入的致命回归）**
- 根因：build80 的拦截器为了判定「是不是挑战页」用 `body.bytes()` 读正文，而 `ResponseBody.bytes()` 会把源**读完并关闭**。读完发现「不是挑战页」后又直接 `return response` —— 调用方拿到的是一个已关闭的 body，再调 `body.string()` 直接抛 `IllegalStateException("closed")` 或返回空串。
- 后果：所有走这条拦截器的 HTML 响应对上层都是空的。登录链路（`member.php?mod=logging&action=login` 返回 HTML）因此拿不到任何内容，表现为「cookie 获取登录失效」；列表页/帖子页表现为「服务器返回空页面」。这也让整机观感明显变慢——空页面触发解析失败与重试。
- 修法：改用 okio 的 `peek()` **预读但不消费**。数据被读进底层 buffer，源的位置没动，调用方照常读到完整响应。已用真实 OkHttp + 本地 HTTP 服务器验证（不 mock）：错误写法调用方拿到 `IllegalStateException: closed`，`peek()` 写法拿到字节级完整的响应。

**二、评论区滚动/首屏卡顿**
- `ReplyAdapter.onBindViewHolder` 里对每条回帖跑 `upgradeImageSources()`，即一次 Jsoup 解析 + 重新序列化，而且是在**主线程、每次 bind 都跑**——列表每滚一屏就重解析几十段 HTML。改为把升级后的 HTML 缓存进 `ReplyItem.upgradedHtml`，只算一次。
- 顺带修掉一处 v4.8 漏掉的图片 bug：这里的占位图黑名单只有 none/blank/grey，**漏了 `imageloading.gif`**（Comiis 真正在用的懒加载占位名）。v4.8 只改了 `ThreadDetailActivity` 那一处。现在统一走 `ImageUrl`。
- `syncFavoriteStateFromServer` 原来无条件拉**整个收藏列表页**，只为回答「当前 tid 收不收藏」这一个布尔值。而 Discuz 帖子页操作栏的 `#comiis_favorite_a i.comiis_favorite_a_color` 就带着这个状态，`ForumParser` 早已解析并置了 `favoritedStateKnown`。页面能确认时不再发这个请求（它带的 `_refresh=<ts>` 还会让服务端缓存失效）。

---

## v4.8 (versionCode 33) — 过人机验证 · 图片链路 · 进帖速度 · 签到读数

对照 `qcxs/mtbbs_app` 解决的四个问题：

**一、过人机验证（移植 mtbbs_app 的四层机制）**
- 拦截器守门器接到全部 HTML 响应上：原来只有走 `checkAuthFailure` 的少数路径会发现挑战页，列表页/帖子页的其它请求命中挑战时只是「解析不出东西」，没有任何提示。现在每个 200 的 text/html 响应都会判定，命中即打开验证 WebView，通过后重放原请求（仅 GET，写操作不自动重放以避免重复提交）。
- 挑战页判定从「厂商特征串匹配」改为「结构判定」优先：原判据是 `acw_sc__v2`/`aliyungf_tc`/`__jsl_clearance`/`人机验证` 等字符串，换个 WAF 或站点改版就全军覆没，而页面还是那个页面。新判据是「看起来像一个完整 HTML 文档，却完全没有 Discuz 骨架」，与是哪家 WAF 无关，且自带保守性（残留 `discuz`/`formhash`/`comiis_` 就不判拦截），不会平白弹浏览器。厂商特征保留为兜底。验证 WebView 的恢复判定也改用同一套判据，两边不会再打架。
- 修掉「疯狂重登死循环」：原来 `checkAuthFailure` 把 HTTP 401/403 当掉线信号。但 403 现在绝大多数是阿里云 ESA 的 WAF 拦截（含图片 CDN 对空 UA 的 403），跟登录态无关，拿它触发静默重登会形成「重登还是 403」的循环，而且重登拿的新 Cookie 根本解不开 JS 挑战。现在掉线只认一件事：本以为已登录，结果返回的是登录页。
- 请求头发送前剔除「浏览方式」Cookie `{cookiepre}_mobile`：站点在「该页面无手机版」时会下发它，而它的优先级高于 UA 和 URL 里的 `mobile=2`，一旦罐里躺着这条，所有请求都被强制返回 PC 模板，而本项目解析器按 Comiis 移动模板写，于是正文/图片整套解析失效（实测同 UA 同 URL，有它 89KB PC 表格 vs 没它 173KB 移动卡片）。只改「发什么」，不动罐里数据。
- 新增 `CookieSync`：核心/临时 Cookie 分类回流，判据 `{cookiepre}auth`（MT 前缀 `cQWy_2132_`），把防护 Cookie 补写到所有账号罐。

**二、正文图片显示异常/不显示**
- 评论区图片：`ReplyAdapter` 原来只用一条正则读 `src`，而本论坛用 Comiis 懒加载，真实地址在 `file`/`comiis_loadimages` 属性里，`src` 只是占位图。改用 Jsoup 走真实属性链，正则退为兜底。
- `ForumParser.isPostImageUrl` 原来按 `icon`/`face`/`stamp`/`magic` 裸子串过滤，而 Discuz 附件命名固定 `common_{aid}_{hash}_icon.png`，即每个附件缩略图都带 `_icon`，全被误杀——这就是「选择显示图片到原处时部分帖子图片消失」的直接原因。改为只认真实 Discuz smiley 路径。
- 占位图黑名单补 `imageloading.gif`（Comiis 真正在用的懒加载占位文件名），否则解析出的「真实 URL」就是占位图本身。
- `upgradeThumbnailsToFull` 原来把取不到真实地址的 img 整张删掉，但取不到地址不等于不该显示，删掉就成了「能看的图被吃掉」。改为能升级就升级，不能升级就原样留着。
- 统一 `ImageUrl`，让列表页、帖子页、评论区三条路径对「什么是真实 URL / 占位图 / 内联表情」判断一致。

**三、加载速度**
- 进帖链路的 `enrichGoodReviewAvatars` + `refreshServerActionState` 从「解析后、渲染前」的同步位置移到渲染后异步执行。两者都是整页请求（一个拉桌面版、一个拉收藏列表），原位置等于让它们阻塞首屏，进帖要串行发 3 个页面请求才看到第一屏内容。
- 拦截器提前放行：真实 Discuz 页面远大于判定上限（列表页 174KB、帖子页 ~50KB），挑战页只有几 KB，用 Content-Length 提前放行可避免每个 HTML 响应都被整个读进内存再判断，大帖（几百楼）的首屏明显更快。

**四、签到读数**
- 新增连签天数（`lxdays`）、累计天数（`lxtdays`）、签到等级（`lxlevel`）、签到排名（`qiandaobtnnum`）的读取。
- 修 `extractRanking`：原来把「您的签到排名：123」整段返回，上层拼成「· 排名 您的签到排名：123」，用户看到一句重复的废话。现在归一成纯数字。
- 修 `extractRewardFromText`：原来只认「数字 币种」顺序（`奖励 8 金币`），而 k_misign 真实文案是「币种 数字」（`金币 8`），导致奖励永远显示 0。两种顺序都收，币种在前的优先。
- 签到时间仅做文案兜底：k_misign 没有公认的 `lxtime` 隐藏域，拿不到返回空串，不编造。

**工程**
- `ImageUrl`/`InterstitialDetector`/`CookieSync` 改为不依赖任何 `android.*` 类（本项目 `SignParser` 从 build60 起就是同一条约定），单元测试不再需要 mock `TextUtils`；release 单测 95 项全绿。
- 修复「从仓库源码构建出的 APK 比发布版小 21MB」：CHANGELOG v4.4 起就写着构建时下载并校验官方 `cloudflared 2026.9.3` arm64 静态程序，但该任务在 build.gradle 被工具重新生成时被丢掉，导致 `lib/arm64-v8a/libcloudflared.so` 进不了包，公网 MCP / Quick Tunnel 直接不可用。现补回 `fetchCloudflared` 任务（缺文件才下载，校验 ELF 魔数，失败只警告不阻断构建），并把 `app/src/main/jniLibs/` 加入 .gitignore 以免 37MB 二进制入库。

---

## v4.7 (versionCode 32) — Android DNS 隧道与帖子图片修复

- 根据真机错误确认 cloudflared 的 Go DNS 在 Android 上读取到占位解析器 `[::1]:53`，请求 `api.trycloudflare.com/tunnel` 因而直接失败；这不是公网服务地址，旧版还误把错误日志中的 API 域名复制成了 MCP URL。
- Quick Tunnel 注册改由 App 的 OkHttp/Android 网络栈完成；生成临时凭据后，通过 Android DNS（失败时回退到 1.1.1.1 DoH）预解析 Cloudflare region1/region2 边缘 IPv4，并以 `--edge` 传给 cloudflared，完全避开其错误的本机 DNS。
- 公网 URL 只采用注册接口返回的随机 hostname，并且仅在至少一个 Tunnel Connection 注册成功后开放复制，不会再生成 `https://api.trycloudflare.com/mcp` 这种无效配置。
- 修复帖子图片变成随机表情的根因：部分 Comiis 模板的 `comiis_loadimages` 值只是开关 `1`，旧解析器却优先把它当 URL，最终请求 `bbs.binmt.cc/1` 并显示站点占位/随机表情。
- 主楼、评论、帖子列表和离线页面统一拒绝 `1/true/false/lazy` 等伪图片属性，继续向后查找 `file/zoomfile/data-original/src` 的真实地址。
- 收紧表情识别为真实 Discuz smiley 路径；普通附件 URL 或文件名中包含 `face`、`icon`、`mini` 等单词时不再被错误丢弃或缩成表情尺寸。

---

## v4.6 (versionCode 31) — 顶栏 MCP · Quick Tunnel 参数修复

- MCP 入口调整到首页右上角原 AI 助手符号位置，移除首页大卡片；图标尺寸、毛玻璃背景和主题色与旁边搜索按钮保持一致。
- 轻点顶栏符号即可启用或重试公网 MCP；连接成功后再次轻点复制包含 URL 与授权头的完整配置，长按进入详细设置。
- 进一步定位隧道代码 1：`--no-autoupdate` 在新版 cloudflared 中属于全局参数，放到 `tunnel` 子命令后会打印 usage 并直接退出。现在严格使用 Cloudflare 官方最小命令 `cloudflared tunnel --url <本机MCP>`，自动更新仍通过环境变量关闭。
- 捕获 cloudflared 最后一行输出；即使输出不含 error 关键字，异常退出后也会显示具体内容而不再只有代码 1。
- 移除与应用信息架构不一致的 MCP 状态卡片，恢复紧凑导读布局并统一符号主题。

---

## v4.5 (versionCode 30) — 首页 MCP · 隧道稳定性 · 一键完整配置

- MCP 入口移到首页顶部，集中显示公网隧道状态；首次点击会自动启用只读 MCP 和免费公网隧道，不再要求先进入多层设置。
- 修复 cloudflared 自动退出代码 1：不再依赖临时注册接口及手写 credentials/YAML，改用 cloudflared 官方的账号免费 Quick Tunnel 启动方式 `tunnel --url`，减少版本间配置不兼容。
- 隧道退出时保留并展示 cloudflared 最后一条具体错误，而不是只显示笼统的退出代码；仍支持异常后自动重连。
- 首页连接成功后提供“一键复制完整配置”，一次复制公网 URL 和 Authorization 请求头；可直接整体粘贴给支持 MCP 的 AI，无需分别复制地址和 Token。
- MCP 设置页同步合并复制入口，简化说明和按钮层级；首页卡片、状态文字、导读筛选间距与层级重新排版。

---

## v4.4 (versionCode 29) — 内置公网 MCP · 功能审计与分类重排

- 真正内置 Cloudflare Quick Tunnel：构建时下载并校验官方 `cloudflared 2026.9.3` arm64 静态程序，随 APK 解压到可执行目录；App 内自动注册临时隧道、生成凭据与 ingress 配置，将公网 HTTPS 地址转发到手机回环 MCP，不要求用户安装或配置任何第三方隧道工具。
- 隧道提供 STARTING/RUNNING/FAILED 状态、公网 `/mcp` 地址复制、运行日志解析、异常退出自动重连；MCP 与隧道由前台服务保活，App 进程恢复时按开关自动恢复。
- MCP 安全继续执行 Bearer Token、Host/Origin 校验、只读工具白名单、请求限长和 Cookie/密码/formhash 脱敏；增加访问审计、通知读取工具及 Token 撤销重生。公网模式不会开放发帖、回复、点赞、解锁或任意 HTTP 请求。
- 对照 `qcxs/mtbbs_app` 的导读、版块、详情、BBCode、编辑器、互动、用户空间、浏览器、RSS/列表与设置完成逐项审计，审计结果记录于 `docs/MTBBS_FEATURE_AUDIT.md`。
- 首页补齐“新帖 / 最新回复 / 热门 / 精华”四类原生导读，切换时隔离请求代次，避免慢请求覆盖当前筛选。
- 设置页按“账号与自动化 / 外观与阅读 / 网络、浏览与下载 / AI 与高级工具 / 关于与更新”重新分组；MCP 子页独立展示本地服务与免费公网隧道状态。
- 延续 v4.3 的应用内浏览器右上角系统浏览器/复制链接/电脑模式、认证图片与原图画廊、登录键盘避让，以及 v4.2 的全自动凭据恢复和回复编辑能力。

---

## v4.3 (versionCode 28) — 内置浏览器、图片与 MCP

- “应用内打开与下载”模式下，正文链接不再跳出 App，改为携带论坛 Cookie 的内置浏览器；右上角可选择系统浏览器打开、复制链接和切换电脑模式，文件仍直接保存到系统 Download。
- 图片请求统一携带论坛 Cookie、User-Agent 与 Referer，修复评论区、隐藏内容及全屏预览中受保护附件不显示；完整识别 `file`、`comiis_loadimages`、`zoomfile`、`data-original`、`data-src` 等原图属性，正文图片按可用宽度显示，表情保持行内尺寸。
- 参考 qcxs/mtbbs_app 增加只读 Streamable HTTP MCP 服务：Bearer Token 鉴权、默认仅回环监听、可选局域网/外部 HTTPS 隧道转发、只读工具白名单、敏感字段脱敏及输出限长。工具覆盖搜索、版块、帖子全文、全部回复、用户资料和当前账号主题。
- 设置页新增独立“MCP 服务”分组，可启停服务、设置端口、复制端点与 Token；默认关闭，绝不暴露发帖、回复等写操作。
- 登录底部面板强制随输入法调整高度并展开，修复账号密码输入框被键盘遮挡。
- 下载、浏览、MCP、账号与自动化入口按功能分组整理，减少同类选项分散。

> 公网访问必须通过用户可信的 HTTPS 隧道或组网服务转发 MCP 端口，并保留 Bearer Token；App 不会把账号 Cookie 暴露给隧道服务，也不会默认监听公网。

---

## v4.2 (versionCode 27) — 全自动登录恢复与回复编辑

- 站点验证进入论坛登录页时，自动读取 Android Keystore 中当前账号已保存的密码，填写账号密码并提交，无需再次手动输入。
- 修复打开“保存登录”面板时预取线程会清除刚完成验证的全局 Cookie，进而误报“登录页异常”的问题；密码登录改用隔离会话，成功后才替换当前会话。
- 密码登录、掉线自动重登和自动签到重登都会复用 WebView 获取的 ESA 防护 Cookie，同时只隔离替换账号认证 Cookie。
- 单独保存登录账号名与论坛显示昵称，避免昵称和登录名不同导致自动登录失败；旧账号数据自动兼容。
- 恢复本人回复长按菜单中的“编辑回复”，编辑时读取并原样提交 Discuz 编辑表单的全部隐藏字段，保存后自动刷新帖子。

---

## v4.1 (versionCode 26) — 签到验证、原图与离线页面

- 修复本地仍有过期 `_auth` Cookie 时误判“登录态正常”，导致自动重登根本不执行。
- 网络层识别阿里云 ESA / JS 人机验证页，不再把混淆 HTML 当论坛页面继续解析。
- 自动打开同域内置验证页，启用 JavaScript 执行验证，并与 OkHttp 双向同步 clearance Cookie。
- 验证完成后真实请求账号页面：账号 Cookie 有效则直接恢复，失效则使用本机加密保存的密码静默重登。
- 用户资料页遇到验证或掉线时不再反复弹窗并退出；验证后自动重试，重登失败才显示登录面板。
- 设置新增“站点验证与 Cookie 同步”手动入口，防护 Cookie 再次变化时可随时刷新。
- 自动签到遇到 ESA 验证时使用无窗口 WebView 在后台执行 JavaScript、同步防护 Cookie 并重试，最多 3 次，仍失败才按签到失败处理；运行日志会记录每次验证未通过的具体阶段和最终处理建议。
- 参考 Forinxy/mt 的 k_misign 处理补全已签到页面奖励文案兜底，并在日志中保留签到接口返回的奖励内容、排名和奖励数值。
- 帖子与回帖图片固定在正文原位加载原图，并按正文实际可用宽度等比完整显示，不再汇总成底部缩略图廊。
- 修复可选择正文中链接命中坐标未扣除内边距和滚动量，导致复制选区光标与指尖错位的问题。
- 离线 HTML 与导出 HTML 会保留当前登录会话已解锁的隐藏内容，识别更多 Discuz/Comiis 原图属性并内嵌资源；正文大图固定占满内容宽度，便于离线查看和后续分析。
- 发布流程将 APK 与源码 ZIP 作为两个独立文件上传，并在同一个版本 Tag 的 GitHub Release 中发布。

---

## v4.0 (versionCode 25) — 发帖编辑器 · 帖子离线与导出 · ID 跳转

- 发帖页加入完整 BBCode 横向快捷工具条、色板和 300ms 防抖实时预览。
- 帖子支持后台保存为应用内离线页面，可选是否抓取全部评论，并内嵌正文图片。
- 新增已保存帖子列表，可在无网络时查看和删除本地留存。
- 支持导出 HTML、纯文本，以及通过系统打印服务保存 PDF；HTML/文本/PDF 均可选择目标位置及是否包含评论。
- 侧边栏新增用户 UID / 帖子 TID 快速跳转，支持直接输入数字或粘贴论坛链接。

---

## v3.9 (versionCode 24) — 媒体上传与下载方式 · 安装包瘦身

- 修复悬浮侧边栏右上角被系统状态栏矩形底色覆盖，强制按 22dp 背景轮廓裁切。
- 图片超过论坛约 1MB 限制时自动以“质量优先、必要时再缩分辨率”的方式压到限制内；小图保持原文件不重编码。
- 发帖和回复的图片入口同时支持短视频，本机采样转换为循环 GIF，并逐级调整帧率/尺寸到 1MB 内。
- 设置新增“文件下载方式”：可选应用内下载到系统 `Download` 目录，或跳转浏览器下载。
- Release 启用 R8 代码优化、无用资源裁剪和语言资源筛选，减小 APK。
- 删除仓库中两份重复源码 ZIP；GitHub Release 直接使用自动生成的 Source code，避免源码包重复膨胀。

> GIF 受格式和论坛 1MB 限制，视频转换无法真正做到无损；应用会优先保留清晰度，过长或内容复杂的视频会提示改用更短片段。

---

## v3.8 (versionCode 23) — 自动解锁重复回复（真因）· 日志可复制 · 侧边栏统一主题

### 1. 自动解锁重复触发回复 —— 前三版都没修对，真因是「有两条链路」

v3.5 加了本次访问标志、v3.5 加了持久化认领、v3.6 加了 6 小时冷却 ——
全都加在 `AutoReplyEngine.tryUnlockOnOpen()` 这条链路上。

但 `ThreadDetailActivity` 里**还有第二条完全独立的解锁链路**：

```java
// bindData() 第 860 行
maybeAutoUnlock();      // → AutoReplyEngine.unlockSingleThread(tid)
```

这条路：

- 走的是 `unlockSingleThread()`，**一个认领检查都没有**
- 防重只靠一个 `private final Set<String> autoUnlockTried` —— **per-Activity 实例**，
  退出帖子再点进去就是新实例，集合是空的，于是又回一遍

两条链路并行跑，我前面所有的防重都加在其中一条上，另一条照发不误。

修法：

- **删掉 `maybeAutoUnlock()` 及其调用**，只保留 `runUnlockInBackground()` 一条链路
- **给 `unlockSingleThread()` 补上同一套认领保护**，防止以后又有别的调用方绕过

### 2. 日志允许复制

- 卡片正文和副标题都改成**可长按选中复制**
- 每张卡片右侧固定一个「复制 / 操作」按钮 —— 因为 `textIsSelectable` 会吃掉单击，
  操作必须有独立入口
- 历史与解锁卡片的「操作」按钮直接出菜单（打开帖子 / 复制链接 / 复制记录 / 复制标题）

### 3. 侧边栏与底栏统一主题（参考 dyparse）

侧边栏从「贴边铺满的实心面板」改成和底栏同款的**悬浮胶囊**：

- 同一个 `nav_bar_bg`：22dp 圆角 + 0.5dp 细描边
- 四周留白 + 10dp elevation，浮在内容之上
- 头部改用上圆角背景，和外框对齐
- **共用底栏那一档不透明度设置** —— 在「设置 → 应用主题」里调底栏透明度，侧边栏同步变

---

## v3.7 (versionCode 22) — 图片消失找到真因 · 记录中心双模式 · 补齐动效

### 10. 原位显示时部分帖子图片消失 —— 找到真因了

前两版我在 `zoomfile` / `file` / `data-original` 里找原图地址。
但**这个论坛用的是 Comiis 模板，它的懒加载属性叫 `comiis_loadimages`** ——
我的候选列表里恰好漏了这一个。

于是这类图的 `src` 一直停在占位的 `none.gif` 上，渲染出来就是空白。

证据就在项目自己的代码里 —— `extractAndSeparateImages()` 早就列全了：

```java
if (img.hasAttr("file")) ...
else if (img.hasAttr("comiis_loadimages")) ...   // ← 我漏的就是这个
else if (img.hasAttr("data-original")) ...
else if (img.hasAttr("data-src")) ...
else if (img.hasAttr("data-file")) ...
```

现在原位模式的取值顺序和它**完全对齐**，并且：

- 所有候选统一绝对化
- 过滤 `none.gif` / `blank.gif` / `grey.gif` / `data:` 等占位
- 实在找不到真实地址的图**直接删掉** —— 留着就是个永远转圈的空白块

（顺带发现 `extractAndSeparateImages` 里有 `doc.select("ignore_js_op").remove()`，
会把附件图片整块删掉，这是非原位模式下附件图不进图廊的原因，已记录待改。）

### 11. 记录中心重做

- **两种显示模式**：`简洁` / `详细`，右上角一键切换
  - 只影响**显示和复制内容**，底层照常记全量，切到详细随时看完整信息
  - 简洁：一行一条，扫着看；详细：完整时间戳、原因、链接全展开
- **每张卡片都能操作**：轻点打开帖子 / 长按出菜单（打开帖子、复制链接、复制这条记录、复制标题）
- **复制本页**：按当前模式导出整页，简洁模式复制精简版、详细模式复制完整版
- **运行日志美化**：按 `[tag]` 拆出时间 / 标签 / 正文，不同标签配不同色条
  （auto-unlock 绿、sign-in 蓝、perf 橙、session 紫、auto-reply 青）
- 卡片平铺、左侧色条区分状态、进场错峰

### 7（续）动效补齐

- 首页 / 版块 / 社区列表加入场错峰（原来只有侧边栏和消息页有）
- 点赞加弹性反馈（欠阻尼弹簧，按下去有"弹一下"的确认感）

---

## v3.6 (versionCode 21) — 删除回复（第三次）· 图片消失 · 记录中心

### 1. 评论删除依旧无效 —— 我前两版都在猜参数

v3.4 发 `deletesubmit=yes`，v3.5 改成 `editsubmit=yes`，都还是不行。
原因是 Discuz 的编辑接口除了这两个，还要 **`fid`** 和一堆隐藏字段
（`posttime` / `wysiwyg` / 各种 hash），少一个就静默失败。

**这次不猜了**：把编辑页那张表单整个用 Jsoup 解析出来，
所有 `input` / `textarea` / `select` 原样收集，只额外塞一个 `delete=1` 再回提。
不管 Discuz 版本要什么字段都不会漏。

另外加了**兜底验证**：如果响应没认出成功，就重拉帖子页确认那条 pid 是否真的没了 ——
服务端删掉了但返回格式不认识的情况，也能正确报成功。

（参考项目 `mtbbs_app` 里没有实现回复删除，只有删附件和删收藏，所以这块借鉴不上。）

### 2. 部分帖子图片直接消失

v3.4 我把 `img` 的 `src` 换成 `zoomfile`/`file` 来拿原图，但少了两道保护：

- 那些属性可能是**相对路径**（`data/attachment/forum/...`），直接塞进 `src` 就加载不出来
  → 现在统一绝对化
- 也可能是**占位图**（`none.gif` / `blank.gif`），换过去等于把真图替换成空白
  → 现在过滤掉这类候选，并保留原 `src` 作为 `data-fallback`

### 3. 记录中心（新页面）

侧边栏「运行日志」点进去不再是一个挤成一团的对话框，而是独立页面，四个分页：

| 分页 | 内容 | 可点击 |
| --- | --- | --- |
| 浏览历史 | 标题 + 时间 + 作者 | ✅ 点卡片直接打开帖子 |
| 自动解锁 | 已解锁 / 跳过 / 失败 + 原因 | ✅ 点卡片直接打开帖子 |
| 加载耗时 | 网络 / 解析 / 渲染 / 请求数 | — |
| 运行日志 | 原始日志 | — |

全部卡片式，左侧色条区分状态（绿=已解锁、灰=跳过、红=失败、蓝=浏览），
每页可单独清空，进场有错峰动画。

「自动解锁」还做了去重：同一帖子的重复「跳过」只留最新一条，不再刷屏。
（长按侧边栏那一行仍可打开旧的纯文本日志。）

---

## v3.5 (versionCode 20) — 紧急：重复自动回复 · 删除回复无效

### 9. 对隐藏帖反复自动回复（紧急，有封号风险）

三个原因叠在一起，难怪「概率很大」：

1. **解锁成功后会调 `refreshPostDetail()`**，刷新链路又会设置 `pendingUnlockHtml`
   并再次进入 `runUnlockInBackground()`。而服务端刚回完帖，页面状态往往还没更新，
   于是判定「仍是锁定」→ **再回一条**。
   → 加 `unlockAttemptedThisVisit` 标志，本次进入该帖只尝试一次。

2. **`HANDLED_TIDS` 只存在内存里**，杀进程/重启后全忘，同一帖子会被再回一次。
   → 认领记录**落盘**（带时间戳）。

3. **回帖失败就立即 `releaseTid` 允许重试** —— 但「失败」很多时候只是我们的
   成功判定没认出来，服务端其实已经发出去了，一释放下次进来就又发一条。
   → 失败**不再立即释放**，认领保留 6 小时，到点才允许重试一次。

### 8. 删除自己的回复无效

两个问题：

- **参数错了**：Discuz 的编辑接口要 `editsubmit=yes`，代码发的是 `deletesubmit=yes`，
  服务端根本不认，等于什么都没做。
- **从不校验响应**：无论服务端返回什么都弹「已删除该回复」，
  所以你看到提示成功、实际没删。

现在：参数改对，失败会自动退到版主删帖接口 `topicadmin&operation=delpost` 再试一次，
并且**如实校验响应**——成功才提示成功，失败会把服务端的原话显示出来。

---

## v3.4 (versionCode 19) — 角标误报 · 隐藏标注 · 图片全宽 · 主题透明度

### 1. 每次打开全部历史都变新消息 / 切号后重现

`NoticeBadgeManager` 的基线键是 `baseline_{viewType}`，**所有账号共用一份**。
切换账号后拿的是上一个账号的基线，于是新账号的全部历史消息都被算成「新」。

现在基线按 **uid 隔离**（`baseline_{uid}_{viewType}`）。配合原有的
「首次无基线则只建基线、返回 0」逻辑，切号后会为新账号重新建基线，不再全标红。

### 2. 隐藏帖还是没标注

标记写在**内存里的 Set**，杀进程/重启就没了 —— 重开 App 就是一张白纸，
所以你一直看不到标签。现在落盘到 SharedPreferences（保留最近 500 条），
启动时恢复。

### 3. 长按帖子改为复制链接

原来长按 = 拉黑作者，误触代价太大（一不小心整个人的帖子都消失）。现在：

- **长按帖子卡片** → 复制链接 / 复制标题+链接
- **拉黑** 挪到帖子内的评论操作菜单（有明确上下文，且会说明可在小黑屋取消）

### 4. 原位图片直接显示全图

两个问题一起修：

- 原来只有「超宽才缩小」，论坛缩略图本身就小，于是原位显示出来是张小图 →
  改成**无论大小都等比缩放到内容宽度**
- Discuz 的图片是 `<img src="缩略图" file="原图" zoomfile="原图">`，
  只用 `src` 放大了也是糊的 → 渲染前把 src 换成 `zoomfile`/`file`

### 5. 「日志定位卡顿」是什么意思

是我想让你帮我抓数据来定位——这不该是你的活，撤回这个要求。
渲染耗时埋点留着（在运行日志顶部），但我不再等它，直接按上面几条实际问题改。

### 6. 主题细粒度配置（参考 mtbbs_app）

设置 → 应用主题 新增三档不透明度滑块：

| 项目 | 默认 |
| --- | --- |
| 卡片与列表项 | 94% |
| 对话框与弹窗 | 98% |
| 底部导航栏 | 100% |

范围 40–100%（下限 40 是防止调到读不清）。毛玻璃背景直接读这个值，不再写死。

### 7. 动效

帖子列表卡片加按压反馈（motion-web handfeel：按下即缩、抬手弹簧回弹）。

---

## v3.3 (versionCode 18) — 渐变字 · 附件下载 · 渲染耗时埋点

### 渐变彩虹字

「彩虹字」是 HSV 色相跑满 360°（七彩），新增的**「渐变字」**是在两个颜色之间
**平滑插值**，每个字的颜色连续过渡，观感更柔和、更适合做标题。

内置 8 种预设：日落橙→粉、海洋蓝→青、极光紫→蓝、薄荷绿→青、樱花粉→紫、
火焰红→黄、深海深蓝→紫，以及「彩虹全色相」。空白字符不着色，不产生空标签。

### 附件解析与下载

DOM 结构参考 [Forinxy/mtbbs_app](https://github.com/Forinxy/mtbbs_app) 的
`core/parser/html2bbcode.dart`（我这边 IP 被论坛封了，抓不到登录后的页面，
结构是从那个项目的解析代码里对出来的）：

```
普通附件：<ignore_js_op><img src="...filetype/xxx.gif">
          <span id="attach_N"><a href="forum.php?mod=attachment&aid=XXX">name.ext</a>
          <em class="xg1">(2.3 MB, 下载次数: 12)</em></span></ignore_js_op>

图片附件：<ignore_js_op><img class="zoom" aid="XXX" src="...">
          <div class="aimg_tip"><strong>名字</strong><em class="xg1">(大小, 下载次数: N)</em>
          <p class="xg1 y">时间 上传</p></div></ignore_js_op>
```

正文下方渲染成附件列表：文件名 + 大小 + 下载次数 + 上传时间。

**关于金币**：解析阶段<b>不发任何请求</b>；图片附件点「查看」直接全屏预览（不走下载接口）；
文件附件点「下载」会先弹二次确认并明确提示「部分附件下载会扣除金币/积分」，
确认后才用系统下载器带 Cookie 下载。

### 渲染耗时埋点 —— 找「感觉慢」的真凶

你的日志里 `合计 221–343ms` 其实已经很快了，但体感还是慢。原因是
**`PerfLog` 里的「解析」只统计了 `ForumParser.parseThreadDetail`**，
而 BBCode→HTML、`Html.fromHtml`、内联图片 getter、代码块抽取这些
**全在主线程跑**，一点没被统计进去。

现在加了一行：

```
04:21:31  帖子 tid=173653  网络 321ms | 解析 22ms | 合计 343ms | 81KB | 本次请求 2 个
          └ 渲染 xxxms（主线程：BBCode→HTML、Html.fromHtml、图片、代码块）
```

---

## v3.2 (versionCode 17) — BBCode 编辑器 · 浏览历史 · 三处修复

### BBCode 编辑器（参考论坛「发帖预览插件」）

新增 `ui/widget/BBCodeEditor`，回复弹窗与发帖页共用：

- **实时预览** —— 输入停顿 300ms 自动重渲染，不用再来回点「预览/编辑」切换
- **21 个标签预设** —— 加粗 / 斜体 / 下划线 / 删除线 / 字号 1·3·5·7 / 链接 / 图片 /
  代码 / 引用 / 隐藏 / 免费 / 居中·居左·居右 / 分割线 / 列表 / 表格 / 背景色
- **10 色快速色板** —— 点一下直接套 `[color=#RRGGBB]`
- **RGB 取色器** —— 三滑块 + 实时色块，想要什么色有什么色
- **彩虹字** —— 选中文字后一键按 HSV 色相均匀铺开，逐字包 `[color]`（空白不着色，不产生空标签）

所有标签都是**套在选区上**；没选区就插一对并把光标放中间。

### 浏览历史

长按侧边栏「运行日志」打开。记 tid / 标题 / 作者 / 时间，最多 200 条，
同一帖子只留最新一条并置顶，点击直达，可一键清空。只存本机，不联网。

### 修复：隐藏帖标签一直不显示

标记本身是对的，问题在**列表没有重新绑定**：`applyCachedLikes()` 只在
「点赞数变了」时才 `notifyDataSetChanged()`，而隐藏标记是另一条路径写进来的，
于是标签永远不出现。改为返回列表时无条件重绑。

### 修复：应用主题全局不生效

主题色之前**只在 MainActivity 创建时刷了一次**，帖子详情页、设置页、账号页
根本没应用过 —— 所以看起来「切了没用」。
现在在 `Application` 里注册 `ActivityLifecycleCallbacks`，
每个 Activity 恢复时都刷一遍内容树。

### 「一个帖子排队 60 次」—— 加了计数

耗时日志现在会写出**本次页面加载实际发了几个请求**：

```
帖子 tid=173670  网络 312ms | 解析 6ms | 合计 318ms | 58KB | 本次请求 1 个  [...]
```

你之前看到的 `窗口 30/50` 是**最近 60 秒内全 App 的累计请求数**（含角标轮询、
签到检查等后台任务），不是单个帖子发了 30 次。有了「本次请求 N 个」就能直接确认。

---

## v3.1 (versionCode 16) — 图片点击终于修对 · 帖子秒开 · 回复框重做

### 图片原位显示还是点不开 —— v3.0 的修法根本没生效

v3.0 我用的是「给 ImageSpan 叠一层 ClickableSpan」。实测无效，原因是紧随其后的
`setupClickableLinks()` 会把 `textIsSelectable` 设为 **true** —— TextView 一旦进入
文本选择模式，单击就被 Editor 拿去放光标了，ClickableSpan 根本收不到。

改成**触摸命中测试**：按下时按坐标算出触点落在第几个字符，查该位置有没有 ImageSpan，
命中就拦下这次触摸并打开全屏预览。完全不依赖 MovementMethod，也不影响文本选择和链接点击。
（顺带把调用挪到 `setupClickableLinks` 之后。）

### 进帖再快一点 —— 帖子页内存缓存

网络已经压到 ~320ms、解析 5–80ms，想再快只能**不发请求**。
新增 `ThreadHtmlCache`（LRU 8 篇 / 5 分钟）：

- 刚看过的帖子直接用缓存**瞬间渲染**，同时后台静默拉新版本
- 从帖子退回列表再点进去、在几个帖子间来回切 → 0ms 出内容
- 回帖 / 下拉刷新会主动让该帖缓存失效，不会看到旧内容

耗时日志里会标出 `(缓存)` 以便区分。

### 回复框重做

| 问题 | 原因 / 改法 |
| --- | --- |
| 输入法覆盖输入框 | 弹窗没设 `SOFT_INPUT_ADJUST_RESIZE`，现在随键盘上移 |
| 滑动内容把回复框带走 | BottomSheet 的拖拽手势会抢走输入框的滚动，已 `setDraggable(false)` |
| 长内容看不全 | 输入框 4→10 行、可内部滚动；弹窗展开到全高并禁止折叠 |
| 没有 BBCode 预览 | 新增「预览」按钮，就地渲染成最终效果，再点回到编辑 |
| 常用 BBCode 要手打 | 新增工具条：加粗/斜体/下划线/删除线/颜色/字号/链接/图片/代码/引用/隐藏/居中，**套在选区上**，没选区就插一对标签并把光标放中间 |

**发帖页**同样支持：长按正文 = 插入 BBCode 预设，长按「高级」= BBCode 预览。

---

## v3.0 (versionCode 15) — 进帖慢的真正根因：是我的节流

### 你的日志直接定位了问题

```
令牌 11.0/12 → 网络 344ms / 364ms / 266ms / 306ms     均值 320ms
令牌  0.0/12 → 网络 2813ms / 2830ms / 2799ms / 2730ms  均值 2337ms
```

**真实网络只要 320ms，解析只要 5–80ms。慢出来的那 2000ms 全是我的节流器在罚站**
（v2.9 的 `MAX_WAIT_MS = 2500`）。日志里 `累计节流 187 次 / 351321ms` ——
光排队就烧掉了 351 秒。

而当初引入节流要挡的三个流量源（角标 5 秒 × 6 并发、首页每页 20 发收藏预取、
社区页每次 12 发版块头部）**在 v2.6/v2.7 就都已经在源头修掉了**。
留着一个对所有请求一视同仁收税的节流器，纯属帮倒忙。

### 改成两条车道

| 车道 | 谁走 | 行为 |
| --- | --- | --- |
| **前台** | 用户点开帖子 / 用户主页 / 下拉刷新 | **不排队**，只记账 |
| **后台** | 轮询、批量扫帖、预取 | 令牌桶限速（这才是该压的） |

同时把桶从 12 放大到 20、回填从 1300ms 加快到 700ms、后台最长等待从 2500ms 砍到 1200ms。

预期效果：进帖从 2800ms 回到 300ms 左右。

### 图片「原位显示」的两个问题

- **底部还有一份**：原位模式下我只跳过了正文抽离，但 `postDetail.getImageUrls()`
  仍然被塞进底部图廊的数据源，于是图片在正文和底部各出现一次。现在原位模式下
  底部图廊直接不收数据。
- **原位的图点不开**：ImageGetter 画出来的是 `ImageSpan`，本身不响应点击。
  现在遍历所有 ImageSpan 叠一层 `ClickableSpan`，点哪张就从哪张开始全屏翻页
  （表情、静态资源图不参与）。

### 日志太乱

- **自动解锁记录独立成条**，一行一个帖子，结果一目了然：
  ```
  ═════ 自动解锁记录 ═════
  09-30 02:21  ✓ 已解锁  tid=173615  「网飞猫Ncat350」看着不错，谢谢分享，下来试试
  09-30 02:22  ○ 跳过    tid=173615  已经是解锁状态
  09-30 02:30  ○ 跳过    tid=162387  已经是解锁状态
  ```
- **签到「今日已签，跳过」不再刷屏** —— 之前每次回到前台都为每个账号刷一行，
  把运行日志冲得没法看。现在跳过的不记，只在真正动作时记。
- 运行日志现在分三段：加载耗时 / 自动解锁记录 / 运行日志。

---

## v2.9 (versionCode 14) — 七项反馈修复

### 1. 加载慢但看不到日志

**我上版的埋点确实等于没做**：它写进了 `AiLog`，而 `AiLog` 是 300 条的环形缓冲，
自动回复、签到、角标刷新的日志一冲，几条性能记录立刻被挤没。而且用户主页**根本没埋点**。

现在：

- 新增独立的 `PerfLog`（80 条小缓冲，只装加载耗时，不会被别的日志冲掉）
- 帖子详情页 **和** 用户主页都埋点
- 侧边栏「运行日志」顶部直接给出加载耗时摘要：

```
===== 页面加载耗时（最新在上）=====
14:22:07  帖子 tid=173642  网络 2840ms | 解析 310ms | 合计 3150ms | 186KB  [窗口 7/50，令牌 9.2/12]
14:21:33  用户主页          网络 980ms | 解析 0ms | 合计 980ms | 42KB
```

### 2. 自动解锁「开启却没用」—— 我上版改过头了

v2.8 我写的是两个开关都要为真：

```java
if (!isUnlockMode || !isUnlockOnView) return false;
```

但 AI 配置页里这两个开关是**分开的**，只要任意一个被单独关掉，进帖解锁就整个失效。
现在只认「进帖自动解锁」这一个开关。

另外这个方法原本有 **5 个静默 `return false`**（缺 tid、快照为空、无隐藏块、已解锁、已认领过），
出问题完全看不出卡在哪 —— 现在每个分支都记一行日志。
还修了一个隐患：回帖失败时没有释放 tid 认领，导致该帖在本次运行内**永远不会再试**。

### 3. 隐藏帖的「回复」不再跳浏览器

「如果您要查看本帖隐藏内容请回复」里的链接以前会拉起浏览器，
现在点击直接弹出应用内的快捷回复面板。

### 4. AI 总结关不掉 —— 漏了列表卡片上那个

v2.8 只 gate 了详情页的按钮，**帖子列表每张卡片上还有一个**（`ThreadAdapter` 里的
`btn_ai_summary`），所以看起来完全没关掉。现在两处都受同一个开关控制。

### 5. 隐藏帖在列表里打标

进过详情页确认含隐藏内容的帖子，回到列表会显示「隐藏」标签，方便快速区分。
（论坛列表页 HTML 本身不提供隐藏标记，所以只能进过一次才知道。）

### 6. 签到结果有时只显示「今日已签」

已签到状态下，服务端页面上**拿不到当天的奖励数字**（`lxreward` 只在未签到的按钮上有值）。
之前会用空串把先前记下的排名/金币覆盖掉，于是同一天再点一次签到，明细就没了。
现在空值不覆盖已有记录。

### 7. 正文图片位置可配

侧边栏新增「正文图片原位显示」（默认开）：
开 = 图文混排留在原位；关 = 全部抽出来汇总到帖子底部的横滑图廊（旧行为）。

---

## v2.8 (versionCode 13) — 七项反馈修复

### 1. 侧边栏签到时间/奖励 + 自动签到不生效

- **奖励显示不出来的原因**：自动签到有两条链路，单账号旧链路只把一句文案写进记录
  （`recordSign(..., "", "")`，排名和奖励都是空串），只有多账号链路才会解析排名/奖励。
  现在**只要账号库里有记录就一律走多账号链路**，时间 / 排名 / 金币都能记上。
- **自动签到有没有跑、为什么没签上**：以前侧边栏那行永远写死「启动时自动打卡」，
  完全看不出状态。现在显示上一轮的真实结果，例如
  `09-30 08:31 成功 2 · 跳过 1 · 失败 0`，没跑过就显示「还没跑过」。
- **防重复签到**：有两层，一直都在——
  ① 每个账号按天记录 `lastSignDate`，同一天不会重复提交；
  ② 全局 `isSignedInToday` 兜底。账号管理页顶部的「今日已签 N/M」就是这个数据。

### 2. 部分帖子加载很慢

详情页 URL 一直拼着 `&_load=当前时间戳` 这个**缓存破坏参数**，每次进帖都是一个全新 URL，
彻底绕开 CDN 缓存、连接复用和应用内请求去重。几百楼的大帖每次都要整页重下 ——
这是「部分帖子特别慢」的主因。已去掉（下拉刷新仍保留强刷参数）。

同时加了分阶段耗时埋点，慢的时候可以在「运行日志」里直接看到卡在哪：

```
thread-load  tid=173642 网络 2840ms 解析 310ms 页面 186KB 节流[窗口 7/50，令牌 9.2/12]
```

### 3. 代码块自动换行

代码块工具条新增「换行 / 不换行」按钮：

- **换行**（默认）：整段可见，手机上好读
- **不换行**：横向滚动，保持严格缩进对齐

换行状态下一条逻辑行会占多个视觉行，行号再标就对不上了，所以换行时自动隐藏行号装订线。

### 4. 隐藏内容位置可配

侧边栏新增「隐藏内容就地展开」（默认开）：

- **开**：内容就在正文原处展开，不再留占位胶囊
- **关**：正文留占位、完整内容放帖子底部（旧行为）

### 5. AI 总结按钮可开关

侧边栏新增「帖子页 AI 总结按钮」，**默认关闭**。避免被当成论坛自带功能产生误会。

### 6. 修复：自动解锁隐藏内容关掉后仍然生效

`AutoReplyEngine.tryUnlockOnOpen()` **完全没有检查开关** ——
只要点开带隐藏块的帖子就会自动回帖，不管用户有没有把开关关掉。
现在 `isUnlockMode` 与 `isUnlockOnView` 任意一个为关都会跳过。

### 7. 社区签到按钮：特殊符号 + 状态不同步

- **特殊符号**：Comiis 模板用 `<i class="comiis_font">&#xe60c;</i>` 这类**图标字体**，
  字符落在 Unicode 私用区（U+E000–U+F8FF）。网页上有字体文件所以显示成图标，
  抓进 TextView 后没有那套字体，就渲染成方块。
  `TextClean` 现在会剥掉私用区字符，签到按钮文案统一过一遍清洗。
- **状态不同步**：`refreshSignIn()` 只看全局 `isSignedInToday`，
  而多账号签到写的是账号库记录，两边对不上。现在**两个来源任一为真**即显示已签到。

---

## v2.7 (versionCode 12) — 合并分支修复 · 崩溃与慢加载修复 · 应用主题

### 合并了分支作者的修复

对着原始 `MT源码.zip` 做了三方 diff，分支共改动 27 个文件、新增 10 个：

| 分支修复 | 说明 |
| --- | --- |
| 「只看楼主」不生效 | 过滤动作的方法体是**空块**，点了没反应 |
| 帖子发出后无法编辑 | 走 `forum.php?mod=post&action=edit`，复用发帖页编辑模式 |
| 评论发图片发不出去 | 上传得到的 `aid` 没随回复提交 `attachnew`，附件不会被关联 |
| 评论的评论不能打赏/举报/删除 | 新增评论操作菜单（长按或 ⋮ 触发） |
| 长按标题举报帖子 | 新增举报对话框 |
| 查看点赞人全称显示错误 | 详情页 DOM 只有头像没昵称，改走 `misc.php?op=recommend` 独立接口 |
| 分享链接帖子的 AI 总结 | 详情页加 AI 总结入口 |
| 回复内容无法复制 | `setupClickableLinks` 写死 `setLongClickable(false)` 把长按也禁了 |

### 请求过多 —— 另外两个大头

上个版本我只找到「角标轮询 5 秒 × 6 并发 = 72 次/分钟」。对照分支发现还有两处：

- **首页列表每加载一页发 20 个请求**（`FavoritePrefetcher` 预取收藏数），
  改为进详情页时回填缓存（`PostCountsCache`）
- **每进一次社区页发 12 个请求**（逐版块抓头部），
  而版块描述字段在 UI 里是 `GONE` 的，**纯浪费**
- 自动回复的「解锁模式」由后台扫全站改为**进帖触发**
- 角标 `onResume` 加 60 秒节流；进入分类后本地即时清红点，零额外请求

### 修复：进帖加载很慢（v2.6 引入）

是我上个版本的节流写坏了，两个问题：

1. 「每个请求固定等 220ms」的硬间隔 —— 打开一个帖子要发好几个请求，每次进帖平白多等一截
2. 收到 403 后把 `lastRequestAt` 推到 **20 秒之后**，导致之后**每一个**请求都各等满 8 秒超时

改成**令牌桶**：桶里有令牌立刻放行（突发 12 个），只压制持续高频；
403 只清空令牌桶透支一轮，不再把时间轴整体后推。单请求最长等待 8s → 2.5s。

### 修复：退出帖子时崩溃

```
java.lang.IllegalArgumentException: You cannot start a load for a destroyed activity
  at com.bumptech.glide.Glide.with(Glide.java:574)
  at ThreadDetailActivity.bindData(ThreadDetailActivity.java:504)
```

`loadPostDetail` 是异步的，用户在加载完成前退出时 Activity 已销毁，
回调里的 `Glide.with(this)` 直接抛异常。已在 `bindData` 入口加销毁守卫。

### 新增：应用主题

设置页新增「应用主题」卡片：

- **深色模式**：跟随系统 / 浅色 / 深色，切换后立即生效
- **主题色**：6 个预设（科技蓝 / 墨绿 / 曜紫 / 赤橙 / 玫红 / 石墨），圆形色板选择

补齐了 `values-night` 里会漏成浅色的 30 个颜色（置顶卡片底、引用框、图片占位、
主色 alpha 版、消息分类色等），深色模式下不再出现刺眼白块。

> 说明：工程里绝大多数控件写死的是 `@color/primary` 这个**资源**而不是
> `?attr/colorPrimary` 属性，资源没法在运行时改。所以主题色采用
> 「遍历视图树、把原本等于默认主色的地方换成所选主色」的方式实现，
> 少量固定配色的插图不参与换肤。

---

## v2.6 (versionCode 11) — 请求节流（风控根因）· BBCode 复制 · 切号刷新 · 底栏自动隐藏

### 找到并修掉「被禁止访问」的根因

这一条同时对应「执行一轮自动回复会被风控」和分支作者说的
「原作者发送了太多无效请求，一分钟内可能连发几十次」。

```java
// 改版前 MainActivity
private static final long BADGE_REFRESH_INTERVAL_MS = 5000;  // 5 秒
```

而每一轮 `refreshMessageBadge()` 会**并发 6 个请求**（pm / follower / mypost /
interactive / system / app）。也就是说——

> **光挂在首页什么都不干，就是 6 × 12 = 72 次请求/分钟。**

再叠加自动回复扫帖、搜索翻页、切页重载，撞上阿里云 ESA 的 IP 级 403 是必然的。

修法（三层）：

1. **角标轮询 5 秒 → 60 秒**，72 次/分钟直接降到 6 次/分钟
2. **全局请求节流** `RequestThrottle`，挂在 OkHttp 拦截器上，所有走 `HttpClient`
   的请求都过这一关：
   - 相邻请求最小间隔 220ms（削掉并发突刺）
   - 任意 60 秒窗口内最多 45 个请求，超了阻塞等待
   - 一旦收到 403，主动退避冷却 20 秒，不再火上浇油
3. 搜索的 900ms 翻页间隔（v2.4）保留，与全局节流叠加

### 正文复制改为 BBCode

之前复制下来的是「看到的文本」，代码块、图片、链接、排版全丢了，没法转发引用。

新增 `HtmlToBBCode`，思路来自油猴脚本的 `html2bbcode()`，但改成 **Jsoup 递归遍历**
而不是一串正则替换 —— 正则版在嵌套时闭合标签容易错配
（比如 `<font color>` 里套 `<b>`）。

支持：`[code] [quote] [hide] [img] [url] [email] [qq] [b] [i] [u] [s]
[color] [size] [backcolor] [align] [table] [tr] [td] [list] [*] [hr]`

交互：

- **主楼「复制正文」按钮**：点击 = BBCode 原文，长按 = 纯文本
- **评论区长按菜单**新增「复制 BBCode 原文」

### 切换账号后的两个问题

1. **切号后还显示旧账号数据 / 莫名 403** —— `HttpClient` 有个按 URL 做的
   「飞行中请求去重」缓存 `pendingGets`/`pendingPosts`，切号时没清，
   新账号请求同一个 URL 会直接拿到**上一个账号的页面**。
   现在 `switchTo()` 会先 `clearPendingCache()`，并整体加 `synchronized`
   避免清 Cookie 与恢复 Cookie 之间的竞态
2. **「我的」页不自动刷新** —— 新增账号切换代数 `AccountManager.currentEpoch()`，
   首页 / 版块 / 我的在 `onResume` 比对代数，变了就自动重载；
   切号当下还会立即刷新当前可见页，不用手动下拉

### 楼层标签异常符号

论坛结构是 `<span class="f_d y">\n21<sup>#</sup></span>`，
Jsoup 的 `text()` 本身能给出干净的 `21#`（已实测确认）。
但不同模板/插件可能在数字与 `#` 之间塞零宽字符或控制符，
渲染出来就是方块/问号，而日志里完全看不出来。

新增 `TextClean`，统一清洗零宽字符（ZWSP/ZWNJ/ZWJ/BOM/软连字符/方向控制符）
与 C0/C1 控制符，数字型楼层统一归一成 `N#`。

### 底栏滚动自动隐藏（可开关）

下滑隐藏、上滑立刻出现，切页与滑到顶部一定显示。用弹簧位移而非
`setVisibility`，避免布局跳动。侧边栏「其他」组上方新增开关，默认开。

### AI 功能归拢

侧边栏的 AI 相关项在 v2.3 已统一到「AI 自动化」分组
（自动回复 / 自动解锁 / 解锁回复内容 / 演练模式 / 隐藏运行 / 立即执行一轮 /
AI 配置 / AI 助手），本版未再拆分。

### 测试

新增 `HtmlToBBCodeTest`（13 项）与 `TextCleanTest`（6 项），
连同原有 37 项共 **56 项全部通过**。

---

## v2.5 (versionCode 10) — 正文/评论复制 · 修复复制代码带行号

参考用户提供的油猴脚本「mt手机代码快复制功能」（作者 hw1020）的实现思路。

### 修复：复制出来的代码带行号（v2.4 引入）

脚本里的选择器 `.blockcode>div, .comiis_blockcode>div` 和正则
`<div class="comiis_blockcode[^>]*><div class="bg_f b_l"><ol>([\s\S]*?)</ol></div></div>`
点破了论坛的真实结构：**行号来自 `<ol>` 的 CSS 计数器，不在文本里**，
所以脚本取 `innerText` 拿到的就是干净代码。

而本地 `ForumParser.normalizeCodeBlocks()` 是这么干的：

```java
lines.add(lineNo + " " + t);   // 把行号直接拼进文本
```

于是 v2.4 新增的「复制代码」会把行号一起复制走，粘到编辑器里每行顶着个数字，全是语法错误。
**界面上看不出问题**——行号本来就该显示。

修法：

- 行号改为包进 `<span class="mt-ln">`，只用于显示
- `CodeBlockView` 自己画**行号装订线**（独立固定列，代码横向滚动时行号不动），
  显示与复制彻底解耦，复制永远拿干净代码
- 抽取时剥掉 `span.mt-ln`

### 抽取改用 Jsoup，覆盖论坛原生结构

`extractCodeBlocks` 从正则改为 Jsoup 解析，现在同时支持：

| 结构 | 来源 |
| --- | --- |
| `<pre class="comiis_blockcode">` | 本地 `normalizeCodeBlocks` / `[code]` 渲染产物 |
| `<div class="comiis_blockcode"><div><ol><li>…` | **论坛移动版原生结构**（v2.4 漏了这个） |
| `<div class="blockcode">` | 桌面版结构 |

嵌套情况（`div.blockcode` 里套 `<pre>`）只摘最外层，不会出现两块。

### 正文复制按钮 / 评论区长按复制

按要求分工：**正文是按钮，评论区是长按**。

- **主楼**：正文右上方一个「复制正文」按钮。代码块已被摘走，复制时会自动把干净代码补回去，
  不会漏内容
- **主楼代码块**：和回复一样渲染成可折叠 + 可复制的卡片（v2.4 只有回复有）
- **评论区**：长按任意一条回复弹出菜单
  - 复制这条回复
  - 只复制代码（仅当该条含代码块 —— 多数时候人就是来抄那段代码的）
  - 复制含楼层署名

### 修掉的两个解析 bug（单元测试发现）

1. `</?p[^>]*>` 这个正则会把 `<pre>` 和 `</pre>` 一起吃掉（`p` 后面跟 `re` 正好落进 `[^>]*`），
   导致代码首尾多出空行。已限定为 `</?p(?:\s[^>]*)?>`
2. 读语言标签时行号 span 还没剥掉，于是行号「1」被当成了语言名。已调整剥离顺序

### 测试

新增 **两个测试类共 15 项**，连同原有 22 项共 **37 项全部通过**：

- `CodeBlockExtractTest`（11 项）：论坛原生结构、行号剥离、语言标签、多块、嵌套、实体顺序
- `CodePipelineEndToEndTest`（4 项）：走完整真实管线
  `normalizeCodeBlocks → extractCodeBlocks`，**逐行断言复制结果不含行号前缀**

---

## v2.4 (versionCode 9) — 提高不透明度 · 修搜索风控 · 代码块折叠复制 · 快捷回复 · 底栏刷新

### 1. 窗口不透明度

`FrostedGlassDrawable` 原来把填充色**写死成 62%→50% 不透明度**，对话框和卡片叠在帖子列表上时
背景文字会透上来，长文基本没法读。现在拆成两档：

| 档位 | 不透明度 | 用于 |
| --- | --- | --- |
| `LEVEL_CARD` | 96% → 93% | 卡片、列表项（保留一点玻璃感） |
| `LEVEL_DIALOG` | 99% → 97% | 对话框、底部弹窗（本来就该压住背景） |

`DialogHelper` / `FrostedGlassHelper` 同步支持传档位。

### 2. 搜索风控

**根因找到了**：`SearchActivity.fetchAllResults()` 抓完第 1 页后，会用一个 4 线程池
**把剩余所有页一次性并发抓完**。命中多的关键词能一口气打出二三十个请求 ——
bbs.binmt.cc 前面挂着阿里云 ESA，这种突发流量必定触发 IP 级 403。

「最新回复」之所以感觉更容易中招：它是每次新搜索的默认排序，而且服务端要对整个结果集
按最后回复时间重排、命中面通常更大、页数更多，于是突发请求也更多。

修法：

- **改为按需分页** —— 滚到底才取下一页，串行单请求，相邻两次强制间隔 900ms
- **默认排序改为「最新发布」**（`dateline`），三个排序选项都保留
- **识别 403** —— 命中防护时给出明确提示（"请求太密集了，歇一会儿再搜"）并停止继续翻页，
  而不是继续硬撞

### 3. 帖子正文：代码块折叠 + 一键复制

之前 `[code]` 是用 `Html.fromHtml` 的 TagHandler 直接画成正文里的一段 span ——
几百行的代码会把整条回复撑到翻不完，而且没法单独复制。

- 新增 `BBCodeUtil.extractCodeBlocks()`：把 `<pre>` 从正文 HTML 里摘出来，还原成纯代码文本
- 新增 `ui/widget/CodeBlockView`：每块代码独立成卡片，带
  **语言标签 + 行数 + 复制按钮 + 展开/收起**，等宽字体、横向可滚动（代码不折行）
- 超过 12 行默认折叠，只露 8 行，底部渐隐条提示"点击展开"
- **引用块**同样支持：超过 4 行折叠，旁边给复制按钮

### 4. 快捷回复（内容可自定义）

回复弹窗新增快捷短语条：

- **轻点** = 把短语填进输入框（可继续修改）
- **长按** = 直接发送
- 「自定义」按钮打开多行编辑器，一行一条，最多 20 条，可一键恢复默认
- 内置 8 条默认短语，存在 `app_settings/quick_reply_items`

### 5. 底栏点击刷新

新增 `ui.Refreshable` 接口。底栏再次点击**当前已选中**的 Tab 会触发该页刷新：

| Tab | 行为 |
| --- | --- |
| 首页 | 回到顶部 + 重新拉取帖子（正在刷新时不重复发请求） |
| 版块 | 重新加载版块数据 |
| 消息 | 重新拉取六类角标 |
| 我的 | 重新加载个人资料 |

图标会弹一下给出"已响应"的反馈。

### 测试

新增 11 项 JVM 单元测试（代码块抽取 6 项 + 快捷回复解析 5 项），连同原有 17 项共 **28 项全部通过**。
为此把 `extractCodeBlocks` / `parseLines` 等解析逻辑从 `android.text.TextUtils` 解耦成纯 Java。

---

## v2.3 (versionCode 8) — 底栏重做 · 图标全矢量 · 动效体系 · 排版优化

本版参考了两个项目：底栏学 [kd64i/dyparse](https://github.com/kd64i/dyparse)，
动效体系移植 [feitangyuan/motion-web](https://github.com/feitangyuan/motion-web)。

### 底栏重做（参考 dyparse）

dyparse 的 `FloatingBottomBarInsets.kt` 里写得很清楚：栏体 64dp，**悬在系统导航栏之上 12dp**，
内容区预留 `navInset + 64 + 12 + 16`。对照我们原来的实现：

| | 改版前 | 改版后 |
| --- | --- | --- |
| 栏体高度 | 68dp | 64dp |
| 底部间距 | 写死 12dp，**不管系统导航栏** | 12dp + `WindowInsets.navigationBars` 实测值 |
| 选中反馈 | 150ms 淡入 | 指示器 snappy 弹簧滑动 + 图标 bouncy 弹簧过冲 |
| 按压反馈 | 无 | 按下 0.90 缩放，抬手弹簧回弹 |
| 底板 | `<shape>` 里写了个 `<elevation>` 标签（**该元素不存在，等于没生效**） | 交给 View 的 elevation，加 0.5dp 描边 |

- 新增独立的指示器图层 `nav_indicator`，切页时用 `SpringAnimation` 滑到目标槽位
  （中间凸起的发布键占第 3 槽，指示器会跳过它）
- 边到边模式下内容区额外让出手势条高度，避免各 Fragment 里写死的留白不够用

### 图标全部矢量化

新增 12 个 `VectorDrawable`（等价 SVG，路径矢量、可 `tint`、不依赖系统 emoji 字体），
替换掉所有当图标用的 emoji：

| 位置 | 原 emoji | 新图标 |
| --- | --- | --- |
| 消息页 6 个分类 | 💬 👥 📝 💬 🔔 📱 | `ic_msg_chat` / `ic_msg_fans` / `ic_msg_posts` / `ic_msg_interactive` / `ic_msg_system` / `ic_msg_app` |
| 发帖页工具栏 | 📝 🖼 📎 ⚙ | `ic_smile` / `ic_image` / `ic_attach` / `ic_gear`（改用 `drawableStart`） |
| AI 配置状态 | ✅ ❌ | 文案化 + `ic_check_circle` / `ic_error_circle` |
| 资料页性别 | ♂ ♀ | 文案化 + `ic_gender_male` / `ic_gender_female` |
| 返回按钮 | 「← 返回」文本 | `ic_arrow_left` 矢量图 |
| 侧边栏已签标记 | ✓ 字形 | `ic_check` 矢量 `drawableStart` |

emoji 当图标的问题：跨设备字形不一致（三星/小米/原生各画各的）、无法跟随主题 tint、
在部分定制 ROM 上直接显示成豆腐块。

### 动效体系（移植 motion-web）

新增 `ui/anim/Motion.java` 作为统一令牌层。motion-web 是给 Web 写的，但数学是通用的：
CSS `cubic-bezier(x1,y1,x2,y2)` 与 Android `PathInterpolator` 控制点一一对应；
Framer 的 `stiffness/damping` 换算 `ζ = damping/(2√stiffness)` 就是 `SpringForce` 的阻尼比。

- **时长刻度**：INSTANT 90 / FAST 180 / STANDARD 320 / MEDIUM 450 / SLOW 700，列表错峰 70ms
- **缓动字典**：easeOutExpo `(.16,1,.3,1)`、easeOutBack `(.34,1.56,.64,1)`、
  easeOutCirc `(0,.55,.45,1)`、easeInExpo `(.7,0,.84,0)`、easeStandard `(.4,0,.2,1)`
  —— 同时做成 `res/interpolator/*.xml` 供 XML 动画复用
- **弹簧预设**：snappy 350/0.75、bouncy 200/0.35、default 200/0.78、
  pager 322/0.90（dyparse 同款）
- **按压反馈**：按 handfeel.md §7「down-records / up-decides」—— 按下只缩放，
  抬手立刻弹簧回弹，不挂任何计时器
- **全局转场**：`MtWindowAnimation` 挂到主题上，进场 easeOutExpo 320ms、
  退场 easeInExpo 240ms（退出用 easeIn 才干脆）

核心原则（handfeel.md §1）：**别用 lerp，用欠阻尼弹簧**。lerp 单调减速读起来像"滑过去"，
ζ 略小于 1 的弹簧轻微过冲再落位，才有"有重量地落下"的感觉。

已接入：底栏指示器与图标、侧边栏账号卡片（错峰 + 按压）、消息页六个入口（错峰 + 按压）、
账号管理列表（`layoutAnimation` 错峰）、全局 Activity 转场。

### 排版优化

- 消息页六项收进一张 14dp 圆角卡片，行高统一 60dp，**新增副标题**补全信息层级
  （私信与对话 / 关注你的人 / 你发布的主题 / 回复、@ 与点赞 / 论坛官方通知 / 版本更新与公告）
- 顶栏标题 20sp bold，「全部已读」改为淡主色药丸而非整条红色按钮
- 图标统一 38dp 圆底 + 22dp 图形，分隔线从 62dp 起始对齐文字左缘
- 新增 12 个分类语义色（各自 14% 淡底）

---

## v2.2 (versionCode 7) — 侧边栏重排 · 账号平铺快切 · 掉线自动重登 · 消息页修复

### 侧边栏重新分组

分组从「自动化 / 操作 / AI / 其他」改为按使用场景归拢：

| 新分组 | 内容 |
| --- | --- |
| **账号** | 已登录账号平铺列表、添加账号、账号与签到管理 |
| **签到** | 自动签到开关（含定时状态）、立即签到（含今日进度） |
| **AI 自动化** | 自动回复、自动解锁、解锁回复内容、演练模式、隐藏运行、立即执行一轮、AI 配置、AI 助手 |
| **其他** | 个人小黑屋、设置、运行日志 |

签到相关的开关和动作原本散在「自动化」和「操作」两个组里，现在归到一处；账号从最底下提到最顶上。

### 账号平铺快速切换

- 侧边栏直接平铺所有已保存账号，**点一下整行即切换**，不再弹对话框
- 每行显示：头像、昵称、`当前` 标记、**今日签到状态 + 签到时间 + 获得金币 + 排名**
- 右侧「签到」小胶囊可单独给该账号补签，已签到则置灰显示「已签」
- 长按账号行 → 切换 / 修改密码 / 清除密码 / 删除 / 进管理页
- 当前账号用主色描边 + 淡主色底高亮

### 掉线自动重登（全局）

之前只有签到链路会自动重登，正常浏览时被 403 打掉会话就得手动登录。现在：

- 新增 `SessionGuard`：网络层一旦收到 **HTTP 403** 或返回登录页，就用已加密保存的密码静默重登，
  新 Cookie 写回全局会话，用户无感
- 带 60 秒冷却 + 单飞锁，密码错误时不会反复打服务器
- App 回前台时（`onStart`）也做一次兜底检查
- 切换到登录态已失效的账号时，自动尝试用保存的密码恢复
- `HttpClient` 用监听器回调解耦，network 包不反向依赖 session 包

### 密码只写不读

- 已保存密码的账号，弹窗只显示 `当前状态：已保存密码（••••••••）`，**永不回显明文**
- 输入新密码即覆盖，另提供「清除已保存的密码」
- 侧边栏长按菜单与账号管理页行为一致

### 修复

- **登录了侧边栏仍显示「未登录」**。两个原因：
  1. `drawer_accounts_desc` 在 XML 里写死成「当前账号：未登录」，代码里**从未被赋值过**；
  2. `UserSessionManager.isLoggedIn()` 强依赖 uid，资料页抓取失败时 uid 为空就判未登录。
  现在补了「Cookie 还活着也算已登录」的兜底，并异步把 uid/头像补回来；
  账号库为空（老用户升级）时用当前会话合成一行，不再显示成「没有账号」。
- **消息页底部被导航栏遮挡**。四个 Tab 里只有 `fragment_notice.xml` 漏了底部留白
  （首页 80dp / 版块 92dp / 我的 80dp），而悬浮胶囊导航栏占 68+12=80dp，
  屏幕越矮盖得越多，所以表现为「有概率」。已改为可滚动 + 96dp 底部留白。
- **私信气泡不符合主题**。原本是 `background="@color/surface"` 的直角方块，
  改为圆角气泡 drawable（对方白底细描边、自己主题蓝，尖角指向头像）。
  头像默认图从系统的绿色安卓机器人换成应用内占位图。
- **私信长消息显示不全**。`maxWidth="280dp"` 写在 `LinearLayout` 上 —— 该属性对
  LinearLayout 无效，长消息会一路撑出屏幕。已把 `maxWidth` 移到 `TextView` 上并折行。
- **消息列表文字被硬截断**。标题/摘要 `maxLines="1"` 却没有 `ellipsize`，
  被切掉且没有省略号；标题还没设 `textColor`。现在标题 2 行、摘要 3 行、
  统一 `ellipsize="end"` 并补齐颜色。

---

## v2.1 (versionCode 6) — 多账号登录 · 切换 · 批量签到

参考 [Forinxy/mt](https://github.com/Forinxy/mt) 的多账号签到实现，移植到本客户端的 Cookie 会话体系。

### 新增

- **独立会话签到引擎** `MtSignApi`
  - 每次调用新建带独立内存 CookieJar 的 OkHttpClient，批量签到时**不会顶掉前台登录的账号**
  - 两条链路：Cookie 免登录直签 / 账号密码登录后签到
  - 固定 HTTP/1.1 + `Connection: close` + IOException 自动重试，规避论坛服务端 `unexpected end of stream`
  - 签到走 `plugin.php?id=k_misign:sign&...&format=text`，失败自动回退到伪静态按钮接口
- **密码托管** `CryptoUtils`
  - Android KeyStore 硬件密钥，AES/GCM-256，密文格式 `ks:Base64(IV+CT+Tag)`
  - KeyStore 不可用的定制 ROM 降级为混淆存储，功能不整个失效
- **多账号存储升级** `AccountManager`
  - 新增字段：加密密码、是否参与批量签到、每账号签到记录（状态/排名/奖励/日期）
  - 新增 Cookie 快照 ↔ Cookie 请求头 双向转换
  - 兼容旧版数据结构，老用户升级不丢账号
- **批量签到调度** `MultiSignInManager`
  - 逐账号签到，Cookie 失效且已托管密码时自动重登并回存新 Cookie
  - 账号间可配间隔（默认 5 秒），防论坛 ESA 按 IP 限流 403
  - 本地记录「今日已签」的账号自动跳过，减少无谓请求
- **每日定时签到** `SignInScheduler` + `SignInWorker`
  - WorkManager 周期任务，可设定执行时刻（默认 08:30）
  - 全部失败时走指数退避重试
- **结果通知** `SignInNotifier`，可展开查看每个账号的明细
- **账号与签到管理页** `AccountManagerActivity`
  - 账号卡片：头像、昵称、UID/等级、今日签到状态、是否参与批量签到
  - 单条操作：切换 / 单独签到 / 存改密码 / 删除
  - 设置项：进入应用自动签到、多账号一起签、每日定时、签到时间、掉线自动重登、结果通知、账号间隔
- **单元测试** `SignParserTest`（17 项），含一份实测抓取的游客签到页作回归 fixture

### 修复

- **游客页被误判为已登录**：早期实现拿页面里是否含 `k_misign` / `spacecp` 当登录态标记，
  但实测游客访问签到页时这两个字样同样存在（侧边栏推广链接），而 `formhash` 出现 0 次。
  判定改为只认 `formhash`，并写进单元测试防回归。
- **限流提示被误报成「密码错误」**：论坛的「密码错误次数过多」提示里也含「密码错误」四个字，
  原判断顺序会先命中密码错误分支，导致被临时锁定的用户以为密码不对跑去改密码。已调整优先级。
- **切换账号后沿用上一个账号的签到日期**：签到日期是全局键，切号后未重置会让新账号被误判
  「今日已签」而跳过签到。切换 / 登录时统一清除。

### 变更

- 登录弹窗新增「记住密码」勾选项（默认勾选）与说明文案，支持预填用户名
- 登录成功（密码 / Cookie 两种方式）后自动入账号库，不再需要手动补存
- 侧边栏「立即签到」在多账号场景下改为批量签到并弹出逐账号结果明细
- 侧边栏「切换账号」弹窗展示每个账号的今日签到状态，并新增管理页入口
- 设置页新增「账号与签到」入口卡片，显示已保存账号数
- `AutoSignInManager` 重构为入口分流（多账号 / 单账号），旧的单账号逻辑完整保留
- 新增依赖 `androidx.work:work-runtime:2.9.1`、`junit:junit:4.13.2`
- 新增权限 `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`

---

## v2.0 (versionCode 5)

- build59 版本，含登录链路修复：切换账号、错误密码拒绝、ESA 403 识别
