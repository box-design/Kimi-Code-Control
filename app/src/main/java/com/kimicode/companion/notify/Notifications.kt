package com.kimicode.companion.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kimicode.companion.MainActivity
import com.kimicode.companion.R
import com.kimicode.companion.data.ApprovalRequest
import com.kimicode.companion.data.LiveTaskStatus

/**
 * 通知中心：审批强提醒 / 问题提醒 / 任务完成 / 工作状态常驻 / 任务实时横幅（v0.5.2）。
 *
 * v0.5.2 新增：
 * - 任务横幅通知（task_banner 通道，HIGH）：最早开始的运行任务以横幅（heads-up）形式
 *   常驻展示实时状态；用户划走横幅后常驻通知栏，任务结束自动移除。
 * - 其他运行任务通知（task_bg 通道，LOW）：多个任务同时运行时，较晚任务常驻通知栏。
 * - 工作状态常驻通知支持极简（原来的）/ 详细（实时状态）两种模式。
 */
object Notifications {

    const val CHANNEL_APPROVAL = "approval"
    const val CHANNEL_QUESTION = "question"
    const val CHANNEL_COMPLETION = "completion"
    const val CHANNEL_WORK = "work"
    const val CHANNEL_TASK_BANNER = "task_banner"
    const val CHANNEL_TASK_BG = "task_bg"

    private const val NOTIF_WORK = 1001
    private const val APPROVAL_BASE = 2000
    private const val QUESTION_BASE = 102000
    private const val COMPLETION_ID = 200000
    private const val TASK_BASE = 300000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val approval = NotificationChannel(
            CHANNEL_APPROVAL,
            context.getString(R.string.channel_approval_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_approval_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250, 120, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        nm.createNotificationChannel(approval)

        val question = NotificationChannel(
            CHANNEL_QUESTION,
            context.getString(R.string.channel_question_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_question_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(question)

        val completion = NotificationChannel(
            CHANNEL_COMPLETION,
            context.getString(R.string.channel_completion_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_completion_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(completion)

        val work = NotificationChannel(
            CHANNEL_WORK,
            context.getString(R.string.channel_work_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_work_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(work)

        // v0.5.2：任务横幅（最早开始的运行任务，heads-up 常驻；默认静音防打扰）
        val taskBanner = NotificationChannel(
            CHANNEL_TASK_BANNER,
            context.getString(R.string.channel_task_banner_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_task_banner_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(taskBanner)

        // v0.5.2：其他运行任务（较晚任务，常驻通知栏不弹横幅）
        val taskBg = NotificationChannel(
            CHANNEL_TASK_BG,
            context.getString(R.string.channel_task_bg_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_task_bg_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(taskBg)
    }

    private fun mainIntent(context: Context, openApproval: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_APPROVAL, openApproval)
        }
        return PendingIntent.getActivity(
            context,
            if (openApproval) 4001 else 4002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** v0.5.2：直达某会话任务详情页的 PendingIntent（横幅/任务通知点击用） */
    private fun taskIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            5000 + (sessionId.hashCode().toLong() and 0x3FFFL).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------- 审批 ----------

    fun approvalNotificationId(approvalId: String): Int =
        APPROVAL_BASE + ((approvalId.hashCode().toLong() and 0x7FFFFFFFL) % 100_000L).toInt()

    /**
     * 审批强提醒：横幅 + 声音 + 震动；锁屏可见并可直接操作。
     * 亮屏：顶部横幅，可直接点"允许 / 拒绝"；
     * 熄屏：震动 + 提示音，亮屏后在锁屏看到并直接操作。
     */
    fun showApproval(context: Context, approval: ApprovalRequest, sessionTitle: String) {
        val id = approvalNotificationId(approval.approval_id)
        val body = approval.displayInfo()?.oneLiner().orEmpty()

        val approveIntent = ApprovalActionReceiver.pending(
            context, ApprovalActionReceiver.ACTION_APPROVE,
            approval.session_id, approval.approval_id, id
        )
        val rejectIntent = ApprovalActionReceiver.pending(
            context, ApprovalActionReceiver.ACTION_REJECT,
            approval.session_id, approval.approval_id, id
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_approval)
            .setContentTitle(context.getString(R.string.notif_approval_title, approval.tool_name))
            .setContentText(body.ifBlank { sessionTitle })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body.ifBlank { sessionTitle }.ifBlank { approval.action })
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(mainIntent(context, openApproval = true))
            .addAction(0, context.getString(R.string.notif_approval_act_approve), approveIntent)
            .addAction(0, context.getString(R.string.notif_approval_act_reject), rejectIntent)
            .setOnlyAlertOnce(true)
            .build()

        notifySafely(context, id, notification)
    }

    // ---------- 问题 ----------

    fun questionNotificationId(questionId: String): Int =
        QUESTION_BASE + ((questionId.hashCode().toLong() and 0x7FFFFFFFL) % 100_000L).toInt()

    fun showQuestion(context: Context, questionId: String, questionText: String, sessionTitle: String) {
        val id = questionNotificationId(questionId)
        val notification = NotificationCompat.Builder(context, CHANNEL_QUESTION)
            .setSmallIcon(R.drawable.ic_stat_question)
            .setContentTitle(context.getString(R.string.notif_question_title))
            .setContentText(questionText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(questionText + if (sessionTitle.isNotBlank()) "\n会话：$sessionTitle" else "")
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context, openApproval = false))
            .addAction(0, context.getString(R.string.notif_question_act_open), mainIntent(context, openApproval = false))
            .build()
        notifySafely(context, id, notification)
    }

    // ---------- 任务完成 ----------

    /** kind: completed | failed | cancelled；每个会话独立通知 ID，避免相互覆盖 */
    fun showCompletion(context: Context, kind: String, sessionTitle: String, minutes: Long?, sessionId: String = "") {
        val title = when (kind) {
            "failed" -> context.getString(R.string.notif_completed_failed, sessionTitle)
            "cancelled" -> context.getString(R.string.notif_completed_cancelled, sessionTitle)
            else -> context.getString(R.string.notif_completed_done, sessionTitle)
        }
        val text = if (minutes != null && minutes > 0) {
            "运行了约 $minutes 分钟" + if (kind == "failed") "，请查看失败原因" else ""
        } else {
            if (kind == "failed") "请查看失败原因" else ""
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(if (kind == "failed") R.drawable.ic_stat_question else R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent(context, openApproval = false))
            .build()
        val id = COMPLETION_ID + (sessionId.hashCode().toLong() and 0x7FFF).toInt()
        notifySafely(context, id, notification)
    }

    /** 后台任务（Bash 后台 / 子代理）结束 */
    fun showTaskTerminated(context: Context, status: String?, description: String?, sessionTitle: String, taskId: String = "") {
        val kind = when (status) {
            "failed", "timed_out" -> "failed"
            "killed" -> "cancelled"
            else -> "completed"
        }
        val title = when (kind) {
            "failed" -> "后台任务失败 · $sessionTitle"
            "cancelled" -> "后台任务已取消 · $sessionTitle"
            else -> "后台任务完成 · $sessionTitle"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(description ?: "")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent(context, openApproval = false))
            .build()
        val id = COMPLETION_ID + 50000 + (taskId.hashCode().toLong() and 0x7FFF).toInt()
        notifySafely(context, id, notification)
    }

    // ---------- 工作状态常驻（FGS） ----------

    /** FGS 常驻通知：文本随状态变化（连接中 / 正在工作·已运行X分钟 / 待命） */
    fun buildWork(context: Context, text: String, disconnected: Boolean = false): Notification =
        NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(
                when {
                    disconnected -> R.drawable.ic_stat_offline
                    else -> R.drawable.ic_stat_kimi
                }
            )
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(mainIntent(context, openApproval = false))
            .build()

    fun updateWork(context: Context, text: String, disconnected: Boolean = false) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WORK, buildWork(context, text, disconnected))
    }

    /**
     * v0.5.2：FGS 常驻通知·详细模式——聚合所有运行任务的实时状态。
     * 无任务时回退为待命文案。
     */
    fun updateWorkDetailed(
        context: Context,
        tasks: List<LiveTaskStatus>,
        idleText: String,
        disconnected: Boolean = false
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WORK, buildWorkDetailed(context, tasks, idleText, disconnected))
    }

    fun buildWorkDetailed(
        context: Context,
        tasks: List<LiveTaskStatus>,
        idleText: String,
        disconnected: Boolean = false
    ): Notification {
        if (tasks.isEmpty() || disconnected) {
            return buildWork(context, idleText, disconnected)
        }
        val earliest = tasks.first()
        val title = if (tasks.size == 1) {
            context.getString(R.string.notif_work_detailed_single, earliest.title)
        } else {
            context.getString(R.string.notif_work_detailed_multi, tasks.size)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(phaseIcon(earliest))
            .setContentTitle(title)
            .setContentText(phaseLine(context, earliest))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(taskIntent(context, earliest.sessionId))
        applyChronometer(builder, earliest.startedAtMs)
        applyProgress(builder, earliest)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(tasksBigText(context, tasks)))
        return builder.build()
    }

    fun cancelWork(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_WORK)
    }

    // ---------- 任务实时通知（v0.5.2 横幅 / 后台常驻） ----------

    fun taskNotificationId(sessionId: String): Int =
        TASK_BASE + ((sessionId.hashCode().toLong() and 0x7FFFFFFFL) % 90_000L).toInt()

    /**
     * 构建单任务的实时状态通知。
     * [banner] = true：最早任务 → task_banner 通道（HIGH，heads-up 横幅，划走后常驻通知栏）；
     * false：较晚任务 → task_bg 通道（LOW，常驻通知栏，不弹横幅）。
     */
    fun buildTaskStatus(context: Context, status: LiveTaskStatus, banner: Boolean): Notification {
        val channel = if (banner) CHANNEL_TASK_BANNER else CHANNEL_TASK_BG
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(phaseIcon(status))
            .setContentTitle(status.title.ifBlank { context.getString(R.string.notif_task_default_title) })
            .setContentText(phaseLine(context, status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF1F3A8F.toInt())
            .setContentIntent(taskIntent(context, status.sessionId))
            .setPriority(if (banner) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        applyChronometer(builder, status.startedAtMs)
        applyProgress(builder, status)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(taskBigText(context, status)))
        return builder.build()
    }

    fun updateTaskStatus(context: Context, status: LiveTaskStatus, banner: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        nm.notify(taskNotificationId(status.sessionId), buildTaskStatus(context, status, banner))
    }

    fun cancelTask(context: Context, sessionId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(taskNotificationId(sessionId))
    }

    /** 任务通知全部撤除（空闲 / 功能关闭时） */
    fun cancelAllTaskNotifications(context: Context, sessionIds: Collection<String>) {
        sessionIds.forEach { cancelTask(context, it) }
    }

    // ---------- 实时状态文本辅助（通知 / 详情页共用口径） ----------

    /** 相位小图标 */
    fun phaseIcon(status: LiveTaskStatus): Int = when {
        status.pendingInteraction == "approval" || status.phaseKind == "awaiting_approval" ->
            R.drawable.ic_stat_approval
        status.phaseKind == "tool_call" || status.phaseKind == "tool_done" || status.phaseKind == "retrying" -> when (status.displayKind) {
            "command" -> R.drawable.ic_stat_shell
            "file_io", "diff" -> R.drawable.ic_stat_write
            else -> R.drawable.ic_stat_kimi
        }
        else -> R.drawable.ic_stat_thinking
    }

    /** 相位单行文案：思考中 / 执行命令 · xxx / 写文件 · xxx / 执行工具 · xxx / 等待审批 */
    fun phaseLine(context: Context, status: LiveTaskStatus): String {
        val base = when {
            status.pendingInteraction == "approval" -> context.getString(R.string.phase_approval)
            status.pendingInteraction == "question" -> context.getString(R.string.phase_question)
            status.phaseKind == "tool_call" || status.phaseKind == "tool_done" || status.phaseKind == "retrying" -> when (status.displayKind) {
                "command" -> context.getString(R.string.phase_shell) +
                    (status.command?.take(48)?.let { " · $it" } ?: "")
                "file_io", "diff" -> context.getString(R.string.phase_write) +
                    (status.path?.take(48)?.let { " · $it" } ?: "")
                "todo_list" -> context.getString(R.string.phase_todo_updating)
                else -> status.toolName?.let {
                    context.getString(R.string.phase_tool, it)
                } ?: context.getString(R.string.phase_thinking)
            }
            status.phaseKind == "awaiting_approval" -> context.getString(R.string.phase_approval)
            else -> context.getString(R.string.phase_thinking)
        }
        return base
    }

    /** 展开大文本：相位细节 + todo 进度 + swarm 个数 + goal */
    private fun taskBigText(context: Context, status: LiveTaskStatus): String {
        val lines = mutableListOf<String>()
        lines.add(phaseLine(context, status))
        status.todoDone?.let { done ->
            status.todoTotal?.let { total ->
                lines.add(context.getString(R.string.notif_task_todo, done, total))
            }
        }
        status.swarmActive?.takeIf { it > 0 }?.let {
            lines.add(context.getString(R.string.notif_task_swarm, it))
        }
        status.goalObjective?.takeIf { it.isNotBlank() }?.let {
            lines.add(context.getString(R.string.notif_task_goal, it.take(64)))
        }
        status.cwd?.let { lines.add(it) }
        return lines.joinToString("\n")
    }

    /** FGS 详细模式展开大文本：逐任务列出 */
    private fun tasksBigText(context: Context, tasks: List<LiveTaskStatus>): String {
        return tasks.take(4).joinToString("\n\n") { t ->
            val progress = t.todoDone?.let { d -> t.todoTotal?.let { n -> " · ${context.getString(R.string.notif_task_todo, d, n)}" } } ?: ""
            "${t.title}${progress}\n${phaseLine(context, t)}"
        }
    }

    /** 实时计时（setUsesChronometer：系统每秒自动刷新运行时长） */
    private fun applyChronometer(builder: NotificationCompat.Builder, startedAtMs: Long?) {
        if (startedAtMs != null && startedAtMs > 0) {
            builder.setWhen(startedAtMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
        }
    }

    /** todo 进度条（无 todo 时用不定态进度条表达"进行中"） */
    private fun applyProgress(builder: NotificationCompat.Builder, status: LiveTaskStatus) {
        val total = status.todoTotal
        if (total != null && total > 0) {
            builder.setProgress(total, status.todoDone ?: 0, false)
        } else {
            builder.setProgress(0, 0, true)
        }
    }

    fun cancelApproval(context: Context, approvalId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(approvalNotificationId(approvalId))
    }

    fun cancelQuestion(context: Context, questionId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(questionNotificationId(questionId))
    }

    fun cancelAllTransient(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(COMPLETION_ID)
        nm.cancel(COMPLETION_ID + 1)
    }

    fun areEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun notifySafely(context: Context, id: Int, notification: android.app.Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        nm.notify(id, notification)
    }

    /** 把毫秒格式化为"X 分钟" / "X 小时 Y 分钟" */
    fun formatDuration(context: Context, ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> "$minutes 分钟"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "$h 小时" else "$h 小时 $m 分钟"
            }
        }
    }
}
