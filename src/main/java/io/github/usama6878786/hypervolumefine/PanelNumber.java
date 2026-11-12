package io.github.usama6878786.hypervolumefine;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 音量数字 —— 直接画进系统音量面板自己的窗口里（SystemUI 进程）。
 *
 * hook {@code WindowManagerImpl / WindowManagerGlobal.addView}，识别出音量面板窗口后，
 * 把数字 TextView 加进该窗口的 content 容器并置于最上层。数字是纯文字（无背景板），
 * 放在面板内部、三点图标下方，宽度与滑条一致。
 *
 * 位置策略：面板内部是 MIUI 自绘的 {@code MiuiVolumeSeekBar}，但每次弹出面板它的 window 坐标
 * 会累积右移十几像素（870 → 882 → 898 …），逐帧跟随会让数字越点越偏。所以：
 *   1) 面板动画稳定后标定一次，把相对屏幕右边缘/顶部的距离与尺寸换算成 dp 缓存；
 *   2) 之后每次弹面板直接用缓存坐标摆放，不再重新测量；
 *   3) 出场随面板淡入/淡出，并与滑入滑出动画错开，避免错位。
 */
final class PanelNumber {

    private static final String TAG = "VolumeFine";
    /** 音量面板窗口类型：AOSP 是 2014，这台 HyperOS 上实测是 2020 */
    private static final int[] VOLUME_WINDOW_TYPES = {2014, 2020};

    private static boolean sInstalled;

    private final VolumeFineModule m;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mNumber;
    private ViewGroup mHost;
    private View mPanelDecor;
    private View mSeekBarRef;      // 上次找到的滑条，缓存以省掉每轮遍历视图树
    private AudioManager mAudioManager;
    private boolean mPolling;
    private boolean mFadedOut;
    private boolean mStableThisShow;
    private boolean mTreeDumped;
    /** 面板关闭后无窗口可跟时，连续空转的轮数，达到阈值即停止循环 */
    private int mIdleTicks;

    // 位置
    private Rect mPrevBounds;
    private Rect mAppliedBounds;
    private int mCalRightDp = -1;
    private int mCalTopDp = -1;
    private int mCalWidthDp = -1;
    private int mCalHeightDp = -1;

    // 配色
    private Rect mTextBarRect;
    private int mTextTop;
    private int mTextHeight;
    private boolean mDarkText;
    private int mStreamMax = -1;   // 缓存的音乐流最大档位，避免每轮 IPC 查询
    private int mShownVolume = Integer.MIN_VALUE;   // 当前已显示的档位，避免重复 setText
    private boolean mSwitchOn = true;               // enabled && showNumber 缓存
    private long mSwitchAt;                         // 上次读开关的时间

    PanelNumber(VolumeFineModule module) {
        m = module;
    }

    // ------------------------------------------------------------------ 安装

    void install(ClassLoader cl) throws Throwable {
        if (sInstalled) {
            return;   // onPackageLoaded 可能回调多次，避免重复挂载出现两个数字
        }
        sInstalled = true;

        int hooked = 0;
        for (String clsName : new String[]{"android.view.WindowManagerImpl", "android.view.WindowManagerGlobal"}) {
            try {
                Class<?> cls = Class.forName(clsName, false, cl);
                for (final Method method : cls.getDeclaredMethods()) {
                    if (!method.getName().equals("addView")) {
                        continue;
                    }
                    Class<?>[] ps = method.getParameterTypes();
                    if (ps.length < 2 || ps[0] != View.class) {
                        continue;
                    }
                    method.setAccessible(true);
                    m.hook(method).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object params = chain.getArg(1);
                            if (params instanceof WindowManager.LayoutParams) {
                                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
                                if (m.debugEnabled()) {
                                    m.log(3, TAG, "[sysui] addView type=" + lp.type
                                            + " title=" + lp.getTitle());
                                }
                                if (isVolumePanel(lp)) {
                                    Object view = chain.getArg(0);
                                    if (view instanceof View) {
                                        onVolumePanelAdded((View) view);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            m.log(5, TAG, "[sysui] panel detect failed: " + t);
                        }
                        return result;
                    });
                    hooked++;
                }
            } catch (Throwable t) {
                m.log(5, TAG, "[sysui] hook " + clsName + " failed: " + t);
            }
        }

        m.log(4, TAG, "[sysui] installed, addView hooks=" + hooked);
    }

    /** 把“面板内可显示”标记写进 Settings.System，带重试与多路 context 兜底 */
    private static boolean isVolumePanel(WindowManager.LayoutParams lp) {
        for (int type : VOLUME_WINDOW_TYPES) {
            if (lp.type == type) {
                return true;
            }
        }
        CharSequence title = lp.getTitle();
        return title != null && title.toString().contains("VolumePanel");
    }

    // ------------------------------------------------------------------ 挂载

    private void onVolumePanelAdded(View decor) {
        try {
            View content = decor.findViewById(android.R.id.content);
            ViewGroup host = content instanceof ViewGroup ? (ViewGroup) content : null;
            if (host == null && decor instanceof ViewGroup) {
                host = (ViewGroup) decor;
            }
            if (host == null) {
                m.log(5, TAG, "[sysui] no content container");
                return;
            }
            mHost = host;
            mPanelDecor = decor;
            m.log(4, TAG, "[sysui] volume panel window detected");

            if (mNumber == null) {
                mNumber = buildView(decor.getContext());
            }
            if (mNumber.getParent() instanceof ViewGroup) {
                ((ViewGroup) mNumber.getParent()).removeView(mNumber);
            }

            mPrevBounds = null;
            mAppliedBounds = null;
            mSeekBarRef = null;
            mFadedOut = false;
            mStableThisShow = false;
            mDarkText = false;
            mTextBarRect = null;
            mShownVolume = Integer.MIN_VALUE;   // 新面板：强制重写一次文字
            // 先彻底隐身再挂上去：anchor 位置只是临时占位，若以可见状态挂上会在错位置闪一下
            mNumber.animate().cancel();
            mNumber.setAlpha(0f);

            host.addView(mNumber, anchorParams(decor.getContext()));
            mNumber.bringToFront();     // 只在这里置顶一次，之后不再重复调用（它会 remove+add 触发重绘）
            refreshText();

            // 已标定过：直接用缓存坐标摆好，等滑条可见后再淡入
            Rect cached = cachedBarRect(decor.getContext());
            if (cached != null) {
                mAppliedBounds = cached;
                applyPosition(cached);
            }

            final ViewTreeObserver.OnGlobalLayoutListener listener =
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                applyExactPosition(decor);
                            } catch (Throwable ignored) {
                            } finally {
                                // 一次性：定位到后自移除，避免监听器在每次弹出面板时无限累积
                                try {
                                    decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.postDelayed(() -> {
                try {
                    applyExactPosition(decor);
                } catch (Throwable ignored) {
                }
            }, 60);

            startPolling(decor);
        } catch (Throwable t) {
            m.log(5, TAG, "[sysui] attach failed: " + t);
        }
    }

    private FrameLayout.LayoutParams anchorParams(Context ctx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 56), dp(ctx, 22));
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.rightMargin = dp(ctx, 19);
        lp.topMargin = dp(ctx, 100);
        return lp;
    }

    private void startPolling(View decor) {
        mPanelDecor = decor;
        if (mPolling) {
            return;   // 已有循环，它会读 mPanelDecor，自动跟随新窗口
        }
        mPolling = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mNumber == null) {
                    mPolling = false;
                    return;
                }
                // 面板已关闭、宿主不再显示时：先让数字立即淡出（与滑条同步消失），
                // 连续空转若干轮后再停止循环，避免后台空耗
                boolean hostAlive = mHost != null && mHost.isShown() && mHost.isAttachedToWindow();
                if (!hostAlive) {
                    if (mStableThisShow && !mFadedOut) {
                        fadeOut();               // 立即淡出，不等延迟窗口
                    }
                    mIdleTicks++;
                    if (mIdleTicks >= 10) {      // 约 600ms 后确认面板确实关了
                        mPolling = false;
                        mIdleTicks = 0;
                        return;
                    }
                    mHandler.postDelayed(this, 60);
                    return;
                }
                mIdleTicks = 0;

                // 开关关掉时不显示数字（原来这两个开关只控制悬浮窗，现在数字在面板里，判断挪到这里）
                if (!numberEnabled()) {
                    if (!mFadedOut) {
                        fadeOut();
                    }
                    mHandler.postDelayed(this, 60);
                    return;
                }

                if (mNumber.getParent() == null) {
                    // 面板窗口被复用、内容重建会把我们的 View 摘掉，这里自动补挂
                    try {
                        mNumber.setAlpha(0f);
                        mAppliedBounds = null;
                        mPrevBounds = null;
                        mDarkText = false;
                        mTextBarRect = null;
                        mHost.addView(mNumber, anchorParams(mNumber.getContext()));
                        m.log(4, TAG, "[sysui] re-attached number view");
                    } catch (Throwable t) {
                        m.log(5, TAG, "[sysui] re-attach failed: " + t);
                    }
                }
                try {
                    refreshText();

                    // 定位：标定完成后不再重新测量位置（用缓存），但**始终**检查滑条是否还可见，
                    // 用它的可见性来驱动淡出 —— 否则缓存矩形永远非空，数字会滞后于滑条消失。
                    Rect now = null;
                    boolean seekbarVisible = mPanelDecor != null && hasVisibleSeekBar(mPanelDecor);
                    if (seekbarVisible) {
                        if (mStableThisShow && mAppliedBounds != null) {
                            now = cachedBarRect(mNumber.getContext());
                        } else {
                            now = findSliderBounds(mPanelDecor);
                        }
                    }

                    if (now == null) {
                        if (mAppliedBounds != null && mStableThisShow) {
                            fadeOut();          // 滑条不可见 => 面板在退出，立即淡出
                        }
                    } else if (mPrevBounds != null && mPrevBounds.equals(now)) {
                        mStableThisShow = true;
                        if (mAppliedBounds == null || !mAppliedBounds.equals(now)) {
                            mAppliedBounds = now;
                            applyPosition(now);
                        }
                        fadeIn();
                    } else {
                        mPrevBounds = now;
                        // 已标定过：位置一开始就摆好了，滑条一出现就可以亮，不必等两轮稳定
                        if (mAppliedBounds != null) {
                            mStableThisShow = true;
                            fadeIn();
                        } else if (mStableThisShow) {
                            fadeOut();          // 稳定过又开始移动 => 退出动画
                        }
                    }
                } catch (Throwable ignored) {
                }
                mHandler.postDelayed(this, 60);
            }
        }, 60);
    }

    /** 缓存读取“启用”与“显示数值”两个开关，1 秒刷新一次，避免每轮读设置 */
    private boolean numberEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - mSwitchAt > 1000L) {
            mSwitchAt = now;
            mSwitchOn = true;
            try {
                Context ctx = mNumber != null ? mNumber.getContext() : null;
                if (ctx != null) {
                    ContentResolver cr = ctx.getContentResolver();
                    boolean enabled = Settings.System.getInt(cr, "volume_fine_enabled", 1) != 0;
                    boolean show = Settings.System.getInt(cr, "volume_fine_show_number", 1) != 0;
                    mSwitchOn = enabled && show;
                }
            } catch (Throwable ignored) {
            }
        }
        return mSwitchOn;
    }

    /** 只刷新数字文字与配色，不触碰视图树；缓存 AudioManager 避免每轮 getSystemService */
    private void refreshText() {
        try {
            Context ctx = mNumber.getContext();
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (mAudioManager == null) {
                return;
            }
            int vol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            // 值没变就不 setText：TextView 每次赋值都会走 measure/layout + invalidate，
            // 每 60ms 无条件刷新会造成持续的无效重绘（观感上的“闪”）
            if (vol != mShownVolume || mShownVolume == Integer.MIN_VALUE) {
                mShownVolume = vol;
                mNumber.setText(String.valueOf(vol));
            }
            updateTextColor(ctx, vol);   // 复用刚读到的音量，避免再发一次 IPC
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 定位

    /** 用缓存的标定值还原滑条矩形；未标定时返回 null */
    private Rect cachedBarRect(Context ctx) {
        if (mCalWidthDp <= 0 || mCalHeightDp <= 0) {
            return null;
        }
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int left = screenW - dp(ctx, mCalRightDp) - dp(ctx, mCalWidthDp);
        int top = dp(ctx, mCalTopDp);
        return new Rect(left, top, left + dp(ctx, mCalWidthDp), top + dp(ctx, mCalHeightDp));
    }

    /** 找滑条并贴上去；找不到就保持 dp 锚点 */
    private void applyExactPosition(View decor) {
        if (mNumber == null || mHost == null) {
            return;
        }
        if (m.debugEnabled() && !mTreeDumped) {
            mTreeDumped = true;
            dumpTree(mHost, 0);
        }
        Rect bounds = findSliderBounds(decor);
        if (bounds == null) {
            if (m.debugEnabled()) {
                m.log(3, TAG, "[sysui] slider bounds not found, keep anchor; view="
                        + mNumber.getWidth() + "x" + mNumber.getHeight());
            }
            return;
        }
        if (mAppliedBounds == null || !mAppliedBounds.equals(bounds)) {
            mAppliedBounds = bounds;
            applyPosition(bounds);
        }
    }

    /** 数字画在面板内部：横向与滑条对齐，纵向放在面板顶部（三点图标下方） */
    private void applyPosition(Rect measured) {
        try {
            if (mNumber == null || mHost == null || measured == null) {
                return;
            }
            if (measured.left <= 0 || measured.top <= 0
                    || measured.width() <= 0 || measured.height() <= 0) {
                return;   // 布局还没稳定，保持原位
            }
            Context ctx = mNumber.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;

            if (mCalWidthDp <= 0) {
                mCalRightDp = Math.round((screenW - measured.right) / density);
                mCalTopDp = Math.round(measured.top / density);
                mCalWidthDp = Math.round(measured.width() / density);
                mCalHeightDp = Math.round(measured.height() / density);
                if (m.debugEnabled()) {
                    m.log(3, TAG, "[sysui] calibrated right=" + mCalRightDp + "dp top=" + mCalTopDp
                            + "dp size=" + mCalWidthDp + "x" + mCalHeightDp + "dp");
                }
            }

            Rect bar = cachedBarRect(ctx);
            if (bar == null) {
                bar = measured;
            }

            int h = dp(ctx, 22);
            int top = bar.top + dp(ctx, 40);          // 让开面板顶部的三点图标
            top = Math.min(top, bar.bottom - h - dp(ctx, 8));

            int[] hostLoc = new int[2];
            mHost.getLocationInWindow(hostLoc);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bar.width(), h);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = Math.max(0, bar.left - hostLoc[0]);
            lp.topMargin = Math.max(0, top - hostLoc[1]);
            mHost.updateViewLayout(mNumber, lp);

            mTextBarRect = new Rect(bar);
            mTextTop = top;
            mTextHeight = h;
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            }
            int vol = mAudioManager == null ? 0
                    : mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            updateTextColor(ctx, vol);

            if (m.debugEnabled()) {
                m.log(3, TAG, "[sysui] place bar=" + bar.toShortString()
                        + " host=" + hostLoc[0] + "," + hostLoc[1]
                        + " lp=" + lp.width + "x" + lp.height
                        + " at " + lp.leftMargin + "," + lp.topMargin);
            }
        } catch (Throwable t) {
            m.log(5, TAG, "[sysui] applyPosition failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 配色

    /** 数字压到白色填充上时用深色、在玻璃底色上用白色；带迟滞，避免填充扫过文字时黑白乱闪 */
    private void updateTextColor(Context ctx, int vol) {
        try {
            if (mNumber == null || mTextBarRect == null || mTextHeight <= 0) {
                return;
            }
            if (mStreamMax <= 0) {
                if (mAudioManager == null) {
                    mAudioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                }
                if (mAudioManager != null) {
                    mStreamMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                }
            }
            int max = mStreamMax;
            if (max <= 0) {
                return;
            }
            float ratio = Math.max(0f, Math.min(1f, vol / (float) max));
            int fillTop = Math.round(mTextBarRect.bottom - ratio * mTextBarRect.height());
            int textBottom = mTextTop + mTextHeight;

            boolean dark;
            if (fillTop <= textBottom - dp(ctx, 1)) {
                dark = true;                        // 填充已盖到文字
            } else if (fillTop >= textBottom + dp(ctx, 6)) {
                dark = false;                       // 填充退到文字下方
            } else {
                dark = mDarkText;                   // 迟滞区保持
            }
            if (dark != mDarkText) {
                mDarkText = dark;
                applyTextStyle(ctx, dark);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyTextStyle(Context ctx, boolean dark) {
        try {
            if (mNumber == null) {
                return;
            }
            mNumber.setTextColor(dark ? 0xE6202024 : 0xFFF7F7FA);
            mNumber.setShadowLayer(dark ? 0f : dp(ctx, 2), 0f, dp(ctx, 1), 0x66000000);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 视图查找

    /**
     * 取当前可见的滑条。优先复用上一次找到的 View 引用 —— 只做 isShown/边界校验，
     * 失效了才重新遍历视图树。轮询每 60ms 一次，这样可以省掉绝大部分遍历。
     */
    private View activeSeekBar(View decor) {
        View cached = mSeekBarRef;
        if (cached != null && isValidSeekBar(cached)) {
            return cached;
        }
        View found = findActiveSeekBar(decor, "MiuiVolumeSeekBar");
        if (found == null) {
            found = findActiveSeekBar(decor, "VolumeSeekBar");
        }
        if (found == null) {
            found = findActiveSeekBar(decor, "MiuiVolumeDialogView");
        }
        mSeekBarRef = found;
        return found;
    }

    private boolean isValidSeekBar(View v) {
        if (!v.isShown() || !v.isAttachedToWindow()
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        int screenH = v.getResources().getDisplayMetrics().heightPixels;
        return loc[0] >= 0 && loc[1] >= 0
                && loc[0] + v.getWidth() <= screenW
                && loc[1] + v.getHeight() <= screenH;
    }

    private Rect findSliderBounds(View decor) {
        View bar = activeSeekBar(decor);
        if (bar == null) {
            return null;
        }
        int[] loc = new int[2];
        bar.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1], loc[0] + bar.getWidth(), loc[1] + bar.getHeight());
    }

    /** 找当前真正可见的滑条：尺寸有效、已布局、完整落在屏幕内、取面积最大的那个 */
    private View findActiveSeekBar(View v, String simpleName) {
        if (v == null) {
            return null;
        }
        List<View> out = new ArrayList<>();
        collectByClassName(v, simpleName, 0, out);
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        int screenH = v.getResources().getDisplayMetrics().heightPixels;
        View best = null;
        long bestArea = -1;
        for (View cand : out) {
            if (!cand.isShown() || cand.getWidth() <= 0 || cand.getHeight() <= 0) {
                continue;
            }
            int[] loc = new int[2];
            cand.getLocationInWindow(loc);
            if (loc[0] < 0 || loc[1] < 0
                    || loc[0] + cand.getWidth() > screenW
                    || loc[1] + cand.getHeight() > screenH) {
                continue;
            }
            long area = (long) cand.getWidth() * cand.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = cand;
            }
        }
        return best;
    }

    /** 判断滑条是否仍可见（用于驱动淡出，比 findSliderBounds 轻量） */
    private boolean hasVisibleSeekBar(View decor) {
        if (decor == null) {
            return false;
        }
        return activeSeekBar(decor) != null;
    }

    private void collectByClassName(View v, String simpleName, int depth, List<View> out) {
        if (v == null || depth > 14) {
            return;
        }
        if (v.getClass().getSimpleName().equals(simpleName)) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByClassName(g.getChildAt(i), simpleName, depth + 1, out);
            }
        }
    }

    /** 打印面板窗口里的视图树（排查用） */
    private void dumpTree(View v, int depth) {
        if (v == null || depth > 10) {
            return;
        }
        try {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(v.getClass().getSimpleName()).append(" [")
                    .append(loc[0]).append(',').append(loc[1]).append(' ')
                    .append(v.getWidth()).append('x').append(v.getHeight()).append(']');
            m.log(4, TAG, "[sysui] tree " + sb);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    dumpTree(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 其它

    private void fadeIn() {
        if (mNumber == null) {
            return;
        }
        mFadedOut = false;
        // 不再每轮 bringToFront()：它的实现是 remove + add，会触发重绘并从最上层重新入栈，
        // 表现为数字“闪一下”。置顶只在挂载时做一次。
        if (mNumber.getAlpha() < 1f) {
            mNumber.animate().cancel();
            mNumber.animate().alpha(1f).setDuration(120).start();
        }
    }

    private void fadeOut() {
        if (mNumber == null || mFadedOut) {
            return;
        }
        mFadedOut = true;
        mNumber.animate().cancel();
        mNumber.animate().alpha(0f).setDuration(110).start();
    }

    private TextView buildView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(0xFFF7F7FA);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tv.setShadowLayer(dp(ctx, 2), 0f, dp(ctx, 1), 0x66000000);
        tv.setBackground(null);
        return tv;
    }

    private static int dp(Context ctx, int value) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * value);
    }

}
