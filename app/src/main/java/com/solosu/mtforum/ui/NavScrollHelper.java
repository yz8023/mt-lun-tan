package com.solosu.mtforum.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.MainActivity;

/**
 * 列表滚动 → 底栏自动隐藏（build65 新增）。
 *
 * <p>论坛用户反馈：底部悬浮栏一直占着一块屏幕，看长帖很挡。
 * 现在下滑隐藏、上滑立刻出现，并且可以在侧边栏关掉。
 *
 * <p>一行接入：{@code NavScrollHelper.attach(recyclerView, this);}
 */
public final class NavScrollHelper {

    /** 超过这个位移才判定为一次有效方向变化，避免手抖来回抽动 */
    private static final int THRESHOLD_PX = 12;

    private NavScrollHelper() {
    }

    public static void attach(RecyclerView rv, final Fragment owner) {
        if (rv == null) return;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int accumulated = 0;

            @Override
            public void onScrolled(@NonNull RecyclerView v, int dx, int dy) {
                if (owner == null || !owner.isAdded()) return;
                if (!(owner.getActivity() instanceof MainActivity)) return;
                MainActivity host = (MainActivity) owner.getActivity();
                if (!UiSettings.isNavAutoHide(host)) {
                    host.setNavBarShown(true);
                    return;
                }
                // 方向反转就清零，保证反向滑动能立刻起效
                if ((dy > 0) != (accumulated > 0)) accumulated = 0;
                accumulated += dy;

                if (accumulated > THRESHOLD_PX) {
                    host.setNavBarShown(false);
                    accumulated = 0;
                } else if (accumulated < -THRESHOLD_PX) {
                    host.setNavBarShown(true);
                    accumulated = 0;
                }
                // 滑到顶部一定显示
                if (!v.canScrollVertically(-1)) host.setNavBarShown(true);
            }
        });
    }
}
