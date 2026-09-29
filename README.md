# MTForum — MT 论坛第三方客户端

[bbs.binmt.cc](https://bbs.binmt.cc/) 的第三方 Android 客户端。原生 Java + Material Design，覆盖板块浏览、帖子阅读、回复/发帖、个人中心、**多账号登录与切换**、**多账号自动签到**、AI 自动回复。

当前版本：**v3.4（versionCode 19）**

---

## v3.4：角标误报 · 隐藏标注 · 图片全宽 · 主题透明度

角标基线按账号隔离（修「全部历史变新消息」）；隐藏标记落盘；长按改复制链接、
拉黑挪进帖子；原位图片全宽显示原图；主题三档不透明度可调。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v3.3：渐变字 · 附件下载 · 渲染耗时埋点

渐变字（8 种预设，两色平滑插值）；附件解析与下载（带金币消耗二次确认）；
新增渲染耗时埋点定位「感觉慢」的真凶。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v3.2：BBCode 编辑器 · 浏览历史

实时预览 + 21 个标签预设 + RGB 取色器 + 彩虹字；浏览历史；
修复隐藏帖标签不显示、主题色全局不生效；耗时日志加「本次请求 N 个」。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v3.1：图片点击修对 · 帖子秒开 · 回复框重做

触摸命中测试替代无效的 ClickableSpan；帖子页内存缓存（LRU 8 篇）实现秒开；
回复框修输入法遮挡/滑动带走/长内容，新增 BBCode 预览与 12 个常用标签预设（发帖页同样支持）。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v3.0：进帖慢的真正根因

实测日志显示真实网络只要 320ms、解析 5–80ms，慢出来的 2000ms 全是节流器罚站。
改为前台/后台两条车道：用户导航不排队，只压后台轮询。
另修图片原位显示（底部重复 + 点不开）、日志分三段。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.9：七项反馈修复

独立 PerfLog 加载耗时日志（帖子+用户页）、修复解锁开关改过头、隐藏帖「回复」不跳浏览器、
AI 总结补上列表卡那个、隐藏帖列表打标、签到明细不被空值覆盖、正文图片位置可配。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.8：七项反馈修复

签到时间/奖励显示、自动签到状态可见、进帖变快（去掉缓存破坏参数）、代码块自动换行、
隐藏内容就地展开、AI 总结按钮可关、修复解锁开关失效、修复签到按钮特殊符号与状态不同步。
详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.7 新增：合并分支修复 · 崩溃与慢加载修复 · 应用主题

- 合并分支作者的 8 项功能修复（只看楼主、帖子编辑、评论发图、评论打赏/举报/删除、
  长按标题举报、点赞人全称、AI 总结、回复可复制）
- 再砍两处请求大头：首页每页 20 发的收藏预取、社区页每次 12 发的版块头部
- 修 v2.6 的进帖慢（节流改令牌桶）与退出帖子崩溃（Glide 销毁守卫）
- 设置页新增**应用主题**：深色模式 + 6 种主题色

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.6 新增：请求节流（风控根因）· BBCode 复制 · 切号刷新 · 底栏自动隐藏

- **找到「被禁止访问」根因**：角标轮询 5 秒一轮 × 每轮 6 个并发 = **72 次请求/分钟**。
  已改为 60 秒，并加全局 `RequestThrottle`（220ms 最小间隔 + 60s/45 次窗口 + 403 自动退避）
- **复制正文改为 BBCode**（`HtmlToBBCode`，Jsoup 递归而非正则），点击=BBCode / 长按=纯文本
- **切号修复**：清请求去重缓存（旧账号页面会被复用）+ 账号代数驱动各页自动重载
- **楼层异常符号**：`TextClean` 清洗零宽与控制字符
- **底栏滚动自动隐藏**，侧边栏可开关

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.5 新增：正文/评论复制 · 修复复制代码带行号

- **正文是按钮、评论区是长按** —— 主楼「复制正文」按钮；长按回复弹出复制菜单（整条 / 只复制代码 / 含楼层署名）
- **修复 v2.4 的行号污染**：`normalizeCodeBlocks` 把行号拼进了文本，复制出来每行顶个数字。
  现在行号由 `CodeBlockView` 画成独立装订线，显示与复制解耦
- **抽取改用 Jsoup**，覆盖论坛移动版原生结构 `div.comiis_blockcode > div > ol > li`
- 主楼代码块也渲染成可折叠 + 可复制的卡片

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.4 新增：代码块折叠复制 · 快捷回复 · 底栏刷新 · 搜索风控修复

- **代码块**从正文摘出来独立成可折叠卡片，带语言标签 / 行数 / 一键复制；引用块同样可折叠复制
- **快捷回复**短语条，轻点填入、长按直发，内容可自定义（一行一条）
- **底栏再点当前 Tab** 即刷新该页
- **搜索风控修复**：原本一次性并发抓完所有结果页，改为按需分页 + 900ms 间隔 + 403 识别
- **不透明度**：对话框由 50% 提到 97%，长文可读

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.3 新增：底栏重做 · 图标全矢量 · 动效体系

- **底栏**参考 [kd64i/dyparse](https://github.com/kd64i/dyparse)：64dp 悬浮胶囊、
  按 `WindowInsets.navigationBars` 避让系统导航栏、指示器弹簧滑动、按压缩放反馈
- **图标全矢量**：12 个新 `VectorDrawable` 替换所有当图标用的 emoji
- **动效体系**移植 [feitangyuan/motion-web](https://github.com/feitangyuan/motion-web)：
  统一的时长刻度 / 缓动字典 / 弹簧预设（`ui/anim/Motion.java` + `res/interpolator/`）
- **排版**：消息页卡片化 + 副标题 + 分类语义色

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.2 新增：侧边栏重排 · 账号平铺快切 · 掉线自动重登

- **侧边栏按场景重新分组**：账号 → 签到 → AI 自动化 → 其他
- **账号平铺**：侧边栏直接列出所有账号，点一下即切换，每行显示今日签到状态 / 时间 / 金币 / 排名
- **掉线自动重登**：浏览时被 403 打掉会话，自动用加密保存的密码静默重登（`SessionGuard`）
- **密码只写不读**：已存密码只显示 `••••••••`，可覆盖可清除，永不回显
- 修复：登录后侧边栏仍显示「未登录」、消息页底部被导航栏遮挡、私信气泡是直角方块、
  长消息与列表文字被硬截断

详见 [CHANGELOG.md](CHANGELOG.md)。

## v2.1 新增：多账号登录 · 切换 · 批量签到

本次把 [Forinxy/mt](https://github.com/Forinxy/mt)（MT 论坛签到助手）的多账号签到思路移植了过来，并接到本客户端原有的 Cookie 会话体系上。

### 能做什么

| 功能 | 说明 |
| --- | --- |
| 多账号登录入库 | 账号密码 / Cookie 两种方式登录后自动存为账号快照 |
| 密码托管 | 勾选「记住密码」后，密码经 **Android KeyStore AES-GCM-256** 加密存本机 |
| 掉线自动重登 | Cookie 过期时用托管密码静默重新登录，拿到新 Cookie 后继续签到并回存 |
| 一键全部签到 | 给所有「已启用」账号逐个签到，**不影响前台正在使用的账号** |
| 单账号签到 | 账号列表里针对某个账号单独补签 |
| 账号切换 | Cookie 快照整体回灌，秒切；切换后自动重置当日签到判定 |
| 签到记录 | 每个账号独立记录 状态 / 排名 / 奖励 / 日期 |
| 每日定时签到 | WorkManager 周期任务，可设定时刻（默认 08:30），App 不开也能跑 |
| 结果通知 | 后台签到完成推送可展开的汇总通知 |
| 风控保护 | 账号之间可设间隔（默认 5 秒），避免论坛 ESA 按 IP 限流 403 |

### 入口在哪

- 侧边栏 → **切换账号**（短按快速切换 / 长按进管理页）
- 侧边栏 → 切换账号弹窗 → **⚙ 账号与签到管理**
- 设置 → **账号与签到**

### 关键设计

**1. 批量签到不会顶掉前台登录态**

全局 `HttpClient` 只有一份 cookieStore，拿它给别的账号登录会直接把当前账号挤下线。
所以新增了 `MtSignApi`，每次调用都新建一个带独立内存 CookieJar 的 `OkHttpClient`，
完全不碰前台会话 —— 这是「后台给 N 个账号签到，前台账号照常用」的前提。

**2. Discuz 的 auth 必须和 saltkey 配套**

`xxx_auth` 是用签发它时的 `xxx_saltkey` 加密的。只存/只发 auth，服务端会重新随机生成
saltkey 导致解密失败、判定为游客。因此账号快照始终整串 Cookie 存取，
并对「只粘贴了一个 `xxx_auth`」的情况直接给出明确报错。

**3. 登录态判定以 `formhash` 为准**

实测游客访问 `k_misign-sign.html` 时页面里 **有** `k_misign`、`spacecp` 字样
（侧边栏推广链接），**没有** `formhash`。所以这两个字样不能当登录标记，
判定只认 `formhash`。这条规则连同抓下来的真实游客页面一起进了单元测试做回归。

### 代码位置

```
app/src/main/java/com/solosu/mtforum/
├── session/
│   ├── AccountManager.java        多账号存储：快照/密码/签到记录/切换
│   ├── MtSignApi.java             独立会话签到引擎（Cookie 直签 + 密码登录）
│   ├── SignParser.java            纯 Java 解析规则（可单测，无 android 依赖）
│   ├── MultiSignInManager.java    批量签到调度 + 自动重登 + 限流间隔
│   ├── SignInSettings.java        签到相关设置集中管理
│   ├── SignInScheduler.java       WorkManager 每日排程
│   ├── SignInWorker.java          后台签到任务
│   ├── SignInNotifier.java        结果通知
│   ├── SessionGuard.java          403/掉线时用已存密码静默重登
├── ui/anim/Motion.java            动效令牌：时长/缓动/弹簧/按压/错峰
├── ui/widget/CodeBlockView.java   可折叠 + 一键复制的代码块
├── ui/Refreshable.java            底栏再点当前 Tab 触发页面刷新
└── session/QuickReplyManager.java 快捷回复短语（可自定义）
│   └── AutoSignInManager.java     入口分流（多账号 / 单账号）
├── ui/account/
│   ├── AccountManagerActivity.java 账号与签到管理页
│   └── AccountAdapter.java
└── util/CryptoUtils.java          KeyStore AES-GCM 凭据加密
```

单元测试：`app/src/test/java/com/solosu/mtforum/session/SignParserTest.java`（17 项，覆盖登录态判定、字段提取、接口返回解析、登录错误分流、Cookie 合法性）。

---

## 构建

需要：JDK 17、Android SDK 36、Gradle 8.13+

```bash
# 调试包
gradle :app:assembleDebug

# 发布包（需 keystore）
gradle :app:assembleRelease

# 跑单元测试
gradle :app:testDebugUnitTest
```

产物在 `app/build/outputs/apk/`。

> aapt2 走 Android SDK 自带即可。项目 `gradle.properties` 已剔除本机 `aapt2FromMavenOverride` 绝对路径，别加回来。
>
> 内存吃紧的机器（<4G）可保持 `gradle.properties` 里的 `org.gradle.jvmargs=-Xmx1400m` 与 `org.gradle.workers.max=1`，否则 dex 合并阶段容易 OOM。

### 发布包签名

`app/keystore.jks` 为打包示例（密码 `mtforum123`、别名 `mtforum`、有效期 10000 天）。**分享出去前请重新生成自己的 keystore，别用这个**：

```bash
keytool -genkeypair -v -keystore your.jks -alias your_alias -keyalg RSA -keysize 2048 -validity 10000
```

然后修改 `app/build.gradle` 里的 `signingConfigs.release` 三个字段。

## 目录结构

```
.
├── build.gradle                 # 根配置
├── settings.gradle
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml       # 依赖版本
│   └── wrapper/
├── app/
│   ├── build.gradle             # app 配置(含 signingConfigs)
│   ├── proguard-rules.pro
│   ├── keystore.jks             # 示例签名,请自换
│   └── src/
│       ├── main/                # 源代码 + 资源
│       └── test/                # JVM 单元测试
├── CHANGELOG.md
└── README.md
```

## 依赖

OkHttp（网络）、Jsoup（HTML 解析）、Glide（图片）、Material Components（UI）、ViewPager2、DrawerLayout、WebKit、**WorkManager（定时签到）**、JUnit（测试）。

## 注意

- 论坛站点 `bbs.binmt.cc` 挂了阿里云 ESA，频率过高会被 IP 级 403 拦截。批量签到默认每个账号间隔 5 秒，账号多时别把间隔调成 0。
- `HttpClient` 里的 USER_AGENT 是写死的（三星 S918B/Chrome 120），论坛风控严格时可按需调整。
- 托管的密码只存在本机 KeyStore 加密区，不上传任何服务器；卸载应用即失效。换机 / 清除应用数据后需要重新输入。
- 登录若触发验证码，需要在 App 内手动登录一次拿到 Cookie，之后才能走免登录直签。
- Android 13+ 需要授予通知权限才能收到签到结果通知。
