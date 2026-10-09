package com.solosu.mtforum.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.solosu.mtforum.model.ChatMessage;
import com.solosu.mtforum.model.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 消息角标快照管理器。
 *
 * Comiis 页面不提供可靠的 unread 标记，因此不再依赖 Message.isRead()。
 * 角标含义为：当前服务器列表相对于用户上次进入该分类时，新增的内容数量。
 *
 * PM 使用“会话内消息总数”计算差值；粉丝使用新增 UID 数量；
 * 其它通知使用通知内容指纹计算新增条目数量。
 */
public final class NoticeBadgeManager {
    private static final String PREF_NAME = "notice_badge_snapshot_v2";
    private static final String KEY_BASELINE_PREFIX = "baseline_";
    private static final String KEY_CURRENT_PREFIX = "current_";
    private static final String KEY_PENDING_PREFIX = "pending_";
    private static final String KEY_PENDING_ALL_READ_PREFIX = "pending_all_read_";
    private static final String KEY_READ_EPOCH_PREFIX = "read_epoch_";
    private static final String KEY_READSET_PREFIX = "readset_";
    private static final String KEY_CLICKED_PM_PREFIX = "clicked_pm_";
    private static final String KEY_CLICKED_NOTICE_PREFIX = "clicked_notice_";
    private static final String KEY_NEW_COUNT_PREFIX = "new_count_";

    private NoticeBadgeManager() {}

    /** build83: 分类被查看时回调. MainActivity 注册后可在本地即时清零红点, 无需等 60s 轮询, 零额外请求 */
    public interface OnViewedListener { void onViewed(String viewType); }

    private static volatile OnViewedListener sViewedListener;

    public static void setOnViewedListener(OnViewedListener listener) { sViewedListener = listener; }

    private static void fireViewed(String viewType) {
        OnViewedListener listener = sViewedListener;
        if (listener == null) return;
        try { listener.onViewed(viewType); } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * build73: 基线必须<b>按账号隔离</b>。
     *
     * <p>原来的键是 {@code baseline_{viewType}}，所有账号共用一份 ——
     * 切换账号后拿的是上一个账号的基线，于是新账号的全部历史消息都被算成"新"。
     * 现在带上 uid 前缀。
     */
    public static String activeAccountUid(Context context) {
        try {
            String uid = context == null ? "" : com.solosu.mtforum.session.AccountManager.activeUid(context);
            if (TextUtils.isEmpty(uid) && context != null) {
                uid = com.solosu.mtforum.session.UserSessionManager.getInstance().getUid(context);
            }
            return TextUtils.isEmpty(uid) ? "" : uid;
        } catch (Exception e) {
            return "";
        }
    }

    private static String scope(Context context) {
        return scopeUid(activeAccountUid(context));
    }

    private static String scopeUid(String uid) {
        return TextUtils.isEmpty(uid) ? "anon_" : (uid + "_");
    }

    /**
     * 根据当前 HTML 建立规范化快照。
     * PM 会继续请求每个会话的详情页，统计当前可见聊天消息总数。
     */
    public static String buildSnapshot(String viewType, String html, HttpClient client) {
        if (TextUtils.isEmpty(html)) return "";

        List<Message> items;
        if ("pm".equals(viewType)) items = ForumParser.parsePmList(html);
        else if ("follower".equals(viewType)) items = ForumParser.parseFollowerList(html);
        else items = ForumParser.parseNoticeList(html);
        if (items == null) items = new ArrayList<>();

        if ("pm".equals(viewType)) return buildPmSnapshot(items, client);
        if ("follower".equals(viewType)) return buildFollowerSnapshot(items);
        return buildNoticeSnapshot(items);
    }

    private static String buildPmSnapshot(List<Message> items, HttpClient client) {
        Map<String, Integer> totals = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Message item : items) {
            String uid = firstNonEmpty(item.getAuthorUid(), item.getPmid(), item.getAuthor());
            if (TextUtils.isEmpty(uid)) continue;
            String key = sha256(uid);
            if (!seen.add(key)) continue;

            // 列表页通常只有会话预览，详情页才包含实际消息条数。
            int total = 1;
            if (client != null && !TextUtils.isEmpty(item.getPmid())) {
                try {
                    String url = HttpClient.BASE_URL
                            + "home.php?mod=space&do=pm&subop=view&touid="
                            + item.getPmid() + "&mobile=2&_badge_ts="
                            + System.currentTimeMillis();
                    String detail = client.get(url);
                    List<ChatMessage> chat = ForumParser.parseChatMessages(
                            detail, "", item.getAvatarUrl());
                    if (chat != null && !chat.isEmpty()) {
                        int incoming = 0;
                        for (ChatMessage message : chat) {
                            if (!message.isOutgoing()) incoming++;
                        }
                        // 只统计对方发来的消息；用户自己的回复不应增加未读角标。
                        total = incoming;
                    }
                } catch (Exception ignored) {
                    // 详情请求失败时保留列表中的一条会话消息，避免将该会话丢失。
                }
            }
            totals.put(key, total);
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            result.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return result.toString();
    }

    private static String buildFollowerSnapshot(List<Message> items) {
        Set<String> ids = new HashSet<>();
        for (Message item : items) {
            String uid = firstNonEmpty(item.getAuthorUid(), item.getAuthor(), item.getTitle());
            if (!TextUtils.isEmpty(uid)) ids.add(sha256(uid));
        }
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        return join(sorted);
    }

    private static String buildNoticeSnapshot(List<Message> items) {
        // 保留 stable key 重复项，避免解析结果中不同事件因字段缺失碰撞后被 Set 合并。
        Map<String, Integer> seen = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (Message item : items) {
            String key = sha256(stableNoticeKey(item));
            int occurrence = seen.containsKey(key) ? seen.get(key) : 0;
            seen.put(key, occurrence + 1);
            ids.add(occurrence == 0 ? key : key + "#" + occurrence);
        }
        Collections.sort(ids);
        return join(ids);
    }

    /** 稳定通知事件键；同一通知中的不同回复楼层必须通过 PID 分开。 */
    private static String stableNoticeKey(Message item) {
        String id = firstNonEmpty(item.getPmid(), "");
        String pid = firstNonEmpty(item.getPid(), "");
        if (!TextUtils.isEmpty(id)) return "id|" + id + "|pid|" + pid;
        return "content|" + firstNonEmpty(item.getAuthorUid(), "") + "|"
                + firstNonEmpty(item.getAuthor(), "") + "|"
                + firstNonEmpty(item.getTitle(), "") + "|"
                + firstNonEmpty(item.getSummary(), "") + "|"
                + pid + "|" + normalizeKeyTime(item.getTime());
    }

    private static String normalizeKeyTime(String time) {
        if (TextUtils.isEmpty(time)) return "";
        String normalized = time.replace('\u00a0', ' ').trim();
        if (normalized.matches(".*\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}.*")
                || normalized.matches("^\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}.*")) {
            return normalized;
        }
        // 相对时间会随日历前进而变化，不应造成同一事件的 key 漂移。
        return "";
    }

    /** 与通知快照使用完全相同的键，供列表逐条已读状态查询。 */
    public static String stableKeyFor(Message item) {
        return stableNoticeKey(item);
    }

    /**
     * 保存当前快照并返回相对于上次查看基线的新增数量。
     * 首次运行只建立基线，返回 0。
     */
    public static synchronized int saveCurrentAndGetNewCount(Context context,
                                                               String viewType,
                                                               String snapshot) {
        return saveCurrentAndGetNewCountForAccount(context, activeAccountUid(context), viewType, snapshot);
    }

    /** 网络请求以发起时捕获的 UID 写快照，避免切号期间旧响应污染新账号基线。 */
    public static synchronized int saveCurrentAndGetNewCountForAccount(Context context,
                                                                        String accountUid,
                                                                        String viewType,
                                                                        String snapshot) {
        return saveCurrentAndGetNewCountForAccount(context, accountUid, viewType, snapshot,
                getReadEpochForAccount(context, accountUid));
    }

    /** requestEpoch 需在发起网络请求前捕获；旧响应不得覆盖“全部已读”操作之后的状态。 */
    public static synchronized int saveCurrentAndGetNewCountForAccount(Context context,
                                                                        String accountUid,
                                                                        String viewType,
                                                                        String snapshot,
                                                                        int requestEpoch) {
        if (context == null || TextUtils.isEmpty(viewType)) return 0;
        SharedPreferences p = prefs(context);
        String account = scopeUid(accountUid);
        String baselineKey = KEY_BASELINE_PREFIX + account + viewType;
        String currentKey = KEY_CURRENT_PREFIX + account + viewType;
        String pendingKey = KEY_PENDING_PREFIX + account + viewType;
        String readSetKey = KEY_READSET_PREFIX + account + viewType;
        String countKey = KEY_NEW_COUNT_PREFIX + account + viewType;
        String currentSnapshot = snapshot == null ? "" : snapshot;
        String oldBaseline = p.getString(baselineKey, null);
        boolean notificationType = !"pm".equals(viewType) && !"follower".equals(viewType);
        int latestReadEpoch = p.getInt(KEY_READ_EPOCH_PREFIX + account, 0);

        if (requestEpoch != latestReadEpoch) {
            // 该列表请求在“全部已读”之前发起、之后才返回。把这份旧请求视为清除时快照，
            // 不能让它把刚清掉的角标重新点亮。
            SharedPreferences.Editor editor = p.edit()
                    .putString(baselineKey, currentSnapshot)
                    .putString(currentKey, currentSnapshot)
                    .putBoolean(pendingKey, false)
                    .putBoolean(KEY_PENDING_ALL_READ_PREFIX + account + viewType, false)
                    .putInt(countKey, 0);
            Set<String> allSnapshotKeys = baseKeys(parseSet(currentSnapshot));
            if ("pm".equals(viewType)) {
                editor.putStringSet(KEY_CLICKED_PM_PREFIX + account,
                        baseKeys(parsePmMap(currentSnapshot).keySet()));
            } else if (notificationType) {
                editor.putStringSet(readSetKey, allSnapshotKeys)
                        .putStringSet(KEY_CLICKED_NOTICE_PREFIX + account + viewType, allSnapshotKeys);
            }
            editor.apply();
            return 0;
        }

        if (p.getBoolean(pendingKey, false) || oldBaseline == null) {
            SharedPreferences.Editor editor = p.edit()
                    .putString(baselineKey, currentSnapshot)
                    .putString(currentKey, currentSnapshot)
                    .putBoolean(pendingKey, false)
                    .putInt(countKey, 0);
            Set<String> firstSnapshotKeys = baseKeys(parseSet(currentSnapshot));
            if (notificationType) {
                editor.putStringSet(readSetKey, firstSnapshotKeys);
            }
            if (p.getBoolean(KEY_PENDING_ALL_READ_PREFIX + account + viewType, false)) {
                if ("pm".equals(viewType)) {
                    editor.putStringSet(KEY_CLICKED_PM_PREFIX + account,
                            baseKeys(parsePmMap(currentSnapshot).keySet()));
                } else if (notificationType) {
                    editor.putStringSet(KEY_CLICKED_NOTICE_PREFIX + account + viewType,
                            firstSnapshotKeys);
                }
                editor.putBoolean(KEY_PENDING_ALL_READ_PREFIX + account + viewType, false);
            }
            editor.apply();
            return 0;
        }

        int count;
        SharedPreferences.Editor editor = p.edit().putString(currentKey, currentSnapshot);
        if (!notificationType) {
            count = calculateNewCount(viewType, oldBaseline, currentSnapshot);
        } else {
            Set<String> readSet = p.getStringSet(readSetKey, null);
            if (readSet == null) {
                // 从 v5.21 旧基线迁移：已查看基线维持已读，基线之后出现的新通知仍未读。
                readSet = baseKeys(parseSet(oldBaseline));
                editor.putStringSet(readSetKey, readSet);
            }
            count = countNotInSet(currentSnapshot, readSet);
        }
        editor.putInt(countKey, count).apply();
        return count;
    }

    /**
     * 进入分类时的兼容回调。私信/粉丝仍按分类基线处理；其它通知只刷新角标，
     * 不再把未点击的整页内容批量标成已读。
     */
    public static synchronized void markViewed(Context context, String viewType) {
        if (context == null || TextUtils.isEmpty(viewType)) return;
        SharedPreferences p = prefs(context);
        String account = scope(context);
        String current = p.getString(KEY_CURRENT_PREFIX + account + viewType, null);
        SharedPreferences.Editor editor = p.edit();
        if ("pm".equals(viewType) || "follower".equals(viewType)) {
            if (current == null) {
                editor.putBoolean(KEY_PENDING_PREFIX + account + viewType, true);
            } else {
                editor.putString(KEY_BASELINE_PREFIX + account + viewType, current)
                        .putBoolean(KEY_PENDING_PREFIX + account + viewType, false);
            }
            editor.putInt(KEY_NEW_COUNT_PREFIX + account + viewType, 0);
        } else if (current == null) {
            editor.putBoolean(KEY_PENDING_PREFIX + account + viewType, true)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + viewType, 0);
        } else {
            Set<String> readSet = p.getStringSet(KEY_READSET_PREFIX + account + viewType, null);
            if (readSet == null) readSet = baseKeys(parseSet(
                    p.getString(KEY_BASELINE_PREFIX + account + viewType, "")));
            editor.putBoolean(KEY_PENDING_PREFIX + account + viewType, false)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + viewType,
                            countNotInSet(current, readSet));
        }
        editor.apply();
        fireViewed(viewType);
    }

    /** 用户实际点开的通知/会话才逐条写入已读集合。 */
    public static synchronized void markItemRead(Context context, String viewType, String itemKey) {
        if (context == null || TextUtils.isEmpty(viewType) || TextUtils.isEmpty(itemKey)) return;
        SharedPreferences p = prefs(context);
        String account = scope(context);
        String current = p.getString(KEY_CURRENT_PREFIX + account + viewType, "");
        if ("pm".equals(viewType)) {
            Map<String, Integer> baseline = parsePmMap(
                    p.getString(KEY_BASELINE_PREFIX + account + viewType, ""));
            Map<String, Integer> currentCounts = parsePmMap(current);
            String hashedUid = sha256(itemKey);
            if (currentCounts.containsKey(hashedUid)) baseline.put(hashedUid, currentCounts.get(hashedUid));
            String serializedBaseline = serializePmMap(baseline);
            String clickedKey = KEY_CLICKED_PM_PREFIX + account;
            Set<String> clicked = new HashSet<>(p.getStringSet(clickedKey, Collections.emptySet()));
            clicked.add(hashedUid);
            p.edit().putString(KEY_BASELINE_PREFIX + account + viewType, serializedBaseline)
                    .putStringSet(clickedKey, clicked)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + viewType,
                            calculatePmDelta(serializedBaseline, current)).apply();
        } else if ("follower".equals(viewType)) {
            Set<String> baseline = parseSet(
                    p.getString(KEY_BASELINE_PREFIX + account + viewType, ""));
            baseline.add(sha256(itemKey));
            String serializedBaseline = serializeSet(baseline);
            p.edit().putString(KEY_BASELINE_PREFIX + account + viewType, serializedBaseline)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + viewType,
                            calculateNewCount(viewType, serializedBaseline, current)).apply();
        } else {
            String readSetKey = KEY_READSET_PREFIX + account + viewType;
            Set<String> readSet = new HashSet<>(p.getStringSet(readSetKey,
                    baseKeys(parseSet(p.getString(KEY_BASELINE_PREFIX + account + viewType, "")))));
            String hashedKey = sha256(itemKey);
            readSet.add(hashedKey);
            String clickedKey = KEY_CLICKED_NOTICE_PREFIX + account + viewType;
            Set<String> clicked = new HashSet<>(p.getStringSet(clickedKey, new HashSet<>()));
            clicked.add(hashedKey);
            int remaining = countNotInSet(current, readSet);
            p.edit().putStringSet(readSetKey, readSet)
                    .putStringSet(clickedKey, clicked)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + viewType, remaining).apply();
        }
        fireViewed(viewType);
    }

    public static synchronized boolean isItemRead(Context context, String viewType, String itemKey) {
        if (context == null || TextUtils.isEmpty(viewType) || TextUtils.isEmpty(itemKey)) return false;
        if ("pm".equals(viewType)) return !hasPmUnread(context, itemKey);
        SharedPreferences p = prefs(context);
        String account = scope(context);
        if ("follower".equals(viewType)) {
            String hashedUid = sha256(itemKey);
            String current = p.getString(KEY_CURRENT_PREFIX + account + viewType, null);
            String baseline = p.getString(KEY_BASELINE_PREFIX + account + viewType, null);
            if (current == null || baseline == null) return true;
            return !parseSet(current).contains(hashedUid) || parseSet(baseline).contains(hashedUid);
        }
        Set<String> readSet = p.getStringSet(KEY_READSET_PREFIX + account + viewType, null);
        if (readSet == null) {
            readSet = baseKeys(parseSet(p.getString(KEY_BASELINE_PREFIX + account + viewType, "")));
        }
        return readSet.contains(sha256(itemKey));
    }

    public static synchronized boolean isNoticeUnread(Context context, String viewType, String itemKey) {
        return context != null && !TextUtils.isEmpty(viewType)
                && !isItemRead(context, viewType, itemKey);
    }

    /** PM 列表的点击标记只用于压制服务端尚未刷新的旧未读状态。 */
    public static synchronized boolean isPmClicked(Context context, String itemKey) {
        if (context == null || TextUtils.isEmpty(itemKey)) return false;
        String key = KEY_CLICKED_PM_PREFIX + scope(context);
        return prefs(context).getStringSet(key, Collections.emptySet()).contains(sha256(itemKey));
    }

    /** 服务端明确返回已读时清理旧点击标记，使该会话以后新增消息可重新高亮。 */
    public static synchronized void clearPmClickedIfServerRead(Context context, String itemKey) {
        if (context == null || TextUtils.isEmpty(itemKey)) return;
        String key = KEY_CLICKED_PM_PREFIX + scope(context);
        SharedPreferences p = prefs(context);
        Set<String> existing = p.getStringSet(key, null);
        String hashed = sha256(itemKey);
        if (existing == null || !existing.contains(hashed)) return;
        Set<String> updated = new HashSet<>(existing);
        updated.remove(hashed);
        p.edit().putStringSet(key, updated).apply();
    }

    /** 用户真正点开的条目；与首屏基线分开，避免旧服务端未读 class 把它重新点亮。 */
    public static synchronized boolean isNoticeClicked(Context context, String viewType, String itemKey) {
        if (context == null || TextUtils.isEmpty(viewType) || TextUtils.isEmpty(itemKey)) return false;
        String key = KEY_CLICKED_NOTICE_PREFIX + scope(context) + viewType;
        return prefs(context).getStringSet(key, Collections.emptySet()).contains(sha256(itemKey));
    }

    public static synchronized void markNoticeClicked(Context context, String viewType, String itemKey) {
        markItemRead(context, viewType, itemKey);
    }

    public static synchronized int getNewCount(Context context, String viewType) {
        return getNewCountForAccount(context, activeAccountUid(context), viewType);
    }

    public static synchronized int getNewCountForAccount(Context context, String accountUid, String viewType) {
        if (context == null || TextUtils.isEmpty(viewType)) return 0;
        return prefs(context).getInt(KEY_NEW_COUNT_PREFIX + scopeUid(accountUid) + viewType, 0);
    }

    public static synchronized int getReadEpochForAccount(Context context, String accountUid) {
        if (context == null) return 0;
        return prefs(context).getInt(KEY_READ_EPOCH_PREFIX + scopeUid(accountUid), 0);
    }

    public static synchronized int getTotalNewCount(Context context) {
        if (context == null) return 0;
        String[] types = {"pm", "follower", "mypost", "interactive", "system", "app"};
        int total = 0;
        for (String type : types) total += getNewCount(context, type);
        return total;
    }

    /** 显式“全部已读”：当前快照整体进入已读集合，不影响随后出现的新事件。 */
    public static synchronized void markAllViewed(Context context) {
        if (context == null) return;
        SharedPreferences p = prefs(context);
        String account = scope(context);
        String[] types = {"pm", "follower", "mypost", "interactive", "system", "app"};
        SharedPreferences.Editor editor = p.edit().putInt(
                KEY_READ_EPOCH_PREFIX + account,
                p.getInt(KEY_READ_EPOCH_PREFIX + account, 0) + 1);
        for (String type : types) {
            String current = p.getString(KEY_CURRENT_PREFIX + account + type, null);
            if (current == null) {
                editor.putBoolean(KEY_PENDING_PREFIX + account + type, true)
                        .putBoolean(KEY_PENDING_ALL_READ_PREFIX + account + type, true)
                        .putInt(KEY_NEW_COUNT_PREFIX + account + type, 0);
                continue;
            }
            editor.putString(KEY_BASELINE_PREFIX + account + type, current)
                    .putBoolean(KEY_PENDING_PREFIX + account + type, false)
                    .putInt(KEY_NEW_COUNT_PREFIX + account + type, 0);
            if ("pm".equals(type)) {
                Set<String> clickedPm = new HashSet<>(p.getStringSet(
                        KEY_CLICKED_PM_PREFIX + account, Collections.emptySet()));
                clickedPm.addAll(parsePmMap(current).keySet());
                editor.putStringSet(KEY_CLICKED_PM_PREFIX + account, clickedPm);
            } else if (!"follower".equals(type)) {
                Set<String> allCurrent = baseKeys(parseSet(current));
                editor.putStringSet(KEY_READSET_PREFIX + account + type, allCurrent)
                        .putStringSet(KEY_CLICKED_NOTICE_PREFIX + account + type, allCurrent);
            }
        }
        editor.apply();
        fireViewed("*");
    }

    private static int calculateNewCount(String viewType, String baseline, String current) {
        if (TextUtils.isEmpty(current)) return 0;
        if ("pm".equals(viewType)) return calculatePmDelta(baseline, current);

        Set<String> oldSet = parseSet(baseline);
        int count = 0;
        for (String key : parseSet(current)) if (!oldSet.contains(key)) count++;
        return count;
    }

    private static int countNotInSet(String snapshot, Set<String> readSet) {
        if (TextUtils.isEmpty(snapshot)) return 0;
        Set<String> safeReadSet = readSet == null ? Collections.emptySet() : readSet;
        int count = 0;
        for (String key : parseSet(snapshot)) {
            if (!safeReadSet.contains(baseKey(key))) count++;
        }
        return count;
    }

    private static Set<String> baseKeys(Set<String> keys) {
        Set<String> result = new HashSet<>();
        if (keys != null) for (String key : keys) result.add(baseKey(key));
        return result;
    }

    private static String baseKey(String key) {
        if (key == null) return "";
        int duplicate = key.indexOf('#');
        return duplicate > 0 ? key.substring(0, duplicate) : key;
    }

    private static boolean hasPmUnread(Context context, String uid) {
        if (TextUtils.isEmpty(uid)) return false;
        SharedPreferences p = prefs(context);
        String account = scope(context);
        String key = sha256(uid);
        Map<String, Integer> baseline = parsePmMap(
                p.getString(KEY_BASELINE_PREFIX + account + "pm", ""));
        Map<String, Integer> current = parsePmMap(
                p.getString(KEY_CURRENT_PREFIX + account + "pm", ""));
        int currentCount = current.containsKey(key) ? current.get(key) : 0;
        int baselineCount = baseline.containsKey(key) ? baseline.get(key) : 0;
        return currentCount > baselineCount;
    }

    private static String serializePmMap(Map<String, Integer> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(values).entrySet()) {
            result.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return result.toString();
    }

    private static int calculatePmDelta(String baseline, String current) {
        Map<String, Integer> oldMap = parsePmMap(baseline);
        Map<String, Integer> currentMap = parsePmMap(current);
        int count = 0;
        for (Map.Entry<String, Integer> entry : currentMap.entrySet()) {
            int old = oldMap.containsKey(entry.getKey()) ? oldMap.get(entry.getKey()) : 0;
            if (entry.getValue() > old) count += entry.getValue() - old;
        }
        return count;
    }

    private static Set<String> parseSet(String value) {
        Set<String> result = new HashSet<>();
        if (TextUtils.isEmpty(value)) return result;
        for (String item : value.split(";")) {
            if (!TextUtils.isEmpty(item)) result.add(item);
        }
        return result;
    }

    private static Map<String, Integer> parsePmMap(String value) {
        Map<String, Integer> result = new HashMap<>();
        if (TextUtils.isEmpty(value)) return result;
        for (String item : value.split(";")) {
            int index = item.lastIndexOf('=');
            if (index <= 0) continue;
            try {
                result.put(item.substring(0, index), Integer.parseInt(item.substring(index + 1)));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value).append(';');
        return result.toString();
    }

    private static String serializeSet(Set<String> values) {
        List<String> sorted = new ArrayList<>();
        if (values != null) sorted.addAll(values);
        Collections.sort(sorted);
        return join(sorted);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return String.valueOf(value == null ? "" : value.hashCode());
        }
    }
}
