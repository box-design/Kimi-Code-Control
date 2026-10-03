@file:OptIn(ExperimentalFoundationApi::class)

package com.kimicode.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.ApprovalRequest
import com.kimicode.companion.data.AppSettings
import com.kimicode.companion.data.ConnState
import com.kimicode.companion.data.QuotaEntry
import com.kimicode.companion.data.Repository
import com.kimicode.companion.data.Session
import com.kimicode.companion.data.SessionPhase
import com.kimicode.companion.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 控制台（v0.5 重制 · 单页简洁布局；v0.5.8 顶栏固定 + 内容整体滚动）：
 *
 * ┌────────────────────────────────────┐
 * │ Kimi Code Control        ⚙ ↻ 更改服务器 │  ← 固定顶部栏（不随内容滚动）
 * ├────────────────────────────────────┤
 * │ ● 已连接   host:port · v1.x        │  ← 连接状态 banner（随内容滚动）
 * ├────────────────────────────────────┤
 * │ 套餐用量                            │
 * │ 近 5 小时        32% ────────░░░░  │
 * │ 近 7 天          64% ─────░░░░░░  │
 * │ 本月             41% ──────░░░░░  │
 * │ 加油包余额 ¥5.00 / ¥10.00          │
 * ├────────────────────────────────────┤
 * │ 最近任务                            │
 * │ 进行中                              │  ← v0.5.4 子分栏（排最上）
 * │ ● 修复登录闪退    ~/kimi-app        │  ← 扁平行，无卡片包裹
 * │   思考中 · 已运行 3 分钟             │
 * │ 已完成                              │  ← v0.5.4 子分栏（排最下）
 * │ ✓ 重构 API  ~/kimi-app              │
 * │   已完成 · 09-25 14:30              │
 * └────────────────────────────────────┘
 *
 * v0.5.8：仅「Kimi Code Control」标题栏固定置顶；连接状态 banner 移入滚动区，
 * 原 banner 右侧三按钮（设置 / 刷新 / 更多）上移至顶栏右侧，「更多」菜单
 * 替换为「更改服务器」直达按钮。
 * 点击进行中的任务进入 [TaskDetailScreen]；点击 ⚙ 进入 [SettingsScreen]（v0.5.2）。
 * 待审批 / 待回答项仅在存在时以轻量区块出现（保持核心审批能力）。
 */
@Composable
fun ConsoleScreen(
    settings: AppSettings,
    onChangeServer: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val scope = rememberCoroutineScope()

    val connection by app.repository.connection.collectAsStateWithLifecycle()
    val sessionsMap by app.repository.sessions.collectAsStateWithLifecycle()
    val phasesMap by app.repository.sessionPhases.collectAsStateWithLifecycle()
    val approvalsMap by app.repository.pendingApprovals.collectAsStateWithLifecycle()
    val questionsMap by app.repository.pendingQuestions.collectAsStateWithLifecycle()
    val usageState by app.repository.usage.collectAsStateWithLifecycle()

    // 30 秒计时：刷新"已运行 X 分钟"时长文案
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    val approvals = remember(approvalsMap) { approvalsMap.values.sortedBy { it.created_at ?: "" } }
    val questions = remember(questionsMap) { questionsMap.values.toList() }

    // 正在进行的任务 = 忙碌或有待交互的会话（时间倒序）
    val activeSessions = remember(sessionsMap) {
        sessionsMap.values
            .filter { it.busy || (it.pending_interaction == "approval") || (it.pending_interaction == "question") }
            .sortedWith(
                compareByDescending<Session> { it.busy }
                    .thenByDescending { it.updated_at }
            )
    }

    // v0.5.4：已完成（当前未进行的任务）= 空闲且无待交互会话，更新时间倒序，最多 20 条
    val doneSessions = remember(sessionsMap) {
        sessionsMap.values
            .filter { !it.busy && (it.pending_interaction == null || it.pending_interaction == "none") }
            .sortedByDescending { it.updated_at }
            .take(20)
    }

    var deciding by remember { mutableStateOf(false) }

    // 从通知 / 小组件点进来时消费直达信号
    LaunchedEffect(approvals.size) {
        if (approvals.isNotEmpty()) {
            app.autoOpenApproval.compareAndSet(true, false)
        }
    }

    fun decide(approval: ApprovalRequest, decision: String, sessionScope: Boolean) {
        if (deciding) return
        deciding = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                app.api.decideApproval(
                    approval.session_id, approval.approval_id,
                    decision, if (sessionScope) "session" else null
                )
            }
            app.repository.removeApproval(approval.approval_id)
            withContext(Dispatchers.Main) { deciding = false }
        }
    }

    // v0.5.9：顶栏悬浮覆盖式（毛玻璃 / 不透明由「减少模糊」决定），滚动区从其下穿过
    val listState = rememberLazyListState()

    // v0.5.10：高斯动态模糊顶栏 —— 记录滚动内容到共享 GraphicsLayer，
    // 供顶栏毛玻璃实时取样背后内容（仅玻璃模式 + Android 12+ 时记录，避免多余开销）
    val glassOn = LocalGlassEffects.current
    val contentLayer = rememberGraphicsLayer()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassOn && supportsBlur) {
                        Modifier.drawWithContent {
                            // 把滚动内容录制进共享图层，顶栏毛玻璃在此取样的基础上叠加高斯模糊
                            contentLayer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = IntSize(
                                    size.width.toInt().coerceAtLeast(0),
                                    size.height.toInt().coerceAtLeast(0)
                                )
                            ) { this@drawWithContent.drawContent() }
                            drawContent()
                        }
                    } else {
                        Modifier
                    }
                ),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 顶栏占位（顶栏覆盖悬浮，滚动区起点在状态栏 + 顶栏之下）
            item(key = "topbar_space") {
                Spacer(
                    Modifier
                        .statusBarsPadding()
                        .height(62.dp)
                )
            }

            // ---------- 连接状态 banner（v0.5.8：随内容滚动，仅顶栏标题固定） ----------
            item(key = "banner") {
                ConnectionBanner(
                    connection = connection,
                    host = settings.config.host,
                    port = settings.config.port,
                    version = settings.serverVersion
                )
            }

            // ---------- 套餐使用量 ----------
            item(key = "usage") {
                UsageSection(usageState)
            }

            // ---------- 待审批（存在时才显示） ----------
            if (approvals.isNotEmpty()) {
                item(key = "approval_header") {
                    SectionLabel(
                        text = stringResource(R.string.console_section_approvals, approvals.size),
                        alert = true,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
                items(approvals, key = { "approval_${it.approval_id}" }) { approval ->
                    ApprovalRow(
                        approval = approval,
                        deciding = deciding,
                        onDecide = { decision, sessionScope ->
                            decide(approval, decision, sessionScope)
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
            }

            // ---------- 待回答（存在时才显示） ----------
            if (questions.isNotEmpty()) {
                item(key = "question_header") {
                    SectionLabel(
                        text = stringResource(R.string.console_section_questions, questions.size),
                        alert = true,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
                items(questions, key = { "question_${it.question_id}" }) { question ->
                    QuestionRow(
                        questionText = question.questions.firstOrNull()?.question.orEmpty(),
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
            }

            // ---------- 最近任务（v0.5.4：拆为进行中 / 已完成两栏） ----------
            item(key = "task_header") {
                SectionLabel(
                    text = stringResource(R.string.tasks_title),
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                        placementSpec = KimiMotion.placement(),
                        fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                    )
                )
            }

            // ---- 进行中（排最上） ----
            item(key = "task_active_sub") {
                SubSectionLabel(
                    text = stringResource(R.string.tasks_section_active),
                    count = activeSessions.size
                )
            }

            if (activeSessions.isEmpty()) {
                item(key = "task_empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tasks_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    activeSessions,
                    key = { "task_${it.id}" }
                ) { session ->
                    TaskRow(
                        session = session,
                        phase = phasesMap[session.id],
                        busySinceMs = app.repository.busySinceOf(session.id),
                        nowTick = nowTick,
                        onClick = { onOpenTask(session.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
            }

            // ---- 已完成（排最下，即当前未进行的任务） ----
            if (doneSessions.isNotEmpty()) {
                item(key = "task_done_sub") {
                    SubSectionLabel(
                        text = stringResource(R.string.tasks_section_done),
                        count = doneSessions.size
                    )
                }
                items(
                    doneSessions,
                    key = { "done_${it.id}" }
                ) { session ->
                    DoneTaskRow(
                        session = session,
                        onClick = { onOpenTask(session.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
                            placementSpec = KimiMotion.placement(),
                            fadeOutSpec = tween(KimiMotion.DUR_SHORT)
                        )
                    )
                }
            }

            // ---------- 底部留白 ----------
            item(key = "bottom") {
                Spacer(Modifier.navigationBarsPadding().height(32.dp))
            }
        }

        // ---------- v0.5.10 悬浮顶栏：不透明（减少模糊开）或高斯动态毛玻璃（减少模糊关） ----------
        ConsoleTopBar(
            listState = listState,
            contentLayer = contentLayer,
            onOpenSettings = onOpenSettings,
            onRefresh = { MonitorService.send(context, MonitorService.ACTION_RECONNECT) },
            onChangeServer = onChangeServer
        )
    }
}

// ================= 悬浮顶栏（v0.5.9 / v0.5.10） =================

/**
 * 控制台顶栏（v0.5.9 重构为悬浮覆盖式；v0.5.10 毛玻璃全面升级）：
 * - 「减少模糊」开启（默认）：不透明 surface 背景，观感与 v0.5.8 固定顶栏一致
 * - 「减少模糊」关闭：**真实高斯动态毛玻璃** —— 通过共享 GraphicsLayer 实时取样
 *   滚动内容，在顶栏范围内叠加 RenderEffect 高斯模糊（滚动文字/卡片在玻璃后
 *   真实雾化，而非旧的渐变自模糊）；模糊半径随滚动状态动态过渡
 *   （顶部停留 16dp 轻雾 → 内容滚入后 28dp 深雾），配合半透明 surface 蒙层、
 *   主色→第三色染色渐变 + 底缘消散渐变，观感对齐 iOS 毛玻璃工具栏
 */
@Composable
private fun ConsoleTopBar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentLayer: GraphicsLayer,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onChangeServer: () -> Unit
) {
    val glass = LocalGlassEffects.current
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4 }
    }

    // v0.5.10：高斯动态模糊半径 —— 顶部停留时轻雾，内容滚入玻璃下后加重（非线性过渡）
    val gaussRadius by animateFloatAsState(
        targetValue = when {
            !glass || !supportsBlur -> 0f
            scrolled -> 28f
            else -> 16f
        },
        animationSpec = tween(KimiMotion.DUR_LONG, easing = KimiMotion.EmphasizedDecelerate),
        label = "topBarGauss"
    )

    // 蒙层浓度：真实高斯模糊下适当降低不透明度，让玻璃后的内容雾化可见；
    // 滚入后加深保证文字对比度
    val bgAlpha by animateFloatAsState(
        targetValue = when {
            !glass -> 1f
            scrolled -> 0.82f
            else -> 0.55f
        },
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
        label = "topBarBg"
    )
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard),
        label = "topBarHairline"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        // v0.5.10 真实毛玻璃层：共享图层中取样滚动内容 → 裁剪到顶栏范围 → 高斯动态模糊。
        // 取代 v0.5.9 的「渐变自模糊」（模糊平滑渐变在视觉上是 no-op，并非真实玻璃）
        if (glass && supportsBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        if (gaussRadius > 0.5f) {
                            val r = gaussRadius.dp.toPx()
                            renderEffect = BlurEffect(r, r)
                        } else {
                            renderEffect = null
                        }
                    }
                    .clipToBounds()
                    .drawWithContent { drawLayer(contentLayer) }
            )
        }
        // 玻璃染色层：主色 → 第三色水平渐变（v0.5.10：去掉无效的自身模糊，仅保留染色）
        if (glass) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.02f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f)
                            )
                        )
                    )
            )
        }
        // 蒙层（浓度随滚动 / 模式变化；真实模糊下降低基线浓度，玻璃更通透）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha))
        )

        // 顶栏内容（v0.5.8：应用名称 + 设置 / 刷新 / 更改服务器）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.console_menu_settings),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.console_menu_refresh),
                    modifier = Modifier.size(20.dp)
                )
            }
            TextButton(
                onClick = onChangeServer,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = stringResource(R.string.console_menu_change_server),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }

        // 底缘：发丝线（滚动时浮现）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.75.dp)
                .graphicsLayer { alpha = hairlineAlpha }
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
        // 底缘：内容消散渐变（玻璃模式，内容从玻璃下滚过时渐隐）
        if (glass) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

// ================= 连接状态 banner =================

/**
 * 连接状态卡片（v0.5.8）：移入滚动区随内容滚动，仅展示连接状态与服务器信息；
 * v0.5.10：Aurora 柔光色斑已移除，玻璃模式下仅保留半透明底色，
 * 颜色过渡改用 emphasized 曲线，呼吸点改用 FastOutSlowIn 非线性脉冲。
 */
@Composable
private fun ConnectionBanner(
    connection: ConnState,
    host: String,
    port: Int,
    version: String?
) {
    val online = connection == ConnState.CONNECTED
    val connecting = connection == ConnState.CONNECTING
    val glass = LocalGlassEffects.current

    val bgColor by animateColorAsState(
        targetValue = when {
            online -> MaterialTheme.colorScheme.primary.copy(alpha = if (glass) 0.06f else 0.08f)
            connecting -> MaterialTheme.colorScheme.tertiary.copy(alpha = if (glass) 0.08f else 0.10f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (glass) 0.28f else 0.40f)
        },
        animationSpec = tween(KimiMotion.DUR_LONG, easing = KimiMotion.EmphasizedDecelerate),
        label = "bannerBg"
    )
    val dotColor by animateColorAsState(
        targetValue = when {
            online -> Color(0xFF34C77B)
            connecting -> Color(0xFFF0A132)
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(KimiMotion.DUR_LONG, easing = KimiMotion.EmphasizedDecelerate),
        label = "bannerDot"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // 呼吸状态点（仅连接中动画，避免 v0.4 的 0 时长无限动画反模式）
            val pulseAlpha = if (connecting) rememberPulseAlpha() else 1f
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .alpha(pulseAlpha)
                    .background(dotColor, CircleShape)
            ) {}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (connection) {
                        ConnState.CONNECTED -> stringResource(R.string.console_state_connected)
                        ConnState.CONNECTING -> stringResource(R.string.console_state_connecting)
                        ConnState.DISCONNECTED -> stringResource(R.string.console_state_disconnected)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "$host:$port" + (version?.takeIf { it.isNotBlank() }?.let { "  ·  v$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 呼吸透明度（仅连接中时使用；v0.5.9 非线性 FastOutSlowIn 脉冲；离开该状态重组即自动停止） */
@Composable
private fun rememberPulseAlpha(): Float {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(650, easing = KimiMotion.Prompt),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    ).value
}

// ================= 套餐使用量 =================

@Composable
private fun UsageSection(state: com.kimicode.companion.data.UsageUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        SectionLabel(text = stringResource(R.string.usage_title))

        when {
            state.loading && state.quota == null -> {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.usage_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.quota != null -> UsageBars(state.quota)

            else -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error ?: stringResource(R.string.usage_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsageBars(quota: com.kimicode.companion.data.UsageQuota) {
    val usages = quota.usages
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        usages.limit5h?.let { QuotaBar(stringResource(R.string.usage_5h), it) }
        usages.limit7d?.let { QuotaBar(stringResource(R.string.usage_7d), it) }
        usages.monthTotal?.let { QuotaBar(stringResource(R.string.usage_month), it) }
        usages.monthCode?.let { QuotaBar(stringResource(R.string.usage_month_code), it) }

        quota.extraUsage?.let { wallet ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.usage_booster),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatCents(wallet.balanceCents, wallet.currency) +
                        " / " + formatCents(wallet.totalCents, wallet.currency),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun QuotaBar(label: String, entry: QuotaEntry) {
    val ratio = entry.usedRatio.coerceIn(0.0, 1.0)
    // v0.5.9：进度落位用低阻尼弹簧（轻微回弹），颜色过渡用非线性 tween
    val animatedRatio by animateFloatAsState(
        targetValue = ratio.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "quotaRatio"
    )
    val barColor by animateColorAsState(
        targetValue = when {
            ratio >= 0.9 -> MaterialTheme.colorScheme.error
            ratio >= 0.7 -> Color(0xFFF0A132)
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
        label = "quotaColor"
    )
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(ratio * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = barColor,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        // 自绘细进度条（5dp 圆角，避免 M3 版本 API 差异）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedRatio)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
        entry.resetAt?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.usage_reset_at, formatResetTime(it)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

// ================= 分区标题 =================

@Composable
private fun SectionLabel(
    text: String,
    alert: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(top = 22.dp, bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(
                    if (alert) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(1.5.dp)
                )
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** v0.5.4：子分栏标题（进行中 / 已完成）—— 比主分栏更轻量，附计数 */
@Composable
private fun SubSectionLabel(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ================= 任务行（扁平，无卡片） =================

@Composable
private fun TaskRow(
    session: Session,
    phase: SessionPhase?,
    busySinceMs: Long?,
    nowTick: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (session.pending_interaction) {
        "approval" -> MaterialTheme.colorScheme.error
        "question" -> MaterialTheme.colorScheme.tertiary
        else -> if (session.busy) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 13.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    when (phase?.phaseKind) {
                        "tool_call", "retrying" -> Color(0xFFF0A132)
                        else -> badgeColor
                    },
                    CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                session.metadata?.cwd?.let {
                    Text(
                        text = it.substringAfterLast('/').ifBlank { it },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = phaseSummary(session, phase, busySinceMs, nowTick),
                    style = MaterialTheme.typography.bodySmall,
                    color = badgeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 任务行的第二行文案：AI 相位摘要（思考中 / 执行命令 / 等待审批…） */
@Composable
private fun phaseSummary(session: Session, phase: SessionPhase?, busySinceMs: Long?, nowTick: Long): String {
    val base = when {
        session.pending_interaction == "approval" -> stringResource(R.string.phase_approval)
        session.pending_interaction == "question" -> stringResource(R.string.phase_question)
        !session.busy -> stringResource(R.string.phase_idle)
        else -> when (phase?.phaseKind) {
            "tool_call", "retrying" -> when (phase.displayKind) {
                "command" -> stringResource(R.string.phase_shell) +
                    (phase.command?.let { " · ${it.take(40)}" } ?: "")
                "file_io", "diff" -> stringResource(R.string.phase_write)
                "todo_list" -> stringResource(R.string.phase_todo_updating)
                else -> phase.toolName?.let {
                    stringResource(R.string.phase_tool, it)
                } ?: stringResource(R.string.phase_thinking)
            }
            "awaiting_approval" -> stringResource(R.string.phase_approval)
            else -> stringResource(R.string.phase_thinking)
        }
    }
    // 忙碌任务追加运行时长
    if (session.busy && busySinceMs != null) {
        val minutes = (nowTick - busySinceMs) / 60_000
        val duration = when {
            minutes >= 60 -> "${minutes / 60}h${minutes % 60}m"
            minutes >= 1 -> "${minutes}m"
            else -> null
        }
        if (duration != null) return "$base · $duration"
    }
    return base
}

// ================= 已完成任务行（v0.5.4：扁平，无卡片） =================

/**
 * 已完成任务行：状态图标（✓ 完成 / ✗ 失败 / ⊘ 取消）+ 标题 + 第二行（结果 · 相对时间）。
 * 与进行中 [TaskRow] 同款扁平行排版，保持简洁。
 */
@Composable
private fun DoneTaskRow(
    session: Session,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reason = session.last_turn_reason
    val (icon, tint, label) = when (reason) {
        "failed" -> Triple(Icons.Rounded.Close, MaterialTheme.colorScheme.error, stringResource(R.string.task_done_failed))
        "cancelled" -> Triple(Icons.Rounded.Remove, MaterialTheme.colorScheme.outline, stringResource(R.string.task_done_cancelled))
        else -> Triple(Icons.Rounded.Check, Color(0xFF34C77B), stringResource(R.string.task_done_completed))
    }
    val relTime = remember(session.updated_at) { relativeTimeOf(session.updated_at) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 11.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.85f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.copy(alpha = 0.9f),
                    maxLines = 1
                )
                session.metadata?.cwd?.let {
                    Text(
                        text = it.substringAfterLast('/').ifBlank { it },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                relTime?.let {
                    Text(
                        text = "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** ISO 时间 → 相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 / MM-dd HH:mm） */
private fun relativeTimeOf(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val parsed = runCatching {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
    }.getOrNull() ?: return null
    val now = java.time.LocalDateTime.now()
    val minutes = java.time.Duration.between(parsed, now).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 48 -> "昨天"
        else -> String.format("%02d-%02d %02d:%02d", parsed.monthValue, parsed.dayOfMonth, parsed.hour, parsed.minute)
    }
}

// ================= 审批 / 问题（轻量扁平行） =================

@Composable
private fun ApprovalRow(
    approval: ApprovalRequest,
    deciding: Boolean,
    onDecide: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "${approval.tool_name} · ${approval.action}".trim('·'),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val detail = approval.displayInfo()?.oneLiner().orEmpty()
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                onClick = { onDecide("rejected", false) },
                enabled = !deciding
            ) { Text(stringResource(R.string.approval_btn_reject)) }
            TextButton(
                onClick = { onDecide("approved", false) },
                enabled = !deciding
            ) { Text(stringResource(R.string.approval_btn_approve)) }
            Button(
                onClick = { onDecide("approved", true) },
                enabled = !deciding
            ) { Text(stringResource(R.string.approval_btn_approve_session)) }
        }
    }
}

@Composable
private fun QuestionRow(
    questionText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.console_question_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        if (questionText.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                questionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            stringResource(R.string.console_question_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ================= 工具函数 =================

/** 分单位格式化为货币字符串（CNY → ¥，其他显示代码） */
private fun formatCents(cents: Long, currency: String): String {
    val amount = cents / 100.0
    val formatted = if (amount % 1.0 == 0.0) "${amount.toLong()}" else String.format("%.2f", amount)
    return when (currency.uppercase()) {
        "CNY", "CNH" -> "¥$formatted"
        "USD" -> "\$$formatted"
        else -> "$formatted $currency"
    }
}

/** ISO 时间 → 简短本地时间（今天 HH:mm / MM-dd HH:mm） */
private fun formatResetTime(iso: String): String {
    val parsed = runCatching {
        java.time.Instant.parse(iso)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
    }.getOrNull() ?: return iso
    val now = java.time.LocalDate.now()
    return if (parsed.toLocalDate() == now) {
        String.format("%02d:%02d", parsed.hour, parsed.minute)
    } else {
        String.format("%02d-%02d %02d:%02d", parsed.monthValue, parsed.dayOfMonth, parsed.hour, parsed.minute)
    }
}
