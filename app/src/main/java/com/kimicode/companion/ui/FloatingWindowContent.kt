package com.kimicode.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Rect
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.LiveTaskStatus
import com.kimicode.companion.data.SessionPhase
import com.kimicode.companion.data.WorkNotifMode
import com.kimicode.companion.overlay.FloatingWindowController
import com.kimicode.companion.overlay.FloatingWindowService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗调色板（v0.5.4）：固定深色，覆盖任意应用之上都保持可读。
 */
object FloatingWindowPalette {
    /** 卡片面板底色（带轻微透明） */
    val SURFACE = Color(0xF20F1216)
    /** 头部 / 菜单稍亮一层 */
    val SURFACE_HIGH = Color(0xFF191D24)
    val ON_SURFACE = Color(0xFFE9ECF2)
    val ON_SURFACE_DIM = Color(0xFF98A1B0)
    val PRIMARY = Color(0xFF8FB4FF)
    val ON_PRIMARY = Color(0xFF0A1B3D)
    val ERROR = Color(0xFFFF8080)
    val OUTLINE = Color(0xFF697181)
    val STROKE = Color(0x14FFFFFF)
    /** 胶囊（灵动岛式）底色：近黑 */
    val CAPSULE_BG = Color(0xF50B0D10)
    val GREEN = Color(0xFF34C77B)
    val ORANGE = Color(0xFFF0A132)
    /** 提示卡强调底色（类型色 8%~12% 透明度） */
    val APPROVAL_TINT = Color(0xFFF0A132)
    val QUESTION_TINT = Color(0xFF8FB4FF)
    val COMPLETION_TINT = Color(0xFF34C77B)
}

/** 悬浮窗内容类型（AnimatedContent 形变目标） */
private enum class FloatContent { CAPSULE, CARD, PROMPT }

/**
 * 悬浮窗内容根（v0.5.5 重构；v0.5.5-fixed 动画与闪烁根治；v0.5.6 小窗架构根治点按失灵）：
 *
 * - 每次出现固定以胶囊形态开始；点击胶囊 → spring 非线性形变展开为卡片；
 * - 卡片宽度固定、高度包裹内容（刚好展示全部信息，不留白）；卡片内「更多」菜单收起 / 退出，
 *   点窗外收起由 Service 的 FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE 承担（v0.5.6 起
 *   不再需要全屏背板手势区 —— 正是全屏窗口 + 触摸区域路由让下层应用的点按被
 *   Android 12+ 的 untrusted-touch 拦截吞掉的）；
 * - 提示卡（审批 / 问题 / 完成提醒）出现时自动从当前形态展开，完成选择后自动缩回；
 * - 退出请求 → 先缩回胶囊，再整体缩小淡出（向内容自身中心收拢，丝滑消失），
 *   动画结束后回调 Service 收尾（detach + stopSelf）。
 *
 * v0.5.6（紧贴内容小窗架构，全 API 统一）：
 * - **废除全屏窗口与 setTouchableRegion 触摸路由**：收起态窗口 WRAP_CONTENT 紧贴
 *   胶囊，展开态窗口为固定卡片盒（宽 = 卡片宽，高 = 卡片高上限，settle 后贴合
 *   内容高度）。窗口 frame 之外的触摸由 FLAG_NOT_TOUCH_MODAL 天然穿透，与覆盖层
 *   无交集 —— 不触发 untrusted-touch 拦截，下层应用点按 / 滑动全部恢复；
 * - **窗口尺寸即挡位**：布局分支由测量约束驱动（固定宽约束 = 展开分支 / 宽松约束 =
 *   包裹分支），窗口换挡与内容重排同帧生效（API 31+ BLAST 原子应用），胶囊跨挡位
 *   逐像素同位 —— 保留 v0.5.5-fixed 根治的「无左右闪烁」；
 * - 内容边界经 onGloballyPositioned 上报 Service（展开态 settle 后窗口高度贴合内容）。
 *
 * AI 状态文案与动画完全复用 [StatusUiShared]（Kimi Code 官方文案 +
 * braille/moon spinner + 动态模糊切换），与任务详情页逐字逐帧一致。
 */
@Composable
fun FloatingWindowContent(service: FloatingWindowService) {
    val app = KimiApp.of(service)

    val form by FloatingWindowController.form.collectAsStateWithLifecycle()
    val prompt by FloatingWindowController.prompt.collectAsStateWithLifecycle()
    val exitRequested by FloatingWindowController.exitRequested.collectAsStateWithLifecycle()
    // v0.5.6：内容不读取任何窗口挡位状态 —— 布局自适应测量约束（见 floatingAnchorLayout），
    // 窗口参数与内容渲染因此不存在跨帧竞态；anchor 仅供布局阶段消费（变更只触发重排）。
    val anchorState = service.anchor.collectAsStateWithLifecycle()

    val liveTasks by app.repository.liveTasks.collectAsStateWithLifecycle()
    val sessionsMap by app.repository.sessions.collectAsStateWithLifecycle()
    val phasesMap by app.repository.sessionPhases.collectAsStateWithLifecycle()
    val detailsMap by app.repository.sessionDetails.collectAsStateWithLifecycle()
    val approvalsMap by app.repository.pendingApprovals.collectAsStateWithLifecycle()

    // 1s 计时：运行时长 + 思考新鲜度
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }

    // ---------- 优雅退出动画：缩回胶囊 → 缩小淡出 → Service 收尾 ----------
    val exitProgress = remember { Animatable(1f) }
    var exitAnimDone by remember { mutableStateOf(false) }
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            FloatingWindowController.clearPrompt()
            FloatingWindowController.setForm(FloatingWindowController.Form.CAPSULE)
            delay(380) // 等内容形变（卡片 / 提示卡 → 胶囊）基本 settle
            exitProgress.snapTo(1f)
            exitProgress.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
            exitAnimDone = true
            // 保持透明态收尾，避免窗口 detach 前闪回一帧
            FloatingWindowController.onExitAnimationFinished(service)
        } else {
            // 退出被取消（新任务到来）：恢复可见；动画已自然结束则保持透明等 detach
            if (!exitAnimDone) exitProgress.snapTo(1f)
        }
    }

    // ---------- 内容形态 ----------
    // v0.5.5-fix：直接由 form / prompt 推导，不再经 LaunchedEffect 延迟一帧 ——
    // 统一锚定布局下窗口换挡瞬间内容渲染位置不变，AnimatedContent 的 SizeTransform
    // 又总是自当前尺寸起跳，无需再防「直接跳变」；少一帧延迟，展开更跟手。
    val contentType = when {
        prompt != null -> FloatContent.PROMPT
        form == FloatingWindowController.Form.CARD -> FloatContent.CARD
        else -> FloatContent.CAPSULE
    }

    // ---------- 尺寸换算（卡片宽度 / 高度上限） ----------
    val density = LocalDensity.current
    val screenW = service.screenWidthPx()
    val screenH = service.screenHeightPx()
    val edgeMarginPx = with(density) { 8.dp.toPx() }.roundToInt()

    // 退出变换：整体缩小淡出 —— v0.5.5-fixed：graphicsLayer 移入 MorphingContent 的内容盒
    // （见 MorphingContent）。根节点在卡片态是卡片盒尺寸，缩放原点必须是内容自身中心，
    // 否则缩小时内容被拉向窗口中心（即 v0.5.5 未彻底修复的「退出方向错误」）。

    // ---------- 统一承载（v0.5.6 紧贴内容小窗架构） ----------
    // 窗口尺寸即挡位（Service 侧 WindowManager 参数驱动）：收起态窗口 WRAP_CONTENT
    // 紧贴胶囊 —— 本节点即胶囊大小、置于 (0,0)，屏幕定位由窗口 gravity(TOP|END)+x/y
    // 承担，窗口外触摸天然穿透（无全屏背板、不吞下层输入）；展开态窗口为卡片盒
    // （宽 EXACTLY 卡片宽）—— 本节点铺满窗口，内容钉在盒内右上锚点位（越界钳制屏内）。
    // 两种挡位下内容渲染的屏幕像素一致（换挡由测量约束驱动、同帧生效），
    // 胶囊 / 卡片跨挡位逐像素同位 —— 保留 v0.5.5-fixed 的无闪烁，同时根治点按失灵。
    Box(
        modifier = Modifier.floatingAnchorLayout(
            anchor = anchorState,
            screenW = screenW,
            screenH = screenH,
            edgeMarginPx = edgeMarginPx
        )
    ) {
        MorphingContent(
            service = service,
            app = app,
            exitProgress = exitProgress,
            contentType = contentType,
            prompt = prompt,
            liveTasks = liveTasks,
            phasesMap = phasesMap,
            sessionsMap = sessionsMap,
            detailsMap = detailsMap,
            approvalsMap = approvalsMap,
            nowTick = nowTick,
            screenW = screenW,
            screenH = screenH
        )
    }
}

// ================================================================ 统一锚定布局

/**
 * 统一锚定布局（v0.5.6）：让同一棵内容树在两种窗口挡位下都渲染在同一屏幕位置。
 *
 * 窗口挡位由 WindowManager 参数决定，测量约束即挡位的唯一事实来源 ——
 * 卡片盒窗口给根节点 EXACTLY(卡片宽) 约束；WRAP 窗口给宽松（AT_MOST）约束。
 * 本布局据此自适应：
 * - 卡片盒（固定宽约束）：节点占满窗口；子内容以宽松约束测量后钉在窗口右上
 *   （窗口右缘 = 屏幕右缘 - anchor.rightMargin、窗口顶 = anchor.top，由窗口
 *   gravity(TOP|END)+x/y 承载）；卡片越出屏幕左缘 / 底缘时钳制回屏内。
 *   锚点在放置阶段读取（快照状态布局期读取），拖动 / 形变全程尺寸与位置同帧更新；
 * - 包裹（宽松约束）：节点即子内容大小、置于 (0,0)，屏幕定位交由窗口
 *   gravity(TOP|END)+x/y 完成 —— 与卡片盒路径的右上锚定完全同位。
 *
 * 两种路径渲染的屏幕像素一致，且换挡由测量约束驱动（窗口几何与内容重排同帧
 * 生效）—— 挡位切换瞬间逐像素同位，彻底消除左右闪烁，也不需要全屏背板。
 */
private fun Modifier.floatingAnchorLayout(
    anchor: State<FloatingWindowService.Anchor>,
    screenW: Int,
    screenH: Int,
    edgeMarginPx: Int
): Modifier = layout { measurable, constraints ->
    if (constraints.hasFixedWidth && constraints.maxWidth > 0) {
        // 卡片盒窗口：EXACTLY(卡片宽) —— 节点铺满窗口，内容钉在窗口右上锚点位
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val placeable = measurable.measure(
            Constraints(maxWidth = maxW, maxHeight = maxH)
        )
        layout(maxW, maxH) {
            val a = anchor.value
            val w = placeable.width
            val h = placeable.height
            // 窗口右缘钉在屏幕右缘 - rightMargin 处：windowLeft = screenW - a.rightMargin - maxW。
            // 内容期望屏幕左缘（与胶囊同右缘锚点）= screenW - a.rightMargin - w，
            // 换算到窗口本地即 maxW - w；卡片将越出屏幕左缘时钳到 edgeMargin。
            val windowLeft = screenW - a.rightMargin - maxW
            val screenLeft =
                if (w >= screenW - edgeMarginPx * 2) edgeMarginPx
                else (screenW - a.rightMargin - w).coerceAtLeast(edgeMarginPx)
            val left = (screenLeft - windowLeft).coerceIn(0, maxOf(0, maxW - w))
            // 竖直：窗口顶 = a.top（params.y），内容本地顶 0；卡片越出屏底时钳到屏内。
            val screenTop =
                if (a.top + h + edgeMarginPx <= screenH) a.top
                else (screenH - edgeMarginPx - h).coerceAtLeast(0)
            val top = (screenTop - a.top).coerceIn(0, maxOf(0, maxH - h))
            placeable.place(left, top)
        }
    } else {
        // 包裹窗口：AT_MOST —— 窗口即内容，屏幕定位由窗口参数（gravity + x/y）承担
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

// ================================================================ 形变容器

/**
 * 形变容器：AnimatedContent + SizeTransform(spring) 完成胶囊 / 卡片 / 提示卡之间的
 * 非线性丝滑形变（尺寸 spring 驱动 + 新旧内容交叉淡入淡出 + 轻微缩放）。
 * 圆角与底色随内容类型平滑过渡。
 * v0.5.5-fix：本容器位于统一锚定布局之内，两种窗口挡位间不再销毁重建 ——
 * 形变 / 圆角 / 底色动画状态在换挡全程连续，不会因分支切换归零重播。
 * v0.5.5-fixed：
 * - 退出缩小淡出的 graphicsLayer 挂在本盒（内容盒）上 —— 缩放中心永远是内容自身中心，
 *   不受根节点（卡片态为全屏）尺寸影响，彻底修正退出方向；
 * - AnimatedContent 的 contentAlignment = TopEnd + SizeTransform(clip = true)：
 *   容器右缘由锚定布局全程固定，内容钉在右缘 —— 形变为「右缘锚定的揭示式展开 /
 *   收拢」（灵动岛语义），不再随移动中的容器左缘左右平移；clip 同时保证内容
 *   永远不会出现在形变胶囊底色之外；
 * v0.5.6：本盒边界经 onGloballyPositioned 上报 Service —— 展开态形变 settle 后，
 *   窗口高度贴合内容（消除卡片下方透明吞触余量）；拖动 / 形变 / settle 尾段
 *   全程跟随，同帧布局阶段回调，无跨帧错拍。
 */
@Composable
private fun MorphingContent(
    service: FloatingWindowService,
    app: KimiApp,
    exitProgress: Animatable<Float, AnimationVector1D>,
    contentType: FloatContent,
    prompt: FloatingWindowController.FloatingPrompt?,
    liveTasks: List<LiveTaskStatus>,
    phasesMap: Map<String, SessionPhase>,
    sessionsMap: Map<String, com.kimicode.companion.data.Session>,
    detailsMap: Map<String, com.kimicode.companion.data.SessionDetailState>,
    approvalsMap: Map<String, com.kimicode.companion.data.ApprovalRequest>,
    nowTick: Long,
    screenW: Int,
    screenH: Int
) {
    val density = LocalDensity.current
    val cardWidth = with(density) {
        minOf(336.dp, (screenW - 24.dp.toPx()).coerceAtLeast(200f).toDp())
    }
    val maxCardHeight = with(density) { (screenH * 0.72f).toDp() }

    val corner by animateDpAsState(
        targetValue = if (contentType == FloatContent.CAPSULE) 22.dp else 24.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "morphCorner"
    )
    val bg by animateColorAsState(
        targetValue = if (contentType == FloatContent.CAPSULE) FloatingWindowPalette.CAPSULE_BG
        else FloatingWindowPalette.SURFACE,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "morphBg"
    )

    // v0.5.5-fixed：退出变换挂在本（内容）盒 —— 缩放中心永远是内容自身中心；
    // 边界上报用于展开态窗口高度贴合（见 Service.onContentBoundsChanged）。
    Box(
        modifier = Modifier
            .graphicsLayer {
                val p = exitProgress.value
                val s = 0.55f + 0.45f * p
                scaleX = s
                scaleY = s
                alpha = p
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                val size = coords.size
                service.onContentBoundsChanged(
                    Rect(
                        pos.x.toInt(), pos.y.toInt(),
                        (pos.x + size.width).toInt(),
                        (pos.y + size.height).toInt()
                    )
                )
            }
            .clip(RoundedCornerShape(corner))
            .background(bg)
    ) {
        AnimatedContent(
            targetState = contentType,
            // v0.5.5-fixed：右缘锚定 —— 容器右缘在形变全程固定，内容钉在右上角，
            // 展开 / 收缩为揭示式形变而非平移；配合锚定布局与灵动岛语义一致
            contentAlignment = Alignment.TopEnd,
            transitionSpec = {
                val enter = fadeIn(animationSpec = tween(210, delayMillis = 80)) +
                    scaleIn(
                        initialScale = 0.9f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                val exit = fadeOut(animationSpec = tween(130)) +
                    scaleOut(
                        targetScale = 0.94f,
                        animationSpec = tween(130, easing = FastOutSlowInEasing)
                    )
                (enter togetherWith exit).using(
                    // v0.5.5-fixed：clip = true —— 新内容随容器增长被逐帧「揭开」，
                    // 不会出现在尚未展开的胶囊底色之外；旧实现 clip = false 时卡片内容
                    // 会先于容器完全裸露在透明区域上
                    SizeTransform(clip = true) { _, _ ->
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    }
                )
            },
            label = "morphContent"
        ) { type ->
            when (type) {
                FloatContent.CAPSULE -> CapsuleBody(
                    service = service,
                    app = app,
                    liveTasks = liveTasks,
                    phasesMap = phasesMap,
                    sessionsMap = sessionsMap,
                    approvalsMap = approvalsMap,
                    nowTick = nowTick
                )
                FloatContent.CARD -> CardBody(
                    service = service,
                    app = app,
                    liveTasks = liveTasks,
                    phasesMap = phasesMap,
                    sessionsMap = sessionsMap,
                    detailsMap = detailsMap,
                    approvalsMap = approvalsMap,
                    nowTick = nowTick,
                    width = cardWidth,
                    maxHeight = maxCardHeight
                )
                FloatContent.PROMPT -> PromptBody(
                    service = service,
                    app = app,
                    prompt = prompt,
                    width = cardWidth,
                    maxHeight = maxCardHeight
                )
            }
        }
    }
}

// ================================================================ 胶囊形态（灵动岛式）

/**
 * 胶囊：`● 任务正在进行  ⠋ Working…`
 * 左侧呼吸圆点 + 固定文案；右侧 AI 状态（详情页同款 spinner + 动态模糊文案）。
 * 可拖动；点击展开为卡片。
 */
@Composable
private fun CapsuleBody(
    service: FloatingWindowService,
    app: KimiApp,
    liveTasks: List<LiveTaskStatus>,
    phasesMap: Map<String, SessionPhase>,
    sessionsMap: Map<String, com.kimicode.companion.data.Session>,
    approvalsMap: Map<String, com.kimicode.companion.data.ApprovalRequest>,
    nowTick: Long
) {
    val primary = liveTasks.firstOrNull()

    // AI 状态（与详情页同款引擎）
    val s = rememberKimiStatusStrings()
    val sessionId = primary?.sessionId
    val session = sessionId?.let { sessionsMap[it] }
    val phase = sessionId?.let { phasesMap[it] }
    val pending = session?.pending_interaction?.takeIf { it != "none" }
    val approvalTool = sessionId?.let { sid ->
        approvalsMap.values.firstOrNull { it.session_id == sid }?.tool_name
    }
    val thinkingFresh = sessionId != null && app.repository.thinkingOf(sessionId) != null &&
        app.repository.thinkingUpdatedAtOf(sessionId)?.let { nowTick - it < 4_000 } == true
    val display = KimiStatus.resolve(
        s,
        busy = session?.busy ?: true,
        phase = phase,
        pendingInteraction = pending,
        approvalToolName = approvalTool,
        thinkingFresh = thinkingFresh,
        readBurst = sessionId?.let { app.repository.readBurstOf(it) } ?: 0,
        workspaceDir = session?.metadata?.cwd
    )
    val color by animateColorAsState(
        targetValue = toneColor(display.tone),
        animationSpec = tween(360),
        label = "capsuleStatusColor"
    )

    // v0.5.6：胶囊始终可拖动 —— moveBy 同步窗口位置（两挡位均由 gravity+x/y 承载），
    // 布局阶段即时响应，拖动全程平滑跟手
    val dragModifier = Modifier
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { service.onGestureEnd() },
                onDragCancel = { service.onGestureEnd() }
            ) { change, amount ->
                change.consume()
                service.moveBy(amount.x, amount.y)
            }
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(FloatingWindowPalette.CAPSULE_BG)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { FloatingWindowController.setForm(FloatingWindowController.Form.CARD) }
            .then(dragModifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 左：呼吸状态点（任务活跃中，与详情页同款呼吸动画）
        val dotAlpha = if (display.pulse) rememberPulseAlphaShared() else 1f
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .background(FloatingWindowPalette.GREEN, CircleShape)
        )
        Text(
            text = stringResource(R.string.float_task_running),
            color = FloatingWindowPalette.ON_SURFACE,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        // 右：AI 执行状态（详情页同款 spinner + 动态模糊文案）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.width(150.dp)
        ) {
            StatusSpinner(display.spinner, display.pulse, color)
            MotionBlurText(
                text = display.label,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

// ================================================================ 卡片形态（完整信息）

/**
 * 卡片：宽度固定、高度包裹内容（刚好展示全部信息，不留白）。
 * 头部可拖动；「更多」菜单提供收起胶囊 / 退出。
 * v0.5.5：大小调节手柄已删除。
 */
@Composable
private fun CardBody(
    service: FloatingWindowService,
    app: KimiApp,
    liveTasks: List<LiveTaskStatus>,
    phasesMap: Map<String, SessionPhase>,
    sessionsMap: Map<String, com.kimicode.companion.data.Session>,
    detailsMap: Map<String, com.kimicode.companion.data.SessionDetailState>,
    approvalsMap: Map<String, com.kimicode.companion.data.ApprovalRequest>,
    nowTick: Long,
    width: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp
) {
    val primary = liveTasks.firstOrNull()
    val sessionId = primary?.sessionId
    val session = sessionId?.let { sessionsMap[it] }
    val detail = sessionId?.let { detailsMap[it] }
    val phase = sessionId?.let { phasesMap[it] }

    var menuOpen by remember { mutableStateOf(false) }

    // ---- 主任务 AI 状态（与详情页同款引擎） ----
    val s = rememberKimiStatusStrings()
    val pending = session?.pending_interaction?.takeIf { it != "none" }
    val approvalTool = sessionId?.let { sid ->
        approvalsMap.values.firstOrNull { it.session_id == sid }?.tool_name
    }
    val thinkingFresh = sessionId != null && app.repository.thinkingOf(sessionId) != null &&
        app.repository.thinkingUpdatedAtOf(sessionId)?.let { nowTick - it < 4_000 } == true
    val display = KimiStatus.resolve(
        s,
        busy = session?.busy ?: true,
        phase = phase,
        pendingInteraction = pending,
        approvalToolName = approvalTool,
        thinkingFresh = thinkingFresh,
        readBurst = sessionId?.let { app.repository.readBurstOf(it) } ?: 0,
        workspaceDir = session?.metadata?.cwd
    )
    val statusColor by animateColorAsState(
        targetValue = toneColor(display.tone),
        animationSpec = tween(360),
        label = "cardStatusColor"
    )

    // 运行时长
    val busySince = sessionId?.let { app.repository.busySinceOf(it) }
    val durationLabel = busySince?.let {
        val minutes = (nowTick - it) / 60_000
        when {
            minutes >= 60 -> "${minutes / 60}h${minutes % 60}m"
            minutes >= 1 -> "${minutes}m"
            else -> "<1m"
        }
    }

    val todoDone = detail?.todos?.takeIf { it.isNotEmpty() }?.count { it.status == "done" }
    val todoTotal = detail?.todos?.takeIf { it.isNotEmpty() }?.size
    val swarmActive = detail?.subagents?.values
        ?.count { it.state == "spawned" || it.state == "working" }
        ?.takeIf { detail.subagents.isNotEmpty() }
    val goalObjective = detail?.goal?.takeIf { it.status == "active" }?.objective

    Column(
        modifier = Modifier
            .width(width)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- 头部（拖动移动 + 更多菜单） ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(FloatingWindowPalette.SURFACE_HIGH.copy(alpha = 0.55f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { service.onGestureEnd() },
                        onDragCancel = { service.onGestureEnd() }
                    ) { change, amount ->
                        change.consume()
                        service.moveBy(amount.x, amount.y)
                    }
                }
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
        ) {
            // 标题：单任务 = 任务名；多任务 = N 个任务
            Text(
                text = if (liveTasks.size > 1) stringResource(R.string.float_task_multi, liveTasks.size)
                else primary?.title?.ifBlank { stringResource(R.string.notif_task_default_title) }
                    ?: stringResource(R.string.notif_task_default_title),
                color = FloatingWindowPalette.ON_SURFACE,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            durationLabel?.let {
                Text(
                    text = stringResource(R.string.float_running_for, it),
                    color = FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            // 「更多」按钮（右上角）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { menuOpen = !menuOpen }
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.float_more),
                    tint = FloatingWindowPalette.ON_SURFACE,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        // ---------- 更多菜单（面板内展开，避免子窗口兼容性问题） ----------
        AnimatedVisibility(
            visible = menuOpen,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut()
        ) {
            FloatingMoreMenu(
                onCollapse = {
                    FloatingWindowController.setForm(FloatingWindowController.Form.CAPSULE)
                    menuOpen = false
                },
                onExit = {
                    menuOpen = false
                    FloatingWindowController.notifyUserExited()
                    FloatingWindowController.requestExit()
                }
            )
        }

        // ---------- 主任务 AI 状态 ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusSpinner(display.spinner, display.pulse, statusColor)
                MotionBlurText(
                    text = display.label,
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
            // 相位细节（结果首行 / 重试详情 / cwd）
            if (!display.hint.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = display.hint,
                    color = if (display.tone == KimiStatus.Tone.ERROR) FloatingWindowPalette.ERROR
                    else FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            primary?.cwd?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = it,
                    color = FloatingWindowPalette.ON_SURFACE_DIM.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ---------- Todo 进度条 ----------
        if (todoTotal != null && todoTotal > 0) {
            val ratio = (todoDone ?: 0).toFloat() / todoTotal
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.float_todo, todoDone ?: 0, todoTotal),
                        color = FloatingWindowPalette.ON_SURFACE_DIM,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(FloatingWindowPalette.STROKE)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(FloatingWindowPalette.PRIMARY)
                    )
                }
            }
        }

        // ---------- Swarm / Goal 摘要 ----------
        if ((swarmActive ?: 0) > 0 || !goalObjective.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                if ((swarmActive ?: 0) > 0) {
                    FloatChip(
                        text = stringResource(R.string.float_swarm, swarmActive ?: 0),
                        tint = FloatingWindowPalette.ORANGE
                    )
                }
                if (!goalObjective.isNullOrBlank()) {
                    FloatChip(
                        text = stringResource(R.string.float_goal, goalObjective.take(36)),
                        tint = FloatingWindowPalette.PRIMARY
                    )
                }
            }
        }

        // ---------- 其他任务列表 ----------
        if (liveTasks.size > 1) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(FloatingWindowPalette.STROKE)
            )
            Text(
                text = stringResource(R.string.float_other_tasks),
                color = FloatingWindowPalette.ON_SURFACE_DIM,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            liveTasks.drop(1).forEach { task ->
                OtherTaskRow(service, app, s, task, phasesMap, sessionsMap, approvalsMap, nowTick)
            }
        }

        // 底部收边（不额外留白：仅等同于列表项的呼吸空间）
        Spacer(Modifier.height(6.dp))
    }
}

/** 其他任务行（点击直达详情页） */
@Composable
private fun OtherTaskRow(
    service: FloatingWindowService,
    app: KimiApp,
    s: KimiStatus.Strings,
    task: LiveTaskStatus,
    phasesMap: Map<String, SessionPhase>,
    sessionsMap: Map<String, com.kimicode.companion.data.Session>,
    approvalsMap: Map<String, com.kimicode.companion.data.ApprovalRequest>,
    nowTick: Long
) {
    val session = sessionsMap[task.sessionId]
    val phase = phasesMap[task.sessionId]
    val pending = session?.pending_interaction?.takeIf { it != "none" }
    val approvalTool = approvalsMap.values.firstOrNull { it.session_id == task.sessionId }?.tool_name
    val thinkingFresh = app.repository.thinkingOf(task.sessionId) != null &&
        app.repository.thinkingUpdatedAtOf(task.sessionId)?.let { nowTick - it < 4_000 } == true
    val display = KimiStatus.resolve(
        s,
        busy = session?.busy ?: true,
        phase = phase,
        pendingInteraction = pending,
        approvalToolName = approvalTool,
        thinkingFresh = thinkingFresh,
        readBurst = app.repository.readBurstOf(task.sessionId),
        workspaceDir = session?.metadata?.cwd
    )
    val color = toneColor(display.tone)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { service.openTask(task.sessionId) }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        StatusSpinner(display.spinner, display.pulse, color)
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title.ifBlank { stringResource(R.string.notif_task_default_title) },
                color = FloatingWindowPalette.ON_SURFACE,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = display.label,
                color = color,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ================================================================ 提示卡（审批 / 问题 / 完成提醒）

/**
 * 提示卡（v0.5.5）：从胶囊展开的轻量操作卡。
 * - APPROVAL：允许 / 拒绝 / 本会话均允许，操作后自动缩回
 * - QUESTION：打开应用回答 / 忽略
 * - COMPLETION：展示完成信息，点击直达详情，5 秒自动缩回（过期由 Controller 管理）
 */
@Composable
private fun PromptBody(
    service: FloatingWindowService,
    app: KimiApp,
    prompt: FloatingWindowController.FloatingPrompt?,
    width: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp
) {
    if (prompt == null) return
    val scope = rememberCoroutineScope()
    var deciding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(width)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp)
    ) {
        // ---------- 头部：类型图标 + 标题 + 任务名 ----------
        val (icon, iconTint, titleText) = when (prompt.kind) {
            FloatingWindowController.PromptKind.APPROVAL -> Triple(
                Icons.Rounded.Gavel, FloatingWindowPalette.APPROVAL_TINT,
                stringResource(R.string.float_prompt_approval_title)
            )
            FloatingWindowController.PromptKind.QUESTION -> Triple(
                Icons.Rounded.Help, FloatingWindowPalette.QUESTION_TINT,
                stringResource(R.string.float_prompt_question_title)
            )
            FloatingWindowController.PromptKind.COMPLETION -> Triple(
                Icons.Rounded.CheckCircle, FloatingWindowPalette.COMPLETION_TINT,
                stringResource(R.string.float_prompt_completion_title)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.14f))
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    color = FloatingWindowPalette.ON_SURFACE,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = prompt.taskTitle.ifBlank {
                        stringResource(R.string.notif_task_default_title)
                    },
                    color = FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            prompt.durationLabel?.let {
                Text(
                    text = it,
                    color = FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------- 正文 ----------
        when (prompt.kind) {
            FloatingWindowController.PromptKind.APPROVAL -> {
                Text(
                    text = prompt.toolName ?: "",
                    color = FloatingWindowPalette.APPROVAL_TINT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = prompt.actionSummary?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.float_prompt_no_summary),
                    color = FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (deciding) return@OutlinedButton
                            deciding = true
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    app.api.decideApproval(
                                        prompt.sessionId, prompt.approvalId.orEmpty(), "rejected"
                                    )
                                }
                                prompt.approvalId?.let { app.repository.removeApproval(it) }
                                FloatingWindowController.clearPrompt()
                            }
                        },
                        enabled = !deciding,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = FloatingWindowPalette.ERROR
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.float_prompt_reject), fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            if (deciding) return@Button
                            deciding = true
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    app.api.decideApproval(
                                        prompt.sessionId, prompt.approvalId.orEmpty(), "approved"
                                    )
                                }
                                prompt.approvalId?.let { app.repository.removeApproval(it) }
                                FloatingWindowController.clearPrompt()
                            }
                        },
                        enabled = !deciding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FloatingWindowPalette.APPROVAL_TINT,
                            contentColor = Color(0xFF201404)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.float_prompt_allow), fontSize = 13.sp)
                    }
                }
                TextButton(
                    onClick = {
                        if (deciding) return@TextButton
                        deciding = true
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                app.api.decideApproval(
                                    prompt.sessionId, prompt.approvalId.orEmpty(),
                                    "approved", scope = "session"
                                )
                            }
                            prompt.approvalId?.let { app.repository.removeApproval(it) }
                            FloatingWindowController.clearPrompt()
                        }
                    },
                    enabled = !deciding,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        stringResource(R.string.float_prompt_allow_session),
                        color = FloatingWindowPalette.ON_SURFACE_DIM,
                        fontSize = 11.sp
                    )
                }
            }

            FloatingWindowController.PromptKind.QUESTION -> {
                Text(
                    text = prompt.questionText ?: "",
                    color = FloatingWindowPalette.ON_SURFACE,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    TextButton(
                        onClick = { FloatingWindowController.clearPrompt() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.float_prompt_dismiss),
                            color = FloatingWindowPalette.ON_SURFACE_DIM,
                            fontSize = 13.sp
                        )
                    }
                    Button(
                        onClick = {
                            FloatingWindowController.clearPrompt()
                            service.openTask(prompt.sessionId)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FloatingWindowPalette.PRIMARY,
                            contentColor = FloatingWindowPalette.ON_PRIMARY
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.float_prompt_open_app), fontSize = 13.sp)
                    }
                }
            }

            FloatingWindowController.PromptKind.COMPLETION -> {
                val resultText = when (prompt.resultKind) {
                    "failed" -> stringResource(R.string.float_prompt_failed)
                    "cancelled" -> stringResource(R.string.float_prompt_cancelled)
                    else -> stringResource(
                        R.string.float_prompt_done,
                        prompt.durationLabel ?: ""
                    )
                }
                Text(
                    text = resultText,
                    color = if (prompt.resultKind == "failed") FloatingWindowPalette.ERROR
                    else FloatingWindowPalette.ON_SURFACE_DIM,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
                // 点击卡片 → 直达详情
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            FloatingWindowController.clearPrompt()
                            service.openTask(prompt.sessionId)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.float_prompt_open_detail),
                        color = FloatingWindowPalette.PRIMARY,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ================================================================ 菜单 / 小组件

/** 「更多」菜单：收起为胶囊 / 退出 */
@Composable
private fun FloatingMoreMenu(
    onCollapse: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FloatingWindowPalette.SURFACE_HIGH)
            .padding(vertical = 4.dp)
    ) {
        MoreMenuItem(
            icon = {
                Icon(
                    Icons.Rounded.KeyboardArrowUp, null,
                    tint = FloatingWindowPalette.PRIMARY, modifier = Modifier.size(17.dp)
                )
            },
            label = stringResource(R.string.float_collapse),
            selected = false,
            onClick = onCollapse
        )
        MoreMenuItem(
            icon = {
                Icon(
                    Icons.Rounded.Close, null,
                    tint = FloatingWindowPalette.ERROR, modifier = Modifier.size(17.dp)
                )
            },
            label = stringResource(R.string.float_exit),
            selected = false,
            onClick = onExit
        )
    }
}

@Composable
private fun MoreMenuItem(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !selected) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        icon()
        Text(
            text = label,
            color = if (selected) FloatingWindowPalette.PRIMARY else FloatingWindowPalette.ON_SURFACE,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = FloatingWindowPalette.PRIMARY,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** 轻量芯片（Swarm / Goal 摘要） */
@Composable
private fun FloatChip(text: String, tint: Color) {
    Text(
        text = text,
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
