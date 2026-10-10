package com.solosu.mtforum.ui.detail;

import com.solosu.mtforum.util.ImageUrl;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.BBCodeUtil;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.space.UserProfileActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回复列表适配器
 * 支持 Glide 加载头像、楼层标签、楼主标识、等级、时间、地点等完整信息
 */
public class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.ViewHolder> {

    private List<ReplyItem> replyList;
    private OnReplyClickListener replyClickListener;
    private OnUserClickListener userClickListener;
    private OnReplyLongClickListener replyLongClickListener; // build73: 长按出操作菜单
    private String highlightedPid = "";

    public interface OnReplyClickListener {
        void onReplyClick(ReplyItem item, int position);
    }

    public interface OnUserClickListener {
        void onUserClick(ReplyItem item, int position);
    }

    /** build73: 长按评论 -> 回复/举报/删除 菜单 */
    public interface OnReplyLongClickListener {
        void onReplyLongClick(ReplyItem item, int position);
    }

    public void setOnReplyLongClickListener(OnReplyLongClickListener listener) {
        this.replyLongClickListener = listener;
    }

    public void setOnReplyClickListener(OnReplyClickListener listener) {
        this.replyClickListener = listener;
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        this.userClickListener = listener;
    }

    public ReplyAdapter(List<ReplyItem> replyList) {
        this.replyList = replyList;
    }

    public void updateData(List<ReplyItem> newList) {
        this.replyList = newList;
        notifyDataSetChanged();
    }

    public void setHighlightedPid(String pid) {
        highlightedPid = TextUtils.isEmpty(pid) ? "" : pid;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reply, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReplyItem item = replyList.get(position);
        holder.bind(item);
        boolean highlighted = item != null && !TextUtils.isEmpty(highlightedPid)
                && highlightedPid.equals(item.getPid());
        holder.itemView.setBackgroundResource(highlighted
                ? R.drawable.reply_jump_highlight_bg : R.drawable.reply_nested_bg);
    }

    @Override
    public int getItemCount() {
        return replyList == null ? 0 : replyList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView ivAvatar;
        private final TextView tvFloorLabel;
        private final TextView tvAuthor;
        private final TextView tvOpBadge;
        private final TextView tvLevel;
        private final TextView tvTime;
        private final TextView tvContent;
        private final LinearLayout layoutReplyQuote;
        private final TextView tvReplyQuote;
        private final LinearLayout llReplyImages;
        private final TextView btnReplyTo;
        private final ImageView ivReplyMore;
        private final LinearLayout llCodeBlocks;
        private final TextView btnQuoteCopy;
        private final TextView btnQuoteToggle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_reply_avatar);
            tvFloorLabel = itemView.findViewById(R.id.tv_floor_label);
            tvAuthor = itemView.findViewById(R.id.tv_reply_author);
            tvOpBadge = itemView.findViewById(R.id.tv_op_badge);
            tvLevel = itemView.findViewById(R.id.tv_reply_level);
            tvTime = itemView.findViewById(R.id.tv_reply_time);
            tvContent = itemView.findViewById(R.id.tv_reply_content);
            layoutReplyQuote = itemView.findViewById(R.id.layout_reply_quote);
            tvReplyQuote = itemView.findViewById(R.id.tv_reply_quote);
            llCodeBlocks = itemView.findViewById(R.id.ll_code_blocks);
            btnQuoteCopy = itemView.findViewById(R.id.btn_quote_copy);
            btnQuoteToggle = itemView.findViewById(R.id.btn_quote_toggle);
            llReplyImages = itemView.findViewById(R.id.ll_reply_images);
            btnReplyTo = itemView.findViewById(R.id.btn_reply_to);
            ivReplyMore = itemView.findViewById(R.id.iv_reply_more);
        }

        void bind(ReplyItem item) {
            // 头像 - Glide 加载圆图
            String avatarUrl = item.getAvatarUrl();
            if (!TextUtils.isEmpty(avatarUrl)) {
                Glide.with(ivAvatar.getContext())
                        .load(com.solosu.mtforum.util.ForumImageLoader.model(avatarUrl))
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account);
            }

            // ★ 新增：头像和用户名点击 → 打开原生用户资料页
            final int pos = getAdapterPosition();
            final ReplyItem currentItem = item;
            ivAvatar.setOnClickListener(v -> {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.getAuthorUid())) {
                    userClickListener.onUserClick(currentItem, pos);
                }
            });
            tvAuthor.setOnClickListener(v -> {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.getAuthorUid())) {
                    userClickListener.onUserClick(currentItem, pos);
                }
            });

            // build73: 整项长按 -> 操作菜单(回复/举报/删除)
            itemView.setOnLongClickListener(v -> {
                if (replyLongClickListener != null) {
                    replyLongClickListener.onReplyLongClick(currentItem, getAdapterPosition());
                    return true;
                }
                return false;
            });

            // 楼层标签（沙发/椅子/地毯/报纸/N#）
            // build65: 楼层文案清洗（去零宽/控制字符）
            String floorLabel = com.solosu.mtforum.util.TextClean.floorLabel(item.getFloorLabel());
            if (!TextUtils.isEmpty(floorLabel)) {
                tvFloorLabel.setVisibility(View.VISIBLE);
                tvFloorLabel.setText(floorLabel);
            } else {
                tvFloorLabel.setVisibility(View.GONE);
            }

            // 作者名
            tvAuthor.setText(!TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor() : "匿名");

            // 楼主标识
            if (item.isOP()) {
                tvOpBadge.setVisibility(View.VISIBLE);
            } else {
                tvOpBadge.setVisibility(View.GONE);
            }

            // 等级
            String level = item.getAuthorLevel();
            if (!TextUtils.isEmpty(level)) {
                tvLevel.setVisibility(View.VISIBLE);
                tvLevel.setText(level);
            } else {
                tvLevel.setVisibility(View.GONE);
            }

            // 时间
            String time = item.getTime();
            if (!TextUtils.isEmpty(time)) {
                tvTime.setVisibility(View.VISIBLE);
                tvTime.setText(time);
            } else {
                tvTime.setVisibility(View.GONE);
            }

            // 评论区按参考样式仅显示时间，不显示回复项地点，避免与回复按钮并列出现重复灰色定位文字。

            // 回复按钮 + 更多(⋮)
            String author = item.getAuthor();
            if (!TextUtils.isEmpty(author)) {
                btnReplyTo.setVisibility(View.VISIBLE);
                btnReplyTo.setOnClickListener(v -> {
                    if (replyClickListener != null) {
                        replyClickListener.onReplyClick(item, getAdapterPosition());
                    }
                });
                if (ivReplyMore != null) {
                    ivReplyMore.setVisibility(View.VISIBLE);
                    ivReplyMore.setOnClickListener(v -> {
                        if (replyLongClickListener != null) {
                            replyLongClickListener.onReplyLongClick(item, getAdapterPosition());
                        }
                    });
                }
            } else {
                btnReplyTo.setVisibility(View.GONE);
                if (ivReplyMore != null) ivReplyMore.setVisibility(View.GONE);
            }

            // 内容 - 优先显示纯文本

            // 引用内容单独显示，模拟网页端的浅黄色引用框；当前回复保持深色正文。
            String quotedText = item.getQuotedContentText();
            if (!TextUtils.isEmpty(quotedText)) {
                layoutReplyQuote.setVisibility(View.VISIBLE);
                tvReplyQuote.setText(parseCached(
                        item, true, quotedText, tvReplyQuote, itemView.getContext()));
                // build107: 引用块以前也没挂 linkify —— 被引用的正文里带网址时点不动。
                // 必须放在 attachCopyOnLongClick 之前：后者要把 longClickable 打开，
                // 而 setupClickableLinks 会先关掉它。
                setupClickableLinks(tvReplyQuote);
                attachCopyOnLongClick(tvReplyQuote);
                // build63: 长引用默认折 4 行，可展开；旁边给一键复制
                setupQuoteControls(tvReplyQuote, btnQuoteToggle, btnQuoteCopy);
            } else {
                layoutReplyQuote.setVisibility(View.GONE);
                tvReplyQuote.setText("");
            }

            String contentText = item.getContentText();
            String htmlContent = item.getContentHtml();

            // build63: 代码块摘出来单独渲染成可折叠 + 可复制的卡片
            boolean hasCodeBlock = false;
            if (htmlContent != null
                    && (htmlContent.contains("comiis_blockcode") || htmlContent.contains("<pre"))) {
                BBCodeUtil.Extracted extracted = BBCodeUtil.extractCodeBlocks(htmlContent);
                if (extracted.hasBlocks()) {
                    renderCodeBlocks(llCodeBlocks, extracted.blocks);
                    htmlContent = extracted.html;
                    contentText = null;
                } else {
                    hasCodeBlock = true;
                }
            } else if (llCodeBlocks != null) {
                llCodeBlocks.setVisibility(View.GONE);
                llCodeBlocks.removeAllViews();
            }

            // 图片始终在正文原位渲染。优先使用 HTML，以免把图片抽到回帖底部，
            // ImageGetter 会加载原图并按 TextView 实际可用宽度等比缩放。
            // build81: 缓存升级后的 HTML。Jsoup 解析只跑一次，
            // 之后每次 bind（滚动/复用）直接复用，主线程不再重复解析。
            String rendered;
            if (!TextUtils.isEmpty(htmlContent)) {
                String cached = item.getUpgradedHtml();
                if (cached == null) {
                    cached = upgradeImageSources(htmlContent);
                    item.setUpgradedHtml(cached);
                }
                rendered = cached;
            } else {
                rendered = contentText;
            }
            if (!TextUtils.isEmpty(rendered)) {
                tvContent.setVisibility(View.VISIBLE);
                tvContent.setText(parseCached(
                        item, false, rendered, tvContent, itemView.getContext()));
                setupClickableLinks(tvContent);
                attachCopyOnLongClick(tvContent);
                // build97: 评论区的图以前完全没挂点击 —— 图文混排走 ImageGetter，
                // 而 setImageClick 只服务旧方案（llReplyImages 里独立的 ImageView），
                // 那条路现在已经被隐藏。用户报的就是「评论区图片无法显示原图」。
                // 必须放在 setupClickableLinks 之后：它会把 textIsSelectable 设 true，
                // 选择模式会吞掉点击，所以这里用触摸命中测试，不依赖 MovementMethod。
                final android.content.Context ctx = itemView.getContext();
                com.solosu.mtforum.util.InlineImageClicks.attach(tvContent, url -> {
                    if (ctx == null || TextUtils.isEmpty(url)) return;
                    try {
                        android.content.Intent it = new android.content.Intent(
                                ctx, com.solosu.mtforum.ui.detail.ImagePreviewActivity.class);
                        it.putExtra("image_url", url);
                        ctx.startActivity(it);
                    } catch (Exception ignored) {
                    }
                });
                llReplyImages.removeAllViews();
                llReplyImages.setVisibility(View.GONE);
            } else {
                tvContent.setVisibility(View.GONE);
                llReplyImages.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 为 TextView 设置可点击链接（蓝色高亮 + 可点击跳转）
     * ★ 统一方案：与 ThreadDetailActivity.setupClickableLinks 保持一致
     * 
     * 注意：此方法不会覆盖已有文本，只会在现有 Spannable 上添加链接处理
     */
    /**
     * build71: 长按复制回复内容。
     * 之前 setupClickableLinks 里写死 setLongClickable(false)(为了不拦截链接点击),
     * 导致别人回复根本没法复制。长按和单击是两个事件,挂长按不影响链接跳转。
     */
    private static void attachCopyOnLongClick(final TextView textView) {
        if (textView == null) return;
        textView.setLongClickable(true);
        textView.setOnLongClickListener(v -> {
            CharSequence cs = textView.getText();
            String text = cs == null ? "" : cs.toString().trim();
            if (text.isEmpty()) return false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        textView.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("回复内容", text));
                    android.widget.Toast.makeText(textView.getContext(), "已复制回复内容",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        });
    }

    private static void setupClickableLinks(TextView textView) {
        if (textView == null) return;
        
        // ★ 关键：禁用文本选择和长按，避免拦截触摸事件
        textView.setTextIsSelectable(false);
        textView.setFocusable(false);
        textView.setClickable(false);
        textView.setLongClickable(false);
        textView.setHighlightColor(0x332196F3);
        
        // 获取当前文本（可能已经是 Spannable）
        CharSequence value = textView.getText();
        if (!(value instanceof Spannable)) {
            // 如果不是 Spannable，创建一个并设置回 TextView
            value = new SpannableString(value == null ? "" : value);
            textView.setText(value, TextView.BufferType.SPANNABLE);
        }
        
        // 强制重新获取 Spannable（确保是最新的）
        Spannable spannable = (Spannable) textView.getText();
        
        // 与正文统一使用可识别 http/https、www、.cn 等常见裸域名的链接模式。
        Pattern urlPattern = com.solosu.mtforum.util.PlainTextUrlPattern.WEB_URL;
        FixNestedScrollLinkMovementMethod.matcherLinkify(
            spannable, 
            urlPattern, 
            url -> url,  // URL处理器：直接返回原始URL
            url -> openContentLink(url, textView.getContext())  // 点击处理器：打开链接
        );
        
        // Html.fromHtml() 产生 URLSpan；统一替换为应用自己的 ClickableSpan
        URLSpan[] urlSpans = spannable.getSpans(0, spannable.length(), URLSpan.class);
        for (URLSpan oldSpan : urlSpans) {
            String targetUrl = oldSpan.getURL();
            if (TextUtils.isEmpty(targetUrl)) {
                spannable.removeSpan(oldSpan);
                continue;
            }
            // 绝对链接先走统一规范化（包含大写 HTTP、www 和裸域名），相对 href 才拼本站域名。
            String normalized = com.solosu.mtforum.ui.web.LinkRouter.normalizeWebUrl(targetUrl);
            if (!TextUtils.isEmpty(normalized)) {
                targetUrl = normalized;
            } else if (targetUrl.startsWith("/")) {
                targetUrl = HttpClient.BASE_URL + targetUrl.substring(1);
            } else if (targetUrl.startsWith("./")) {
                targetUrl = HttpClient.BASE_URL + targetUrl.substring(2);
            } else if (!targetUrl.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                targetUrl = HttpClient.BASE_URL + targetUrl;
            }
            
            int start = spannable.getSpanStart(oldSpan);
            int end = spannable.getSpanEnd(oldSpan);
            int flags = spannable.getSpanFlags(oldSpan);
            spannable.removeSpan(oldSpan);
            if (start >= 0 && end > start && !TextUtils.isEmpty(targetUrl)) {
                final String finalUrl = targetUrl;
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        openContentLink(finalUrl, widget.getContext());
                    }
                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(0xFF1976D2);
                        ds.setUnderlineText(true);
                    }
                }, start, end, flags);
            }
        }
        
        // ★ 关键修复：不要再次调用 setText()，直接更新 movementMethod
        textView.setMovementMethod(new FixNestedScrollLinkMovementMethod());
        textView.setAutoLinkMask(0);
    }
    
    /**
     * 回复里的纯文本 URL 与 HTML 超链接统一按「链接与文件打开方式」路由。
     * 应用内模式下，论坛帖子/用户页仍使用原生页面；浏览器模式下不再绕过设置。
     */
    private static void openContentLink(String rawUrl, Context context) {
        if (TextUtils.isEmpty(rawUrl) || context == null) return;
        String url = com.solosu.mtforum.ui.web.LinkRouter.normalizeWebUrl(rawUrl);
        if (TextUtils.isEmpty(url)) return;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (com.solosu.mtforum.ui.web.LinkRouter.isInAppMode(context)
                    && "bbs.binmt.cc".equalsIgnoreCase(host)) {
                Matcher threadMatcher = Pattern.compile("thread-(\\d+)", Pattern.CASE_INSENSITIVE)
                        .matcher(path);
                if (threadMatcher.find()) {
                    NavigationHelper.openThread(context, threadMatcher.group(1));
                    return;
                }
                String tid = uri.getQueryParameter("tid");
                if ("viewthread".equalsIgnoreCase(uri.getQueryParameter("mod"))
                        && !TextUtils.isEmpty(tid)) {
                    NavigationHelper.openThread(context, tid);
                    return;
                }

                Matcher uidInPath = Pattern.compile("space-uid-(\\d+)", Pattern.CASE_INSENSITIVE)
                        .matcher(path);
                String uid = uidInPath.find() ? uidInPath.group(1) : uri.getQueryParameter("uid");
                if (!TextUtils.isEmpty(uid)) {
                    openUserProfile(context, uid, null);
                    return;
                }
                Matcher usernameMatcher = Pattern.compile("space-username-([^./?&]+)",
                        Pattern.CASE_INSENSITIVE).matcher(path);
                if (usernameMatcher.find()) {
                    openUserProfile(context, null, usernameMatcher.group(1));
                    return;
                }
            }
            com.solosu.mtforum.ui.web.LinkRouter.open(context, url);
        } catch (Exception ignored) {
        }
    }

    private static void openUserProfile(Context context, String uid, String username) {
        Intent intent = new Intent(context, UserProfileActivity.class);
        if (!TextUtils.isEmpty(uid)) intent.putExtra("uid", uid);
        if (!TextUtils.isEmpty(username)) intent.putExtra("username", username);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 为评论中的图片 ImageView 添加点击预览监听
     */
    private static void setImageClick(Context context, ImageView imageView, String imgUrl) {
        imageView.setOnClickListener(v -> {
            if (context != null && !android.text.TextUtils.isEmpty(imgUrl)) {
                Intent intent = new Intent(context, ImagePreviewActivity.class);
                intent.putExtra("image_url", imgUrl);
                context.startActivity(intent);
            }
        });
    }

    /**
     * 从 HTML 内容中提取所有 img 标签的 src URL，并返回去掉 img 标签后的纯 HTML
     */
    /**
     * 从评论正文 HTML 里抽出图片，并把 img 标签从文本中抹掉。
     *
     * <p>build80: 原来只用一条正则读 {@code src}。问题是本论坛用的是
     * Comiis 懒加载，真实地址在 {@code file}/{@code comiis_loadimages}
     * 属性里，{@code src} 只是 {@code imageloading.gif} 占位图——
     * 所以「评论区图片不显示」就是这么来的：抓到的全是占位图 URL。
     *
     * <p>改为先用 Jsoup 走真实属性链，正则只作兜底（Jsoup 解析失败时）。
     */
    private static String extractImagesFromHtml(String html, List<String> outImageUrls) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        String stripped = stripImagesWithJsoup(html, outImageUrls);
        if (stripped != null) {
            return stripped;
        }
        return stripImagesWithRegex(html, outImageUrls);
    }

    /** 用 Jsoup 走真实属性链抽图；解析失败返回 null，由调用方走正则兜底。 */
    private static String stripImagesWithJsoup(String html, List<String> outImageUrls) {
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(html);
            for (org.jsoup.nodes.Element img : doc.select("img")) {
                String real = ImageUrl.resolve(img);
                if (real == null) {
                    // 取不到真实地址 → 保留标签，交给 Glide 自己渲染 src
                    continue;
                }
                String abs = ImageUrl.toAbsolute(real);
                if (abs == null || abs.isEmpty()) {
                    continue;
                }
                if (isInlineForumImage(abs)) {
                    // 内联表情：保留在文本里，src 换成补全后的地址
                    img.attr("src", abs);
                    continue;
                }
                if (abs.startsWith("http://") || abs.startsWith("https://")) {
                    if (!outImageUrls.contains(abs)) {
                        outImageUrls.add(abs);
                    }
                }
                img.remove();
            }
            return doc.body().html();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 正则兜底：只认 http/https 的 src。 */
    private static String stripImagesWithRegex(String html, List<String> outImageUrls) {
        Pattern pattern = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1);
            String fullUrl = normalizeImageUrl(url);
            if (fullUrl == null) fullUrl = url;

            if (isInlineForumImage(fullUrl)) {
                String origTag = matcher.group(0);
                String newTag = origTag.replaceFirst("src\\s*=\\s*['\"][^'\"]*['\"]",
                        "src=\"" + fullUrl + "\"");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(newTag));
                continue;
            }
            if (fullUrl.startsWith("http://") || fullUrl.startsWith("https://")) {
                if (!outImageUrls.contains(fullUrl)) {
                    outImageUrls.add(fullUrl);
                }
            }
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Resolve all known Discuz/Comiis lazy-load attributes, including tags without src. */
    /**
     * 把懒加载的占位 {@code src} 换成真实附件地址。
     *
     * <p>build81 两处改动：
     * <ol>
     *   <li><b>占位图黑名单补 {@code imageloading.gif}</b>。原来只有
     *       none/blank/grey，而本论坛实际下发的 src 恰好是 {@code imageloading.gif}
     *       —— 于是它被当成正常 URL 原样返回，图片停在占位图上。
     *       v4.8 只改了 {@code ThreadDetailActivity} 那一处，漏了这里。</li>
     *   <li>判定改走 {@link ImageUrl}，与列表页/主楼/评论区三条路径保持一致。</li>
     * </ol>
     *
     * <p>注意：本方法本身仍在主线程被调用（见调用方的缓存逻辑），
     * 所以务必配合 {@link ReplyItem#getUpgradedHtml()} 缓存，别每次 bind 都算。
     */
    /**
     * build97: 委托 {@link PostImageHtml}，与主楼正文共用真图挑选和地址校验。
     * build101: helper 还会检查包图附件链接/签名 aid；不从缩略图裸数字猜附件 URL。
     */
    private static String upgradeImageSources(String html) {
        return com.solosu.mtforum.util.PostImageHtml
                .upgradeThumbnailsToFull(html, HttpClient.BASE_URL);
    }

    /**
     * 补全图片URL：处理 //、/ 和 ./ 开头的相对路径
     */
    private static boolean isUsableImageValue(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String low=value.trim().toLowerCase(java.util.Locale.ROOT);
        if (low.matches("\\d+") || "true".equals(low) || "false".equals(low)
                || "lazy".equals(low) || low.startsWith("javascript:") || low.startsWith("data:")) return false;
        return low.startsWith("http://") || low.startsWith("https://") || low.startsWith("//")
                || low.startsWith("/") || low.startsWith("./") || low.contains("/") || low.contains(".");
    }

    private static boolean isInlineForumImage(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String low=url.toLowerCase(java.util.Locale.ROOT);
        return low.contains("/static/image/smiley/") || low.contains("/smiley/")
                || low.contains("/emoticon/") || low.contains("static/image/common/smiley");
    }

    private static String normalizeImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        if (url.startsWith("//")) {
            return "https:" + url;
        } else if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1);
        } else if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        // 其他情况（不含协议的相对路径如 "data/attachment/..."）
        return HttpClient.BASE_URL + url;
    }

    /**
     * 从 HTML 内容中提取图片 URL 列表，并用 Glide 加载到 llReplyImages 布局中
     */
    private static void loadReplyImages(Context context, String html, LinearLayout container) {
        List<String> urls = new ArrayList<>();
        extractImagesFromHtml(html, urls);
        if (urls.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        int maxImgWidth = getMaxImageWidth(context);
        for (String imgUrl : urls) {
            ImageView imageView = new ImageView(context);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundColor(context.getColor(R.color.background_secondary));
            imageView.setMaxWidth(maxImgWidth);
            imageView.setMaxHeight((int)(maxImgWidth * 1.2f));
            // ★ 添加点击预览
            imageView.setClickable(true);
            imageView.setFocusable(true);
            setImageClick(context, imageView, imgUrl);
            Glide.with(context)
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(imgUrl))
                    .placeholder(new ColorDrawable(context.getColor(R.color.background_secondary)))
                    .error(new ColorDrawable(context.getColor(R.color.divider)))
                    .into(imageView);
            container.addView(imageView);
        }
    }

    /**
     * 获取评论图片的最大显示宽度（屏幕宽度的80%，最多不超过360dp）
     */
    private static int getMaxImageWidth(Context context) {
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int maxDp = 360;
        int maxPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, maxDp, context.getResources().getDisplayMetrics());
        int width80 = (int)(screenWidth * 0.8f);
        return Math.min(width80, maxPx);
    }

    // ==================== 内联图片渲染（表情等） ====================

    /**
     * build82: 解析 HTML，并在「没有内联图片」时把结果缓存回 {@link ReplyItem}。
     *
     * <p>{@code Html.fromHtml} 每次都要完整解析 HTML 并构建整棵 span 树，
     * 原来写在 {@code onBindViewHolder} 里，滚动时每滚一屏就把可见的十几段
     * 全部重解析一遍。纯文本回帖占绝大多数，这部分现在只在首次算一次。
     *
     * <p><b>为什么带图的不缓存</b>：{@code ImageGetter} 在解析时捕获了当时那个
     * TextView，Glide 加载完成后把图回填到那个 View 上。回帖 View 会被
     * RecyclerView 复用，缓存带图的 Spanned 会让图片回填到错误的一行。
     * 所以这里用 {@code ImageSpan} 数量判定：有图就每次重新解析，保证正确。
     */
    private static CharSequence parseCached(ReplyItem item, boolean quote,
                                            String html, TextView target, Context ctx) {
        CharSequence cached = quote ? item.getRenderedQuote() : item.getRenderedText();
        if (cached != null) {
            return copyOf(cached);
        }
        CharSequence parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT,
                createInlineImageGetter(target), BBCodeUtil.createTagHandler(ctx));
        if (parsed instanceof android.text.Spanned) {
            android.text.Spanned sp = (android.text.Spanned) parsed;
            if (sp.getSpans(0, sp.length(), android.text.style.ImageSpan.class).length == 0) {
                // 无内联图片 → 与目标 View 无关，可安全缓存。
                // 注意存的是<b>未被污染的副本</b>：见 copyOf 的说明。
                CharSequence pristine = copyOf(parsed);
                if (quote) {
                    item.setRenderedQuote(pristine);
                } else {
                    item.setRenderedText(pristine);
                }
            }
        }
        return parsed;
    }

    /**
     * 复制一份可自由改写的文本。
     *
     * <p>为什么必须复制：{@code setupClickableLinks} 是<b>就地</b>改 span 的 ——
     * {@code matcherLinkify} 往里加 ClickableSpan，又把 Html 产生的 URLSpan
     * removeSpan 后换成自定义 ClickableSpan。如果缓存对象被直接复用，
     * 第二次 bind 时 URLSpan 已经没了（不会重复替换，这段没事），
     * 但 {@code matcherLinkify} 会<b>再叠一套</b> ClickableSpan 上去 ——
     * 同一段文字挂两个点击处理器，点一下可能开两个页面。
     *
     * <p>{@code Html.fromHtml}（HTML 解析 + 建整棵 span 树）才是真正贵的部分，
     * {@code new SpannableString(...)} 只是字符和 span 的浅拷贝，便宜得多。
     * 所以「存原件、发副本」既拿到缓存的收益，又不破坏 linkify 的语义。
     *
     * <p>纯 {@code String} 不可变，直接返回即可。
     */
    private static CharSequence copyOf(CharSequence src) {
        if (src instanceof android.text.Spanned) {
            return new android.text.SpannableString(src);
        }
        return src;
    }

    /**
     * 为 Html.fromHtml 提供 ImageGetter，用 Glide 异步加载内联图片（表情等小图）
     * 修复：旧实现 Glide 加载完成后未回填 ImageSpan 持有的占位 Drawable，导致内联图永远透明。
     * 现改用 UrlDrawable 占位 + setReal 回填 + 宿主 TextView 重排。表情限 24dp，大图限宽。
     */
    private static Html.ImageGetter createInlineImageGetter(TextView targetView) {
        return source -> {
            String imgUrl = normalizeImageUrl(source);
            if (imgUrl == null) imgUrl = source;
            if (imgUrl.startsWith("//")) {
                imgUrl = "https:" + imgUrl;
            } else if (imgUrl.startsWith("/")) {
                imgUrl = HttpClient.BASE_URL + imgUrl.substring(1);
            } else if (!imgUrl.startsWith("http")) {
                imgUrl = HttpClient.BASE_URL + imgUrl;
            }

            final boolean inlineIcon = isInlineForumImage(imgUrl);
            final TextView tv = targetView;
            final com.solosu.mtforum.util.UrlDrawable placeholder =
                    new com.solosu.mtforum.util.UrlDrawable(tv, dpToPx(tv.getContext(), 24));
            final int measured = tv.getWidth() - tv.getCompoundPaddingLeft() - tv.getCompoundPaddingRight();
            final int maxW = measured > 0 ? measured
                    : Resources.getSystem().getDisplayMetrics().widthPixels - dpToPx(tv.getContext(), 32);

            Glide.with(tv.getContext())
                    .load(com.solosu.mtforum.util.ForumImageLoader.model(imgUrl))
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource,
                                                    @Nullable Transition<? super Drawable> transition) {
                            int w = resource.getIntrinsicWidth();
                            int h = resource.getIntrinsicHeight();
                            int emotSize = dpToPx(tv.getContext(), 24);
                            int maxSize = maxW > 0 ? maxW : dpToPx(tv.getContext(), 320);
                            if (w <= 0) {
                                w = emotSize;
                            }
                            if (h <= 0) {
                                h = emotSize;
                            }
                            // Only known emoji/icon URLs stay inline; even a low-resolution
                            // attachment is a content image and must expand to the text width.
                            if (inlineIcon) {
                                if (w > emotSize || h > emotSize) {
                                    float r = (float) emotSize / Math.max(w, h);
                                    w = (int) (w * r);
                                    h = (int) (h * r);
                                }
                            } else {
                                // 正文图片无论原始像素大小都占满正文可用宽度；原图地址已在
                                // upgradeImageSources 中优先替换，避免继续显示小缩略图。
                                h = (int) ((long) h * maxSize / Math.max(1, w));
                                w = maxSize;
                            }
                            resource.setBounds(0, 0, w, h);
                            placeholder.setReal(resource, tv);
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholderD) {
                        }
                    });
            return placeholder;
        };
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // ==================== 代码块 / 引用 折叠与复制 ====================

    /** 把抽出来的代码块渲染成一排可折叠卡片 */
    private static void renderCodeBlocks(LinearLayout container,
                                         java.util.List<BBCodeUtil.CodeBlock> blocks) {
        if (container == null) return;
        container.removeAllViews();
        if (blocks == null || blocks.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        int gap = (int) (8 * container.getResources().getDisplayMetrics().density);
        for (int i = 0; i < blocks.size(); i++) {
            BBCodeUtil.CodeBlock b = blocks.get(i);
            com.solosu.mtforum.ui.widget.CodeBlockView view =
                    new com.solosu.mtforum.ui.widget.CodeBlockView(container.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            view.setLayoutParams(lp);
            view.bind(b.lang, b.code);
            container.addView(view);
        }
    }

    /** 引用块：超过 4 行折叠，按钮切换；复制按钮拷贝纯文本 */
    private static void setupQuoteControls(final TextView quote,
                                           final TextView btnToggle,
                                           final TextView btnCopy) {
        if (quote == null) return;
        final int collapsedLines = 4;
        quote.setMaxLines(collapsedLines);

        if (btnToggle != null) {
            btnToggle.setVisibility(View.GONE);
            quote.post(() -> {
                android.text.Layout layout = quote.getLayout();
                boolean overflow = layout != null && layout.getLineCount() > collapsedLines;
                // getLineCount 在 maxLines 生效时已被截断，改用测量整段行数
                if (!overflow) {
                    overflow = quote.getText() != null
                            && countTextLines(quote.getText().toString()) > collapsedLines;
                }
                if (!overflow) return;
                btnToggle.setVisibility(View.VISIBLE);
                btnToggle.setText("展开");
                final boolean[] expanded = {false};
                btnToggle.setOnClickListener(v -> {
                    expanded[0] = !expanded[0];
                    quote.setMaxLines(expanded[0] ? Integer.MAX_VALUE : collapsedLines);
                    btnToggle.setText(expanded[0] ? "收起" : "展开");
                });
            });
        }

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            v.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm == null) return;
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "quote", quote.getText() == null ? "" : quote.getText().toString()));
                    android.widget.Toast.makeText(v.getContext(), "引用内容已复制",
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static int countTextLines(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

}
