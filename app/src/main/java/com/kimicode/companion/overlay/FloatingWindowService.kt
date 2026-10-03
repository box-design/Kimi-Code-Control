package com.kimicode.companion.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kimicode.companion.MainActivity
import com.kimicode.companion.ui.FloatingWindowContent
import com.kimicode.companion.ui.FloatingWindowPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗服务（v0.5.6）：胶囊 / 卡片 / 提示卡 三态一体的承载窗口。
 *
 * - TYPE_APPLICATION_OVERLAY（需 SYSTEM_ALERT_WINDOW 授权，minSdk 26 起可用）
 * - Compose 渲染（ComposeView + Service 侧手动接线 lifecycle/viewModelStore/savedState）
 * - **v0.5.6 紧贴内容小窗架构（API 26+ 统一路径）**：
 *
 *   v0.5.5-fixed 曾让窗口恒为全屏透明（MATCH_PARENT），胶囊态靠公共 API
 *   `View.rootSurfaceControl.setTouchableRegion()` 把输入裁剪到胶囊区域 —— 这正是
 *   「悬浮窗在场时全屏点按失灵」的根源：全屏 TYPE_APPLICATION_OVERLAY 窗口的 frame
 *   覆盖整块屏幕，胶囊以外的每次触摸都「穿过」这层不可信(untrusted)覆盖层，而
 *   Android 12 起系统会拦截这类穿透触摸（untrusted touch events；覆盖层窗口不在
 *   系统信任名单内，除非窗口 alpha ≤ 0.8）。表现为：下层应用的点按全部无响应，
 *   只有直接落在胶囊上的触摸（点按 / 拖动）可用。
 *
 *   v0.5.6 废除「全屏窗口 + 触摸区域路由」，全 API 统一回归「窗口即内容」的小窗：
 *   - **收起态**：WRAP_CONTENT × WRAP_CONTENT 紧贴胶囊。窗口 frame 之外的屏幕区域
 *     与覆盖层毫无交集，触摸由 FLAG_NOT_TOUCH_MODAL 天然穿透 —— 不构成遮挡、不触发
 *     untrusted-touch 拦截，下层应用的点按 / 滑动全部恢复正常；
 *   - **展开态**（卡片 / 提示卡 / 形变中）：窗口为固定卡片盒（宽 = 卡片宽，高 = 卡片
 *     高上限），形变动画在盒内播放；形变 settle 后窗口高度再贴合实际内容高度，
 *     消除卡片下方的透明「吞触」余量；
 *   - **窗口尺寸即挡位载体**：Compose 布局分支由测量约束驱动（固定宽约束 = 展开
 *     分支 / 宽松约束 = 包裹分支），换挡瞬间窗口几何与内容重排同帧生效（API 31+
 *     BLAST 原子应用），胶囊跨挡位逐像素同位 —— 不回退 v0.5.5 修复过的左右闪烁；
 *   - 展开 = 立即换挡（先撑满高度头寸，形变在盒内播放不被裁剪）；收起 = 等形变
 *     settle（[MORPH_SETTLE_DELAY_MS]）后缩回胶囊小窗，收缩全程逐像素同位；
 *   - **「点悬浮窗以外收起」改由 FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE 实现**：
 *     窗外触摸正常分发给下层应用（本窗口 frame 与触点无交集，无遮挡无拦截），
 *     本窗口同时收到一次 ACTION_OUTSIDE 通知并据此收起 —— 交互语义保留，且不再
 *     吞掉任何下层输入。不需要该功能时删掉 [onOutsideTouch] 的调用即可。
 * - 每次出现固定以胶囊形态开始；提示卡（审批 / 问题 / 完成提醒）出现时自动展开。
 * - 优雅退出：[FloatingWindowController.requestExit] → Compose 播放缩小消失动画 →
 *   [FloatingWindowController.onExitAnimationFinished] → 本服务 detach + stopSelf；
 *   另有 [EXIT_FAILSAFE_MS] 兜底防止动画未执行导致窗口残留。
 *
 * 启停由 MonitorService 调度（详细模式 / 有提醒 + 权限 → show；否则优雅退出或 hide）。
 */
class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "FloatingWindow"
        const val ACTION_SHOW = "com.kimicode.companion.action.FLOAT_SHOW"
        const val ACTION_HIDE = "com.kimicode.companion.action.FLOAT_HIDE"

        private const val PREFS = "floating_window_prefs"
        private const val KEY_RIGHT_MARGIN = "right_margin"  // px（Gravity END 偏移：右缘距屏幕右边）
        private const val KEY_Y = "y"                        // px（顶部偏移）

        /** 默认边距 / 上下限（px，onCreate 时按密度换算） */
        private const val DEF_MARGIN_DP = 12f
        private const val DEF_TOP_GAP_DP = 10f

        /**
         * 形变动画 settle 时长：收起形变结束后窗口再缩回小窗；展开后窗口高度贴合
         * 内容也等这一时长（避开形变期的逐帧 relayout / 窗口尺寸抖动）。
         */
        private const val MORPH_SETTLE_DELAY_MS = 620L

        /** 优雅退出兜底：动画完成回调超时未到则强制关闭 */
        private const val EXIT_FAILSAFE_MS = 2500L

        /** 卡片宽度公式（与 Compose 侧 MorphingContent 的 cardWidth 一致）：min(336dp, 屏宽-24dp) */
        private const val CARD_WIDTH_DP = 336f
        private const val CARD_WIDTH_SCREEN_MARGIN_DP = 24f

        /** 卡片高度上限比例（与 Compose 侧 maxCardHeight = screenH * 0.72 一致） */
        private const val CARD_MAX_HEIGHT_RATIO = 0.72f

        /** 窗口高度贴合内容时的余量（px，防 dp 换算取整裁掉 1px 内容） */
        private const val HEIGHT_SLACK_PX = 2
    }

    /** 内容锚点：右缘距屏幕右边的距离 + 顶部偏移（px）。 */
    data class Anchor(val rightMargin: Int, val top: Int)

    // ------------------------------------------------------------------ 基础生命周期

    override fun onCreate() {
        super.onCreate()
        savedStateCtrl.performAttach()
        savedStateCtrl.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 每次出现固定以胶囊形态开始，不再恢复上次形态
        FloatingWindowController.setForm(FloatingWindowController.Form.CAPSULE)
        FloatingWindowController.clearPrompt()

        val (sw, sh) = screenRealSize()
        val savedRight = prefs.getInt(KEY_RIGHT_MARGIN, dp(DEF_MARGIN_DP))
        val savedTop = prefs.getInt(KEY_Y, statusBarTopPx() + dp(DEF_TOP_GAP_DP))
        _anchor.value = Anchor(
            rightMargin = savedRight.coerceIn(0, maxOf(0, sw - dp(56f))),
            top = savedTop.coerceIn(0, maxOf(0, sh - dp(96f)))
        )

        // form / prompt / exit → 窗口挡位（展开 = 卡片盒 / 收起 = 贴胶囊小窗）
        serviceScope.launch {
            combine(
                FloatingWindowController.form,
                FloatingWindowController.prompt,
                FloatingWindowController.exitRequested
            ) { form, prompt, exiting ->
                Triple(form, prompt, exiting)
            }
                .distinctUntilChanged()
                .collect { (form, prompt, exiting) ->
                    if (exiting) return@collect // 退出动画期间保持窗口挡位不动
                    applyWindowState(prompt != null || form == FloatingWindowController.Form.CARD)
                }
        }

        // 优雅退出兜底：动画回调超时未到 → 强制收尾（防窗口残留）
        serviceScope.launch {
            FloatingWindowController.exitRequested.collect { requested ->
                if (requested) {
                    exitFailsafeJob?.cancel()
                    exitFailsafeJob = serviceScope.launch {
                        delay(EXIT_FAILSAFE_MS)
                        if (FloatingWindowController.exitRequested.value) {
                            FloatingWindowController.onExitAnimationFinished(this@FloatingWindowService)
                        }
                    }
                } else {
                    exitFailsafeJob?.cancel()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> attachWindow()
            ACTION_HIDE -> {
                detachWindow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detachWindow()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Compose 接线（Service 无视图树，手动提供 owners）

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateCtrl = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateCtrl.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------------ 窗口

    private lateinit var windowManager: WindowManager
    /** 挂到 WindowManager 的根视图（拦截 ACTION_OUTSIDE 的 OverlayRootView） */
    private var rootView: OverlayRootView? = null
    private var composeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var viewAttached = false

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }

    /** 当前窗口挡位：true = 展开卡片盒（卡片 / 提示卡 / 形变中）；false = 贴胶囊小窗 */
    private var expanded = false

    /** 最近一次进入展开态的时刻（形变期内不做高度贴合） */
    private var expandedAtMs = 0L

    /** Compose 布局阶段上报的内容边界（窗口内坐标；高度供展开态贴合用） */
    @Volatile
    private var lastContentBounds: Rect? = null

    /** 收起换挡（延迟到形变 settle 后执行） */
    private var windowSwitchJob: Job? = null

    /** 展开后高度贴合（延迟到形变 settle 后执行） */
    private var heightSettleJob: Job? = null

    private var exitFailsafeJob: Job? = null

    private val _anchor = MutableStateFlow(Anchor(0, 0))

    /** 内容锚点（拖动 / 定位共享状态；Compose 与窗口参数同步使用） */
    val anchor: StateFlow<Anchor> = _anchor.asStateFlow()

    // ------------------------------------------------------------------ 窗口管理

    private fun screenRealSize(): Pair<Int, Int> = runCatching {
        val metrics = windowManager.maximumWindowMetrics
        val b = metrics.bounds
        b.width() to b.height()
    }.getOrElse { resources.displayMetrics.run { widthPixels to heightPixels } }

    /** 屏幕宽（px），供 Compose 做尺寸 / 位置钳制 */
    fun screenWidthPx(): Int = screenRealSize().first

    /** 屏幕高（px），供 Compose 做尺寸 / 位置钳制 */
    fun screenHeightPx(): Int = screenRealSize().second

    /** 卡片宽度（px，与 Compose 侧 cardWidth 公式一致：min(336dp, 屏宽-24dp)） */
    private fun cardWidthPx(): Int {
        val sw = screenRealSize().first
        return minOf(dp(CARD_WIDTH_DP), maxOf(sw - dp(CARD_WIDTH_SCREEN_MARGIN_DP), 200))
    }

    /** 卡片高度上限（px，与 Compose 侧 maxCardHeight = screenH * 0.72 一致） */
    private fun maxCardHeightPx(): Int = (screenRealSize().second * CARD_MAX_HEIGHT_RATIO).roundToInt()

    // ------------------------------------------------------------------ 窗口挡位（尺寸状态机）

    /**
     * 窗口挡位切换。展开（→ 卡片盒）立即生效 —— 先撑满高度头寸，形变在盒内播放
     * 不被裁剪；收起（→ 贴胶囊小窗）等形变 settle 后再生效 —— 形变期间内容在旧盒
     * 内收缩（钉在右上角），缩回瞬间胶囊逐像素同位，无闪跳。
     */
    private fun applyWindowState(wantExpanded: Boolean) {
        if (wantExpanded == expanded) {
            // 同态：展开盒内部的形态切换（卡片 ↔ 提示卡 / 提示卡顶替）—— 撤销挂起的
            // 收起换挡，重置 settle 计时并先回满头寸（防新内容超出旧的贴合高度被裁），
            // settle 后再重新贴合内容高度。
            windowSwitchJob?.cancel()
            if (expanded && viewAttached) {
                expandedAtMs = SystemClock.uptimeMillis()
                applyExpandedWindowParams()
                scheduleHeightSettle()
            }
            return
        }
        windowSwitchJob?.cancel()
        heightSettleJob?.cancel()
        if (wantExpanded) {
            expanded = true
            expandedAtMs = SystemClock.uptimeMillis()
            applyExpandedWindowParams()
            scheduleHeightSettle()
        } else {
            // 收起：等内容形变动画 settle 后窗口缩回紧贴胶囊的小窗。
            expanded = false
            windowSwitchJob = serviceScope.launch {
                delay(MORPH_SETTLE_DELAY_MS)
                if (FloatingWindowController.exitRequested.value) return@launch
                if (expanded) return@launch // 期间又被展开（快速反复切换）
                applyCollapsedWindowParams()
            }
        }
    }

    /** 展开盒：宽 = 卡片宽，高 = 卡片高上限（+ 余量）。 */
    private fun applyExpandedWindowParams() {
        val params = windowParams ?: return
        if (!viewAttached) return
        params.width = cardWidthPx()
        params.height = maxCardHeightPx() + HEIGHT_SLACK_PX
        runCatching { windowManager.updateViewLayout(rootView, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(expanded) failed", it) }
    }

    /** 收起态：WRAP_CONTENT 紧贴胶囊（窗口外零吞触区）。 */
    private fun applyCollapsedWindowParams() {
        val params = windowParams ?: return
        if (!viewAttached) return
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        runCatching { windowManager.updateViewLayout(rootView, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(collapsed) failed", it) }
    }

    /** 展开后等形变 settle，再把窗口高度贴合实际内容（消除卡片下方的透明吞触余量）。 */
    private fun scheduleHeightSettle() {
        heightSettleJob?.cancel()
        heightSettleJob = serviceScope.launch {
            delay(MORPH_SETTLE_DELAY_MS)
            if (!expanded || FloatingWindowController.exitRequested.value) return@launch
            applyContentHeight()
        }
    }

    private fun applyContentHeight() {
        val params = windowParams ?: return
        if (!viewAttached || !expanded) return
        val h = lastContentBounds?.height() ?: return
        val target = (h + HEIGHT_SLACK_PX).coerceAtMost(maxCardHeightPx() + HEIGHT_SLACK_PX)
        if (params.height == target) return
        params.height = target
        runCatching { windowManager.updateViewLayout(rootView, params) }
            .onFailure { Log.w(TAG, "updateViewLayout(follow) failed", it) }
    }

    /**
     * Compose 内容边界上报（布局阶段回调，主线程）。
     * 展开态且形变已 settle：窗口高度即时贴合内容（卡片内容增减时消除吞触余量）；
     * 其余阶段仅记录，供 settle 时刻取用。
     */
    fun onContentBoundsChanged(bounds: Rect) {
        lastContentBounds = bounds
        if (!expanded || !viewAttached) return
        if (SystemClock.uptimeMillis() - expandedAtMs < MORPH_SETTLE_DELAY_MS) return
        applyContentHeight()
    }

    /**
     * 窗外触摸（FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE）：
     * 下层应用正常收到该触摸（本窗口 frame 与触点无交集 —— 无遮挡、不触发
     * untrusted-touch 拦截），这里仅借系统的一次通知完成「点窗外 → 收起」。
     */
    fun onOutsideTouch() {
        when {
            FloatingWindowController.prompt.value != null -> FloatingWindowController.clearPrompt()
            FloatingWindowController.form.value == FloatingWindowController.Form.CARD ->
                FloatingWindowController.setForm(FloatingWindowController.Form.CAPSULE)
        }
    }

    // ------------------------------------------------------------------ 窗口挂载

    private fun attachWindow() {
        if (viewAttached) return
        val wantExpanded =
            FloatingWindowController.prompt.value != null ||
                FloatingWindowController.form.value == FloatingWindowController.Form.CARD
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // NOT_TOUCH_MODAL：窗口 frame 之外的触摸穿透给下层应用（frame 与触点无交集，
            //   不构成遮挡 —— Android 12+ 的 untrusted-touch 拦截不可能触发）；
            // WATCH_OUTSIDE_TOUCH：窗外触摸额外给本窗口一次 ACTION_OUTSIDE 通知（收起用）。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.TOP or Gravity.END
            // 窗口尺寸即挡位：收起 = WRAP 紧贴胶囊（窗口外零吞触）；展开 = 卡片盒。
            // 窗口右缘钉在屏幕右缘 - x 处（x = rightMargin），顶 = y（y = anchor.top）。
            if (wantExpanded) {
                width = cardWidthPx()
                height = maxCardHeightPx() + HEIGHT_SLACK_PX
            } else {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            x = _anchor.value.rightMargin
            y = _anchor.value.top
        }
        windowParams = params
        expanded = wantExpanded
        expandedAtMs = if (wantExpanded) SystemClock.uptimeMillis() else 0L

        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent { FloatingWindowRoot() }
        }
        // 根节点 = 拦截 ACTION_OUTSIDE 的 FrameLayout（ComposeView 为 final 无法继承）。
        // 子 ComposeView 用 MATCH_PARENT：展开盒（EXACTLY 约束）下铺满窗口，
        // 包裹窗口（AT_MOST 约束）下随内容自适应 —— Compose 侧的挡位判定不受影响。
        //
        // v0.5.6-fixed（任务进行中拉起悬浮窗必闪退 —— 根治）：
        // Compose 1.7 的 WindowRecomposer 从「窗口根视图」解析生命周期 ——
        // AbstractComposeView.onAttachedToWindow() → resolveParentCompositionContext()
        // → windowRecomposer → contentChild（自 ComposeView 向上走到窗口根，
        //   链上无 android.R.id.content 即取最外层根 = 本 OverlayRootView）
        // → WindowRecomposerPolicy.createAndInstallWindowRecomposer(root)
        // → createLifecycleAwareWindowRecomposer() 在根上 findViewTreeLifecycleOwner()，
        //   找不到即抛 IllegalStateException: "ViewTreeLifecycleOwner not found from ..."
        //   （release 混淆后即用户报告的 g2.k / t0.a / n.w.v 堆栈）。
        // 而此前三个 ViewTree owner 只设置在 ComposeView 子视图上，根视图上没有 ——
        // findViewTreeLifecycleOwner 只沿父链向上查找，不会向子视图下发。
        // 故窗口根视图必须设置全部三个 owner（ComposeView 上的设置保留：
        // Compose 子树内部 findViewTree*Owner 的向上查找路径同样成立）。
        val view = OverlayRootView(this) { onOutsideTouch() }.apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            addView(
                compose,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        composeView = compose
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.w(TAG, "addView failed (permission revoked?)", it)
                viewAttached = false
                return
            }
        viewAttached = true
        rootView = view
        FloatingWindowController.internalSetWindowVisible(true)
        if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        if (wantExpanded) scheduleHeightSettle()
    }

    private fun detachWindow() {
        if (viewAttached) persistPrefs()
        windowSwitchJob?.cancel()
        heightSettleJob?.cancel()
        windowSwitchJob = null
        heightSettleJob = null
        rootView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        rootView = null
        composeView = null
        windowParams = null
        viewAttached = false
        expanded = false
        lastContentBounds = null
        FloatingWindowController.internalSetWindowVisible(false)
    }

    // ------------------------------------------------------------------ 拖动（由 Compose 手势回调）

    /**
     * 拖动：更新锚点（右缘距边 / 顶部偏移）并同步窗口位置 —— 两种挡位下窗口位置
     * 都由 gravity(TOP|END)+x/y 承载，内容在窗口内的本地定位不变，拖动全程同帧跟手。
     */
    fun moveBy(dx: Float, dy: Float) {
        val (sw, sh) = screenRealSize()
        val a = _anchor.value
        val rightMargin = clamp((a.rightMargin - dx).roundToInt(), 0, maxOf(0, sw - dp(56f)))
        val top = clamp(a.top + dy.roundToInt(), 0, maxOf(0, sh - dp(96f)))
        _anchor.value = Anchor(rightMargin, top)
        windowParams?.let { p ->
            p.x = rightMargin
            p.y = top
            if (viewAttached) runCatching { windowManager.updateViewLayout(rootView, p) }
        }
    }

    /** 手势结束：持久化位置 */
    fun onGestureEnd() {
        persistPrefs()
    }

    /** 点按任务 → 直达 MainActivity 对应会话详情页 */
    fun openTask(sessionId: String) {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
            }
            startActivity(intent)
        }
    }

    private fun persistPrefs() {
        prefs.edit()
            .putInt(KEY_RIGHT_MARGIN, _anchor.value.rightMargin)
            .putInt(KEY_Y, _anchor.value.top)
            .apply()
    }

    private fun statusBarTopPx(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
        }
    }.getOrDefault(dp(24f))

    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun clamp(v: Int, min: Int, max: Int): Int = if (max < min) min else v.coerceIn(min, max)

    // ------------------------------------------------------------------ Compose 根

    @Composable
    private fun FloatingWindowRoot() {
        // 悬浮窗固定深色主题（覆盖在任意应用之上，深色最通用）
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = FloatingWindowPalette.PRIMARY,
                onPrimary = FloatingWindowPalette.ON_PRIMARY,
                error = FloatingWindowPalette.ERROR,
                outline = FloatingWindowPalette.OUTLINE
            )
        ) {
            FloatingWindowContent(
                service = this
            )
        }
    }

    /** 窗口根视图：拦截 ACTION_OUTSIDE（「点窗外收起」的实现载体，见 [onOutsideTouch]）。
     *  在 dispatchTouchEvent 层拦截，先于内部 ComposeView 的指针处理 —— 行为确定。 */
    private class OverlayRootView(
        context: Context,
        private val onOutsideTouch: () -> Unit
    ) : android.widget.FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }
}
