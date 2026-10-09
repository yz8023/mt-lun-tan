# mtbbs_app 功能差异审计

审计基线：`qcxs/mtbbs_app`（2026-10-01 拉取）与本项目 v4.3 代码。目标不是逐文件复制 Flutter 实现，而是在 Android 原生客户端中覆盖同等论坛能力，并保留本项目已有的多账号签到、AI 与离线能力。

## 用户可见能力映射

| mtbbs_app 能力 | 本项目落点 | 结论 |
|---|---|---|
| 导读：新帖、最新回复、热门、精华 | 首页 `HomeFragment` 顶部四类筛选，调用 Discuz guide 对应 view 并独立分页 | 已补齐 |
| 版块分类与版块帖子 | `CommunityFragment`、`ForumDetailActivity` | 已有 |
| 帖子正文、分页回复、只看楼主 | `ThreadDetailActivity`、`ReplyAdapter` | 已有 |
| BBCode：文本样式、颜色、链接、图片、引用、代码、隐藏内容 | `BBCodeUtil`、`HtmlToBBCode`、`CodeBlockView`、详情页隐藏内容原位解锁 | 已有，并包含复制 BBCode/纯文本 |
| 评论、楼中楼、发帖、回复、编辑、删除 | `PostActivity`、详情页回复弹层与操作菜单 | 已有；帖子回复消息编辑保留 |
| 点赞、收藏、评分/打赏、举报 | `DiscuzUserActionManager` 与详情页操作 | 已有 |
| 图片管理、上传、粘贴/选择媒体、原图画廊 | `MediaUploadProcessor`、编辑器、`ImagePreviewActivity` | 已有；认证 Cookie/Referer 与懒加载原图统一处理 |
| 草稿/快照 | `DraftManager` | 已有 |
| 搜索 | `SearchActivity`，按页加载并带风控节流 | 已有 |
| 用户空间、资料、主题、好友、私信、积分 | `UserProfileActivity`、`SpaceThreadListActivity`、`FriendListActivity`、`ChatActivity`、`CreditDetailActivity` | 已有 |
| 多站点/多账号 | 当前产品固定适配 MT 站点；`AccountManager` 支持该站点多账号、加密凭据、Cookie 快照与秒切 | 站点范围不同；MT 多账号能力已覆盖且更深入 |
| 内置浏览器 | `InAppBrowserActivity` | 已有；系统浏览器、复制链接、电脑模式、Cookie 双向同步 |
| RSS 列表 | Discuz RSS 与导读为相同公开主题数据；原生客户端直接提供四类导读和分页，不另建重复 RSS 页面 | 等价覆盖 |
| 主题与阅读设置 | `ThemeManager`、设置页 | 已有；深浅色、主题色、各层不透明度 |
| MCP 只读读取 | `mcp/McpServerManager` | v5.22 恢复为默认关闭的本机只读 Streamable HTTP 服务；127.0.0.1、Bearer Token、请求大小/线程数限制、工具白名单与敏感字段脱敏 |
| MCP 公网访问 | 不提供 Cloudflare/公网隧道 | 按 ZIP 本机 MCP 范围实现；不恢复 cloudflared，不监听 LAN/公网，不提供写操作 |

## 细则审计与实现原则

1. **帖子解析不降级成纯 WebView**：主楼和回复继续走原生 DOM 解析；引用和代码是独立可折叠卡片，隐藏内容在原位展开。
2. **图片上下文完整**：正文、评论、隐藏内容和全屏预览共用认证图片请求；识别 `data-original`、`file`、`zoomfile`、Comiis 懒加载属性，传递 Cookie、Referer 和移动 UA；表情与正文大图分开排版。
3. **写操作保留完整表单语义**：编辑/删除使用站点返回表单的字段，不猜测 Discuz 参数；回复消息中的编辑入口不得移除。
4. **编辑器能力**：发帖与回复共用 BBCode 工具、预览、颜色/渐变、图片上传、草稿和快捷回复。mtbbs_app 的桌面键盘快捷键属于 Windows 交互，不照搬到触屏 Android。
5. **论坛风控**：列表按需分页，后台请求走节流器；多账号签到使用隔离 CookieJar，不能污染前台账号。
6. **MCP 安全边界**：仅本机 127.0.0.1、默认关闭、Bearer Token、只读工具白名单、请求体/并发限制与敏感字段过滤；不返回认证材料，不提供公网隧道或任何写操作。通知与私信属于私人数据，MCP 工具描述会明确提示。
7. **界面组织**：首页将导读类型集中在列表上方；设置按“账号与自动化 / 外观与阅读 / 网络、浏览与下载 / AI 与高级工具 / 关于与更新”分组；侧栏继续按账号、签到、AI 自动化和其他工具分组。

## 平台差异（不作为缺失）

- mtbbs_app 的 Windows 窗口状态、桌面安装器和键盘快捷键不适用于本 Android-only 项目。
- 本项目是 MT 论坛专用客户端，不开放任意 Discuz 站点配置；账号管理指同一 MT 站点的多账号，避免把不同站点 Cookie 混入固定域名 API。
- RSS 是公开数据的另一种传输格式；本项目以信息更完整的已登录 HTML 导读提供同一列表能力，并可进入完整原生详情。
