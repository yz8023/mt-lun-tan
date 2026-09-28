# MTForum — MT 论坛第三方客户端

[bbs.binmt.cc](https://bbs.binmt.cc/) 的第三方 Android 客户端。原生 Java + Material Design，覆盖板块浏览、帖子阅读、回复/发帖、个人中心、**多账号登录与切换**、**多账号自动签到**、AI 自动回复。

当前版本：**v2.1（versionCode 6）**

---

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
