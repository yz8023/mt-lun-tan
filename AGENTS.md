# AI 协作规则（MTForum 仓库）

给 AI 助手 / 自动化代理在本仓库工作的规则。核心目标：**任何时候都不能失去与 GitHub 仓库的连接，任何时候都不能丢工作成果。**

## 硬规则

1. **仅在 main 分支操作，不要创建分支。**
   不要新建任何分支，也不要把工作留在长期存活的旁支上。
   例外：若所用平台机制强制使用会话分支（如 Arena 的 `arena/*`），
   应在该分支完成后尽快合并回 main，之后不要保留其它自建分支。
2. **操作过程中不删除 PR。**
   不要关闭、删除任何 pull request（包括已合并的）；
   历史与线索必须保留，避免平台因 PR 状态变化切断与 GitHub 的连接。
3. **保持 GitHub 连接可用。**
   不改仓库可见性/设置，不改仓库名，不 `push --force`，
   不删除远端分支与标签，不做任何可能断开仓库连接的破坏性操作。
4. **成果必须落地。**
   每次改动都要提交；远端不可用时，把交付物以文件形式保存在仓库内
   （源码包、`git format-patch` 补丁等），保证恢复连接后一步即可推送。

## 发布流程（现有机制）

1. 版本号：`app/build.gradle`（versionCode / versionName）、README 顶部当前版本行、
   CHANGELOG.md 顶部新增条目（「用户反馈 → 根因 → 修复 → 测试与构建」格式，含 buildN）。
2. `.github/workflows/build-apk.yml`：更新默认 `release_tag` 与该版本的发布说明、标题。
3. 推送消息含 `[release]` 的提交触发 CI：单测 → 签名 Release 构建 → 自动发布
   GitHub Release（APK + 源码包 + SHA256）。
4. 签名：优先 `ANDROID_KEYSTORE_*` Actions Secrets；未配置时工作流回退到
   仓库内置演示 keystore（v5.22 起的机制）。
5. 发布源码包由 `git archive` 生成：`.gitattributes` 的 export-ignore 已排除
   keystore、signing.properties 与历史存档——签名材料永远不进发布源码包。

## 其它约定

- 单元测试跑在 JVM 上（不触碰 android.*）；新增的字符串/解析/脚本逻辑
  必须在 `app/src/test` 配单测。
- 涉及站点 HTML 结构的修复，先在 CHANGELOG 记录实测证据（tid、探针、计数），
  再动代码——不猜。
- 正文/图片管线改动优先复用既有统一入口（`ImageUrl`、`PostImageHtml`、
  `ForumImageLoader`、`OkHttpStreamLoader`），不要再引入第二套属性候选表。
