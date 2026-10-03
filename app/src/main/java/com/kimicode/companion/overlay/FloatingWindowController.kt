package com.kimicode.companion.overlay

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 悬浮窗控制器（v0.5.5）：悬浮窗形态 / 提示卡 / 退出请求 的进程级共享状态。
 *
 * - [FloatingWindowService]（渲染方）读取 [form] 与 [prompt] 决定展示胶囊 / 卡片 / 提示卡；
 * - MonitorService（调度方）根据 工作状态模式(DETAILED) + 悬浮窗权限 + liveTasks
 *   启停悬浮窗服务；审批 / 问题 / 完成提醒（提醒通道=悬浮窗时）通过 [showPrompt] 弹出提示卡；
 * - v0.5.5 形态状态机：悬浮窗每次出现固定以胶囊形态开始（不再恢复上次形态），
 *   点击胶囊 → 卡片；点击卡片外空白 → 收回胶囊；提示卡优先级高于普通形态，
 *   完成选择（审批操作 / 问题打开应用 / 完成卡自动过期）后自动缩回。
 * - 用户在悬浮窗「更多」菜单点「退出」→ [userExited] = true，本批任务期间不再自动弹出，
 *   所有任务结束（liveTasks 变空）后自动复位，下一个新任务开始时重新出现。
 */
object FloatingWindowController {

    /** 悬浮窗形态 */
    enum class Form { CARD, CAPSULE }

    /** 提示卡类型（v0.5.5） */
    enum class PromptKind { APPROVAL, QUESTION, COMPLETION }

    /**
     * 悬浮窗提示卡数据（审批 / 问题 / 任务完成提醒）。
     * 字段按类型选用，展示端按 [kind] 取用。
     */
    data class FloatingPrompt(
        val id: String,
        val kind: PromptKind,
        val sessionId: String,
        /** 任务 / 会话标题 */
        val taskTitle: String,
        /** APPROVAL：工具名（如 Bash） */
        val toolName: String? = null,
        /** APPROVAL：操作内容一行摘要（command / path / url …） */
        val actionSummary: String? = null,
        /** APPROVAL：审批 ID（操作时回传服务器） */
        val approvalId: String? = null,
        /** QUESTION：问题 ID */
        val questionId: String? = null,
        /** QUESTION：问题文本 */
        val questionText: String? = null,
        /** COMPLETION：结果标签 done / failed / cancelled */
        val resultKind: String? = null,
        /** COMPLETION：运行时长文案（如「约 12 分钟」），可空 */
        val durationLabel: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _form = MutableStateFlow(Form.CAPSULE)

    /** 当前形态（胶囊 = 灵动岛式极简，默认每次出现都从胶囊开始 / 卡片 = 完整信息） */
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _prompt = MutableStateFlow<FloatingPrompt?>(null)

    /** 当前提示卡（非空时悬浮窗展开为提示卡，优先级高于 [form]） */
    val prompt: StateFlow<FloatingPrompt?> = _prompt.asStateFlow()

    private val _exitRequested = MutableStateFlow(false)

    /** 退出请求：悬浮窗收到后播放「缩小消失」动画，动画结束后自行 detach + stopSelf */
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /** 悬浮窗窗口是否可见（Service attach / detach 维护；供调度方判断优雅退出） */
    @Volatile
    var windowVisible: Boolean = false
        private set

    /** 用户已主动退出（更多 → 退出）；liveTasks 清空时由调度方复位 */
    @Volatile
    var userExited: Boolean = false
        private set

    private var promptExpireJob: kotlinx.coroutines.Job? = null

    /** 切换形态（悬浮窗 UI 内部调用） */
    fun setForm(form: Form) {
        _form.value = form
    }

    /**
     * 弹出提示卡（审批 / 问题 / 完成提醒）。
     * - 覆盖当前提示卡（新的更紧急，直接顶替）
     * - COMPLETION 类型 5 秒后自动过期缩回；APPROVAL / QUESTION 常驻直到用户处理
     */
    fun showPrompt(prompt: FloatingPrompt, autoExpireMs: Long? = null) {
        promptExpireJob?.cancel()
        _prompt.value = prompt
        _exitRequested.value = false
        if (autoExpireMs != null) {
            promptExpireJob = scope.launch {
                delay(autoExpireMs)
                if (_prompt.value?.id == prompt.id) _prompt.value = null
            }
        }
    }

    /** 收起当前提示卡（用户完成选择 / 点击空白 / 用户忽略） */
    fun clearPrompt() {
        promptExpireJob?.cancel()
        _prompt.value = null
    }

    /** 指定提示卡仍为当前展示的那一张时收起（ApprovalResolved 等对账场景） */
    fun clearPromptIfCurrent(promptId: String) {
        if (_prompt.value?.id == promptId) clearPrompt()
    }

    /**
     * 请求优雅退出：悬浮窗播放缩小消失动画后自行关闭。
     * 窗口不可见时调用方应直接走 [hide]。
     */
    fun requestExit() {
        _exitRequested.value = true
    }

    /** 取消退出请求（新任务开始，悬浮窗继续保留） */
    fun cancelExit() {
        _exitRequested.value = false
    }

    /** 悬浮窗动画结束后回调：Service 收尾（detach + stopSelf） */
    fun onExitAnimationFinished(context: Context) {
        _exitRequested.value = false
        hide(context)
    }

    /** 用户主动退出悬浮窗（更多 → 退出） */
    fun notifyUserExited() {
        userExited = true
    }

    /** 所有任务结束，复位退出标记（下一个任务开始时悬浮窗重新出现） */
    fun resetUserExit() {
        userExited = false
    }

    // ---------- Service 内部维护（窗口可见性） ----------

    /** @suppress 仅供 FloatingWindowService 调用 */
    fun internalSetWindowVisible(visible: Boolean) {
        windowVisible = visible
    }

    // ---------- 服务启停命令 ----------

    fun show(context: Context) = send(context, FloatingWindowService.ACTION_SHOW)

    fun hide(context: Context) = send(context, FloatingWindowService.ACTION_HIDE)

    private fun send(context: Context, action: String) {
        runCatching {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }
    }
}
