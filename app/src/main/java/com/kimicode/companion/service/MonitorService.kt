package com.kimicode.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.ConnState
import com.kimicode.companion.data.NotifySettings
import com.kimicode.companion.data.ReminderChannel
import com.kimicode.companion.data.WsEvent
import com.kimicode.companion.data.WorkNotifMode
import com.kimicode.companion.notify.Notifications
import com.kimicode.companion.overlay.FloatingWindowController
import com.kimicode.companion.overlay.FloatingWindowController.FloatingPrompt
import com.kimicode.companion.overlay.FloatingWindowController.PromptKind
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.kimicode.companion.widget.StatusWidget
import com.kimicode.companion.widget.WidgetStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 监控前台服务：
 * - 持有到 kap-server 的 WebSocket 连接（REST+WS 同源鉴权）
 * - 审批 / 问题 → 强提醒通知（锁屏可见、可直接操作）
 * - turn 结束 / 后台任务结束 → 完成通知（防打扰：前台静默 + 最短时长）
 * - 任意会话忙碌 → 常驻"正在工作"通知：极简模式保持原通知不变；
 *   v0.5.4 详细模式改为悬浮窗面板（FloatingWindowService，需悬浮窗权限），
 *   FGS 通知降级为基础文案，详细内容全部由悬浮窗承载
 * - 任务实时横幅通知（v0.5.2）：最早任务 heads-up 横幅常驻，较晚任务常驻通知栏；
 *   展示工具调用 / 思考 / todo 进度 / swarm 个数 / goal
 * - 全部空闲（且无待交互、App 不在前台）60s 后自动退出，撤掉常驻通知
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.kimicode.companion.action.START"
        const val ACTION_RECONNECT = "com.kimicode.companion.action.RECONNECT"
        const val ACTION_STOP = "com.kimicode.companion.action.STOP"
        const val ACTION_REFRESH_WORK = "com.kimicode.companion.action.REFRESH_WORK"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, MonitorService::class.java).apply { this.action = action }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private lateinit var app: KimiApp
    private val api get() = app.api
    private val repository get() = app.repository

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // 防崩溃：任何子协程未捕获异常不再导致应用整体闪退
            CoroutineExceptionHandler { _, throwable ->
                android.util.Log.w("MonitorService", "uncaught in service scope", throwable)
            }
    )
    private var ws: com.kimicode.companion.data.KimiWs? = null

    private var collectorsStarted = false
    private var idleStopJob: Job? = null

    /** v0.5.5：完成提醒卡展示期宽限（等提示卡 5s 展示完再优雅退出） */
    private var completionGraceJob: Job? = null

    /** v0.5.2：通知设置（设置页实时同步） */
    @Volatile
    private var notifyPrefs = NotifySettings()

    /** v0.5.2：已发布任务通知的会话 → 是否横幅位（撤除 / 角色切换时用） */
    private var postedTaskRoles: Map<String, Boolean> = emptyMap()

    /** 有桌面小组件时的空闲续命计数：最多额外存活 10 分钟后仍全空闲才退出 */
    @Volatile
    private var widgetGraceRetries = 0

    override fun onCreate() {
        super.onCreate()
        app = KimiApp.of(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                enterForeground(getString(R.string.notif_work_connecting))
                restartConnection()
                return START_STICKY
            }
            ACTION_REFRESH_WORK -> {
                // v0.5.4：设置页授权返回等场景 → 仅重算通知/悬浮窗，不重连
                if (!collectorsStarted) startCollectors() else {
                    updateTaskNotifications()
                    updateWorkNotification()
                    updateFloatingWindow()
                }
                return START_NOT_STICKY
            }
            else -> {
                enterForeground(getString(R.string.notif_work_connecting))
                if (!collectorsStarted) startCollectors() else updateWorkNotification()
                if (ws == null) startConnection()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ws?.stop()
        // v0.5.2：服务销毁时撤掉所有任务实时通知
        runCatching {
            Notifications.cancelAllTaskNotifications(this, postedTaskRoles.keys)
            postedTaskRoles = emptyMap()
        }
        // v0.5.4：服务销毁时同步撤掉悬浮窗
        runCatching { FloatingWindowController.hide(this) }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- 前台 ----------

    private fun enterForeground(text: String) {
        val notification = Notifications.buildWork(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    // ---------- 连接生命周期 ----------

    private fun startConnection() {
        repository.resetTransient()
        repository.setConnection(ConnState.CONNECTING)
        // 防崩溃：WS 启动（URL/网络栈异常）不再向外抛
        runCatching {
            ws = com.kimicode.companion.data.KimiWs(serviceScope, repository.wsEvents).also {
                it.start(api.config)
            }
        }
    }

    private fun restartConnection() {
        ws?.stop()
        ws = null
        repository.resetTransient()
        repository.setConnection(ConnState.CONNECTING)
        runCatching {
            ws = com.kimicode.companion.data.KimiWs(serviceScope, repository.wsEvents).also {
                it.start(api.config)
            }
        }
    }

    // ---------- 事件与状态收集 ----------

    private fun startCollectors() {
        collectorsStarted = true

        // WS 事件 → 领域处理
        serviceScope.launch {
            repository.wsEvents.collect { event -> runCatching { handleEvent(event) } }
        }

        // v0.5.2：通知设置（DataStore）实时同步 → 立即重发各类常驻通知
        serviceScope.launch {
            runCatching {
                app.settings.settings.collect { s ->
                    notifyPrefs = s.notify
                    updateTaskNotifications()
                    updateWorkNotification()
                    updateFloatingWindow()
                }
            }
        }

        // v0.5.5：提示卡状态变化（弹出 / 清除）→ 重算悬浮窗显隐
        serviceScope.launch {
            FloatingWindowController.prompt.collect {
                runCatching { updateFloatingWindow() }
            }
        }

        // v0.5.2：实时任务列表（按开始时间排序）→ 横幅/常驻任务通知 + 详细模式工作通知/悬浮窗
        serviceScope.launch {
            repository.liveTasks.collect {
                runCatching {
                    updateTaskNotifications()
                    updateWorkNotification()
                    updateFloatingWindow()
                }
            }
        }

        // 小组件状态 → 推送（v0.5：全链路防崩溃）
        serviceScope.launch {
            repository.widget.collect { state ->
                runCatching { WidgetStore.write(this@MonitorService, state) }
                runCatching {
                    GlanceAppWidgetManager(this@MonitorService)
                        .getGlanceIds(StatusWidget::class.java)
                        .forEach { StatusWidget().update(this@MonitorService, it) }
                }
            }
        }

        // v0.5：套餐用量刷新（连接成功立即 + 每 5 分钟周期）
        serviceScope.launch {
            var first = true
            while (isActive) {
                if (repository.connection.value == ConnState.CONNECTED) {
                    runCatching { refreshUsage() }
                }
                delay(if (first) 2_000 else 300_000)
                first = false
            }
        }

        // 常驻通知：连接中 / 正在工作·已运行 X 分钟 / 待命（详细模式由 liveTasks 驱动 + 计时器）
        serviceScope.launch {
            while (isActive) {
                updateWorkNotification()
                delay(30_000)
            }
        }

        // 周期性检查是否可以退出（全空闲 + 无待交互）
        serviceScope.launch {
            while (isActive) {
                delay(20_000)
                scheduleIdleStopCheck()
            }
        }
    }

    /** v0.5：拉取套餐用量（/oauth/usage，上游不可用时降级为错误文案） */
    private suspend fun refreshUsage() {
        val result = runCatching { api.getUsage() }.getOrNull()
        when {
            result == null -> repository.setUsageResult(null, "用量信息获取失败")
            result.first != null -> repository.setUsageResult(result.first, null)
            else -> repository.setUsageResult(null, result.second ?: "用量信息暂不可用")
        }
    }

    private fun updateWorkNotification() {
        val conn = repository.connection.value
        if (conn != ConnState.CONNECTED) {
            val text = when (conn) {
                ConnState.CONNECTING -> getString(R.string.notif_work_connecting)
                else -> getString(R.string.notif_work_disconnected)
            }
            Notifications.updateWork(this, text, disconnected = conn == ConnState.DISCONNECTED)
            return
        }
        // v0.5.4：详细模式 —— 详细状态由悬浮窗承载（updateFloatingWindow 调度），
        // FGS 通知仅保留基础文案（前台服务必须持有通知）；极简模式 —— 原文案不变
        if (notifyPrefs.workMode == WorkNotifMode.DETAILED) {
            Notifications.updateWork(this, getString(R.string.notif_work_service_running))
            return
        }
        val text = if (repository.anyBusy()) {
            val since = repository.earliestBusySince() ?: System.currentTimeMillis()
            val dur = Notifications.formatDuration(this, System.currentTimeMillis() - since)
            getString(R.string.notif_work_running, dur)
        } else {
            getString(R.string.notif_work_idle)
        }
        Notifications.updateWork(this, text, disconnected = false)
    }

    /**
     * v0.5.5：悬浮窗调度。
     * - 显示：悬浮窗权限 + 用户未退出 +（详细模式有任务 或 有待展示提示卡）
     * - 隐藏：优雅退出（窗口可见时先播放缩小消失动画）或直接 hide
     *   · 最后一个任务刚完成且完成提醒卡在展示 → 宽限 5s+，等提示卡过期再退出
     *   · 极简模式 / 未授权 → 不显示（极简模式职责仍在通知，完全不受影响）
     */
    private fun updateFloatingWindow() {
        val tasks = repository.liveTasks.value
        val overlayAllowed = android.provider.Settings.canDrawOverlays(this)
        val prompt = FloatingWindowController.prompt.value
        val wantForWork = notifyPrefs.workMode == WorkNotifMode.DETAILED && tasks.isNotEmpty()
        val wantForPrompt = prompt != null && notifyPrefs.reminderChannel == ReminderChannel.FLOATING
        if ((wantForWork || wantForPrompt) && overlayAllowed && !FloatingWindowController.userExited) {
            completionGraceJob?.cancel()
            FloatingWindowController.cancelExit()
            FloatingWindowController.show(this)
        } else {
            if (tasks.isEmpty()) FloatingWindowController.resetUserExit()
            if (FloatingWindowController.windowVisible) {
                // 窗口可见 → 优雅退出（缩小消失动画）；退出已在进行则不重复请求
                if (!FloatingWindowController.exitRequested.value) {
                    if (prompt?.kind == PromptKind.COMPLETION) {
                        // 完成提醒卡展示期：等它 5s 过期后再退出
                        scheduleCompletionGraceExit()
                    } else {
                        FloatingWindowController.requestExit()
                    }
                }
            } else {
                FloatingWindowController.hide(this)
            }
        }
    }

    /** 完成提醒卡宽限：等提示卡自动过期（5s）+ 形变收起后重新评估退出 */
    private fun scheduleCompletionGraceExit() {
        if (completionGraceJob?.isActive == true) return
        completionGraceJob = serviceScope.launch {
            delay(5_800)
            runCatching { updateFloatingWindow() }
        }
    }

    /**
     * v0.5.5：提醒路由 —— 审批 / 问题 / 完成 提醒统一选择悬浮窗提示卡或系统通知。
     * - 提醒通道 = 悬浮窗 + 已授权 + 用户未退出 → 提示卡（窗口不在时先拉起）
     * - 其余情况降级为系统通知（提醒不丢失）
     */
    private fun routeReminder(floating: () -> Unit, notification: () -> Unit) {
        val overlayAllowed = android.provider.Settings.canDrawOverlays(this)
        val floatingUsable = notifyPrefs.reminderChannel == ReminderChannel.FLOATING &&
            overlayAllowed && !FloatingWindowController.userExited
        if (floatingUsable) {
            floating()
            if (!FloatingWindowController.windowVisible) FloatingWindowController.show(this)
            else runCatching { updateFloatingWindow() }
        } else {
            notification()
        }
    }

    /**
     * v0.5.2：任务实时通知调度。
     * - 横幅开关关闭 → 撤除全部
     * - 最早任务（liveTasks[0]）→ task_banner 通道（heads-up 横幅，划走后常驻通知栏）
     * - 其余任务 → task_bg 通道（常驻通知栏，不弹横幅）
     * - 任务结束 / 角色切换（最早任务结束后的晋升）→ 撤旧发新
     */
    private fun updateTaskNotifications() {
        val tasks = repository.liveTasks.value
        if (!notifyPrefs.bannerEnabled || tasks.isEmpty()) {
            if (postedTaskRoles.isNotEmpty()) {
                Notifications.cancelAllTaskNotifications(this, postedTaskRoles.keys)
                postedTaskRoles = emptyMap()
            }
            return
        }
        val newRoles = tasks.mapIndexed { idx, t -> t.sessionId to (idx == 0) }.toMap()
        // 撤除：已结束的任务，或角色变化（需先 cancel 才能换通道）
        postedTaskRoles.forEach { (sid, wasBanner) ->
            val nowBanner = newRoles[sid]
            if (nowBanner == null || nowBanner != wasBanner) {
                runCatching { Notifications.cancelTask(this, sid) }
            }
        }
        // 发布/更新
        tasks.forEachIndexed { idx, t ->
            runCatching { Notifications.updateTaskStatus(this, t, banner = idx == 0) }
        }
        postedTaskRoles = newRoles
    }

    private suspend fun handleEvent(event: WsEvent) {
        when (event) {
            WsEvent.Connected -> {
                repository.setConnection(ConnState.CONNECTED)
                refreshSessionsAndSubscribe()
                resyncApprovals()
            }

            WsEvent.Disconnected -> repository.setConnection(ConnState.DISCONNECTED)

            WsEvent.ResyncNeeded -> {
                refreshSessionsAndSubscribe()
                resyncApprovals()
            }

            is WsEvent.ApprovalRequested -> {
                repository.addApproval(event.approval)
                val title = repository.sessions.value[event.sessionId]?.displayTitle.orEmpty()
                if (notifyPrefs.approvalEnabled) {
                    routeReminder(
                        floating = {
                            FloatingWindowController.showPrompt(
                                FloatingPrompt(
                                    id = "approval-${event.approval.approval_id}",
                                    kind = PromptKind.APPROVAL,
                                    sessionId = event.approval.session_id,
                                    taskTitle = title,
                                    toolName = event.approval.tool_name,
                                    actionSummary = event.approval.displayInfo()?.oneLiner(),
                                    approvalId = event.approval.approval_id
                                )
                            )
                        },
                        notification = { Notifications.showApproval(this, event.approval, title) }
                    )
                }
            }

            is WsEvent.ApprovalResolved -> {
                repository.removeApproval(event.approvalId)
                FloatingWindowController.clearPromptIfCurrent("approval-${event.approvalId}")
                Notifications.cancelApproval(this, event.approvalId)
            }

            is WsEvent.QuestionRequested -> {
                repository.addQuestion(
                    com.kimicode.companion.data.QuestionRequest(
                        question_id = event.questionId,
                        session_id = event.sessionId,
                        questions = listOf(
                            com.kimicode.companion.data.QuestionForm(question = event.firstQuestion)
                        )
                    )
                )
                val title = repository.sessions.value[event.sessionId]?.displayTitle.orEmpty()
                if (notifyPrefs.questionEnabled) {
                    routeReminder(
                        floating = {
                            FloatingWindowController.showPrompt(
                                FloatingPrompt(
                                    id = "question-${event.questionId}",
                                    kind = PromptKind.QUESTION,
                                    sessionId = event.sessionId,
                                    taskTitle = title,
                                    questionText = event.firstQuestion,
                                    questionId = event.questionId
                                )
                            )
                        },
                        notification = {
                            Notifications.showQuestion(this, event.questionId, event.firstQuestion, title)
                        }
                    )
                }
            }

            is WsEvent.QuestionResolved -> {
                repository.removeQuestion(event.questionId)
                FloatingWindowController.clearPromptIfCurrent("question-${event.questionId}")
                Notifications.cancelQuestion(this, event.questionId)
            }

            is WsEvent.SessionCreated -> {
                repository.upsertSession(event.session)
                ws?.subscribe(listOf(event.session.id))
            }

            is WsEvent.WorkChanged -> {
                repository.onWorkChanged(
                    event.sessionId, event.busy, event.pendingInteraction, event.lastTurnReason
                )
            }

            is WsEvent.TurnStarted -> {
                repository.onTurnStarted(event.sessionId, event.turnId)
            }

            is WsEvent.TurnEnded -> {
                val durationMs = repository.onTurnEnded(
                    event.sessionId, event.turnId, event.durationMs
                )
                if (event.reason != "blocked") {
                    val sessionTitle = repository.sessions.value[event.sessionId]?.displayTitle
                        ?: "Kimi 会话"
                    if (notifyPrefs.completionEnabled && completionAllowed(durationMs)) {
                        // v0.5.5：悬浮窗提醒 → 完成卡展示 5s 后自动缩回；
                        // 最后一个任务结束时由 updateFloatingWindow 宽限调度优雅退出
                        routeReminder(
                            floating = {
                                FloatingWindowController.showPrompt(
                                    FloatingPrompt(
                                        id = "completion-${event.sessionId}-${event.turnId}",
                                        kind = PromptKind.COMPLETION,
                                        sessionId = event.sessionId,
                                        taskTitle = sessionTitle,
                                        resultKind = event.reason,
                                        durationLabel = durationMs?.let {
                                            Notifications.formatDuration(this, it)
                                        }
                                    ),
                                    autoExpireMs = 5_000
                                )
                            },
                            notification = {
                                Notifications.showCompletion(
                                    this, event.reason, sessionTitle,
                                    durationMs?.let { it / 60_000 }, event.sessionId
                                )
                            }
                        )
                    }
                }
                if (event.reason != "completed") {
                    // 回合被取消/失败：挂起的审批随 turn 结束作废
                    repository.onSessionIdle(event.sessionId)
                }
            }

            is WsEvent.TaskStarted -> {
                repository.onTaskStarted(
                    event.taskId, event.startedAtMs, event.kind, event.description
                )
            }

            is WsEvent.TaskTerminated -> {
                val run = repository.onTaskTerminated(event.taskId)
                if (event.kind != "question") {
                    val sessionTitle = repository.sessions.value[event.sessionId]?.displayTitle
                        ?: "Kimi 会话"
                    val started = event.startedAtMs ?: run?.startedMs
                    if (notifyPrefs.completionEnabled && completionAllowed(started?.let { System.currentTimeMillis() - it })) {
                        routeReminder(
                            floating = {
                                FloatingWindowController.showPrompt(
                                    FloatingPrompt(
                                        id = "task-${event.taskId}",
                                        kind = PromptKind.COMPLETION,
                                        sessionId = event.sessionId,
                                        taskTitle = event.description ?: run?.description ?: sessionTitle,
                                        resultKind = event.status,
                                        durationLabel = started?.let {
                                            Notifications.formatDuration(
                                                this, System.currentTimeMillis() - it
                                            )
                                        }
                                    ),
                                    autoExpireMs = 5_000
                                )
                            },
                            notification = {
                                Notifications.showTaskTerminated(
                                    this, event.status, event.description ?: run?.description,
                                    sessionTitle, event.taskId
                                )
                            }
                        )
                    }
                }
            }

            is WsEvent.AgentStatus -> {
                repository.onAgentStatus(
                    event.sessionId, event.phaseKind, event.toolName,
                    event.usage, event.planMode, event.swarmMode,
                    event.contextTokens, event.maxContextTokens, event.contextUsage,
                    event.model,
                    event.retryNextAttempt, event.retryMaxAttempts, event.retryDelayMs,
                    event.retryErrorName, event.retryStatusCode
                )
            }

            is WsEvent.ToolCallStarted -> {
                repository.onToolCallStarted(
                    event.sessionId, event.toolCallId, event.toolName,
                    event.displayKind, event.displayOperation,
                    event.command, event.path, event.todoItems,
                    event.argsJson,
                    event.displayQuery, event.displayUrl, event.displayAgentName,
                    event.displaySkillName, event.displaySummary, event.displayDescription,
                    event.displayTaskId, event.displayObjective
                )
            }

            is WsEvent.ToolResult -> {
                // v0.5.3：工具完成（Kimi Code: ✓/✗ Used X + 结果首行）
                repository.onToolResult(
                    event.sessionId, event.toolCallId,
                    event.outputFirstLine, event.isError
                )
            }

            is WsEvent.GoalUpdated -> {
                repository.onGoalUpdated(event.sessionId, event.goal)
            }

            is WsEvent.SubagentUpdated -> {
                repository.onSubagentUpdated(
                    event.sessionId, event.subagentId, event.state,
                    event.swarmIndex, event.label, event.resultSummary, event.error, event.usage
                )
            }

            is WsEvent.ThinkingDelta -> {
                // v0.5.2：实时思考流（详情页展示，500ms 节流拼接）
                repository.onThinkingDelta(event.sessionId, event.agentId, event.delta)
            }
        }
    }

    // ---------- 防打扰 ----------

    /** 完成提醒是否放行：前台静默 + 最短运行时长 */
    private fun completionAllowed(durationMs: Long?): Boolean {
        val config = api.config
        if (config.dndForegroundSilence && KimiApp.instance.isAppInForeground) return false
        if (durationMs != null) {
            val minutes = durationMs / 60_000
            if (minutes < config.dndMinDurationMin) return false
        }
        return true
    }

    // ---------- 会话刷新与审批对账 ----------

    private suspend fun refreshSessionsAndSubscribe() {
        val sessions = runCatching { api.listSessions() }.getOrNull() ?: return
        repository.updateSessions(sessions)
        ws?.subscribe(sessions.map { it.id })
        // v0.5：为正在忙的会话回填一次详情快照（transcript 聚合 usage/todo/goal）
        sessions.filter { it.busy }.take(3).forEach { s ->
            runCatching { backfillDetail(s.id) }
        }
    }

    /** 会话详情 REST 快照回填（transcript + goal） */
    private suspend fun backfillDetail(sessionId: String) {
        val transcript = runCatching { api.getTranscript(sessionId) }.getOrNull()
        if (transcript != null) {
            val meta = transcript.meta
            val current = repository.sessionDetails.value[sessionId]
                ?: com.kimicode.companion.data.SessionDetailState(sessionId)
            repository.applyDetailSnapshot(
                sessionId,
                current.copy(
                    usage = meta?.agent?.usage ?: current.usage,
                    contextTokens = meta?.agent?.contextTokens ?: current.contextTokens,
                    maxContextTokens = meta?.agent?.maxContextTokens ?: current.maxContextTokens,
                    contextUsage = meta?.agent?.contextUsage ?: current.contextUsage,
                    model = meta?.agent?.model ?: current.model,
                    thinkingEffort = meta?.agent?.thinkingEffort ?: current.thinkingEffort,
                    planMode = meta?.modes?.plan != null || current.planMode,
                    swarmMode = meta?.modes?.swarm != null || current.swarmMode,
                    todos = transcript.todos.lastOrNull()?.items ?: current.todos,
                    goal = current.goal,
                    subagents = current.subagents
                )
            )
        }
        // goal 独立拉取（transcript 的 meta.goal 只有摘要）
        runCatching { api.getGoal(sessionId) }.getOrNull()?.let { goal ->
            repository.onGoalUpdated(sessionId, goal)
        }
    }

    private suspend fun resyncApprovals() {
        val targets = repository.sessions.value.values.filter {
            it.pending_interaction == "approval" || it.pending_interaction == "question"
        }
        targets.forEach { s ->
            when (s.pending_interaction) {
                "approval" -> {
                    runCatching { api.listPendingApprovals(s.id) }.getOrNull()?.forEach { a ->
                        if (a.session_id.isBlank()) return@forEach
                        repository.addApproval(a)
                        if (notifyPrefs.approvalEnabled) {
                            routeReminder(
                                floating = {
                                    FloatingWindowController.showPrompt(
                                        FloatingPrompt(
                                            id = "approval-${a.approval_id}",
                                            kind = PromptKind.APPROVAL,
                                            sessionId = a.session_id,
                                            taskTitle = s.displayTitle,
                                            toolName = a.tool_name,
                                            actionSummary = a.displayInfo()?.oneLiner(),
                                            approvalId = a.approval_id
                                        )
                                    )
                                },
                                notification = { Notifications.showApproval(this, a, s.displayTitle) }
                            )
                        }
                    }
                }
                "question" -> {
                    runCatching { api.listPendingQuestions(s.id) }.getOrNull()?.forEach { q ->
                        repository.addQuestion(q)
                        if (notifyPrefs.questionEnabled) {
                            q.questions.firstOrNull()?.question?.let { first ->
                                routeReminder(
                                    floating = {
                                        FloatingWindowController.showPrompt(
                                            FloatingPrompt(
                                                id = "question-${q.question_id}",
                                                kind = PromptKind.QUESTION,
                                                sessionId = q.session_id ?: s.id,
                                                taskTitle = s.displayTitle,
                                                questionText = first,
                                                questionId = q.question_id
                                            )
                                        )
                                    },
                                    notification = {
                                        Notifications.showQuestion(this, q.question_id, first, s.displayTitle)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 空闲自动退出 ----------

    /** 用户是否在桌面放置了状态小组件（决定空闲时是否续命保活） */
    private suspend fun hasWidgets(): Boolean = runCatching {
        GlanceAppWidgetManager(this).getGlanceIds(StatusWidget::class.java).isNotEmpty()
    }.getOrDefault(false)

    private fun scheduleIdleStopCheck() {
        val busy = repository.anyBusy()
        val interactive = repository.pendingApprovals.value.isNotEmpty() ||
            repository.pendingQuestions.value.isNotEmpty()
        if (busy || interactive) {
            idleStopJob?.cancel()
            idleStopJob = null
            // 有活动 → 重置小组件续命额度
            widgetGraceRetries = 0
            return
        }
        if (idleStopJob?.isActive == true) return
        idleStopJob = serviceScope.launch {
            delay(60_000)
            val stillIdle = !repository.anyBusy() &&
                repository.pendingApprovals.value.isEmpty() &&
                repository.pendingQuestions.value.isEmpty()
            if (stillIdle && !KimiApp.instance.isAppInForeground) {
                // 修复：用户在平桌面放置了小组件时，全空闲不再 60s 即退——
                // 每次额外续命 60s，最多 10 次（约 10 分钟）；期间任意新活动会重置。
                // 超时后仍空闲才真正退出，退出后由小组件 30 分钟周期更新唤醒重连。
                val widgetsPresent = hasWidgets()
                if (widgetsPresent && widgetGraceRetries < 10) {
                    widgetGraceRetries++
                    idleStopJob = null
                    scheduleIdleStopCheck()
                    return@launch
                }
                repository.setConnection(ConnState.DISCONNECTED)
                Notifications.cancelWork(this@MonitorService)
                runCatching {
                    Notifications.cancelAllTaskNotifications(this@MonitorService, postedTaskRoles.keys)
                    postedTaskRoles = emptyMap()
                }
                runCatching { FloatingWindowController.hide(this@MonitorService) }
                stopForegroundCompat()
                stopSelf()
            } else if (stillIdle) {
                widgetGraceRetries = 0
            }
        }
    }
}
