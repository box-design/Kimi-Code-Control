package com.kimicode.companion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Kimi Code 服务器（kap-server）线协议模型。
 * 所有字段名与服务器 snake_case 保持一致，未知字段忽略（ignoreUnknownKeys）。
 */

// ---------- 统一响应信封 { code, msg, data, request_id } ----------

@Serializable
data class ApiEnvelope(
    val code: Int = -1,
    val msg: String? = null,
    val data: JsonElement? = null,
    val request_id: String? = null
)

// ---------- 服务器元信息 ----------

@Serializable
data class ServerCapabilities(
    val websocket: Boolean? = null,
    val file_upload: Boolean? = null
)

@Serializable
data class ServerMeta(
    val server_version: String? = null,
    val server_id: String? = null,
    val started_at: String? = null,
    val capabilities: ServerCapabilities? = null,
    val dangerous_bypass_auth: Boolean? = null
)

// ---------- 会话 ----------

@Serializable
data class SessionMetadata(
    val cwd: String? = null
)

@Serializable
data class SessionAgentConfig(
    val model: String? = null,
    val permission_mode: String? = null
)

@Serializable
data class Session(
    val id: String,
    val workspace_id: String? = null,
    val title: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val busy: Boolean = false,
    val main_turn_active: Boolean? = null,
    val pending_interaction: String? = null, // none | approval | question
    val last_turn_reason: String? = null,     // completed | cancelled | failed
    val archived: Boolean? = null,
    val last_prompt: String? = null,
    val metadata: SessionMetadata? = null,
    val message_count: Int? = null
) {
    val displayTitle: String
        get() = title.ifBlank { last_prompt?.take(36)?.let { "「$it」" } ?: "未命名会话" }
}

@Serializable
data class SessionListData(
    val items: List<Session> = emptyList(),
    val has_more: Boolean = false
)

@Serializable
data class Workspace(
    val id: String,
    val root: String,
    val name: String? = null,
    val session_count: Int? = null,
    val last_opened_at: String? = null
) {
    val displayName: String get() = name ?: root.substringAfterLast('/')
}

// ---------- 审批 ----------

@Serializable
data class ToolInputDisplay(
    val kind: String? = null,       // command | file_io | diff | search | url_fetch | ...
    val command: String? = null,
    val cwd: String? = null,
    val description: String? = null,
    val operation: String? = null,  // read | write | edit | glob | grep
    val path: String? = null,
    val detail: String? = null,
    val url: String? = null,
    val query: String? = null,
    val summary: String? = null
) {
    /** 通知里展示的一行摘要 */
    fun oneLiner(): String = when (kind) {
        "command" -> command ?: description ?: ""
        "file_io" -> listOfNotNull(operation?.let { "$it " }, path).joinToString("")
        "diff" -> "diff ${path ?: ""}"
        "search" -> "search ${query ?: ""}"
        "url_fetch" -> url ?: ""
        else -> description ?: summary ?: detail ?: ""
    }.take(220)

    companion object {
        /**
         * 从服务器返回的差异化联合 JSON 中安全提取展示字段。
         * 任何字段类型不符都不会抛异常。
         */
        fun fromJson(element: JsonElement?): ToolInputDisplay? {
            val obj = element as? JsonObject ?: return null
            fun s(k: String): String? = (obj[k] as? JsonPrimitive)?.contentOrNull
            return ToolInputDisplay(
                kind = s("kind"),
                command = s("command"),
                cwd = s("cwd"),
                description = s("description"),
                operation = s("operation"),
                path = s("path"),
                detail = s("detail"),
                url = s("url"),
                query = s("query"),
                summary = s("summary")
            )
        }
    }
}

@Serializable
data class ApprovalRequest(
    val approval_id: String,
    val session_id: String,
    val turn_id: Long? = null,
    val tool_call_id: String? = null,
    val tool_name: String = "",
    val action: String = "",
    // 差异化联合，保留原始 JSON，展示时用 [displayInfo] 安全提取
    val tool_input_display: JsonElement? = null,
    val created_at: String? = null,
    val expires_at: String? = null
) {
    /** 安全解析后的展示信息 */
    fun displayInfo(): ToolInputDisplay? = ToolInputDisplay.fromJson(tool_input_display)
}

@Serializable
data class ApprovalDecisionBody(
    val decision: String,             // approved | rejected | cancelled
    val scope: String? = null,        // "session" = 本会话内同类操作免审批
    val feedback: String? = null,
    val selected_label: String? = null
)

@Serializable
data class ApprovalListData(
    val items: List<ApprovalRequest> = emptyList()
)

// ---------- 问题（AskUserQuestion 工具） ----------

@Serializable
data class QuestionOption(
    val id: String? = null,
    val label: String = "",
    val description: String? = null
)

@Serializable
data class QuestionForm(
    val id: String? = null,
    val question: String = "",
    val options: List<QuestionOption> = emptyList()
)

@Serializable
data class QuestionRequest(
    val question_id: String,
    val session_id: String? = null,
    val questions: List<QuestionForm> = emptyList()
)

@Serializable
data class QuestionListData(
    val items: List<QuestionRequest> = emptyList()
)

// ---------- 消息 / 提示提交 ----------

@Serializable
data class PromptItem(
    val prompt_id: String = "",
    val user_message_id: String? = null,
    val status: String? = null
)

// ---------- 后台任务（REST，snake_case） ----------

@Serializable
data class TaskRestInfo(
    val id: String = "",
    val session_id: String? = null,
    val kind: String? = null,          // bash | subagent | tool
    val description: String = "",
    val status: String? = null,        // running | completed | failed | cancelled
    val command: String? = null,
    val created_at: String? = null,
    val completed_at: String? = null,
    val started_at: String? = null,
    val output_preview: String? = null,
    val model: String? = null,
    val subagent_type: String? = null,
    val run_in_background: Boolean? = null
)

// ============================================================
// v0.5.0 新增：套餐用量 / 会话状态 / Todo / Goal / Swarm / Token 用量
// 注意：以下模型对应 kap-server 的 camelCase 接口（oauth/usage、
// transcript、goal、agent.status.updated.usage），字段名与服务器一致；
// snake_case 接口（sessions/{sid}/status、tasks）单独标注。
// ============================================================

// ---------- 套餐用量 GET /api/v1/oauth/usage（camelCase） ----------

@Serializable
data class QuotaEntry(
    val usedRatio: Double = 0.0,       // 0..1
    val resetAt: String? = null
)

@Serializable
data class QuotaUsages(
    val limit5h: QuotaEntry? = null,
    val limit7d: QuotaEntry? = null,
    val monthTotal: QuotaEntry? = null,
    val monthCode: QuotaEntry? = null
)

@Serializable
data class BoosterWallet(
    val balanceCents: Long = 0,
    val totalCents: Long = 0,
    val monthlyChargeLimitEnabled: Boolean = false,
    val monthlyChargeLimitCents: Long = 0,
    val monthlyUsedCents: Long = 0,
    val currency: String = "CNY"
)

@Serializable
data class UsageOk(
    val kind: String = "ok",
    val quota: UsageQuota? = null
)

@Serializable
data class UsageQuota(
    val usages: QuotaUsages = QuotaUsages(),
    val extraUsage: BoosterWallet? = null
)

@Serializable
data class UsageError(
    val kind: String = "error",
    val message: String = "",
    val status: Int? = null
)

// ---------- 会话运行状态 GET /api/v1/sessions/{sid}/status（snake_case） ----------

@Serializable
data class SessionStatus(
    val busy: Boolean = false,
    val model: String? = null,
    val thinking_level: String? = null,
    val permission: String? = null,
    val plan_mode: Boolean = false,
    val swarm_mode: Boolean = false,
    val tower_mode: Boolean? = null,
    val context_tokens: Long = 0,
    val max_context_tokens: Long? = null,
    val context_usage: Double? = null  // 0..1
)

// ---------- Token 用量（camelCase，来自 transcript / agent.status.updated） ----------

@Serializable
data class TokenUsage(
    val inputOther: Long = 0,
    val output: Long = 0,
    val inputCacheRead: Long = 0,
    val inputCacheCreation: Long = 0
) {
    /** 输入 token = 未缓存输入 + 缓存写入（与官方口径一致） */
    val inputTokens: Long get() = inputOther + inputCacheCreation
    val totalTokens: Long get() = inputTokens + output + inputCacheRead
}

@Serializable
data class UsageStatus(
    val byModel: Map<String, TokenUsage> = emptyMap(),
    val currentTurn: TokenUsage? = null,
    val total: TokenUsage? = null
)

// ---------- Todo（camelCase，来自 transcript） ----------

@Serializable
data class TodoItem(
    val title: String = "",
    val status: String = "pending"     // pending | in_progress | done
)

@Serializable
data class TodoList(
    val todoId: String = "",
    val items: List<TodoItem> = emptyList(),
    val updatedAt: String? = null
)

// ---------- Goal（camelCase，GET /sessions/{sid}/goal 与 WS goal.updated） ----------

@Serializable
data class GoalBudgetReport(
    val tokenBudget: Long? = null,
    val turnBudget: Long? = null,
    val wallClockBudgetMs: Long? = null,
    val remainingTokens: Long? = null,
    val remainingTurns: Long? = null,
    val remainingWallClockMs: Long? = null,
    val tokenBudgetReached: Boolean = false,
    val turnBudgetReached: Boolean = false,
    val wallClockBudgetReached: Boolean = false,
    val overBudget: Boolean = false
)

@Serializable
data class GoalSnapshot(
    val goalId: String = "",
    val objective: String = "",
    val completionCriterion: String? = null,
    val status: String = "active",     // active | paused | blocked | complete
    val turnsUsed: Long = 0,
    val tokensUsed: Long = 0,
    val wallClockMs: Long = 0,
    val budget: GoalBudgetReport = GoalBudgetReport(),
    val terminalReason: String? = null
)

// ---------- Transcript（camelCase，GET /sessions/{sid}/transcript?agent_id=main） ----------

@Serializable
data class AgentDescriptor(
    val agentId: String = "",
    val type: String? = null,          // main | sub | independent
    val parentAgentId: String? = null,
    val label: String? = null,
    val createdAt: String? = null
)

@Serializable
data class GoalMeta(
    val objective: String = "",
    val status: String = "active",
    val completionCriterion: String? = null,
    val budgetUsed: Double? = null,
    val budgetLimit: Double? = null
)

@Serializable
data class SwarmMeta(
    val trigger: String? = null
)

@Serializable
data class PlanMeta(
    val reviewPath: String? = null,
    val version: Long? = null
)

@Serializable
data class ModesMeta(
    val plan: PlanMeta? = null,
    val swarm: SwarmMeta? = null
)

@Serializable
data class AgentStatusMeta(
    val model: String? = null,
    val thinkingEffort: String? = null,
    val usage: UsageStatus? = null,
    val contextTokens: Long? = null,
    val maxContextTokens: Long? = null,
    val contextUsage: Double? = null,
    val permission: String? = null
)

@Serializable
data class TranscriptMeta(
    val goal: GoalMeta? = null,
    val modes: ModesMeta? = null,
    val activity: String? = null,      // idle | turn | disposing | unknown
    val agent: AgentStatusMeta? = null
)

/** transcript 中运行中的后台任务（camelCase） */
@Serializable
data class TranscriptTask(
    val taskId: String = "",
    val kind: String? = null,          // shell | subagent | tool | other
    val state: String? = null,         // running | completed | failed | timed_out | killed | lost
    val detached: Boolean = false,
    val description: String? = null,
    val agentId: String? = null,
    val outputTail: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val resultSummary: String? = null,
    val usage: TokenUsage? = null,
    val model: String? = null
)

@Serializable
data class TranscriptData(
    val agent_id: String = "main",
    val has_more: Boolean = false,
    val todos: List<TodoList> = emptyList(),
    val tasks: List<TranscriptTask> = emptyList(),
    val meta: TranscriptMeta? = null,
    val agents: List<AgentDescriptor> = emptyList()
)

// ---------- 会话内 REST 任务列表（snake_case，GET /sessions/{sid}/tasks） ----------

@Serializable
data class TaskListData(
    val items: List<TaskRestInfo> = emptyList()
)

// ---------- WS: subagent 事件载荷（camelCase） ----------

@Serializable
data class SubagentInfo(
    val subagentId: String = "",
    val swarmIndex: Int? = null,
    val label: String? = null,
    val state: String = "working",     // working | suspended | completed | failed | cancelled | queued
    val resultSummary: String? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
    val contextTokens: Long? = null,
    val updatedAtMs: Long = 0
)
