package com.kimicode.companion.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** 小组件 / 常驻通知的派生状态 */
enum class WidgetStatus { THINKING, RUNNING_SHELL, WRITING_FILES, RUNNING_TOOL, WAITING_APPROVAL, IDLE, OFFLINE }

/**
 * v0.5.2：会话实时运行状态快照（通知 / 小组件 / 详情页共用）。
 * 聚合 busy + phase（工具调用/思考）+ todo 进度 + swarm 个数 + goal。
 */
data class LiveTaskStatus(
    val sessionId: String,
    val title: String = "",
    val cwd: String? = null,
    val busy: Boolean = false,
    /** 任务开始变忙的时刻（用于时长 / 多任务优先级：越早越优先上横幅） */
    val startedAtMs: Long? = null,
    val pendingInteraction: String? = null,
    // ---- 相位（思考 / 工具调用） ----
    val phaseKind: String? = null,       // running | tool_call | retrying | awaiting_approval | idle
    val toolName: String? = null,
    val displayKind: String? = null,     // command | file_io | diff | ...
    val command: String? = null,
    val path: String? = null,
    // ---- 进度信息 ----
    val todoDone: Int? = null,
    val todoTotal: Int? = null,
    val swarmActive: Int? = null,
    val swarmMode: Boolean = false,
    val goalObjective: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    /** 是否有可展示的进度信息（todo / swarm / goal 任一） */
    val hasProgress: Boolean
        get() = todoTotal != null || (swarmActive ?: 0) > 0 || !goalObjective.isNullOrBlank()
}

data class WidgetUiState(
    val status: WidgetStatus = WidgetStatus.OFFLINE,
    val toolLabel: String? = null,
    /** v0.5.2：实时细节文本（命令片段 / 文件路径 / 工具名） */
    val detailText: String? = null,
    /** v0.5.2：todo 进度（done/total，null = 无待办） */
    val todoDone: Int? = null,
    val todoTotal: Int? = null,
    val runningTasks: Int = 0,
    val sinceMs: Long = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    /** 胶囊主文案 */
    val primaryTextRes: Int
        get() = when (status) {
            WidgetStatus.THINKING -> com.kimicode.companion.R.string.widget_state_thinking
            WidgetStatus.RUNNING_SHELL -> com.kimicode.companion.R.string.widget_state_shell
            WidgetStatus.WRITING_FILES -> com.kimicode.companion.R.string.widget_state_write
            WidgetStatus.WAITING_APPROVAL -> com.kimicode.companion.R.string.widget_state_approval
            WidgetStatus.IDLE -> com.kimicode.companion.R.string.widget_state_idle
            WidgetStatus.OFFLINE -> com.kimicode.companion.R.string.widget_state_offline
            else -> com.kimicode.companion.R.string.widget_state_tool
        }
}

/** 会话级细粒度状态（来自 agent.status.updated / tool.call.started / tool.result） */
data class SessionPhase(
    val phaseKind: String? = null,     // idle|running|tool_call|retrying|awaiting_approval|tool_done|...
    val toolName: String? = null,
    val displayKind: String? = null,   // command|file_io|diff|search|url_fetch|todo_list|...
    val displayOperation: String? = null,
    val command: String? = null,       // display.kind=command 时的命令内容
    val path: String? = null,          // display.kind=file_io/diff 时的文件路径
    // ---- v0.5.3：完整 display / args 载荷（Kimi Code 官方状态文案引擎使用） ----
    val argsJson: JsonObject? = null,  // tool.call.started 的原始 args
    val query: String? = null,         // search / WebSearch query
    val url: String? = null,           // url_fetch / FetchURL url
    val agentName: String? = null,     // agent_call 的子代理名
    val skillName: String? = null,     // skill_call 的技能名
    val summary: String? = null,       // generic 摘要
    val description: String? = null,   // 通用 description
    val taskId: String? = null,        // task / task_stop / WaitFor 的任务 id
    val objective: String? = null,     // goal_start 目标
    // ---- v0.5.3：retrying 相位详情（Kimi Code: Retrying (n/m) · Error · in Xs） ----
    val retryNextAttempt: Int? = null,
    val retryMaxAttempts: Int? = null,
    val retryDelayMs: Long? = null,
    val retryErrorName: String? = null,
    val retryStatusCode: Int? = null,
    // ---- v0.5.3：tool.result 完成态（Kimi Code: ✓ Used X / 结果首行） ----
    val resultIsError: Boolean = false,
    val resultLine: String? = null,
    val updatedAtMs: Long = 0
)

/**
 * 会话详情聚合状态（v0.5）：Token 用量 / 上下文 / 模式开关 / Todo / Goal / Swarm 子代理。
 * 由 REST 快照（transcript/status/goal）回填 + WS 事件实时更新。
 */
data class SessionDetailState(
    val sessionId: String,
    // Token 用量（agent.status.updated.usage / transcript meta.agent.usage）
    val usage: UsageStatus? = null,
    val contextTokens: Long? = null,
    val maxContextTokens: Long? = null,
    val contextUsage: Double? = null,
    val model: String? = null,
    val thinkingEffort: String? = null,
    // 模式（plan / swarm）
    val planMode: Boolean = false,
    val swarmMode: Boolean = false,
    // Todo 列表（transcript todos + tool.call.started todo_list 实时更新）
    val todos: List<TodoItem> = emptyList(),
    // Goal 快照（REST goal + WS goal.updated）
    val goal: GoalSnapshot? = null,
    // Swarm 子代理（WS subagent.* 事件累积）
    val subagents: Map<String, SubagentInfo> = emptyMap(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/** 套餐用量 UI 状态 */
data class UsageUiState(
    val quota: UsageQuota? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val fetchedAtMs: Long = 0
)

/** 连接状态 */
enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 全局状态仓库：吃进 WS 事件 / REST 结果，吐出 UI、通知、小组件需要的聚合状态。
 * 单实例（AppContainer 持有），MonitorService 是主要生产者。
 */
class Repository {

    // ---------- 给 MonitorService 塞事件 ----------
    val wsEvents: MutableSharedFlow<WsEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ---------- 状态 ----------

    private val _connection = MutableStateFlow(ConnState.DISCONNECTED)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<Map<String, ApprovalRequest>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, ApprovalRequest>> = _pendingApprovals.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<Map<String, QuestionRequest>>(emptyMap())
    val pendingQuestions: StateFlow<Map<String, QuestionRequest>> = _pendingQuestions.asStateFlow()

    private val _widget = MutableStateFlow(WidgetUiState())
    val widget: StateFlow<WidgetUiState> = _widget.asStateFlow()

    /** v0.5：会话详情（用量 / todo / goal / swarm） */
    private val _sessionDetails = MutableStateFlow<Map<String, SessionDetailState>>(emptyMap())
    val sessionDetails: StateFlow<Map<String, SessionDetailState>> = _sessionDetails.asStateFlow()

    /** v0.5.2：主 agent 实时思考文本（thinking.delta 流式拼接，仅保留尾部片段） */
    private val _thinkingTexts = MutableStateFlow<Map<String, String>>(emptyMap())
    val thinkingTexts: StateFlow<Map<String, String>> = _thinkingTexts.asStateFlow()

    /** v0.5.2：忙碌会话的实时状态列表（按开始时间升序，最早在前） */
    private val _liveTasks = MutableStateFlow<List<LiveTaskStatus>>(emptyList())
    val liveTasks: StateFlow<List<LiveTaskStatus>> = _liveTasks.asStateFlow()

    /** v0.5：套餐用量 */
    private val _usage = MutableStateFlow(UsageUiState())
    val usage: StateFlow<UsageUiState> = _usage.asStateFlow()

    /** 各会话开始变忙的时刻（用于"已运行 X 分钟"） */
    private val busySince = HashMap<String, Long>()

    /** WS work_changed 事件的最近本地时间戳（防止 REST 回填覆盖实时状态） */
    private val localTouch = HashMap<String, Long>()

    /** turn 起点：session -> (turnId -> startMs) */
    private val turnStarts = HashMap<String, HashMap<Long, Long>>()

    /** 后台任务起点：taskId -> (startedMs, kind, description) */
    data class TaskRun(val startedMs: Long, val kind: String?, val description: String?)

    @Volatile
    var runningTasks: Map<String, TaskRun> = emptyMap()
        private set

    /** 会话细粒度 phase（v0.5：改为可观察 StateFlow，控制台/详情页实时刷新） */
    private val _sessionPhases = MutableStateFlow<Map<String, SessionPhase>>(emptyMap())
    val sessionPhases: StateFlow<Map<String, SessionPhase>> = _sessionPhases.asStateFlow()

    fun setConnection(state: ConnState) {
        _connection.value = state
        recomputeWidget()
        recomputeLiveTasks()
    }

    private fun putPhase(sessionId: String, phase: SessionPhase) {
        _sessionPhases.update { it + (sessionId to phase) }
    }

    // ---------- REST 回填 ----------

    fun updateSessions(sessions: List<Session>) {
        val now = System.currentTimeMillis()
        _sessions.update { old ->
            val merged = old.toMutableMap()
            sessions.forEach { s ->
                val cur = merged[s.id]
                val wsRecent = cur != null &&
                    (now - (localTouch[s.id] ?: 0)) < 3_000
                merged[s.id] = if (wsRecent && cur != null) {
                    // 近 3 秒内收到过 WS 实时事件：保留本地忙闲状态
                    s.copy(
                        busy = cur.busy,
                        main_turn_active = cur.main_turn_active,
                        pending_interaction = cur.pending_interaction,
                        last_turn_reason = cur.last_turn_reason ?: s.last_turn_reason
                    )
                } else {
                    s
                }
            }
            merged
        }
        sessions.forEach { s ->
            if (s.busy) busySince.putIfAbsent(s.id, System.currentTimeMillis())
            else if ((now - (localTouch[s.id] ?: 0)) >= 3_000) busySince.remove(s.id)
        }
        recomputeWidget()
        recomputeLiveTasks()
    }

    fun upsertSession(session: Session) {
        _sessions.update { it + (session.id to session) }
    }

    fun onWorkChanged(sessionId: String, busy: Boolean, pendingInteraction: String?, lastTurnReason: String?) {
        _sessions.update { map ->
            val cur = map[sessionId]
            if (cur != null) {
                map + (sessionId to cur.copy(
                    busy = busy,
                    main_turn_active = busy,
                    pending_interaction = pendingInteraction ?: "none",
                    last_turn_reason = lastTurnReason ?: cur.last_turn_reason
                ))
            } else {
                map + (sessionId to Session(
                    id = sessionId,
                    busy = busy,
                    pending_interaction = pendingInteraction,
                    last_turn_reason = lastTurnReason
                ))
            }
        }
        if (busy) busySince.putIfAbsent(sessionId, System.currentTimeMillis())
        else {
            busySince.remove(sessionId)
            // 回合结束 / 会话空闲：清理思考文本
            _thinkingTexts.update { it - sessionId }
        }
        localTouch[sessionId] = System.currentTimeMillis()
        recomputeWidget()
        recomputeLiveTasks()
    }

    // ---------- 审批 ----------

    fun addApproval(approval: ApprovalRequest) {
        _pendingApprovals.update { it + (approval.approval_id to approval) }
        recomputeWidget()
    }

    fun removeApproval(approvalId: String) {
        _pendingApprovals.update { it - approvalId }
        recomputeWidget()
    }

    fun addQuestion(question: QuestionRequest) {
        _pendingQuestions.update { it + (question.question_id to question) }
    }

    fun removeQuestion(questionId: String) {
        _pendingQuestions.update { it - questionId }
    }

    // ---------- turn / task 追踪 ----------

    fun onTurnStarted(sessionId: String, turnId: Long) {
        turnStarts.getOrPut(sessionId) { HashMap() }[turnId] = System.currentTimeMillis()
        // 新回合：重置连读计数（Kimi Code ReadGroup 按回合内连续 Read 聚合）
        readBursts[sessionId] = 0
    }

    /** 返回该 turn 的运行毫秒数（本地记录优先，其次服务器 durationMs） */
    fun onTurnEnded(sessionId: String, turnId: Long, durationMs: Long?): Long? {
        val start = turnStarts[sessionId]?.remove(turnId)
        return when {
            start != null -> System.currentTimeMillis() - start
            durationMs != null && durationMs > 0 -> durationMs
            else -> null
        }
    }

    fun onTaskStarted(taskId: String, startedAtMs: Long?, kind: String?, description: String?) {
        runningTasks = runningTasks + (taskId to TaskRun(
            startedAtMs ?: System.currentTimeMillis(), kind, description
        ))
    }

    fun onTaskTerminated(taskId: String): TaskRun? {
        val removed = runningTasks[taskId]
        runningTasks = runningTasks - taskId
        return removed
    }

    fun clearAllTasks() {
        runningTasks = emptyMap()
    }

    // ---------- 会话 phase + 详情（v0.5 扩展 / v0.5.3 官方文案载荷） ----------

    /** v0.5.3：toolCallId → 工具名（tool.result 需要回查名称） */
    private val toolCallNames = HashMap<String, String>()

    /** v0.5.3：当前连续 Read 调用数（Kimi Code ReadGroup: Reading N files…） */
    private val readBursts = HashMap<String, Int>()

    /** v0.5.3：思考流最后更新时刻（区分 Thinking… / Working…） */
    private val thinkingLastAt = HashMap<String, Long>()

    fun onAgentStatus(
        sessionId: String,
        phaseKind: String?,
        toolName: String?,
        usage: UsageStatus? = null,
        planMode: Boolean? = null,
        swarmMode: Boolean? = null,
        contextTokens: Long? = null,
        maxContextTokens: Long? = null,
        contextUsage: Double? = null,
        model: String? = null,
        retryNextAttempt: Int? = null,
        retryMaxAttempts: Int? = null,
        retryDelayMs: Long? = null,
        retryErrorName: String? = null,
        retryStatusCode: Int? = null
    ) {
        val cur = _sessionPhases.value[sessionId]
        // 相位切换语义（与 Kimi Code streamingPhase 一致）：
        // - 进入 tool_call：保留最近一次 tool.call.started 的 display 细节
        // - 回到 running/idle：清空工具细节（工具已结束，回到思考/生成）
        val keepTool = phaseKind == "tool_call"
        putPhase(
            sessionId,
            SessionPhase(
                phaseKind = phaseKind ?: cur?.phaseKind,
                toolName = if (keepTool) (toolName ?: cur?.toolName) else toolName,
                displayKind = if (keepTool) cur?.displayKind else null,
                displayOperation = if (keepTool) cur?.displayOperation else null,
                command = if (keepTool) cur?.command else null,
                path = if (keepTool) cur?.path else null,
                argsJson = if (keepTool) cur?.argsJson else null,
                query = if (keepTool) cur?.query else null,
                url = if (keepTool) cur?.url else null,
                agentName = if (keepTool) cur?.agentName else null,
                skillName = if (keepTool) cur?.skillName else null,
                summary = if (keepTool) cur?.summary else null,
                description = if (keepTool) cur?.description else null,
                taskId = if (keepTool) cur?.taskId else null,
                objective = if (keepTool) cur?.objective else null,
                retryNextAttempt = if (phaseKind == "retrying") (retryNextAttempt ?: cur?.retryNextAttempt) else retryNextAttempt,
                retryMaxAttempts = if (phaseKind == "retrying") (retryMaxAttempts ?: cur?.retryMaxAttempts) else retryMaxAttempts,
                retryDelayMs = if (phaseKind == "retrying") (retryDelayMs ?: cur?.retryDelayMs) else retryDelayMs,
                retryErrorName = if (phaseKind == "retrying") (retryErrorName ?: cur?.retryErrorName) else retryErrorName,
                retryStatusCode = if (phaseKind == "retrying") (retryStatusCode ?: cur?.retryStatusCode) else retryStatusCode,
                resultIsError = false,
                resultLine = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        if (usage != null || planMode != null || swarmMode != null || contextTokens != null || model != null) {
            _sessionDetails.update { map ->
                val curDetail = map[sessionId] ?: SessionDetailState(sessionId)
                map + (sessionId to curDetail.copy(
                    usage = usage ?: curDetail.usage,
                    planMode = planMode ?: curDetail.planMode,
                    swarmMode = swarmMode ?: curDetail.swarmMode,
                    contextTokens = contextTokens ?: curDetail.contextTokens,
                    maxContextTokens = maxContextTokens ?: curDetail.maxContextTokens,
                    contextUsage = contextUsage ?: curDetail.contextUsage,
                    model = model ?: curDetail.model,
                    updatedAtMs = System.currentTimeMillis()
                ))
            }
        }
        recomputeWidget()
        recomputeLiveTasks()
    }

    fun onToolCallStarted(
        sessionId: String,
        toolCallId: String? = null,
        toolName: String? = null,
        displayKind: String? = null,
        displayOperation: String? = null,
        command: String? = null,
        path: String? = null,
        todoItems: List<TodoItem>? = null,
        argsJson: JsonObject? = null,
        displayQuery: String? = null,
        displayUrl: String? = null,
        displayAgentName: String? = null,
        displaySkillName: String? = null,
        displaySummary: String? = null,
        displayDescription: String? = null,
        displayTaskId: String? = null,
        displayObjective: String? = null
    ) {
        val cur = _sessionPhases.value[sessionId]
        putPhase(
            sessionId,
            SessionPhase(
                phaseKind = "tool_call",
                toolName = toolName ?: cur?.toolName,
                displayKind = displayKind ?: cur?.displayKind,
                displayOperation = displayOperation ?: cur?.displayOperation,
                command = command ?: cur?.command,
                path = path ?: cur?.path,
                argsJson = argsJson ?: cur?.argsJson,
                query = displayQuery ?: cur?.query,
                url = displayUrl ?: cur?.url,
                agentName = displayAgentName ?: cur?.agentName,
                skillName = displaySkillName ?: cur?.skillName,
                summary = displaySummary ?: cur?.summary,
                description = displayDescription ?: cur?.description,
                taskId = displayTaskId ?: cur?.taskId,
                objective = displayObjective ?: cur?.objective,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        // 记录 toolCallId → 工具名（tool.result 回查）
        if (toolCallId != null && toolName != null) {
            toolCallNames[toolCallId] = toolName
            if (toolCallNames.size > 256) {
                // 防止无限增长：粗略保留一半
                val keep = toolCallNames.entries.toList().takeLast(128)
                toolCallNames.clear()
                keep.forEach { (k, v) -> toolCallNames[k] = v }
            }
        }
        // Kimi Code ReadGroup：连续 Read 调用聚合成 Reading N files…
        if (toolName == "Read" || toolName == "ReadMediaFile") {
            readBursts[sessionId] = (readBursts[sessionId] ?: 0) + 1
        } else if (toolName != null) {
            readBursts[sessionId] = 0
        }
        if (todoItems != null) {
            _sessionDetails.update { map ->
                val curDetail = map[sessionId] ?: SessionDetailState(sessionId)
                map + (sessionId to curDetail.copy(todos = todoItems, updatedAtMs = System.currentTimeMillis()))
            }
        }
        // 工具调用开始：思考文本结束
        if (toolName != null || displayKind != null) {
            _thinkingTexts.update { it - sessionId }
            thinkingLastAt.remove(sessionId)
        }
        recomputeWidget()
        recomputeLiveTasks()
    }

    /** v0.5.3：tool.result —— 工具完成（Kimi Code: ✓/✗ Used X + 结果首行） */
    fun onToolResult(
        sessionId: String,
        toolCallId: String,
        outputFirstLine: String?,
        isError: Boolean
    ) {
        val cur = _sessionPhases.value[sessionId] ?: return
        // 仅当该结果对应"当前相位中的工具"时才展示完成态（旧工具的迟到结果忽略）
        val name = toolCallNames[toolCallId] ?: cur.toolName ?: return
        if (cur.phaseKind != "tool_call") return
        putPhase(
            sessionId,
            cur.copy(
                phaseKind = "tool_done",
                toolName = name,
                resultIsError = isError,
                resultLine = outputFirstLine,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        recomputeWidget()
        recomputeLiveTasks()
    }

    /** v0.5.3：当前会话待审批工具名（Kimi Code 审批头：Run this command? 等） */
    fun pendingApprovalToolNameOf(sessionId: String): String? =
        _pendingApprovals.value.values.firstOrNull { it.session_id == sessionId }?.tool_name

    /** v0.5.3：连续 Read 计数（Reading N files…） */
    fun readBurstOf(sessionId: String): Int = readBursts[sessionId] ?: 0

    /** v0.5.3：思考流最后更新时刻（null = 无思考流） */
    fun thinkingUpdatedAtOf(sessionId: String): Long? = thinkingLastAt[sessionId]

    /** turn 结束时清理 phase */
    fun onSessionIdle(sessionId: String) {
        putPhase(sessionId, SessionPhase(phaseKind = "idle", updatedAtMs = System.currentTimeMillis()))
        _thinkingTexts.update { it - sessionId }
        thinkingLastAt.remove(sessionId)
        readBursts.remove(sessionId)
        recomputeLiveTasks()
    }

    /** v0.5.2：thinking.delta 流式拼接（仅主 agent，保留尾部 400 字符，500ms 节流） */
    private val thinkingLastEmit = HashMap<String, Long>()

    fun onThinkingDelta(sessionId: String, agentId: String, delta: String) {
        if (agentId != "main" || delta.isBlank()) return
        val now = System.currentTimeMillis()
        thinkingLastAt[sessionId] = now
        if (now - (thinkingLastEmit[sessionId] ?: 0) < 500) return
        thinkingLastEmit[sessionId] = now
        _thinkingTexts.update { map ->
            val cur = map[sessionId].orEmpty()
            map + (sessionId to (cur + delta).takeLast(400))
        }
    }

    // ---------- 详情回填（REST 快照） ----------

    /** 详情页进入时以 transcript / status / goal REST 快照整体回填 */
    fun applyDetailSnapshot(
        sessionId: String,
        detail: SessionDetailState
    ) {
        _sessionDetails.update { map ->
            // REST 快照可覆盖本地状态（打开详情页时主动拉取）
            map + (sessionId to detail)
        }
        recomputeLiveTasks()
    }

    fun updateTodos(sessionId: String, todos: List<TodoItem>) {
        _sessionDetails.update { map ->
            val cur = map[sessionId] ?: SessionDetailState(sessionId)
            map + (sessionId to cur.copy(todos = todos, updatedAtMs = System.currentTimeMillis()))
        }
        recomputeLiveTasks()
    }

    fun onGoalUpdated(sessionId: String, goal: GoalSnapshot?) {
        _sessionDetails.update { map ->
            val cur = map[sessionId] ?: SessionDetailState(sessionId)
            map + (sessionId to cur.copy(goal = goal, updatedAtMs = System.currentTimeMillis()))
        }
        recomputeLiveTasks()
    }

    fun onSubagentUpdated(
        sessionId: String,
        subagentId: String,
        state: String,
        swarmIndex: Int? = null,
        label: String? = null,
        resultSummary: String? = null,
        error: String? = null,
        usage: TokenUsage? = null
    ) {
        _sessionDetails.update { map ->
            val cur = map[sessionId] ?: SessionDetailState(sessionId)
            val existing = cur.subagents[subagentId]
            val info = SubagentInfo(
                subagentId = subagentId,
                swarmIndex = swarmIndex ?: existing?.swarmIndex,
                label = label ?: existing?.label,
                state = state,
                resultSummary = resultSummary ?: existing?.resultSummary,
                error = error ?: existing?.error,
                usage = usage ?: existing?.usage,
                updatedAtMs = System.currentTimeMillis()
            )
            map + (sessionId to cur.copy(
                subagents = cur.subagents + (subagentId to info),
                updatedAtMs = System.currentTimeMillis()
            ))
        }
        recomputeLiveTasks()
    }

    // ---------- 套餐用量 ----------

    fun setUsageLoading() {
        _usage.update { it.copy(loading = true) }
    }

    fun setUsageResult(quota: UsageQuota?, error: String?) {
        _usage.value = UsageUiState(
            quota = quota,
            error = error,
            loading = false,
            fetchedAtMs = System.currentTimeMillis()
        )
    }

    // ---------- 派生 ----------

    /** 是否有任意会话在忙 */
    fun anyBusy(): Boolean = _sessions.value.values.any { it.busy }

    /** 最早的 busy 起始时间（用于"已运行 X 分钟"） */
    fun earliestBusySince(): Long? = busySince.values.minOrNull()

    /** 有待审批 / 待回答的会话 id 集合 */
    fun interactiveSessionIds(): Set<String> = _sessions.value.values
        .filter { it.pending_interaction == "approval" || it.pending_interaction == "question" }
        .map { it.id }
        .toSet()

    /** 最近活跃（busy 或有 pending）的会话标题 */
    fun mostActiveSessionTitle(): String? {
        val candidates = _sessions.value.values.filter { it.busy || it.pending_interaction == "approval" }
        val withPhase = candidates.sortedByDescending { _sessionPhases.value[it.id]?.updatedAtMs ?: 0 }
        return withPhase.firstOrNull()?.displayTitle
    }

    /** 读取某会话 phase（UI 展示"思考中/执行命令"等） */
    fun phaseOf(sessionId: String): SessionPhase? = _sessionPhases.value[sessionId]

    fun busySinceOf(sessionId: String): Long? = busySince[sessionId]

    /** v0.5.2：读取某会话的实时思考文本（详情页展示用） */
    fun thinkingOf(sessionId: String): String? = _thinkingTexts.value[sessionId]

    /** v0.5.2：当前忙碌会话的实时状态（按开始时间升序，最早在前 → 横幅优先级） */
    private fun recomputeLiveTasks() {
        val sessions = _sessions.value
        val phases = _sessionPhases.value
        val details = _sessionDetails.value
        val list = sessions.values
            .filter { it.busy }
            .map { s ->
                val phase = phases[s.id]
                val detail = details[s.id]
                LiveTaskStatus(
                    sessionId = s.id,
                    title = s.displayTitle,
                    cwd = s.metadata?.cwd,
                    busy = true,
                    startedAtMs = busySince[s.id],
                    pendingInteraction = s.pending_interaction,
                    phaseKind = phase?.phaseKind,
                    toolName = phase?.toolName,
                    displayKind = phase?.displayKind,
                    command = phase?.command,
                    path = phase?.path,
                    todoDone = detail?.todos?.takeIf { it.isNotEmpty() }?.count { it.status == "done" },
                    todoTotal = detail?.todos?.takeIf { it.isNotEmpty() }?.size,
                    swarmActive = detail?.subagents?.values
                        ?.count { it.state == "spawned" || it.state == "working" }
                        ?.takeIf { detail.subagents.isNotEmpty() },
                    swarmMode = detail?.swarmMode ?: false,
                    goalObjective = detail?.goal?.takeIf { it.status == "active" }?.objective,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            .sortedWith(compareBy({ it.startedAtMs ?: Long.MAX_VALUE }, { it.sessionId }))
        _liveTasks.value = list
    }

    private fun recomputeWidget() {
        val conn = _connection.value
        val newStatus: WidgetStatus
        var toolLabel: String? = null
        var detailText: String? = null
        var todoDone: Int? = null
        var todoTotal: Int? = null

        if (conn != ConnState.CONNECTED) {
            newStatus = WidgetStatus.OFFLINE
        } else if (_pendingApprovals.value.isNotEmpty()) {
            newStatus = WidgetStatus.WAITING_APPROVAL
        } else {
            val busySession = _sessions.value.values
                .filter { it.busy }
                .maxByOrNull { _sessionPhases.value[it.id]?.updatedAtMs ?: 0 }
            if (busySession == null) {
                newStatus = WidgetStatus.IDLE
            } else {
                val phase = _sessionPhases.value[busySession.id]
                when {
                    phase == null || phase.phaseKind == "running" -> newStatus = WidgetStatus.THINKING
                    phase.phaseKind == "awaiting_approval" -> newStatus = WidgetStatus.WAITING_APPROVAL
                    phase.phaseKind == "tool_call" || phase.phaseKind == "tool_done" || phase.phaseKind == "retrying" -> {
                        when (phase.displayKind) {
                            "command" -> newStatus = WidgetStatus.RUNNING_SHELL
                            "file_io", "diff" -> newStatus = WidgetStatus.WRITING_FILES
                            else -> {
                                newStatus = WidgetStatus.RUNNING_TOOL
                                toolLabel = phase.toolName
                            }
                        }
                    }
                    else -> newStatus = WidgetStatus.THINKING
                }
                // v0.5.2：实时细节文本（命令片段 / 文件路径 / 工具名，截断防溢出）
                detailText = when (phase?.displayKind) {
                    "command" -> phase?.command?.take(48)
                    "file_io", "diff" -> phase?.path?.take(48)
                    else -> phase?.toolName
                }
                // v0.5.2：todo 进度
                val todos = _sessionDetails.value[busySession.id]?.todos
                if (!todos.isNullOrEmpty()) {
                    todoDone = todos.count { it.status == "done" }
                    todoTotal = todos.size
                }
            }
        }

        val busyCount = _sessions.value.values.count { it.busy }
        val old = _widget.value
        val since = if (busyCount > 0) {
            val keepable = old.status != WidgetStatus.IDLE && old.status != WidgetStatus.OFFLINE
            if (keepable) old.sinceMs else System.currentTimeMillis()
        } else 0

        val next = WidgetUiState(
            status = newStatus,
            toolLabel = toolLabel,
            detailText = detailText,
            todoDone = todoDone,
            todoTotal = todoTotal,
            runningTasks = busyCount,
            sinceMs = since,
            updatedAtMs = System.currentTimeMillis()
        )

        // 去重：状态实质未变化（仅时间戳不同）则不发射，避免每个 WS 事件都触发
        // 一次 Glance 重绘排队——排队时后续更新会互相推迟，造成小组件更新迟滞。
        if (old.status == next.status &&
            old.toolLabel == next.toolLabel &&
            old.detailText == next.detailText &&
            old.todoDone == next.todoDone &&
            old.todoTotal == next.todoTotal &&
            old.runningTasks == next.runningTasks &&
            old.sinceMs == next.sinceMs
        ) return

        _widget.value = next
    }

    fun resetTransient() {
        _pendingApprovals.value = emptyMap()
        _pendingQuestions.value = emptyMap()
        turnStarts.clear()
        busySince.clear()
        thinkingLastEmit.clear()
        thinkingLastAt.clear()
        toolCallNames.clear()
        readBursts.clear()
        runningTasks = emptyMap()
        _sessionPhases.value = emptyMap()
        _sessionDetails.value = emptyMap()
        _thinkingTexts.value = emptyMap()
        _liveTasks.value = emptyList()
        _usage.value = UsageUiState()
        recomputeWidget()
    }
}
