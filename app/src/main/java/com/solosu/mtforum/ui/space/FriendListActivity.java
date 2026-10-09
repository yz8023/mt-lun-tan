package com.solosu.mtforum.ui.space;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.solosu.mtforum.R;
import com.solosu.mtforum.adapter.FriendAdapter;
import com.solosu.mtforum.databinding.ActivityFriendListBinding;
import com.solosu.mtforum.model.Friend;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.UserSessionManager;

import java.util.List;

/**
 * 好友/关注/粉丝列表页（原生）
 * 支持 3 种模式：friends（好友）、following（关注）、followers（粉丝）
 */
public class FriendListActivity extends AppCompatActivity {

    private ActivityFriendListBinding binding;
    private HttpClient httpClient;
    private FriendAdapter adapter;
    private String mode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFriendListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        httpClient = HttpClient.getInstance();
        mode = getIntent().getStringExtra("mode");

        String title;
        String listUrl;

        // 空 uid 表示当前账号；传入 uid 时展示目标用户的关注/粉丝，不能串用当前账号统计。
        String currentUid = UserSessionManager.getInstance().getUid(this);
        if (currentUid == null) currentUid = "";
        String requestedUid = getIntent().getStringExtra("uid");
        String ownerUid = TextUtils.isEmpty(requestedUid) ? currentUid : requestedUid;
        String ownerName = getIntent().getStringExtra("username");
        String ownerLabel = TextUtils.isEmpty(ownerName) ? "用户" : ownerName;

        if ("following".equals(mode)) {
            title = TextUtils.isEmpty(requestedUid) ? "我的关注" : ownerLabel + "的关注";
            // ★ 修复：原来的 home.php?mod=space&do=follow 返回空页面
            //   正确 URL: home.php?mod=follow&do=following&uid={uid}&mobile=2
            listUrl = HttpClient.BASE_URL + "home.php?mod=follow&do=following&uid=" + ownerUid + "&mobile=2";
        } else if ("followers".equals(mode)) {
            title = TextUtils.isEmpty(requestedUid) ? "我的粉丝" : ownerLabel + "的粉丝";
            listUrl = HttpClient.BASE_URL + "home.php?mod=follow&do=follower"
                    + (TextUtils.isEmpty(requestedUid) ? "" : "&uid=" + requestedUid)
                    + "&mobile=2";
        } else {
            title = "我的好友";
            listUrl = HttpClient.BASE_URL + "home.php?mod=space&do=friend&mobile=2";
        }

        binding.toolbar.setTitle(title);
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new FriendAdapter(this);
        adapter.setOnItemClickListener((friend, position) -> {
            // ★ 修复：从 WebView 跳转改为原生用户资料页
            if (friend != null && friend.getUid() != null) {
                Intent intent = new Intent(this, UserProfileActivity.class);
                intent.putExtra("uid", friend.getUid());
                intent.putExtra("username", friend.getUsername());
                startActivity(intent);
            }
        });
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.swipeRefresh.setOnRefreshListener(() -> loadData(listUrl));
        binding.swipeRefresh.setColorSchemeResources(R.color.primary);

        loadData(listUrl);
    }

    private void loadData(String listUrl) {
        binding.progressBar.setVisibility(android.view.View.VISIBLE);
        binding.swipeRefresh.setEnabled(false);

        new Thread(() -> {
            try {
                String html = httpClient.get(listUrl);

                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread(() -> {
                        binding.progressBar.setVisibility(android.view.View.GONE);
                        binding.swipeRefresh.setRefreshing(false);
                        binding.swipeRefresh.setEnabled(true);
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                List<Friend> friends = ForumParser.parseFriendList(html);

                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(android.view.View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);

                    if (friends.isEmpty()) {
                        binding.recyclerView.setVisibility(android.view.View.GONE);
                        binding.tvEmpty.setVisibility(android.view.View.VISIBLE);
                    } else {
                        binding.recyclerView.setVisibility(android.view.View.VISIBLE);
                        binding.tvEmpty.setVisibility(android.view.View.GONE);
                        adapter.setFriendList(friends);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(android.view.View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}