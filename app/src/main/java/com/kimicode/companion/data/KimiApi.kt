package com.kimicode.companion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Kimi Code REST API 调用失败 */
class KimiApiException(val code: Int, message: String) : Exception(message)

/** REST 探测结果 */
sealed interface ProbeResult {
    data class Online(val version: String?, val bypassAuth: Boolean) : ProbeResult
    data class NeedToken(val version: String?) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

/**
 * kap-server REST 客户端（/api/v1）。
 * 统一信封 { code, msg, data }，code == 0 视为成功。
 */
class KimiApi {

    @Volatile
    var config: ServerConfig = ServerConfig()
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
    }

    fun updateConfig(newConfig: ServerConfig) {
        config = newConfig
    }

    // ---------- 基础 ----------

    private fun url(path: String): String = config.baseUrl + path

    private fun Request.Builder.auth(): Request.Builder {
        if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}")
        return this
    }

    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val envelope = runCatching { json.parseToJsonElement(body).jsonObject }
                .map { it.toEnvelope() }
                .getOrNull()
            if (!resp.isSuccessful) {
                if (resp.code == 401) throw KimiApiException(40101, "未授权：需要访问令牌")
                throw KimiApiException(
                    envelope?.code ?: resp.code,
                    envelope?.msg ?: "HTTP ${resp.code}"
                )
            }
            val env = envelope ?: throw KimiApiException(-1, "响应解析失败")
            if (env.code != 0) throw KimiApiException(env.code, env.msg ?: "错误 ${env.code}")
            env.data ?: buildJsonObject { }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.toEnvelope(): ApiEnvelope =
        json.decodeFromJsonElement(ApiEnvelope.serializer(), this)

    private suspend fun get(path: String): JsonElement =
        execute(Request.Builder().url(url(path)).auth().get().build())

    private suspend fun post(path: String, body: JsonElement? = null): JsonElement {
        val payload = (body ?: buildJsonObject { })
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return execute(Request.Builder().url(url(path)).auth().post(payload).build())
    }

    // ---------- 探测 / 元信息 ----------

    /** healthz 免认证；meta 需要 token。返回完整探测结论（不改变当前配置）。 */
    suspend fun probe(host: String, port: Int, token: String): ProbeResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = runCatching {
                    val req = Request.Builder()
                        .url("http://$host:$port/api/v1/healthz")
                        .get()
                        .build()
                    client.newCall(req).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (!ok) {
                    ProbeResult.Unreachable("无法访问 healthz")
                } else {
                    val bearer = token.takeIf { it.isNotBlank() }
                    val req = Request.Builder().url("http://$host:$port/api/v1/meta").apply {
                        if (bearer != null) header("Authorization", "Bearer $bearer")
                    }.get().build()
                    client.newCall(req).execute().use { resp ->
                        when {
                            resp.code == 401 -> ProbeResult.NeedToken(null)
                            resp.isSuccessful -> {
                                val data = resp.body?.string().orEmpty()
                                val meta = runCatching {
                                    json.decodeFromJsonElement(
                                        ServerMeta.serializer(),
                                        json.parseToJsonElement(data)
                                            .jsonObject["data"]!!.jsonObject
                                    )
                                }.getOrNull()
                                ProbeResult.Online(
                                    version = meta?.server_version,
                                    bypassAuth = meta?.dangerous_bypass_auth == true
                                )
                            }
                            else -> ProbeResult.Unreachable("meta HTTP ${resp.code}")
                        }
                    }
                }
            }.recover { ProbeResult.Unreachable(it.message ?: it.javaClass.simpleName) }
                .getOrThrow()
        }

    /** 端口扫描：从 start 开始尝试 count 个端口（kimi web 被占自动 +1） */
    suspend fun scanPort(host: String, start: Int, count: Int, token: String): Int? =
        withContext(Dispatchers.IO) {
            val bearer = token.takeIf { it.isNotBlank() }
            (start until start + count).forEach { port ->
                val reachable = runCatching {
                    val req = Request.Builder().url("http://$host:$port/api/v1/healthz").get().build()
                    client.newCall(req).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (reachable) {
                    // 确认 meta 也可用（可能是其他服务恰好在监听）
                    val metaOk = runCatching {
                        val req = Request.Builder().url("http://$host:$port/api/v1/meta").apply {
                            if (bearer != null) header("Authorization", "Bearer $bearer")
                        }.get().build()
                        client.newCall(req).execute().use { it.isSuccessful || it.code == 401 }
                    }.getOrDefault(false)
                    if (metaOk) return@withContext port
                }
            }
            null
        }

    suspend fun meta(): ServerMeta? = runCatching {
        json.decodeFromJsonElement(ServerMeta.serializer(), get("/api/v1/meta"))
    }.getOrNull()

    // ---------- 会话 ----------

    suspend fun listSessions(pageSize: Int = 100): List<Session> {
        val data = get("/api/v1/sessions?page_size=$pageSize&include_archive=false")
        val parsed = json.decodeFromJsonElement(SessionListData.serializer(), data)
        return parsed.items
    }

    suspend fun listWorkspaces(): List<Workspace> {
        val data = get("/api/v1/workspaces")
        val arr = data.jsonObject["items"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull {
            runCatching {
                json.decodeFromJsonElement(Workspace.serializer(), it)
            }.getOrNull()
        }
    }

    /**
     * 创建会话：优先使用已有工作区；没有工作区时回退到 Termux home 目录注册新工作区。
     */
    suspend fun createSession(title: String? = null, workspaceId: String? = null): Session {
        val body = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
            if (!workspaceId.isNullOrBlank()) put("workspace_id", workspaceId)
        }
        val data = post("/api/v1/sessions", body)
        return json.decodeFromJsonElement(Session.serializer(), data)
    }

    /** 无任何工作区时，用 Termux home 作为 cwd 建会话（服务器会自动注册工作区） */
    suspend fun createSessionWithCwd(title: String? = null, cwd: String): Session {
        val body = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
            put("metadata", buildJsonObject { put("cwd", cwd) })
        }
        val data = post("/api/v1/sessions", body)
        return json.decodeFromJsonElement(Session.serializer(), data)
    }

    // ---------- 发送消息 ----------

    /** 提交 prompt：content 为 [{type:"text"...}|{type:"image"...}] 构建的 JSON 数组 */
    suspend fun sendPrompt(sessionId: String, content: JsonElement): PromptItem {
        val body = buildJsonObject {
            put("content", content)
        }
        val data = post("/api/v1/sessions/$sessionId/prompts", body)
        return json.decodeFromJsonElement(PromptItem.serializer(), data)
    }

    suspend fun sendText(sessionId: String, text: String): PromptItem =
        sendPrompt(
            sessionId,
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }.let { kotlinx.serialization.json.buildJsonArray { add(it) } }
        )

    // ---------- 审批 ----------

    suspend fun listPendingApprovals(sessionId: String): List<ApprovalRequest> {
        val data = get("/api/v1/sessions/$sessionId/approvals?status=pending")
        val parsed = json.decodeFromJsonElement(ApprovalListData.serializer(), data)
        return parsed.items
    }

    /**
     * 响应审批。decision: approved | rejected | cancelled；
     * scope = "session" 表示本会话内同类操作免审批。
     */
    suspend fun decideApproval(
        sessionId: String,
        approvalId: String,
        decision: String,
        scope: String? = null,
        feedback: String? = null
    ): Boolean = runCatching {
        val body = buildJsonObject {
            put("decision", decision)
            if (scope != null) put("scope", scope)
            if (feedback != null) put("feedback", feedback)
        }
        post("/api/v1/sessions/$sessionId/approvals/$approvalId", body)
        true
    }.recover { e ->
        if (e is KimiApiException && e.code == 40902) true // 已被处理，视为成功
        else throw e
    }.getOrThrow()

    // ---------- 问题 ----------

    suspend fun listPendingQuestions(sessionId: String): List<QuestionRequest> {
        val data = get("/api/v1/sessions/$sessionId/questions?status=pending")
        val parsed = json.decodeFromJsonElement(QuestionListData.serializer(), data)
        return parsed.items
    }

    // ---------- 中止 ----------

    suspend fun abortSession(sessionId: String) {
        post("/api/v1/sessions/$sessionId::abort")
    }

    // ==========================================================
    // v0.5.0 新增：用量 / 状态 / transcript / goal / 任务 / 模式
    // ==========================================================

    /** 套餐用量：kind=ok 时返回配额，error 时返回 null + 错误信息（上游不可用不视为异常） */
    suspend fun getUsage(provider: String = "managed:kimi-code"): Pair<UsageQuota?, String?> {
        val data = get("/api/v1/oauth/usage?provider=$provider")
        val obj = data as? kotlinx.serialization.json.JsonObject ?: return null to "响应格式异常"
        return when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "ok" -> {
                val quota = runCatching {
                    json.decodeFromJsonElement(UsageQuota.serializer(), obj["quota"] ?: return null to "缺少 quota")
                }.getOrNull()
                quota to null
            }
            else -> {
                val msg = obj["message"]?.jsonPrimitive?.contentOrNull ?: "用量信息暂不可用"
                null to msg
            }
        }
    }

    /** 会话运行状态（含 plan/swarm 模式、上下文用量） */
    suspend fun getSessionStatus(sessionId: String): SessionStatus? = runCatching {
        json.decodeFromJsonElement(SessionStatus.serializer(), get("/api/v1/sessions/$sessionId/status"))
    }.getOrNull()

    /** 会话 transcript（主 agent）：todos / 任务 / meta（usage、goal、modes、agents） */
    suspend fun getTranscript(sessionId: String, agentId: String = "main"): TranscriptData? = runCatching {
        json.decodeFromJsonElement(TranscriptData.serializer(), get("/api/v1/sessions/$sessionId/transcript?agent_id=$agentId"))
    }.getOrNull()

    /** Goal 快照（未设置目标时返回 null） */
    suspend fun getGoal(sessionId: String): GoalSnapshot? = runCatching {
        val data = get("/api/v1/sessions/$sessionId/goal")
        if (data is kotlinx.serialization.json.JsonNull) return null
        json.decodeFromJsonElement(GoalSnapshot.serializer(), data)
    }.getOrNull()

    /** 会话内后台任务列表（live 注册表；冷会话返回空） */
    suspend fun listSessionTasks(sessionId: String, status: String? = null): List<TaskRestInfo> = runCatching {
        val path = if (status.isNullOrBlank()) {
            "/api/v1/sessions/$sessionId/tasks"
        } else {
            "/api/v1/sessions/$sessionId/tasks?status=$status"
        }
        json.decodeFromJsonElement(TaskListData.serializer(), get(path)).items
    }.getOrDefault(emptyList())

    /**
     * 更新会话配置（模式开关 / goal 控制）。
     * agentConfig: plan_mode / swarm_mode / goal_objective / goal_control(pause|resume|cancel)
     */
    suspend fun updateSessionProfile(
        sessionId: String,
        planMode: Boolean? = null,
        swarmMode: Boolean? = null,
        goalObjective: String? = null,
        goalControl: String? = null
    ): Boolean = runCatching {
        val agentConfig = buildJsonObject {
            if (planMode != null) put("plan_mode", planMode)
            if (swarmMode != null) put("swarm_mode", swarmMode)
            if (!goalObjective.isNullOrBlank()) put("goal_objective", goalObjective)
            if (goalControl != null) put("goal_control", goalControl)
        }
        val body = buildJsonObject {
            put("agent_config", agentConfig)
        }
        post("/api/v1/sessions/$sessionId/profile", body)
        true
    }.isSuccess
}
