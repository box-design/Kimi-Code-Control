package com.kimicode.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.AppSettings
import com.kimicode.companion.data.ProbeResult
import com.kimicode.companion.service.MonitorService
import kotlinx.coroutines.launch

/** 连接测试状态 */
sealed interface ProbeUi {
    data object Idle : ProbeUi
    data object Testing : ProbeUi
    data object Scanning : ProbeUi
    data class Online(val version: String?) : ProbeUi
    data class NeedToken(val reason: String? = null) : ProbeUi
    data class Unreachable(val reason: String? = null) : ProbeUi
}

/**
 * 连接页 / 修改服务器页（v0.5.8 调整）：
 * 顶部标题与首页一致 —— 合并原渐变品牌横幅与「Kimi Code 控制台」标题，
 * 统一为无边框的「Kimi Code Control」（titleLarge · SemiBold）。
 * 保留全部连接能力：healthz 探测、端口自动扫描、Token 鉴权提示、防打扰选项。
 */
@Composable
fun ConnectScreen(
    initial: AppSettings,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(initial.config.host) }
    var portText by remember { mutableStateOf(initial.config.port.toString()) }
    var token by remember { mutableStateOf(initial.config.token) }
    var showAdvanced by remember { mutableStateOf(initial.config.token.isNotBlank()) }
    var dndForeground by remember { mutableStateOf(initial.config.dndForegroundSilence) }
    var dndMinMin by remember { mutableStateOf(initial.config.dndMinDurationMin) }
    var probe by remember { mutableStateOf<ProbeUi>(ProbeUi.Idle) }
    var showTokenError by remember { mutableStateOf(false) }
    var autoFilledPort by remember { mutableStateOf<Int?>(null) }
    var entering by remember { mutableStateOf(false) }

    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val hostValid = host.isNotBlank()

    // ---------- 通知权限（进入时确认，保证审批/完成提醒可达） ----------
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.notification_permission_hint),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }

    fun checkNotifyPermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            pendingAfterPermission = then
        } else then()
    }

    // ---------- 连接逻辑（探测 + 端口扫描） ----------
    suspend fun probeWithScan(): ProbeResult {
        val port = portText.toIntOrNull() ?: return ProbeResult.Unreachable("端口无效")
        var result = app.api.probe(host, port, token)
        if (result is ProbeResult.Unreachable) {
            probe = ProbeUi.Scanning
            val found = app.api.scanPort(host, port + 1, 9, token)
            if (found != null) {
                portText = found.toString()
                autoFilledPort = found
                result = app.api.probe(host, found, token)
            }
        }
        return result
    }

    fun testConnection() {
        if (!hostValid || !portValid) return
        probe = ProbeUi.Testing
        autoFilledPort = null
        scope.launch {
            val result = probeWithScan()
            probe = when (result) {
                is ProbeResult.Online -> ProbeUi.Online(result.version)
                is ProbeResult.NeedToken -> {
                    showAdvanced = true
                    showTokenError = true
                    ProbeUi.NeedToken(null)
                }
                is ProbeResult.Unreachable -> ProbeUi.Unreachable(result.reason)
            }
        }
    }

    fun doEnter() {
        if (!hostValid || !portValid || entering) return
        entering = true
        probe = ProbeUi.Testing
        autoFilledPort = null
        scope.launch {
            when (val result = probeWithScan()) {
                is ProbeResult.Online -> {
                    app.settings.saveServer(host, portText.toIntOrNull() ?: 58627, token)
                    app.settings.saveDnd(dndForeground, dndMinMin)
                    app.settings.saveServerVersion(result.version)
                    app.settings.setSetupDone(true)
                    app.api.updateConfig(
                        app.api.config.copy(
                            host = host, port = portText.toIntOrNull() ?: 58627, token = token
                        )
                    )
                    MonitorService.start(context)
                    onConnected()
                }

                is ProbeResult.NeedToken -> {
                    showAdvanced = true
                    showTokenError = true
                    probe = ProbeUi.NeedToken(null)
                    entering = false
                }

                is ProbeResult.Unreachable -> {
                    probe = ProbeUi.Unreachable(result.reason)
                    entering = false
                }
            }
        }
    }

    // ---------- 视觉 ----------
    val bgTop = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val bgBottom = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(bgTop, bgBottom), startY = 0f, endY = 900f)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 品牌标题（v0.5.8：与首页顶部栏同字体、无边框；v0.5.9 入场动画）
            EntranceBlock(delayMillis = 0) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.connect_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(30.dp))

            // 表单卡片（v0.5.9：入场动画 + 回弹按压）
            EntranceBlock(delayMillis = 80) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(
                            alpha = if (LocalGlassEffects.current) 0.86f else 0.92f
                        )
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                Column(Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim(); probe = ProbeUi.Idle },
                        label = { Text(stringResource(R.string.field_host)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5); probe = ProbeUi.Idle },
                        label = { Text(stringResource(R.string.field_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = !portValid,
                        supportingText = if (!portValid) {
                            { Text(stringResource(R.string.field_port_invalid)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 高级选项
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.advanced_options))
                    }
                    AnimatedVisibility(
                        visible = showAdvanced,
                        // v0.5.9：弹簧展开 / 收起（非线性，带轻微回弹）
                        enter = expandVertically(animationSpec = KimiMotion.soft()) +
                                fadeIn(
                                    animationSpec = tween(
                                        durationMillis = KimiMotion.DUR_MEDIUM,
                                        easing = KimiMotion.Standard
                                    )
                                ),
                        exit = shrinkVertically(animationSpec = KimiMotion.soft()) +
                               fadeOut(
                                   animationSpec = tween(
                                       durationMillis = KimiMotion.DUR_SHORT,
                                       easing = KimiMotion.Standard
                                   )
                               )
                    ) {
                        Column {
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it.trim(); showTokenError = false; probe = ProbeUi.Idle },
                                label = { Text(stringResource(R.string.field_token)) },
                                supportingText = {
                                    Text(
                                        stringResource(R.string.field_token_support),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                isError = showTokenError,
                                singleLine = true,
                                leadingIcon = {
                                    Icon(Icons.Rounded.Key, contentDescription = null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(14.dp))

                            DndRow(
                                title = stringResource(R.string.dnd_foreground_title),
                                support = stringResource(R.string.dnd_foreground_support),
                                checked = dndForeground
                            ) { dndForeground = it }
                            Spacer(Modifier.height(12.dp))
                            StepperRow(
                                title = if (dndMinMin == 0) stringResource(R.string.dnd_all_tasks)
                                else stringResource(R.string.dnd_min_duration_title),
                                support = stringResource(R.string.dnd_min_duration_support),
                                value = dndMinMin,
                                onDecrease = { if (dndMinMin > 0) dndMinMin -= 1 },
                                onIncrease = { if (dndMinMin < 60) dndMinMin += 1 }
                            )
                        }
                    }
                }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 连接状态
            ProbeStatus(probe)
            if (autoFilledPort != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.status_port_found, autoFilledPort!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // 主操作（v0.5.9：按压回弹 + 入场动画）
            val enterInteraction = remember { MutableInteractionSource() }
            val enterPressed by enterInteraction.collectIsPressedAsState()
            val enterScale by animateFloatAsState(
                targetValue = if (enterPressed) 0.965f else 1f,
                animationSpec = KimiMotion.bouncy(),
                label = "enterBtnScale"
            )
            EntranceBlock(delayMillis = 140) {
                Button(
                    onClick = { checkNotifyPermission { doEnter() } },
                    enabled = hostValid && portValid && !entering,
                    interactionSource = enterInteraction,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .graphicsLayer {
                            scaleX = enterScale
                            scaleY = enterScale
                        }
                ) {
                    if (entering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            stringResource(R.string.btn_enter),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val testInteraction = remember { MutableInteractionSource() }
            val testPressed by testInteraction.collectIsPressedAsState()
            val testScale by animateFloatAsState(
                targetValue = if (testPressed) 0.965f else 1f,
                animationSpec = KimiMotion.bouncy(),
                label = "testBtnScale"
            )
            EntranceBlock(delayMillis = 190) {
                OutlinedButton(
                    onClick = { testConnection() },
                    enabled = hostValid && portValid && probe !is ProbeUi.Testing && probe !is ProbeUi.Scanning,
                    interactionSource = testInteraction,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .graphicsLayer {
                            scaleX = testScale
                            scaleY = testScale
                        }
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_test))
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.connect_termux_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.navigationBarsPadding().height(40.dp))
        }
    }
}

/** 防打扰开关行 */
@Composable
private fun DndRow(
    title: String,
    support: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** 分钟数步进器 */
@Composable
private fun StepperRow(
    title: String,
    support: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                support,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDecrease) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.decrease))
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(30.dp)
        )
        IconButton(onClick = onIncrease) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.increase))
        }
    }
}

/** 连接状态反馈条（v0.5.9：弹簧展开 + 上滑入场，非线性） */
@Composable
private fun ProbeStatus(probe: ProbeUi) {
    AnimatedVisibility(
        visible = probe !is ProbeUi.Idle,
        enter = expandVertically(animationSpec = KimiMotion.soft()) +
                fadeIn(
                    animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard)
                ) +
                slideInVertically(
                    animationSpec = tween(KimiMotion.DUR_LONG, easing = KimiMotion.EmphasizedDecelerate)
                ) { it / 8 },
        exit = shrinkVertically(animationSpec = KimiMotion.soft()) +
               fadeOut(
                   animationSpec = tween(KimiMotion.DUR_SHORT, easing = KimiMotion.Standard)
               )
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (probe) {
                    is ProbeUi.Online -> MaterialTheme.colorScheme.primaryContainer
                    is ProbeUi.NeedToken, is ProbeUi.Unreachable -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                when (probe) {
                    ProbeUi.Testing, ProbeUi.Scanning -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    is ProbeUi.Online -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    is ProbeUi.NeedToken -> Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    is ProbeUi.Unreachable -> Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    ProbeUi.Idle -> {}
                }
                val text = when (probe) {
                    ProbeUi.Idle -> ""
                    ProbeUi.Testing -> stringResource(R.string.status_testing)
                    ProbeUi.Scanning -> stringResource(R.string.status_scanning)
                    is ProbeUi.Online -> stringResource(R.string.status_ok, probe.version ?: "unknown")
                    is ProbeUi.NeedToken -> stringResource(R.string.status_need_token)
                    is ProbeUi.Unreachable -> stringResource(R.string.status_unreachable) +
                        (probe.reason?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                }
                val textColor = when (probe) {
                    is ProbeUi.Online -> MaterialTheme.colorScheme.onPrimaryContainer
                    is ProbeUi.NeedToken, is ProbeUi.Unreachable -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (text.isNotBlank()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
            }
        }
    }
}

