package com.kimicode.companion.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimicode.companion.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 共享 AI 状态 UI 组件（v0.5.4 抽取）：
 *
 * v0.5.3 起这些组件同时被「任务详情页」与「悬浮窗（工作状态面板）」使用，
 * 从 TaskDetailScreen 的 private 实现抽为 internal 共享——保证两处的
 * Kimi Code 官方文案（Thinking… / Working… / Using X (y)…）与动画
 * （braille/moon spinner 80/120ms、状态切换动态模糊）逐字逐帧一致。
 */
internal object StatusUiShared {
    /** 语义色（固定色值，悬浮窗深色面板与详情页通用） */
    val SUCCESS_GREEN = Color(0xFF34C77B)
    val WARNING_ORANGE = Color(0xFFF0A132)
}

/** Kimi Code 语义色 → MD3（详情页经 MaterialTheme；悬浮窗包深色主题后同样可用） */
@Composable
internal fun toneColor(tone: KimiStatus.Tone): Color = when (tone) {
    KimiStatus.Tone.PRIMARY -> MaterialTheme.colorScheme.primary
    KimiStatus.Tone.SUCCESS -> StatusUiShared.SUCCESS_GREEN
    KimiStatus.Tone.ERROR -> MaterialTheme.colorScheme.error
    KimiStatus.Tone.WARNING -> StatusUiShared.WARNING_ORANGE
    KimiStatus.Tone.OUTLINE -> MaterialTheme.colorScheme.outline
}

/** Kimi Code 官方文案资源表（stringResource 解析原始模板，lambda 内仅做纯格式化） */
@Composable
internal fun rememberKimiStatusStrings(): KimiStatus.Strings {
    // 带参模板：stringResource 不传参时返回原始格式串（含 %1$s 占位）
    val fmtRunningCommand = stringResource(R.string.ks_running_command)
    val fmtUsingTool = stringResource(R.string.ks_using_tool)
    val fmtUsingToolNoArg = stringResource(R.string.ks_using_tool_noarg)
    val fmtUsedTool = stringResource(R.string.ks_used_tool)
    val fmtUsedToolNoArg = stringResource(R.string.ks_used_tool_noarg)
    val fmtUsingMcp = stringResource(R.string.ks_using_mcp)
    val fmtUsedMcp = stringResource(R.string.ks_used_mcp)
    val fmtReadingFiles = stringResource(R.string.ks_reading_files)
    val fmtReadFiles = stringResource(R.string.ks_read_files)
    val fmtWaitingBgTaskId = stringResource(R.string.ks_waiting_bg_task_id)
    val fmtReportingGoal = stringResource(R.string.ks_reporting_goal)
    val fmtRetrying = stringResource(R.string.ks_retrying)
    val fmtRetryingIn = stringResource(R.string.ks_retrying_in)
    val fmtApproveTool = stringResource(R.string.ks_approve_tool)
    val fmtStepInterrupted = stringResource(R.string.ks_step_interrupted)
    return KimiStatus.Strings(
        idle = stringResource(R.string.ks_idle),
        thinking = stringResource(R.string.ks_thinking),
        working = stringResource(R.string.ks_working),
        runningCommand = { cmd -> fmtRunningCommand.format(cmd) },
        ranCommand = stringResource(R.string.ks_ran_command),
        usingTool = { t, a -> fmtUsingTool.format(t, a) },
        usingToolNoArg = { fmtUsingToolNoArg.format(it) },
        usedTool = { t, a -> fmtUsedTool.format(t, a) },
        usedToolNoArg = { fmtUsedToolNoArg.format(it) },
        usingMcp = { t, srv -> fmtUsingMcp.format(t, srv) },
        usedMcp = { t, srv -> fmtUsedMcp.format(t, srv) },
        readingFiles = { fmtReadingFiles.format(it) },
        readFiles = { fmtReadFiles.format(it) },
        waitingInput = stringResource(R.string.ks_waiting_input),
        collectedAnswers = stringResource(R.string.ks_collected_answers),
        startingBgQuestion = stringResource(R.string.ks_starting_bg_question),
        sendingUpdate = stringResource(R.string.ks_sending_update),
        currentPlan = stringResource(R.string.ks_current_plan),
        waitingBgTask = stringResource(R.string.ks_waiting_bg_task),
        waitingBgTaskId = { fmtWaitingBgTaskId.format(it) },
        waitedBgTask = stringResource(R.string.ks_waited_bg_task),
        startingGoal = stringResource(R.string.ks_starting_goal),
        checkingGoal = stringResource(R.string.ks_checking_goal),
        settingGoalBudget = stringResource(R.string.ks_setting_goal_budget),
        reportingGoal = { fmtReportingGoal.format(it) },
        updateTodos = stringResource(R.string.ks_update_todos),
        retrying = { n, m, e -> fmtRetrying.format(n, m, e) },
        retryingIn = { n, m, e, sec -> fmtRetryingIn.format(n, m, e, sec) },
        approveTool = { fmtApproveTool.format(it) },
        needsAnswer = stringResource(R.string.ks_needs_answer),
        interruptedByUser = stringResource(R.string.ks_interrupted_by_user),
        maxSteps = stringResource(R.string.ks_max_steps),
        stepInterrupted = { fmtStepInterrupted.format(it) }
    )
}

// ---------- Kimi Code 原版 spinner（constant/rendering.ts 帧序列与间隔） ----------

/** braille 帧动画（80ms/帧，与 Kimi Code BRAILLE_SPINNER_INTERVAL_MS 一致） */
@Composable
internal fun kimiBrailleFrame(): Int {
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(KimiStatus.BRAILLE_INTERVAL_MS)
            frame = (frame + 1) % KimiStatus.BRAILLE_FRAMES.size
        }
    }
    return frame
}

/** moon 帧动画（120ms/帧，与 Kimi Code MOON_SPINNER_INTERVAL_MS 一致） */
@Composable
internal fun kimiMoonFrame(): Int {
    var frame by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(KimiStatus.MOON_INTERVAL_MS)
            frame = (frame + 1) % KimiStatus.MOON_FRAMES.size
        }
    }
    return frame
}

/**
 * 状态 spinner：
 * - BRAILLE ⠋⠙⠹…（80ms）—— Thinking… / Working…（kimi-tui.ts activity pane）
 * - MOON 🌑🌒…（120ms）—— 等待 / 工具执行（moon-loader.ts）
 * - NONE —— 等待用户（审批/回答），呼吸状态点
 */
@Composable
internal fun StatusSpinner(kind: KimiStatus.Spinner, pulse: Boolean, color: Color) {
    when (kind) {
        KimiStatus.Spinner.BRAILLE -> {
            Text(
                text = KimiStatus.BRAILLE_FRAMES[kimiBrailleFrame()],
                color = color,
                fontSize = 15.sp,
                modifier = Modifier.width(16.dp)
            )
        }
        KimiStatus.Spinner.MOON -> {
            Text(
                text = KimiStatus.MOON_FRAMES[kimiMoonFrame()],
                fontSize = 13.sp,
                modifier = Modifier.width(16.dp)
            )
        }
        KimiStatus.Spinner.NONE -> {
            val alpha = if (pulse) rememberPulseAlphaShared() else 1f
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .alpha(alpha)
                    .background(color, CircleShape)
            )
        }
    }
}

// ---------- 动态模糊切换动画（v0.5.3） ----------

/**
 * 动态模糊状态切换（motion blur）：
 * 状态文案变化时，旧文案边模糊边缘化滑出（模糊度 0→max、透明度 1→0、向上位移），
 * 新文案从下方带模糊滑入并逐渐聚焦（模糊度 max→0）。代替原先的一闪而过。
 *
 * Android 12+（API 31）走 RenderEffect 真实高斯模糊；低版本优雅降级为
 * 滑动 + 淡入淡出（视觉上仍是平滑的方向性过渡而非闪变）。
 */
@Composable
internal fun MotionBlurText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    maxBlur: Float = 9f,
    slide: Float = 10f,
    durationMs: Int = 380
) {
    // outgoing：正在滑出的旧文案；settled：最近一次完整展示的文案
    var outgoing by remember { mutableStateOf(text) }
    var settled by remember { mutableStateOf(text) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(text) {
        if (text != settled) {
            // 连续快速切换：把上一个目标文案当作新的滑出文案，动画从头重放
            outgoing = settled
            settled = text
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            )
        }
    }

    val p = progress.value
    val blurSupported = Build.VERSION.SDK_INT >= 31

    fun blurModifier(fraction: Float): Modifier {
        if (!blurSupported || fraction <= 0.02f) return Modifier
        return Modifier.blur((maxBlur * fraction).dp)
    }

    if (p >= 1f) {
        // 静止态：无模糊、无额外图层开销
        Text(
            text = text,
            color = color,
            style = style,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.clipToBounds()) {
            // 旧文案：模糊加深、上滑、淡出
            Text(
                text = outgoing,
                color = color,
                style = style,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(blurModifier(p))
                    .graphicsLayer {
                        alpha = 1f - p
                        translationY = -slide * p
                    }
            )
            // 新文案：从下方模糊滑入、逐渐聚焦
            Text(
                text = text,
                color = color,
                style = style,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(blurModifier(1f - p))
                    .graphicsLayer {
                        alpha = p
                        translationY = slide * (1f - p)
                    }
            )
        }
    }
}

/** 呼吸动画（详情页 / 悬浮窗共用） */
@Composable
internal fun rememberPulseAlphaShared(): Float {
    val transition = rememberInfiniteTransition(label = "pulseShared")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            tween(700),
            RepeatMode.Reverse
        ),
        label = "pulseAlphaShared"
    ).value
}

/** 状态色平滑过渡（配合文字动态模糊，避免生硬跳变） */
@Composable
internal fun animateToneColor(target: Color): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "aiStatusColor"
    ).value
