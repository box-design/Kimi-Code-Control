package com.kimicode.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 从 WS 事件流解析出的、应用关心的领域事件 */
sealed interface WsEvent {
    data object Connected : WsEvent
    data object Disconnected : WsEvent
    data object ResyncNeeded : WsEvent

    data class ApprovalRequested(
        val sessionId: String,
        val approvalId: String,
        val approval: ApprovalRequest
    ) : WsEvent

    data class ApprovalResolved(
        val sessionId: String,
        val approvalId: String,
        val decision: String?
    ) : WsEvent

    data class QuestionRequested(
        val sessionId: String,
        val questionId: String,
        val firstQuestion: String
    ) : WsEvent

    data class QuestionResolved(val sessionId: String, val questionId: String) : WsEvent

    data class SessionCreated(val session: Session) : WsEvent

    /** 会话忙闲聚合事件（全局广播）：busy / pending_interaction / last_turn_reason */
    data class WorkChanged(
        val sessionId: String,
        val busy: Boolean,
        val pendingInteraction: String?,
        val lastTurnReason: String?
    ) : WsEvent

    data class TurnStarted(val sessionId: String, val agentId: String, val turnId: Long) : WsEvent

    data class TurnEnded(
        val sessionId: String,
        val agentId: String,
        val turnId: Long,
        val reason: String,
        val durationMs: Long?
    ) : WsEvent

    data class TaskStarted(
        val sessionId: String,
        val taskId: String,
        val startedAtMs: Long?,
        val kind: String?,
        val description: String?
    ) : WsEvent

    data class TaskTerminated(
        val sessionId: String,
        val taskId: String,
        val status: String?,
        val startedAtMs: Long?,
        val kind: String?,
        val description: String?
    ) : WsEvent

    /** agent.status.updated：phase 是 idle/running/tool_call/...；v0.5 附带 usage / 模式开关
     *  v0.5.3：携带 retrying 相位详情（Retrying (n/m) · Error · in Xs，与 Kimi Code 官方口径一致） */
    data class AgentStatus(
        val sessionId: String,
        val agentId: String,
        val phaseKind: String?,
        val toolName: String?,
        val usage: UsageStatus? = null,
        val planMode: Boolean? = null,
        val swarmMode: Boolean? = null,
        val contextTokens: Long? = null,
        val maxContextTokens: Long? = null,
        val contextUsage: Double? = null,
        val model: String? = null,
        // retrying 相位详情（phase.kind == "retrying" 时存在）
        val retryNextAttempt: Int? = null,
        val retryMaxAttempts: Int? = null,
        val retryDelayMs: Long? = null,
        val retryErrorName: String? = null,
        val retryStatusCode: Int? = null
    ) : WsEvent

    /** tool.call.started：display.kind 用于区分 Running shell / Write files；todo_list 携带待办
     *  v0.5.3：携带 toolCallId / args / 完整 display 载荷（官方文案引擎 KimiStatus 需要） */
    data class ToolCallStarted(
        val sessionId: String,
        val agentId: String,
        val toolCallId: String? = null,
        val toolName: String?,
        val displayKind: String?,
        val displayOperation: String?,
        val command: String?,
        val path: String?,
        val todoItems: List<TodoItem>? = null,
        // ---- v0.5.3 完整 display / args（Kimi Code 官方状态文案） ----
        val argsJson: JsonObject? = null,
        val displayQuery: String? = null,        // search / WebSearch
        val displayUrl: String? = null,          // url_fetch / FetchURL
        val displayAgentName: String? = null,    // agent_call
        val displaySkillName: String? = null,    // skill_call
        val displaySummary: String? = null,      // generic
        val displayDescription: String? = null,
        val displayTaskId: String? = null,       // task / task_stop / WaitFor
        val displayTaskDescription: String? = null,
        val displayObjective: String? = null     // goal_start
    ) : WsEvent

    /** v0.5.3：tool.result —— 工具执行完成（Used ✓/✗ + 结果首行，对应 Kimi Code 工具卡片完成态） */
    data class ToolResult(
        val sessionId: String,
        val agentId: String,
        val toolCallId: String,
        val outputFirstLine: String?,
        val isError: Boolean
    ) : WsEvent

    /** goal.updated：goal 快照（null 表示目标已清除） */
    data class GoalUpdated(
        val sessionId: String,
        val goal: GoalSnapshot?
    ) : WsEvent

    /** subagent.*：swarm 子代理生命周期（spawned/started/suspended/completed/failed/cancelled） */
    data class SubagentUpdated(
        val sessionId: String,
        val subagentId: String,
        val state: String,               // spawned | working | suspended | completed | failed | cancelled
        val swarmIndex: Int? = null,
        val label: String? = null,       // v0.5.3：子代理名（Kimi Code formatSubagentLabel）
        val resultSummary: String? = null,
        val error: String? = null,
        val usage: TokenUsage? = null
    ) : WsEvent

    /** v0.5.2：thinking.delta：agent 思考流式文本（仅主 agent 有展示价值） */
    data class ThinkingDelta(
        val sessionId: String,
        val agentId: String,
        val turnId: Long?,
        val delta: String
    ) : WsEvent
}

/**
 * kap-server WebSocket 客户端。
 * - 鉴权：Authorization: Bearer（OkHttp 可直接带 header）
 * - 握手：server_hello → client_hello
 * - 心跳：ping → pong（nonce 回传），否则 20s 被踢
 * - 订阅：subscribe { session_ids }（全局事件无需订阅，所有连接可见）
 * - 重连：指数退避 1s..30s
 */
class KimiWs(
    private val scope: CoroutineScope,
    private val events: kotlinx.coroutines.flow.MutableSharedFlow<WsEvent>
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // 传输层保活（与应用层 ping/pong JSON 帧无关）
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var config: ServerConfig? = null

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val started = AtomicBoolean(false)
    private val helloDone = AtomicBoolean(false)

    private var retryAttempt = 0

    fun start(config: ServerConfig) {
        if (!started.compareAndSet(false, true)) return
        this.config = config
        connect()
    }

    fun stop() {
        started.set(false)
        reconnectJob?.cancel()
        webSocket?.close(1000, "client stopped")
        webSocket = null
    }

    fun updateConfig(config: ServerConfig) {
        this.config = config
    }

    private fun connect() {
        val cfg = config ?: return
        if (!started.get()) return
        // 防崩溃：URL 非法 / header 非法字符时不能抛出（OkHttp 会直接 throw）
        val request = runCatching {
            Request.Builder()
                .url(cfg.wsUrl)
                .apply {
                    if (cfg.token.isNotBlank()) header("Authorization", "Bearer ${cfg.token.trim()}")
                }
                .build()
        }.getOrNull() ?: run {
            scheduleReconnect()
            return
        }
        runCatching { webSocket = client.newWebSocket(request, Listener()) }
    }

    /** 订阅指定会话（全局事件无需订阅） */
    fun subscribe(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        send(
            buildJsonObject {
                put("type", "subscribe")
                put("id", UUID.randomUUID().toString())
                put("payload", buildJsonObject {
                    put("session_ids", kotlinx.serialization.json.buildJsonArray {
                        sessionIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                })
            }
        )
    }

    private fun sendHello() {
        send(
            buildJsonObject {
                put("type", "client_hello")
                put("id", UUID.randomUUID().toString())
                put("payload", buildJsonObject {
                    put("client_id", "kimi-code-android")
                })
            }
        )
    }

    private fun send(frame: JsonObject) {
        webSocket?.send(frame.toString())
    }

    private fun scheduleReconnect() {
        if (!started.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 本地回环服务重连代价低：退避封顶 10s（原 30s 会让小组件长时间滞留在旧状态）
            val delayMs = when {
                retryAttempt <= 0 -> 1_000L
                retryAttempt == 1 -> 2_000L
                retryAttempt == 2 -> 4_000L
                retryAttempt == 3 -> 6_000L
                else -> 10_000L
            }
            delay(delayMs)
            retryAttempt++
            connect()
        }
    }

    // ---------- 帧解析 ----------

    private fun onText(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root["type"]?.jsonPrimitive?.contentOrNullSafe) {
            "server_hello" -> {
                helloDone.set(false)
                sendHello()
                helloDone.set(true)
                retryAttempt = 0
                events.tryEmit(WsEvent.Connected)
            }

            "ping" -> {
                val nonce = root["payload"]?.jsonObject?.get("nonce")?.jsonPrimitive?.contentOrNullSafe
                send(
                    buildJsonObject {
                        put("type", "pong")
                        put("payload", buildJsonObject {
                            if (nonce != null) put("nonce", nonce)
                        })
                    }
                )
            }

            "ack" -> {
                val payload = root["payload"]?.jsonObject
                val resync = payload?.get("resync_required")
                    as? kotlinx.serialization.json.JsonArray
                if (!resync.isNullOrEmpty()) events.tryEmit(WsEvent.ResyncNeeded)
            }

            "resync_required" -> events.tryEmit(WsEvent.ResyncNeeded)

            else -> parseEvent(root)
        }
    }

    /** 事件帧：{type, seq, volatile, session_id, timestamp, payload:{...}} */
    private fun parseEvent(root: JsonObject) {
        val type = root["type"]?.jsonPrimitive?.contentOrNullSafe ?: return
        val payload = root["payload"]?.jsonObject ?: return
        val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNullSafe
            ?: root["session_id"]?.jsonPrimitive?.contentOrNullSafe
            ?: payload["session_id"]?.jsonPrimitive?.contentOrNullSafe

        when (type) {
            "event.approval.requested" -> {
                var approval = runCatching {
                    json.decodeFromJsonElement(ApprovalRequest.serializer(), payload)
                }.getOrNull() ?: return
                if (approval.session_id.isBlank()) {
                    val sid = payload["sessionId"]?.jsonPrimitive?.contentOrNullSafe ?: return
                    approval = approval.copy(session_id = sid)
                }
                events.tryEmit(
                    WsEvent.ApprovalRequested(approval.session_id, approval.approval_id, approval)
                )
            }

            "event.approval.resolved" -> {
                events.tryEmit(
                    WsEvent.ApprovalResolved(
                        sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNullSafe
                            ?: sessionId ?: return,
                        approvalId = payload["approval_id"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                        decision = payload["decision"]?.jsonPrimitive?.contentOrNullSafe
                    )
                )
            }

            "event.question.requested" -> {
                val questionId = payload["question_id"]?.jsonPrimitive?.contentOrNullSafe ?: return
                val first = (payload["questions"] as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()
                    ?.let { q ->
                        (q as? JsonObject)?.get("question")?.jsonPrimitive?.contentOrNullSafe
                    }
                events.tryEmit(
                    WsEvent.QuestionRequested(
                        sessionId ?: return, questionId, first ?: "Kimi 正在等待你的回答"
                    )
                )
            }

            "event.question.answered", "event.question.dismissed" -> {
                val questionId = payload["question_id"]?.jsonPrimitive?.contentOrNullSafe ?: return
                events.tryEmit(WsEvent.QuestionResolved(sessionId ?: return, questionId))
            }

            "event.session.created" -> {
                val session = payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement(Session.serializer(), it.jsonObject) }.getOrNull()
                }
                if (session != null) events.tryEmit(WsEvent.SessionCreated(session))
            }

            "event.session.work_changed" -> {
                events.tryEmit(
                    WsEvent.WorkChanged(
                        sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                        busy = payload["busy"]?.jsonPrimitive?.contentOrNullSafe.asBool(),
                        pendingInteraction = payload["pending_interaction"]?.jsonPrimitive?.contentOrNullSafe,
                        lastTurnReason = payload["last_turn_reason"]?.jsonPrimitive?.contentOrNullSafe
                    )
                )
            }

            "turn.started" -> {
                events.tryEmit(
                    WsEvent.TurnStarted(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        turnId = payload["turnId"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull() ?: return
                    )
                )
            }

            "turn.ended" -> {
                events.tryEmit(
                    WsEvent.TurnEnded(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        turnId = payload["turnId"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull() ?: return,
                        reason = payload["reason"]?.jsonPrimitive?.contentOrNullSafe ?: "completed",
                        durationMs = payload["durationMs"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull()
                    )
                )
            }

            "task.started", "background.task.started" -> {
                val info = payload["info"]?.jsonObject
                events.tryEmit(
                    WsEvent.TaskStarted(
                        sessionId = sessionId ?: return,
                        taskId = info?.get("taskId")?.jsonPrimitive?.contentOrNullSafe ?: return,
                        startedAtMs = info?.get("startedAt")?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        kind = info?.get("kind")?.jsonPrimitive?.contentOrNullSafe,
                        description = info?.get("description")?.jsonPrimitive?.contentOrNullSafe
                    )
                )
            }

            "task.terminated", "background.task.terminated" -> {
                val info = payload["info"]?.jsonObject
                events.tryEmit(
                    WsEvent.TaskTerminated(
                        sessionId = sessionId ?: return,
                        taskId = info?.get("taskId")?.jsonPrimitive?.contentOrNullSafe ?: return,
                        status = info?.get("status")?.jsonPrimitive?.contentOrNullSafe,
                        startedAtMs = info?.get("startedAt")?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        kind = info?.get("kind")?.jsonPrimitive?.contentOrNullSafe,
                        description = info?.get("description")?.jsonPrimitive?.contentOrNullSafe
                    )
                )
            }

            "agent.status.updated" -> {
                val phase = payload["phase"]?.jsonObject
                val usage = payload["usage"]?.let { parseUsageStatus(it) }
                events.tryEmit(
                    WsEvent.AgentStatus(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        phaseKind = phase?.get("kind")?.jsonPrimitive?.contentOrNullSafe,
                        toolName = phase?.get("name")?.jsonPrimitive?.contentOrNullSafe,
                        usage = usage,
                        planMode = payload["planMode"]?.jsonPrimitive?.booleanOrNull,
                        swarmMode = payload["swarmMode"]?.jsonPrimitive?.booleanOrNull,
                        contextTokens = payload["contextTokens"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        maxContextTokens = payload["maxContextTokens"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        contextUsage = payload["contextUsage"]?.jsonPrimitive?.contentOrNullSafe?.toDoubleOrNull(),
                        model = payload["model"]?.jsonPrimitive?.contentOrNullSafe,
                        retryNextAttempt = phase?.get("nextAttempt")?.jsonPrimitive?.contentOrNullSafe?.toIntOrNull(),
                        retryMaxAttempts = phase?.get("maxAttempts")?.jsonPrimitive?.contentOrNullSafe?.toIntOrNull(),
                        retryDelayMs = phase?.get("delayMs")?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        retryErrorName = phase?.get("errorName")?.jsonPrimitive?.contentOrNullSafe,
                        retryStatusCode = phase?.get("statusCode")?.jsonPrimitive?.contentOrNullSafe?.toIntOrNull()
                    )
                )
            }

            "tool.call.started" -> {
                val display = payload["display"]?.jsonObject
                // display.kind=todo_list 时提取待办列表（实时 todo 更新通道）
                val todoItems = if (display?.get("kind")?.jsonPrimitive?.contentOrNullSafe == "todo_list") {
                    (display["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                        runCatching {
                            json.decodeFromJsonElement(TodoItem.serializer(), el)
                        }.getOrNull()
                    }?.takeIf { it.isNotEmpty() }
                } else null
                events.tryEmit(
                    WsEvent.ToolCallStarted(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        toolCallId = payload["toolCallId"]?.jsonPrimitive?.contentOrNullSafe,
                        toolName = payload["name"]?.jsonPrimitive?.contentOrNullSafe,
                        displayKind = display?.get("kind")?.jsonPrimitive?.contentOrNullSafe,
                        displayOperation = display?.get("operation")?.jsonPrimitive?.contentOrNullSafe,
                        command = display?.get("command")?.jsonPrimitive?.contentOrNullSafe,
                        path = display?.get("path")?.jsonPrimitive?.contentOrNullSafe,
                        todoItems = todoItems,
                        argsJson = payload["args"] as? JsonObject,
                        displayQuery = display?.get("query")?.jsonPrimitive?.contentOrNullSafe,
                        displayUrl = display?.get("url")?.jsonPrimitive?.contentOrNullSafe,
                        displayAgentName = display?.get("agent_name")?.jsonPrimitive?.contentOrNullSafe,
                        displaySkillName = display?.get("skill_name")?.jsonPrimitive?.contentOrNullSafe,
                        displaySummary = display?.get("summary")?.jsonPrimitive?.contentOrNullSafe,
                        displayDescription = display?.get("description")?.jsonPrimitive?.contentOrNullSafe,
                        displayTaskId = display?.get("task_id")?.jsonPrimitive?.contentOrNullSafe,
                        displayTaskDescription = display?.get("task_description")?.jsonPrimitive?.contentOrNullSafe,
                        displayObjective = display?.get("objective")?.jsonPrimitive?.contentOrNullSafe
                    )
                )
            }

            "tool.result" -> {
                events.tryEmit(
                    WsEvent.ToolResult(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        toolCallId = payload["toolCallId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                        // 结果首行（Write: "Wrote N bytes to path"、Edit: "Replaced N occurrences in path"…）
                        outputFirstLine = payload["output"]?.let { firstLineOf(it) },
                        isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                )
            }

            "goal.updated" -> {
                val snap = payload["snapshot"]?.let {
                    if (it is kotlinx.serialization.json.JsonNull) null
                    else runCatching {
                        json.decodeFromJsonElement(GoalSnapshot.serializer(), it.jsonObject)
                    }.getOrNull()
                }
                events.tryEmit(WsEvent.GoalUpdated(sessionId ?: return, snap))
            }

            "subagent.spawned" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "spawned",
                    swarmIndex = payload["swarmIndex"]?.jsonPrimitive?.contentOrNullSafe?.toIntOrNull(),
                    label = payload["subagentName"]?.jsonPrimitive?.contentOrNullSafe
                )
            )

            "subagent.started" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "working"
                )
            )

            "subagent.suspended" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "suspended",
                    error = payload["reason"]?.jsonPrimitive?.contentOrNullSafe
                )
            )

            "subagent.completed" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "completed",
                    resultSummary = payload["resultSummary"]?.jsonPrimitive?.contentOrNullSafe,
                    usage = payload["usage"]?.let { parseTokenUsage(it) }
                )
            )

            "subagent.failed" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "failed",
                    error = payload["error"]?.jsonPrimitive?.contentOrNullSafe
                )
            )

            "subagent.cancelled" -> events.tryEmit(
                WsEvent.SubagentUpdated(
                    sessionId = sessionId ?: return,
                    subagentId = payload["subagentId"]?.jsonPrimitive?.contentOrNullSafe ?: return,
                    state = "cancelled"
                )
            )

            "thinking.delta" -> {
                val delta = payload["delta"]?.jsonPrimitive?.contentOrNullSafe ?: return
                events.tryEmit(
                    WsEvent.ThinkingDelta(
                        sessionId = sessionId ?: return,
                        agentId = payload["agentId"]?.jsonPrimitive?.contentOrNullSafe ?: "main",
                        turnId = payload["turnId"]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull(),
                        delta = delta
                    )
                )
            }
        }
    }

    /** 解析 agent.status.updated.usage：{byModel, currentTurn, total}（camelCase） */
    private fun parseUsageStatus(element: kotlinx.serialization.json.JsonElement): UsageStatus? {
        val obj = element as? JsonObject ?: return null
        return runCatching {
            val byModel = (obj["byModel"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) ->
                parseTokenUsage(v)?.let { k to it }
            }?.toMap() ?: emptyMap()
            UsageStatus(
                byModel = byModel,
                currentTurn = obj["currentTurn"]?.let { parseTokenUsage(it) },
                total = obj["total"]?.let { parseTokenUsage(it) }
            )
        }.getOrNull()
    }

    /** 解析 tokenUsage：{inputOther, output, inputCacheRead, inputCacheCreation} */
    private fun parseTokenUsage(element: kotlinx.serialization.json.JsonElement): TokenUsage? {
        val obj = element as? JsonObject ?: return null
        fun l(k: String): Long = obj[k]?.jsonPrimitive?.contentOrNullSafe?.toLongOrNull() ?: 0L
        return runCatching {
            TokenUsage(
                inputOther = l("inputOther"),
                output = l("output"),
                inputCacheRead = l("inputCacheRead"),
                inputCacheCreation = l("inputCacheCreation")
            )
        }.getOrNull()
    }

    /** tool.result 的 output：字符串取首行；对象/数组取其 JSON 文本首行；截断到 160 字符 */
    private fun firstLineOf(element: kotlinx.serialization.json.JsonElement): String? {
        val raw = when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNullSafe
            is kotlinx.serialization.json.JsonNull -> null
            else -> runCatching { element.toString() }.getOrNull()
        } ?: return null
        val line = raw
            .replace("\\n", "\n")
            .split('\n')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null
        return if (line.length > 160) line.take(159) + "…" else line
    }

    // ---------- WebSocket 生命周期 ----------

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 等待 server_hello 后再发 client_hello（在 onText 中处理）
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onText(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (started.get()) {
                events.tryEmit(WsEvent.Disconnected)
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (started.get()) {
                events.tryEmit(WsEvent.Disconnected)
                scheduleReconnect()
            }
        }
    }
}

/** jsonPrimitive.content 的空安全包装 */
private val kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

/** 字符串转布尔（"true" → true，其余 false） */
private fun String?.asBool(): Boolean = this == "true"
