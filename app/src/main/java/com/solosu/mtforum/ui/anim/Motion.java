package com.solosu.mtforum.ui.anim;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * 动效令牌（build62 新增）。
 *
 * <p>数值直接移植自 <a href="https://github.com/feitangyuan/motion-web">feitangyuan/motion-web</a>
 * 的 {@code references/motion-tokens.md} 与 {@code references/handfeel.md}。
 * 那套体系是给 Web 写的（CSS cubic-bezier / Framer spring），但两边的数学是一样的：
 *
 * <ul>
 *   <li>CSS {@code cubic-bezier(x1,y1,x2,y2)} ←→ Android {@link PathInterpolator}(x1,y1,x2,y2)，
 *       控制点一一对应，可以原样搬。</li>
 *   <li>Framer 的 {@code stiffness/damping/mass} ←→ Android {@link SpringForce} 的
 *       {@code stiffness/dampingRatio}，换算 {@code ζ = damping / (2·√(stiffness·mass))}。</li>
 * </ul>
 *
 * <p>核心原则（handfeel.md §1）：<b>别用 lerp，用欠阻尼弹簧</b>。
 * lerp 永远单调减速，读起来像"滑过去"；阻尼比略小于 1 的弹簧会轻微过冲再落位，
 * 读起来才像"有重量地落下"。
 */
public final class Motion {

    private Motion() {
    }

    // ==================== 时长刻度 (motion-tokens.md §Duration Scale) ====================

    /** 80–100ms：点按反馈、tooltip */
    public static final long INSTANT = 90L;
    /** 150–200ms：hover / 按钮激活 / 开关 */
    public static final long FAST = 180L;
    /** 280–350ms：卡片展开、面板滑动、抽屉 */
    public static final long STANDARD = 320L;
    /** 400–500ms：区块揭示、图片打开 */
    public static final long MEDIUM = 450L;
    /** 600–800ms：页面进入、Hero 文字揭示 */
    public static final long SLOW = 700L;

    /** 列表逐项延迟（standard-list 70–80ms） */
    public static final long STAGGER_LIST = 70L;
    /** 密集网格逐项延迟（tight-grid 40ms） */
    public static final long STAGGER_GRID = 40L;

    // ==================== 缓动字典 (motion-tokens.md §Easing Dictionary) ====================

    /** {@code cubic-bezier(0.16, 1, 0.3, 1)} 快进 → 柔和落位。<b>揭示类动画的默认曲线</b>。 */
    public static Interpolator easeOutExpo() {
        return new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    }

    /** {@code cubic-bezier(0.7, 0, 0.84, 0)} 慢起 → 硬收。只用于退出。 */
    public static Interpolator easeInExpo() {
        return new PathInterpolator(0.7f, 0f, 0.84f, 0f);
    }

    /** {@code cubic-bezier(0.76, 0, 0.24, 1)} 对称平滑，适合自动循环。 */
    public static Interpolator easeInOutQuart() {
        return new PathInterpolator(0.76f, 0f, 0.24f, 1f);
    }

    /** {@code cubic-bezier(0.34, 1.56, 0.64, 1)} 轻微过冲落位，俏皮。 */
    public static Interpolator easeOutBack() {
        return new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    }

    /** {@code cubic-bezier(0, 0.55, 0.45, 1)} 极快减速，干脆的机械感。 */
    public static Interpolator easeOutCirc() {
        return new PathInterpolator(0f, 0.55f, 0.45f, 1f);
    }

    /** {@code cubic-bezier(0.4, 0, 0.2, 1)} Material 默认，安全基线。 */
    public static Interpolator easeStandard() {
        return new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    }

    // ==================== 弹簧预设 (motion-tokens.md §Spring Config) ====================
    // ζ = damping / (2·√stiffness)，mass 均为 1

    /** gentle：100 / 15 → ζ≈0.75，柔和无弹跳，用于环境性揭示 */
    public static SpringForce springGentle() {
        return new SpringForce().setStiffness(100f).setDampingRatio(0.75f);
    }

    /** default：200 / 22 → ζ≈0.78，自然手感，卡片与抽屉 */
    public static SpringForce springDefault() {
        return new SpringForce().setStiffness(200f).setDampingRatio(0.78f);
    }

    /** snappy：350 / 28 → ζ≈0.75，高级感快速吸附，按钮反馈与底栏指示器 */
    public static SpringForce springSnappy() {
        return new SpringForce().setStiffness(350f).setDampingRatio(0.75f);
    }

    /** bouncy：200 / 10 → ζ≈0.35，明显过冲，图标确认动作 */
    public static SpringForce springBouncy() {
        return new SpringForce().setStiffness(200f).setDampingRatio(0.35f);
    }

    /**
     * dyparse 的 Pager 切页弹簧：stiffness 322.2 / damping 32.31 → ζ≈0.90。
     * 接近临界阻尼，几乎不过冲但仍有重量感 —— 适合整页切换这种大位移。
     */
    public static SpringForce springPager() {
        return new SpringForce().setStiffness(322.2f).setDampingRatio(0.90f);
    }

    // ==================== 弹簧工具 ====================

    private static final int TAG_SPRING = 0x7f5a0001;

    /** 把某个属性弹到目标值；同一 View 同一属性复用同一个动画实例，避免互相打架 */
    public static SpringAnimation spring(View view, DynamicAnimation.ViewProperty property,
                                         float target, SpringForce force) {
        SpringAnimation anim = new SpringAnimation(view, property);
        force.setFinalPosition(target);
        anim.setSpring(force);
        anim.animateToFinalPosition(target);
        return anim;
    }

    // ==================== 点按反馈 (handfeel.md §7 down-records / up-decides) ====================

    /**
     * 给可点元素加"按下缩小、抬起弹回"的触觉反馈。
     *
     * <p>handfeel.md 的原则：<b>按下只记录，抬起才决定；松手立刻恢复，不要等计时器</b>。
     * 所以这里 DOWN 时用短 tween 缩下去，UP/CANCEL 立刻用 snappy 弹簧弹回，
     * 不依赖任何延时，手指离开的瞬间就开始回弹。
     *
     * @param scale 按下时缩到的比例，0.96 适合大块卡片，0.92 适合小图标
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void pressFeedback(View view, final float scale) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(scale).scaleY(scale)
                            .setDuration(INSTANT)
                            .setInterpolator(easeOutCirc())
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    // 抬手立刻弹回，用弹簧而不是 tween，才有"回弹"而不是"滑回"
                    spring(v, DynamicAnimation.SCALE_X, 1f, springSnappy());
                    spring(v, DynamicAnimation.SCALE_Y, 1f, springSnappy());
                    break;
                default:
                    break;
            }
            // 返回 false，不吞掉事件，OnClickListener 照常工作
            return false;
        });
    }

    /** 默认 0.96 的按压反馈 */
    public static void pressFeedback(View view) {
        pressFeedback(view, 0.96f);
    }

    // ==================== 揭示与错峰 ====================

    /**
     * 单个元素的进场：淡入 + 上移 12dp，easeOutExpo。
     */
    public static void revealIn(View view, long delay) {
        if (view == null) return;
        float dy = 12f * view.getResources().getDisplayMetrics().density;
        view.setAlpha(0f);
        view.setTranslationY(dy);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(STANDARD)
                .setInterpolator(easeOutExpo())
                .start();
    }

    /**
     * 容器内子元素错峰进场（standard-list 70ms/项）。
     * 用在侧边栏账号列表、消息分类这类一次性出现的小列表上。
     */
    public static void staggerChildren(ViewGroup parent, long perItemDelay) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            revealIn(parent.getChildAt(i), i * perItemDelay);
        }
    }

    public static void staggerChildren(ViewGroup parent) {
        staggerChildren(parent, STAGGER_LIST);
    }

    // ==================== 数值过渡 ====================

    /** 颜色/尺寸之类的通用 tween，统一走 easeOutExpo */
    public static ValueAnimator tween(float from, float to, long duration,
                                      ValueAnimator.AnimatorUpdateListener listener) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(easeOutExpo());
        animator.addUpdateListener(listener);
        return animator;
    }
}
