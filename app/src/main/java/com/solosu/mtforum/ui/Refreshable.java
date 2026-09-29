package com.solosu.mtforum.ui;

/**
 * 可刷新页面（build63 新增）。
 *
 * <p>底栏再次点击<b>当前已选中</b>的 Tab 时，MainActivity 会找到对应 Fragment 并调用
 * {@link #onTabReselected()}，用来做「回到顶部 + 重新拉数据」。
 *
 * <p>约定：实现方应当自己处理「正在刷新中」的重入，不要每点一次就发一轮请求 ——
 * bbs.binmt.cc 挂着阿里云 ESA，短时间重复请求会吃 403。
 */
public interface Refreshable {

    /** 底栏再次点击当前 Tab 时触发 */
    void onTabReselected();
}
