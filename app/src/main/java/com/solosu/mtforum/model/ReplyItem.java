package com.solosu.mtforum.model;

/**
 * 回复列表项数据模型
 */
public class ReplyItem {
    private String pid;             // 回复ID
    private int floorNumber;        // 楼层号（1=沙发，2=椅子，3=地毯...）
    private String floorLabel;      // 原始楼层标签（"沙发"、"椅子"、"14#"等）

    private String author;          // 用户名
    private String authorUid;       // 用户UID
    private String authorLevel;     // 等级（如 "Lv.7 博士生"）
    private String avatarUrl;       // 头像URL
    private String gender;          // 性别："boy" / "girl"

    private boolean isOP;           // 是否为楼主（帖主本人回复）
    private String contentHtml;     // 回复内容HTML（不含引用块）
    private String contentText;     // 纯文本内容（不含引用块）

    /**
     * build82: 已解析好的正文 {@link CharSequence}（Spanned）缓存。
     *
     * <p>只在「<b>没有任何内联图片</b>」时才写入。原因：{@code Html.fromHtml}
     * 的 {@code ImageGetter} 会在解析时捕获当时那个 TextView，Glide 加载完成后
     * 回填到<b>那个</b> View 上。回帖 View 是被 RecyclerView 复用的，
     * 缓存带图片的 Spanned 会导致图片回填到错误的行上 —— 所以带图的一律不缓存，
     * 保持每次 bind 重新解析（正确性优先）。
     *
     * <p>纯文本回帖占绝大多数，这部分因此能完全跳过 Html.fromHtml。
     */
    private transient CharSequence renderedText;

    /**
     * build82: 引用块的已解析文本缓存（引用块同样只在无图片时缓存）。
     */
    private transient CharSequence renderedQuote;

    /**
     * build81: 「懒加载 src 已升级为真实附件地址」之后的 HTML 缓存。
     *
     * <p>原来这段 Jsoup 解析写在 {@code ReplyAdapter.onBindViewHolder} 里，
     * 也就是<b>主线程 + 每次 bind 都跑一遍</b>——列表每滚一屏就重解析几十段 HTML，
     * 这是评论区滚动掉帧/首屏楼层逐个才出来的直接原因。
     *
     * <p>解析结果只取决于 {@link #contentHtml}，与目标 TextView 无关，
     * 所以可以安全缓存。transient：不参与序列化，进程重建后重新算即可。
     */
    private transient String upgradedHtml;
    private String quotedContentHtml; // 被回复内容HTML
    private String quotedContentText; // 被回复内容纯文本

    private String time;            // 时间（如 "半小时前"）
    private String location;        // 地点（如 "来自 广东"）

    public ReplyItem() {}

    // ===== Getters & Setters =====

    public String getPid() { return pid; }
    public void setPid(String pid) { this.pid = pid; }

    public int getFloorNumber() { return floorNumber; }
    public void setFloorNumber(int floorNumber) { this.floorNumber = floorNumber; }

    public String getFloorLabel() { return floorLabel; }
    public void setFloorLabel(String floorLabel) { this.floorLabel = floorLabel; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String authorUid) { this.authorUid = authorUid; }

    public String getAuthorLevel() { return authorLevel; }
    public void setAuthorLevel(String authorLevel) { this.authorLevel = authorLevel; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public boolean isOP() { return isOP; }
    public void setOP(boolean OP) { isOP = OP; }

    public String getContentHtml() { return contentHtml; }

    /** build81: 升级后的 HTML 缓存。null 表示还没算过。 */
    public String getUpgradedHtml() { return upgradedHtml; }

    /** build81: 写入升级后的 HTML 缓存（只在首次计算时调用）。 */
    public void setUpgradedHtml(String v) { this.upgradedHtml = v; }

    /** build82: 正文 Spanned 缓存；null 表示未缓存（含图片或还没算过）。 */
    public CharSequence getRenderedText() { return renderedText; }

    /** build82: 写入正文 Spanned 缓存（仅无图片时）。 */
    public void setRenderedText(CharSequence v) { this.renderedText = v; }

    /** build82: 引用块 Spanned 缓存。 */
    public CharSequence getRenderedQuote() { return renderedQuote; }

    /** build82: 写入引用块 Spanned 缓存（仅无图片时）。 */
    public void setRenderedQuote(CharSequence v) { this.renderedQuote = v; }
    public void setContentHtml(String contentHtml) { this.contentHtml = contentHtml; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public String getQuotedContentHtml() { return quotedContentHtml; }
    public void setQuotedContentHtml(String quotedContentHtml) { this.quotedContentHtml = quotedContentHtml; }

    public String getQuotedContentText() { return quotedContentText; }
    public void setQuotedContentText(String quotedContentText) { this.quotedContentText = quotedContentText; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}