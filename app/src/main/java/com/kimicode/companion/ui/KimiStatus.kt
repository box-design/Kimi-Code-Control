package com.kimicode.companion.ui

import androidx.annotation.StringRes
import com.kimicode.companion.R
import com.kimicode.companion.data.SessionPhase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Kimi Code 官方状态文案引擎（v0.5.3）。
 *
 * 全部文案逐字移植自 MoonshotAI/kimi-code 官方源码，英文原文保留：
 * - 主状态机  apps/kimi-code/src/tui/kimi-tui.ts        → Thinking… / Working…（braille spinner）
 * - 思考流    apps/kimi-code/src/tui/components/messages/thinking.ts → thinking…
 * - 工具卡片  apps/kimi-code/src/tui/components/messages/tool-call.ts
 *     · 通用头    verb = Using / Used            → `Using Write (a.txt)`
 *     · Bash 头   Running/Ran a command · $ cmd
 *     · Read 分组 apps/.../read-group.ts         → Reading N files… / Read N files
 *     · AskUserQuestion / NotifyUser / ExitPlanMode 专属标签
 *     · MCP 名称解码 mcp-tool-name.ts            → Using tool · MCP/server
 * - 重试      apps/kimi-code/src/tui/utils/step-retry.ts → Retrying (n/m) · Error · in Xs
 * - 审批头    apps/.../dialogs/approval-panel.ts headerFor → Run this command? / Write this file? …
 * - 问题      apps/.../kimi-tui.ts              → Kimi Code needs your answer
 * - 中断      apps/.../session-event-handler.ts  → Interrupted by user / reached per-turn step limit
 * - Swarm     apps/.../agent-swarm-progress.ts   → Queued… / Rate limited… / Aborted.
 * - 子代理    apps/.../agent-group.ts            → ✓ Completed / ✗ Failed / Starting / Running
 * - Spinner 帧 apps/kimi-code/src/tui/constant/rendering.ts（braille 80ms / moon 120ms）
 */
object KimiStatus {

    // ---------- Spinner 帧（逐字移植 constant/rendering.ts） ----------
    val BRAILLE_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    val MOON_FRAMES = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")
    const val BRAILLE_INTERVAL_MS = 80L
    const val MOON_INTERVAL_MS = 120L

    /** Kimi Code 语义色（详情页映射到 MD3 角色） */
    enum class Tone { PRIMARY, SUCCESS, ERROR, WARNING, OUTLINE }

    /** Spinner 类型：braille = 思考/生成（Thinking…/Working…），moon = 等待/工具，none = 等待用户 */
    enum class Spinner { NONE, BRAILLE, MOON }

    /** 解析后的状态展示（label 为 Kimi Code 原文） */
    data class Display(
        val spinner: Spinner,
        val label: String,
        val hint: String? = null,
        val tone: Tone = Tone.PRIMARY,
        /** 状态点是否呼吸（活跃相位） */
        val pulse: Boolean = true
    )

    /** 文案资源表（Composable 中 stringResource 解析后传入） */
    data class Strings(
        val idle: String,                                   // Idle
        val thinking: String,                               // Thinking…
        val working: String,                                // Working…
        val runningCommand: (String) -> String,             // Running a command · $ %s
        val ranCommand: String,                             // Ran a command
        val usingTool: (String, String) -> String,          // Using %1$s (%2$s)
        val usingToolNoArg: (String) -> String,             // Using %1$s
        val usedTool: (String, String) -> String,           // Used %1$s (%2$s)
        val usedToolNoArg: (String) -> String,              // Used %1$s
        val usingMcp: (String, String) -> String,           // Using %1$s · MCP/%2$s
        val usedMcp: (String, String) -> String,            // Used %1$s · MCP/%2$s
        val readingFiles: (Int) -> String,                  // Reading %d files…
        val readFiles: (Int) -> String,                     // Read %d files
        val waitingInput: String,                           // Waiting for your input
        val collectedAnswers: String,                       // Collected your answers
        val startingBgQuestion: String,                     // Starting background question
        val sendingUpdate: String,                          // Sending you an update
        val currentPlan: String,                            // Current plan
        val waitingBgTask: String,                          // Waiting for background task
        val waitingBgTaskId: (String) -> String,            // Waiting for background task (%s)
        val waitedBgTask: String,                           // Waited for background task
        val startingGoal: String,                           // Starting goal
        val checkingGoal: String,                           // Checking goal
        val settingGoalBudget: String,                      // Setting goal budget
        val reportingGoal: (String) -> String,              // Reporting goal %s
        val updateTodos: String,                            // Update Todos
        val retrying: (Int, Int, String) -> String,         // Retrying (%1$d/%2$d) · %3$s
        val retryingIn: (Int, Int, String, Long) -> String, // Retrying (%1$d/%2$d) · %3$s · in %4$ds
        val approveTool: (String) -> String,                // Approve %s?
        val needsAnswer: String,                            // Kimi Code needs your answer
        val interruptedByUser: String,                      // Interrupted by user
        val maxSteps: String,                               // reached per-turn step limit (max_steps)
        val stepInterrupted: (String) -> String             // step interrupted (%s)
    )

    // ---------- MCP 工具名解码（逐字移植 utils/mcp-tool-name.ts decodeMcpToolName） ----------

    /** 返回 (serverName, toolName)；非 MCP 工具返回 null */
    fun decodeMcpToolName(name: String): Pair<String, String>? {
        val prefix = "mcp__"
        if (!name.startsWith(prefix)) return null
        val rest = name.removePrefix(prefix)
        val sep = rest.indexOf("__")
        if (sep <= 0 || sep == rest.length - 2) return null
        return rest.substring(0, sep) to rest.substring(sep + 2)
    }

    // ---------- keyArg 提取（口径对齐 tool-call.ts extractKeyArgumentDetail） ----------

    /** 工作区相对路径（对齐 makeWorkspaceRelativePath：cwd 下路径显示相对形式） */
    fun relativize(path: String, workspaceDir: String?): String {
        if (workspaceDir.isNullOrBlank()) return path
        val cwd = workspaceDir.trimEnd('/')
        if (cwd.isBlank()) return path
        return when {
            path == cwd -> "."
            path.startsWith("$cwd/") -> path.removePrefix("$cwd/")
            else -> path
        }
    }

    /** Bash 多行命令取首行 + …（对齐 tool-call.ts displayValue 处理） */
    private fun firstLineOfCommand(value: String): String {
        val first = value.split('\n').firstOrNull() ?: value
        return if (value.contains('\n')) "$first…" else first
    }

    /** 从 tool.call.started 的 args JsonObject 安全取字符串 */
    private fun argString(args: JsonObject?, key: String): String? =
        (args?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /**
     * 提取工具头部的关键参数（对齐 extractKeyArgumentDetail keyMap）：
     * Bash→command、Read/Write/Edit→path、Grep→pattern、Glob→pattern(+路径+include ignored)、
     * FetchURL→url、WebSearch→query、Agent→description/prompt、NotifyUser→message、
     * 其余工具取 args 中第一个非空字符串。
     */
    fun keyArgOf(phase: SessionPhase, workspaceDir: String?): String? {
        val args = phase.argsJson
        val name = phase.toolName ?: return null

        // Glob 特殊拼接：pattern · path · include ignored（对齐 extractKeyArgumentDetail）
        if (name == "Glob") {
            val pattern = argString(args, "pattern") ?: return null
            var summary = pattern
            val path = argString(args, "path")
            if (!path.isNullOrBlank()) summary += " · ${relativize(path, workspaceDir)}"
            if (argString(args, "include_ignored") == "true") summary += " · include ignored"
            return summary
        }

        // keyMap 命中（对齐 extractKeyArgumentDetail keyMap；path 以工作区相对形式展示）
        when (name) {
            "Bash" -> argString(args, "command")?.let { return firstLineOfCommand(it) }
            "Read", "ReadMediaFile", "Write", "Edit" -> {
                (argString(args, "path") ?: argString(args, "file_path"))
                    ?.let { return relativize(it, workspaceDir) }
            }
            "Grep" -> argString(args, "pattern")?.let { return it }
            "FetchURL" -> argString(args, "url")?.let { return it }
            "WebSearch" -> argString(args, "query")?.let { return it }
            "Agent" -> (argString(args, "description") ?: argString(args, "prompt"))?.let { return it }
            "NotifyUser" -> argString(args, "message")?.let { return it }
        }

        // keyMap 未命中 / 参数缺失：display 载荷补充
        // （search→query、url_fetch→url、agent_call→agent_name、skill_call→skill_name…）
        return when (phase.displayKind) {
            "command" -> phase.command
            "file_io", "diff" -> phase.path?.let { relativize(it, workspaceDir) }
            "search" -> phase.query
            "url_fetch" -> phase.url
            "agent_call" -> phase.agentName ?: phase.description
            "skill_call" -> phase.skillName
            "generic" -> phase.summary ?: phase.description
            else -> phase.description ?: phase.summary
        } ?: args?.entries?.firstOrNull { (k, v) ->
            v is JsonPrimitive && v.contentOrNull?.isNotEmpty() == true && k != "mode"
        }?.let { (it.value as JsonPrimitive).contentOrNull }
    }

    // ---------- 主解析（详情页 AI 状态行） ----------

    /**
     * 会话实时状态 → Kimi Code 官方展示。
     *
     * @param busy 会话是否忙碌
     * @param phase 细粒度相位（含工具 display / retry 详情 / 完成态）
     * @param pendingInteraction 会话待交互（approval / question）
     * @param approvalToolName 待审批工具名（审批头需要）
     * @param thinkingFresh 思考流是否仍在流式更新（区分 Thinking… / Working…）
     * @param readBurst 当前连续 Read 计数（Reading N files…）
     * @param workspaceDir 会话工作目录（路径参数相对化展示，对齐 makeWorkspaceRelativePath）
     */
    fun resolve(
        s: Strings,
        busy: Boolean,
        phase: SessionPhase?,
        pendingInteraction: String?,
        approvalToolName: String?,
        thinkingFresh: Boolean,
        readBurst: Int,
        workspaceDir: String? = null
    ): Display {
        // ---- 待审批（Kimi Code approval-panel.ts headerFor） ----
        if (pendingInteraction == "approval" || phase?.phaseKind == "awaiting_approval") {
            val tool = approvalToolName ?: phase?.toolName
            val label = approvalHeader(s, tool)
            return Display(Spinner.NONE, label, tone = Tone.WARNING, pulse = false)
        }
        // ---- 待回答（kimi-tui.ts: Kimi Code needs your answer） ----
        if (pendingInteraction == "question") {
            return Display(Spinner.NONE, s.needsAnswer, tone = Tone.WARNING, pulse = false)
        }
        if (!busy) return Display(Spinner.NONE, s.idle, tone = Tone.OUTLINE, pulse = false)

        return when (phase?.phaseKind) {
            // ---- 中断（session-event-handler.ts） ----
            "interrupted" -> Display(Spinner.NONE, s.interruptedByUser, tone = Tone.ERROR, pulse = false)

            // ---- 重试（step-retry.ts formatStepRetryLabel） ----
            "retrying" -> {
                val next = phase.retryNextAttempt
                val max = phase.retryMaxAttempts
                val err = phase.retryErrorName ?: ""
                val label = if (next != null && max != null) {
                    val delay = phase.retryDelayMs?.let { ms -> (ms + 999) / 1000 }
                    if (delay != null && delay > 0) s.retryingIn(next, max, err, delay)
                    else s.retrying(next, max, err)
                } else {
                    s.working
                }
                // 详情行：statusCode · message（formatStepRetryDetail 口径）
                val hint = phase.retryStatusCode?.toString()
                Display(Spinner.MOON, label, hint = hint, tone = Tone.WARNING)
            }

            // ---- 工具执行中（tool-call.ts buildHeader） ----
            "tool_call" -> toolCallDisplay(s, phase, readBurst, finished = false, workspaceDir = workspaceDir)

            // ---- 工具完成（tool.result → ✓ Used X，卡片完成态） ----
            "tool_done" -> toolCallDisplay(s, phase, readBurst, finished = true, workspaceDir = workspaceDir)

            // ---- 思考 / 生成（kimi-tui.ts activity pane） ----
            "running", null -> {
                if (thinkingFresh) Display(Spinner.BRAILLE, s.thinking, tone = Tone.PRIMARY)
                else Display(Spinner.BRAILLE, s.working, tone = Tone.PRIMARY)
            }

            "idle", "ended" -> Display(Spinner.NONE, s.idle, tone = Tone.OUTLINE, pulse = false)
            else -> Display(Spinner.BRAILLE, s.working, tone = Tone.PRIMARY)
        }
    }

    /** 工具调用展示（进行中 Using… / 完成 Used…，含全部专属头） */
    private fun toolCallDisplay(
        s: Strings,
        phase: SessionPhase,
        readBurst: Int,
        finished: Boolean,
        workspaceDir: String? = null
    ): Display {
        val name = phase.toolName ?: return Display(
            Spinner.MOON, if (finished) s.usedToolNoArg("") else s.usingToolNoArg("")
        )

        // ---- Read 分组（read-group.ts）：连续 ≥2 个 Read → Reading N files… ----
        if ((name == "Read" || name == "ReadMediaFile") && readBurst >= 2) {
            return Display(
                Spinner.MOON,
                if (finished) s.readFiles(readBurst) else s.readingFiles(readBurst),
                hint = phase.resultLine,
                tone = if (finished) Tone.SUCCESS else Tone.PRIMARY
            )
        }

        val tone = when {
            finished && phase.resultIsError -> Tone.ERROR
            finished -> Tone.SUCCESS
            else -> Tone.PRIMARY
        }

        // ---- 专属头（tool-call.ts buildHeader 特判） ----
        val special: Display? = when (name) {
            "Bash" -> {
                val cmd = phase.command
                    ?: argString(phase.argsJson, "command")?.let { firstLineOfCommand(it) }
                if (finished) Display(Spinner.NONE, s.ranCommand, hint = phase.resultLine, tone = tone)
                else Display(Spinner.MOON, s.runningCommand(cmd ?: ""), tone = tone)
            }
            "AskUserQuestion" -> {
                // Kimi Code tool-call.ts：后台提问 / 前台等待输入 / 已收集回答
                val background = argString(phase.argsJson, "background") == "true"
                val label = when {
                    background && !finished -> s.startingBgQuestion
                    finished -> s.collectedAnswers
                    else -> s.waitingInput
                }
                Display(Spinner.NONE, label, tone = tone)
            }
            "NotifyUser" -> Display(Spinner.MOON, s.sendingUpdate, tone = tone)
            "ExitPlanMode" -> Display(Spinner.MOON, s.currentPlan, tone = tone)
            "WaitFor" -> {
                val taskId = phase.taskId ?: argString(phase.argsJson, "task_id")
                val label = if (taskId != null) s.waitingBgTaskId(taskId) else s.waitingBgTask
                Display(Spinner.MOON, label, tone = tone)
            }
            "CreateGoal" -> Display(Spinner.MOON, s.startingGoal, hint = phase.objective, tone = tone)
            "GetGoal" -> Display(Spinner.MOON, s.checkingGoal, tone = tone)
            "SetGoalBudget" -> Display(Spinner.MOON, s.settingGoalBudget, tone = tone)
            "UpdateGoal" -> {
                val status = argString(phase.argsJson, "status") ?: "status"
                Display(Spinner.MOON, s.reportingGoal(status), tone = tone)
            }
            "SetTodoList", "TodoList" -> Display(Spinner.MOON, s.updateTodos, tone = tone)
            else -> null
        }
        if (special != null) return special

        // ---- MCP 工具（mcp-tool-name.ts：Using tool · MCP/server (keyArg)） ----
        decodeMcpToolName(name)?.let { (server, tool) ->
            val base = if (finished) s.usedMcp(tool, server) else s.usingMcp(tool, server)
            val arg = keyArgOf(phase, workspaceDir)
            val label = if (arg != null) "$base ($arg)" else base
            return Display(Spinner.MOON, label, hint = phase.resultLine, tone = tone)
        }

        // ---- 通用头（Using Tool (keyArg) / Used Tool (keyArg)） ----
        val keyArg = keyArgOf(phase, workspaceDir)
        val label = when {
            finished && keyArg != null -> s.usedTool(name, keyArg)
            finished -> s.usedToolNoArg(name)
            keyArg != null -> s.usingTool(name, keyArg)
            else -> s.usingToolNoArg(name)
        }
        return Display(Spinner.MOON, label, hint = phase.resultLine, tone = tone)
    }

    /** 审批头（approval-panel.ts headerFor 原文） */
    private fun approvalHeader(s: Strings, toolName: String?): String {
        val fallback = toolName?.let { s.approveTool(it) } ?: s.idle
        return when (toolName) {
            "Bash" -> "Run this command?"
            "Write" -> "Write this file?"
            "Edit" -> "Apply these edits?"
            "TaskStop" -> "Stop this task?"
            "ExitPlanMode" -> "Ready to build with this plan?"
            else -> fallback
        }
    }

    // ---------- 子代理状态（agent-group.ts formatLineTail / agent-swarm-progress.ts PHASE_LABELS） ----------

    /** @return 状态文案资源 id；Kimi Code 原文 */
    @StringRes
    fun subagentLabelRes(state: String): Int = when (state) {
        "spawned" -> R.string.ks_sub_starting        // Starting
        "working" -> R.string.ks_sub_running         // Running
        "suspended" -> R.string.ks_sub_rate_limited  // Rate limited…
        "completed" -> R.string.ks_sub_completed     // ✓ Completed
        "failed" -> R.string.ks_sub_failed           // ✗ Failed
        "cancelled" -> R.string.ks_sub_aborted       // Aborted.
        else -> R.string.ks_sub_running
    }

    /** 子代理是否活跃（呼吸/旋转） */
    fun subagentActive(state: String): Boolean =
        state == "spawned" || state == "working"

    /** 子代理状态色 */
    fun subagentTone(state: String): Tone = when (state) {
        "spawned", "working" -> Tone.PRIMARY
        "suspended" -> Tone.WARNING
        "completed" -> Tone.SUCCESS
        "failed" -> Tone.ERROR
        else -> Tone.OUTLINE // cancelled → Aborted.
    }

    /** 子代理名（tool-call.ts formatSubagentLabel：分词首字母大写 + " Agent" 后缀） */
    fun formatSubagentLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "SubAgent"
        val label = trimmed.split(Regex("[-_\\s]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
        return if (Regex("\\bagents?$", RegexOption.IGNORE_CASE).containsMatchIn(label)) label
        else "$label Agent"
    }
}
