# 更新日志

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
