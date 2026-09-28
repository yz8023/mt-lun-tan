# 更新日志

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
