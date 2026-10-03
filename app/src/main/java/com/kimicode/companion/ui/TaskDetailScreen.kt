package com.kimicode.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.ApprovalRequest
import com.kimicode.companion.data.SessionDetailState
import com.kimicode.companion.data.SessionPhase
import com.kimicode.companion.data.Session
import com.kimicode.companion.data.SubagentInfo
import com.kimicode.companion.data.TodoItem
import com.kimicode.companion.data.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务详情页（v0.5 新增）：
 *
 * ┌────────────────────────────────────┐
 * │ ←  任务标题                    ↻    │
 * ├────────────────────────────────────┤
 * │ Token 用量                          │
 * │ 本轮   输入 12.3k  输出 4.5k        │
 * │ 累计   输入 120k   输出 45k         │
 * │ 上下文 45% ──────────░░░ 36k / 80k │
 * │ 模型 kimi-k2 · 思考强度 high        │
 * ├────────────────────────────────────┤
 * │ AI 状态                             │
 * │ ● 思考中 Thinking                   │
 * ├────────────────────────────────────┤
 * │ Todo · 2/7                          │
 * │ ◌ 分析源码                          │
 * │ ◉ 修复登录逻辑                      │
 * │ ✓ 编写单元测试                      │
 * ├────────────────────────────────────┤
 * │ Goal 目标                           │
 * │ ● 进行中 · 已用 12 轮 · 45k tokens  │
 * │ "修复所有登录相关的 bug"             │
 * ├────────────────────────────────────┤
 * │ 模式                                │
 * │ Plan 模式                [关]      │
 * │ Swarm 模式               [开]      │
 * │ · swarm-agent-1   ● 工作中          │
 * │ · swarm-agent-2   ✓ 已完成          │
 * └────────────────────────────────────┘
 *
 * 数据：进入时 REST 快照（transcript/status/goal）+ WS 实时事件
 * （agent.status.updated / tool.call.started / goal.updated / subagent.*）。
 */
@Composable
fun TaskDetailScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val scope = rememberCoroutineScope()

    val sessionsMap by app.repository.sessions.collectAsStateWithLifecycle()
    val detailsMap by app.repository.sessionDetails.collectAsStateWithLifecycle()
    val phasesMap by app.repository.sessionPhases.collectAsStateWithLifecycle()
    // v0.5.3：待审批（审批头 Run this command? 等需要工具名）
    val pendingApprovals by app.repository.pendingApprovals.collectAsStateWithLifecycle()
    // v0.5.2：实时思考流 + 运行时长计时（v0.5.3：忙时 1s 刷新，思考新鲜度判定用）
    val thinkingMap by app.repository.thinkingTexts.collectAsStateWithLifecycle()
    val session = sessionsMap[sessionId]
    val detail = detailsMap[sessionId]
    val phase = phasesMap[sessionId]
    val thinkingText = thinkingMap[sessionId]
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionId, session?.busy) {
        while (isActive) {
            delay(if (session?.busy == true) 1_000 else 30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var modeBusy by remember { mutableStateOf(false) }

    // 进入详情页：REST 快照回填（transcript / goal），失败静默降级（WS 事件继续补充）
    fun loadSnapshot(showSpinner: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (showSpinner) withContext(Dispatchers.Main) { loading = true }
            withContext(Dispatchers.Main) { refreshing = true }

            val transcript = runCatching { app.api.getTranscript(sessionId) }.getOrNull()
            if (transcript != null) {
                val meta = transcript.meta
                val current = app.repository.sessionDetails.value[sessionId]
                    ?: SessionDetailState(sessionId)
                app.repository.applyDetailSnapshot(
                    sessionId,
                    current.copy(
                        usage = meta?.agent?.usage ?: current.usage,
                        contextTokens = meta?.agent?.contextTokens ?: current.contextTokens,
                        maxContextTokens = meta?.agent?.maxContextTokens ?: current.maxContextTokens,
                        contextUsage = meta?.agent?.contextUsage ?: current.contextUsage,
                        model = meta?.agent?.model ?: current.model,
                        thinkingEffort = meta?.agent?.thinkingEffort ?: current.thinkingEffort,
                        todos = transcript.todos.lastOrNull()?.items ?: current.todos,
                        goal = current.goal,
                        subagents = current.subagents
                    )
                )
            }
            runCatching { app.api.getGoal(sessionId) }.getOrNull()?.let {
                app.repository.onGoalUpdated(sessionId, it)
            }
            // 会话忙时同步一次模式状态（status 端点含 plan/swarm 标志）
            runCatching { app.api.getSessionStatus(sessionId) }.getOrNull()?.let { st ->
                app.repository.onAgentStatus(
                    sessionId,
                    phaseKind = phase?.phaseKind,
                    toolName = phase?.toolName,
                    planMode = st.plan_mode,
                    swarmMode = st.swarm_mode,
                    contextTokens = st.context_tokens,
                    maxContextTokens = st.max_context_tokens,
                    contextUsage = st.context_usage
                )
            }
            withContext(Dispatchers.Main) {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(sessionId) { loadSnapshot(showSpinner = true) }

    // 会话忙碌时每 15 秒轻量刷新（token 用量新鲜度），空闲则不轮询
    LaunchedEffect(sessionId, session?.busy) {
        while (isActive && session?.busy == true) {
            delay(15_000)
            if (session.busy) loadSnapshot(showSpinner = false)
        }
    }

    // ---------- 模式开关 ----------
    fun setMode(planMode: Boolean? = null, swarmMode: Boolean? = null) {
        if (modeBusy) return
        modeBusy = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                app.api.updateSessionProfile(sessionId, planMode = planMode, swarmMode = swarmMode)
            }
            // 乐观更新本地状态
            val cur = app.repository.sessionDetails.value[sessionId] ?: SessionDetailState(sessionId)
            app.repository.applyDetailSnapshot(
                sessionId,
                cur.copy(
                    planMode = planMode ?: cur.planMode,
                    swarmMode = swarmMode ?: cur.swarmMode
                )
            )
            withContext(Dispatchers.Main) { modeBusy = false }
        }
    }

    // ---------- Goal 控制 ----------
    fun goalControl(control: String) {
        if (modeBusy) return
        modeBusy = true
        scope.launch(Dispatchers.IO) {
            runCatching { app.api.updateSessionProfile(sessionId, goalControl = control) }
            withContext(Dispatchers.Main) { modeBusy = false }
            loadSnapshot(showSpinner = false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 顶栏（v0.5.9：状态栏边距适配） ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = session?.displayTitle ?: stringResource(R.string.detail_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                session?.metadata?.cwd?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { loadSnapshot(showSpinner = false) }, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.console_menu_refresh)
                    )
                }
            }
        }

        if (loading && detail == null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.detail_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // ---------- Token 用量（v0.5.9：分节阶梯入场） ----------
                item(key = "token_usage") {
                    EntranceBlock(delayMillis = 0) {
                        TokenUsageSection(detail)
                    }
                }

                // ---------- AI 状态 ----------
                item(key = "ai_status") {
                    EntranceBlock(delayMillis = 60) {
                        AiStatusSection(
                            busy = session?.busy ?: false,
                            phase = phase,
                            thinkingText = thinkingText,
                            thinkingFresh = thinkingText != null &&
                                app.repository.thinkingUpdatedAtOf(sessionId)
                                    ?.let { nowTick - it < 4_000 } == true,
                            todoDone = detail?.todos?.takeIf { it.isNotEmpty() }?.count { it.status == "done" },
                            todoTotal = detail?.todos?.takeIf { it.isNotEmpty() }?.size,
                            swarmActive = detail?.subagents?.values
                                ?.count { it.state == "spawned" || it.state == "working" }
                                ?.takeIf { detail.subagents.isNotEmpty() },
                            busySinceMs = app.repository.busySinceOf(sessionId),
                            nowTick = nowTick,
                            pendingInteraction = session?.pending_interaction?.takeIf { it != "none" },
                            approvalToolName = pendingApprovals.values
                                .firstOrNull { it.session_id == sessionId }?.tool_name,
                            readBurst = app.repository.readBurstOf(sessionId),
                            workspaceDir = session?.metadata?.cwd
                        )
                    }
                }

                // ---------- Todo ----------
                item(key = "todos") {
                    EntranceBlock(delayMillis = 120) {
                        TodoSection(detail?.todos ?: emptyList())
                    }
                }

                // ---------- Goal ----------
                item(key = "goal") {
                    EntranceBlock(delayMillis = 180) {
                        GoalSection(detail?.goal, onControl = ::goalControl, busy = modeBusy)
                    }
                }

                // ---------- 模式（Plan / Swarm） ----------
                item(key = "modes") {
                    EntranceBlock(delayMillis = 240) {
                        ModeSection(
                            planMode = detail?.planMode ?: false,
                            swarmMode = detail?.swarmMode ?: false,
                            subagents = detail?.subagents?.values?.sortedBy { it.swarmIndex ?: 0 }
                                ?: emptyList(),
                            busy = modeBusy,
                            onTogglePlan = { setMode(planMode = it) },
                            onToggleSwarm = { setMode(swarmMode = it) }
                        )
                    }
                }

                item(key = "bottom") { Spacer(Modifier.navigationBarsPadding().height(32.dp)) }
            }
        }
    }
}

// ================= Token 用量 =================

@Composable
private fun TokenUsageSection(detail: SessionDetailState?) {
    DetailSection(title = stringResource(R.string.detail_token_usage)) {
        val usage = detail?.usage
        if (usage == null && detail?.contextTokens == null) {
            Text(
                text = stringResource(R.string.detail_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@DetailSection
        }

        // 本轮 / 累计
        UsageRow(
            label = stringResource(R.string.detail_current_turn),
            usage = usage?.currentTurn
        )
        UsageRow(
            label = stringResource(R.string.detail_total),
            usage = usage?.total,
            emphasized = true
        )

        // 上下文窗口
        val ctxTokens = detail?.contextTokens
        val ctxMax = detail?.maxContextTokens
        if (ctxTokens != null && ctxMax != null && ctxMax > 0) {
            val ratio = (ctxTokens.toDouble() / ctxMax).coerceIn(0.0, 1.0)
            // v0.5.9：进度落位低阻尼弹簧 + 颜色非线性过渡
            val animatedRatio by animateFloatAsState(
                targetValue = ratio.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "ctxRatio"
            )
            val barColor by animateColorAsState(
                targetValue = when {
                    ratio >= 0.9 -> MaterialTheme.colorScheme.error
                    ratio >= 0.7 -> Color(0xFFF0A132)
                    else -> MaterialTheme.colorScheme.primary
                },
                animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
                label = "ctxBarColor"
            )
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.detail_context),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(ratio * 100).toInt()}% · ${formatTokens(ctxTokens)} / ${formatTokens(ctxMax)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = barColor,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
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
        }

        // 模型 / 思考强度
        val modelLine = listOfNotNull(
            detail?.model?.let { it },
            detail?.thinkingEffort?.let { stringResource(R.string.detail_thinking_effort, it) }
        ).joinToString(" · ")
        if (modelLine.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = modelLine,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, usage: TokenUsage?, emphasized: Boolean = false) {
    if (usage == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = stringResource(R.string.detail_input_output,
                formatTokens(usage.inputTokens), formatTokens(usage.output)),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ================= AI 状态（v0.5.3：Kimi Code 官方文案引擎 + 动态模糊切换动画） =================
// v0.5.4：文案表 / 语义色 / spinner / 动态模糊文本已抽取到 StatusUiShared.kt，
// 与悬浮窗（FloatingWindowContent）共享同款文案与动画。

@Composable
private fun AiStatusSection(
    busy: Boolean,
    phase: SessionPhase?,
    thinkingText: String? = null,
    thinkingFresh: Boolean = false,
    todoDone: Int? = null,
    todoTotal: Int? = null,
    swarmActive: Int? = null,
    busySinceMs: Long? = null,
    nowTick: Long = 0,
    pendingInteraction: String? = null,
    approvalToolName: String? = null,
    readBurst: Int = 0,
    workspaceDir: String? = null
) {
    DetailSection(title = stringResource(R.string.detail_ai_status)) {
        // ---- v0.5.3：Kimi Code 官方状态文案引擎 ----
        val s = rememberKimiStatusStrings()
        val display = KimiStatus.resolve(
            s, busy, phase, pendingInteraction, approvalToolName,
            thinkingFresh, readBurst, workspaceDir
        )
        // 状态色平滑过渡（配合文字动态模糊，避免生硬跳变）
        val targetColor = toneColor(display.tone)
        val color by animateColorAsState(
            targetValue = targetColor,
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "aiStatusColor"
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Kimi Code 原版 spinner：braille（思考/生成）/ moon（等待/工具）
            StatusSpinner(display.spinner, display.pulse, color)
            // 状态主文案（动态模糊切换：旧文案模糊滑出、新文案由模糊聚焦滑入）
            MotionBlurText(
                text = display.label,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            // v0.5.2：运行时长
            if (busy && busySinceMs != null) {
                val minutes = (nowTick - busySinceMs) / 60_000
                val duration = when {
                    minutes >= 60 -> "${minutes / 60}h${minutes % 60}m"
                    minutes >= 1 -> "${minutes}m"
                    else -> "<1m"
                }
                Text(
                    text = stringResource(R.string.detail_running_for, duration),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 相位补充行（Kimi Code 卡片完成态的结果首行 / 重试详情，动态模糊切换）
        if (!display.hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            MotionBlurText(
                text = display.hint,
                color = if (display.tone == KimiStatus.Tone.ERROR)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // v0.5.2：实时思考流（v0.5.3 对齐 Kimi Code thinking.ts：⠋ thinking… + 内容行）
        if (busy && !thinkingText.isNullOrBlank() &&
            (phase?.phaseKind == null || phase.phaseKind == "running")
        ) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = KimiStatus.BRAILLE_FRAMES[kimiBrailleFrame()],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.ks_thinking_lower),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = thinkingText.take(280),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // v0.5.2：进度芯片（Todo 进度 / Swarm 个数）
        if (busy && (todoTotal != null || (swarmActive ?: 0) > 0)) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (todoTotal != null && todoDone != null) {
                    StatusChip(
                        text = stringResource(R.string.detail_ai_todo_chip, todoDone, todoTotal),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if ((swarmActive ?: 0) > 0) {
                    StatusChip(
                        text = stringResource(R.string.detail_ai_swarm_chip, swarmActive ?: 0),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/** 轻量状态芯片（圆角胶囊） */
@Composable
private fun StatusChip(text: String, tint: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// ================= Todo 列表 =================

/** Todo 状态色：与全局语义色一致（绿 = 完成，橙 = 进行中） */
private val TodoDoneGreen = Color(0xFF34C77B)
private val TodoActiveOrange = Color(0xFFF0A132)

@Composable
private fun TodoSection(todos: List<TodoItem>) {
    val done = todos.count { it.status == "done" }
    DetailSection(title = stringResource(R.string.detail_todo, todos.size, done)) {
        if (todos.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_todo_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@DetailSection
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todos.forEach { item ->
                TodoRow(item)
            }
        }
    }
}

/**
 * MD3 圆角胶囊行 + 圆形状态徽标：
 * - done：绿色圆形徽标 + 白勾，文字淡化 + 删除线
 * - in_progress：呼吸橙色圆形徽标（沿用全局 pulse 动画）+ 橙色淡染背景
 * - pending：空心圆徽标，普通文字
 */
@Composable
private fun TodoRow(item: TodoItem) {
    val done = item.status == "done"
    val active = item.status == "in_progress"
    val pulseAlpha = if (active) rememberPulseAlphaShared() else 1f
    // v0.5.9：状态切换时徽标 / 背景色非线性过渡，完成瞬间轻微回弹放大
    val badgeScale by animateFloatAsState(
        targetValue = if (done) 1.12f else 1f,
        animationSpec = KimiMotion.bouncy(),
        label = "todoBadgeScale"
    )
    val rowBg by animateColorAsState(
        targetValue = when {
            active -> TodoActiveOrange.copy(alpha = 0.14f)
            done -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
        },
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
        label = "todoRowBg"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // 圆形状态徽标
        Box(
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = badgeScale
                    scaleY = badgeScale
                }
                .alpha(pulseAlpha)
                .then(
                    if (done || active) {
                        Modifier.background(
                            color = if (done) TodoDoneGreen else TodoActiveOrange,
                            shape = CircleShape
                        )
                    } else {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            shape = CircleShape
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            } else if (active) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alpha(if (done) 0.55f else 1f)
        )
    }
}

// ================= Goal =================

@Composable
private fun GoalSection(goal: com.kimicode.companion.data.GoalSnapshot?, onControl: (String) -> Unit, busy: Boolean) {
    DetailSection(title = stringResource(R.string.detail_goal)) {
        if (goal == null) {
            Text(
                text = stringResource(R.string.detail_goal_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@DetailSection
        }

        // 状态行
        val (statusText, statusColor) = when (goal.status) {
            "active" -> stringResource(R.string.detail_goal_active) to Color(0xFF34C77B)
            "paused" -> stringResource(R.string.detail_goal_paused) to Color(0xFFF0A132)
            "blocked" -> stringResource(R.string.detail_goal_blocked) to MaterialTheme.colorScheme.error
            "complete" -> stringResource(R.string.detail_goal_complete) to MaterialTheme.colorScheme.primary
            else -> goal.status to MaterialTheme.colorScheme.outline
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.detail_goal_stats, goal.turnsUsed, formatTokens(goal.tokensUsed)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = goal.objective,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        goal.completionCriterion?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.detail_goal_criterion, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 预算
        val budget = goal.budget
        if (budget.tokenBudget != null && budget.tokenBudget > 0) {
            Spacer(Modifier.height(6.dp))
            val usedRatio = (goal.tokensUsed.toDouble() / budget.tokenBudget).coerceIn(0.0, 1.0)
            Text(
                text = stringResource(R.string.detail_goal_budget,
                    (usedRatio * 100).toInt(),
                    formatTokens(goal.tokensUsed),
                    formatTokens(budget.tokenBudget)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Goal 控制（仅进行中/暂停时可操作）
        if (goal.status == "active" || goal.status == "paused") {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onControl(if (goal.status == "active") "pause" else "resume") },
                    enabled = !busy
                ) {
                    Text(
                        if (goal.status == "active") stringResource(R.string.detail_goal_pause)
                        else stringResource(R.string.detail_goal_resume)
                    )
                }
                TextButton(
                    onClick = { onControl("cancel") },
                    enabled = !busy
                ) { Text(stringResource(R.string.detail_goal_cancel)) }
            }
        }
    }
}

// ================= 模式（Plan / Swarm） =================

@Composable
private fun ModeSection(
    planMode: Boolean,
    swarmMode: Boolean,
    subagents: List<SubagentInfo>,
    busy: Boolean,
    onTogglePlan: (Boolean) -> Unit,
    onToggleSwarm: (Boolean) -> Unit
) {
    DetailSection(title = stringResource(R.string.detail_modes)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Plan 模式
            ModeRow(
                title = stringResource(R.string.detail_mode_plan),
                support = stringResource(R.string.detail_mode_plan_desc),
                checked = planMode,
                enabled = !busy,
                onToggle = onTogglePlan
            )
            // Swarm 模式
            ModeRow(
                title = stringResource(R.string.detail_mode_swarm),
                support = stringResource(R.string.detail_mode_swarm_desc),
                checked = swarmMode,
                enabled = !busy,
                onToggle = onToggleSwarm
            )
        }

        // Swarm 子代理列表
        if (subagents.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.detail_swarm_agents, subagents.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                subagents.forEach { agent ->
                    SubagentRow(agent)
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    support: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SubagentRow(agent: SubagentInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        // v0.5.3：Kimi Code 官方子代理状态（agent-group.ts / agent-swarm-progress.ts）
        // Starting / Running / Rate limited… / ✓ Completed / ✗ Failed / Aborted.
        val stateText = stringResource(KimiStatus.subagentLabelRes(agent.state))
        val stateColor = toneColor(KimiStatus.subagentTone(agent.state))
        val pulse = KimiStatus.subagentActive(agent.state)
        // 活跃时使用 braille spinner（对齐 Kimi Code 子代理卡片的旋转标记）
        val alpha = if (pulse && agent.state != "working") rememberPulseAlphaShared() else 1f
        if (pulse) {
            Text(
                text = KimiStatus.BRAILLE_FRAMES[kimiBrailleFrame()],
                color = stateColor,
                fontSize = 13.sp,
                modifier = Modifier.width(16.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(stateColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            // 标题：Kimi Code formatSubagentLabel（"swarm-coder" → "Swarm Coder Agent"）
            val title = if (!agent.label.isNullOrBlank()) {
                KimiStatus.formatSubagentLabel(agent.label)
            } else {
                (agent.swarmIndex?.let { "#${it + 1} · " } ?: "") + agent.subagentId
            }
            MotionBlurText(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = stateColor,
                modifier = Modifier.fillMaxWidth()
            )
            agent.resultSummary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            agent.usage?.let {
                // Kimi Code formatTokens："{n} tok"
                Text(
                    text = stringResource(R.string.ks_tokens, formatTokens(it.totalTokens)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 状态标签（Kimi Code 原文，动态模糊切换）
        MotionBlurText(
            text = stateText,
            style = MaterialTheme.typography.labelMedium,
            color = stateColor
        )
    }
}

// ================= 通用分区 =================

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.padding(start = 11.dp)) { content() }
    }
}

// ================= 工具函数 =================

/** token 数格式化：1234 → 1.2k，5678901 → 5.7M */
internal fun formatTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> {
            val v = tokens / 100_000.0
            if (v >= 100) "${(tokens / 1_000_000)}M" else String.format("%.1fM", v)
        }
        tokens >= 1_000 -> {
            val v = tokens / 100.0
            if (v >= 100) "${(tokens / 1_000)}k" else String.format("%.1fk", v)
        }
        else -> tokens.toString()
    }
}
