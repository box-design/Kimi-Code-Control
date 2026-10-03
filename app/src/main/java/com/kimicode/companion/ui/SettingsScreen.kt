package com.kimicode.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kimicode.companion.KimiApp
import com.kimicode.companion.R
import com.kimicode.companion.data.AppSettings
import com.kimicode.companion.data.NotifySettings
import com.kimicode.companion.data.ReminderChannel
import com.kimicode.companion.data.StyleSettings
import com.kimicode.companion.data.ThemePalette
import com.kimicode.companion.data.WorkNotifMode
import com.kimicode.companion.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.Build

/** 设置子页（v0.5.7：设置主页 + 分类子页结构；v0.5.9 新增「样式」子页） */
private enum class SettingsPage { MAIN, NOTIFICATIONS, STYLE }

/**
 * 设置页（v0.5.7 重构为「主页 + 分类子页」结构）：
 * - 主页：设置分类入口（当前提供「通知」类别，后续可扩展更多设置类别）
 * - 通知子页：v0.5.2 引入的通知设置全部能力，按「运行状态 / 提醒 / 权限」分组呈现
 *
 * 历史演进：
 * - v0.5.2 新增通知设置页，v0.5.4 增悬浮窗权限，v0.5.5 增提醒通道
 * - v0.5.7 升级为设置主页 + 分类子页；通知子页重新分组并统一术语
 *   （「横幅常驻通知」→「任务状态横幅」、「详细模式（悬浮窗）」→「悬浮窗面板」、
 *   「悬浮窗提醒」→「悬浮窗提示卡」），功能与视觉风格保持不变
 *
 * 子页转场与根导航保持一致的横向滑动动画；系统返回键先回到设置主页。
 * 修改实时生效（DataStore → MonitorService 收集器即时重发通知）。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }

    // 子页返回：通知子页 → 设置主页（设置主页的返回由根导航处理）
    BackHandler(enabled = page != SettingsPage.MAIN) {
        page = SettingsPage.MAIN
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // v0.5.9：与根导航同款 MD3 emphasized 非线性转场（部分位移 + 交叉淡入淡出）
            val forward = targetState != SettingsPage.MAIN
            val enter = slideInHorizontally(
                animationSpec = tween(KimiMotion.DUR_LONG, easing = KimiMotion.EmphasizedDecelerate)
            ) { if (forward) it / 5 else -it / 6 } + fadeIn(
                animationSpec = tween(KimiMotion.DUR_MEDIUM, delayMillis = 50, easing = KimiMotion.Standard)
            )
            val exit = slideOutHorizontally(
                animationSpec = tween(260, easing = KimiMotion.EmphasizedAccelerate)
            ) { if (forward) -it / 7 else it / 7 } + fadeOut(
                animationSpec = tween(KimiMotion.DUR_SHORT, easing = KimiMotion.Standard)
            )
            enter togetherWith exit
        },
        label = "settingsNav"
    ) { current ->
        // v0.5.10：子页切换动态模糊（「减少模糊」关闭时生效；开启时返回 0 无模糊）
        val blurRadius = enterExitBlurProgress(maxRadiusDp = 20f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dynamicBlur(blurRadius)
        ) {
            when (current) {
                SettingsPage.MAIN -> SettingsMainPage(
                    onBack = onBack,
                    onOpenNotifications = { page = SettingsPage.NOTIFICATIONS },
                    onOpenStyle = { page = SettingsPage.STYLE }
                )

                SettingsPage.NOTIFICATIONS -> NotificationSettingsPage(
                    onBack = { page = SettingsPage.MAIN }
                )

                SettingsPage.STYLE -> StyleSettingsPage(
                    onBack = { page = SettingsPage.MAIN }
                )
            }
        }
    }
}

// ================= 设置主页 =================

/**
 * 设置主页（v0.5.7）：设置分类入口列表。
 * v0.5.9 新增「样式」入口（主题配色 / 动态取色 / 减少模糊）；
 * 入口行带阶梯式入场动画（上滑 + 淡入，emphasized 减速曲线）。
 */
@Composable
private fun SettingsMainPage(
    onBack: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenStyle: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 通知 ----------
            EntranceBlock(delayMillis = 0) {
                SettingNavRow(
                    icon = {
                        Icon(
                            Icons.Rounded.NotificationsActive,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = stringResource(R.string.settings_notify_entry_title),
                    description = stringResource(R.string.settings_notify_entry_desc),
                    onClick = onOpenNotifications
                )
            }

            // ---------- 样式（v0.5.9） ----------
            EntranceBlock(delayMillis = 70) {
                SettingNavRow(
                    icon = {
                        Icon(
                            Icons.Rounded.Palette,
                            null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    },
                    title = stringResource(R.string.settings_style_entry_title),
                    description = stringResource(R.string.settings_style_entry_desc),
                    onClick = onOpenStyle
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_more_coming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // ---------- 底部版本信息 ----------
            Spacer(Modifier.height(24.dp))
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            Text(
                text = stringResource(
                    R.string.settings_version_footer,
                    stringResource(R.string.app_name), versionName
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
    }
}

// ================= 通知子页 =================

/**
 * 通知子页（v0.5.7 重组）：原通知设置全部能力，按功能分三组：
 * - 运行状态：任务状态横幅（原「横幅常驻通知」）/ 工作状态通知（极简通知 · 悬浮窗面板）
 * - 提醒：提醒方式（悬浮窗提示卡 · 系统通知）/ 审批 / 问题 / 任务完成开关
 * - 权限：悬浮窗权限（授权状态展示 + 「去授权」入口，返回本页自动刷新并重算悬浮窗）
 * 修改实时生效（DataStore → MonitorService 收集器即时重发通知）。
 */
@Composable
private fun NotificationSettingsPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val scope = rememberCoroutineScope()

    val settings by app.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.kimicode.companion.data.AppSettings()
    )
    val notify = settings.notify

    // v0.5.4：悬浮窗权限状态（返回本页时刷新）
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    // 从系统授权页返回 → 刷新权限状态；新授权则触发一次悬浮窗重算
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var lastGranted = overlayGranted
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = android.provider.Settings.canDrawOverlays(context)
                if (now != lastGranted) {
                    lastGranted = now
                    overlayGranted = now
                    MonitorService.send(context, MonitorService.ACTION_REFRESH_WORK)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestOverlayPermission() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
    }

    fun save(update: (NotifySettings) -> NotifySettings) {
        val next = update(notify)
        scope.launch(Dispatchers.IO) {
            runCatching { app.settings.saveNotifySettings(next) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            title = stringResource(R.string.settings_notify_title),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ========== 分组一：运行状态 ==========
            SectionLabel(text = stringResource(R.string.settings_section_status))
            Spacer(Modifier.height(8.dp))

            // ---------- 任务状态横幅（原「横幅常驻通知」） ----------
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.Campaign, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_banner_title),
                description = stringResource(R.string.settings_banner_desc),
                checked = notify.bannerEnabled,
                onCheckedChange = { checked -> save { it.copy(bannerEnabled = checked) } }
            )

            // ---------- 工作状态通知：极简通知 / 悬浮窗面板 ----------
            SettingModeRow(
                title = stringResource(R.string.settings_work_mode_title),
                description = stringResource(R.string.settings_work_mode_desc),
                mode = notify.workMode,
                onModeChange = { mode ->
                    save { it.copy(workMode = mode) }
                    // 切到悬浮窗面板且未授权 → 引导授权
                    if (mode == WorkNotifMode.DETAILED && !overlayGranted) {
                        requestOverlayPermission()
                    }
                }
            )

            // ========== 分组二：提醒 ==========
            SectionLabel(text = stringResource(R.string.settings_section_reminders))
            Spacer(Modifier.height(8.dp))

            // ---------- 提醒方式：悬浮窗提示卡 / 系统通知 ----------
            SettingChannelRow(
                title = stringResource(R.string.settings_reminder_channel_title),
                description = stringResource(R.string.settings_reminder_channel_desc),
                channel = notify.reminderChannel,
                overlayGranted = overlayGranted,
                onChannelChange = { channel ->
                    save { it.copy(reminderChannel = channel) }
                    // 选悬浮窗提示卡且未授权 → 引导授权
                    if (channel == ReminderChannel.FLOATING && !overlayGranted) {
                        requestOverlayPermission()
                    }
                }
            )

            // ---------- 审批提醒 ----------
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.error) },
                title = stringResource(R.string.settings_approval_title),
                description = stringResource(R.string.settings_approval_desc),
                checked = notify.approvalEnabled,
                onCheckedChange = { checked -> save { it.copy(approvalEnabled = checked) } }
            )

            // ---------- 问题提醒 ----------
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.tertiary) },
                title = stringResource(R.string.settings_question_title),
                description = stringResource(R.string.settings_question_desc),
                checked = notify.questionEnabled,
                onCheckedChange = { checked -> save { it.copy(questionEnabled = checked) } }
            )

            // ---------- 任务完成提醒 ----------
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.settings_completion_title),
                description = stringResource(R.string.settings_completion_desc),
                checked = notify.completionEnabled,
                onCheckedChange = { checked -> save { it.copy(completionEnabled = checked) } }
            )

            // ========== 分组三：权限 ==========
            SectionLabel(text = stringResource(R.string.settings_section_permission))
            Spacer(Modifier.height(8.dp))

            // ---------- 悬浮窗权限 ----------
            OverlayPermissionRow(
                granted = overlayGranted,
                onRequest = { requestOverlayPermission() }
            )

            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
    }
}

// ================= 样式子页（v0.5.9） =================

/**
 * 样式子页（v0.5.9 新增；v0.5.10 调整动效描述）：
 * - 主题色：MD3 动态取色开关（Android 12+；关闭时可选四种经典配色）
 * - 动效：减少模糊开关（开启全局无附加模糊；关闭后加入顶栏高斯动态
 *   毛玻璃与界面切换动态模糊；Aurora 光斑已于 v0.5.10 移除）
 * 修改即时生效（DataStore → KimiTheme 实时重组）。
 */
@Composable
private fun StyleSettingsPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val scope = rememberCoroutineScope()

    val settings by app.settings.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    val style = settings.style
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun save(update: (StyleSettings) -> StyleSettings) {
        val next = update(style)
        scope.launch(Dispatchers.IO) {
            runCatching { app.settings.saveStyleSettings(next) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(
            title = stringResource(R.string.settings_style_title),
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ========== 分组一：主题色 ==========
            EntranceBlock(delayMillis = 0) {
                Column {
                    SectionLabel(text = stringResource(R.string.settings_style_section_theme))
                    Spacer(Modifier.height(8.dp))

                    // ---------- MD3 动态取色 ----------
                    SettingSwitchRow(
                        icon = {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        title = stringResource(R.string.settings_style_dynamic_title),
                        description = if (dynamicAvailable) {
                            stringResource(R.string.settings_style_dynamic_desc)
                        } else {
                            stringResource(R.string.settings_style_dynamic_unavailable)
                        },
                        checked = style.dynamicColor && dynamicAvailable,
                        enabled = dynamicAvailable,
                        onCheckedChange = { checked -> save { it.copy(dynamicColor = checked) } }
                    )
                }
            }

            // ---------- 经典配色（动态取色关闭时可选） ----------
            EntranceBlock(delayMillis = 60) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    val gridEnabled = !style.dynamicColor

                    // 选中态整体淡入淡出 + 玻璃化（动态取色开启时不可用）
                    val gridAlpha by animateFloatAsState(
                        targetValue = if (gridEnabled) 1f else 0.45f,
                        animationSpec = KimiMotion.soft(),
                        label = "paletteGridAlpha"
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = gridAlpha }
                            .then(
                                // v0.5.9：不可用时轻微模糊，玻璃拟态的「禁用层」
                                if (gridEnabled) Modifier
                                else if (LocalGlassEffects.current) Modifier.blur(1.5.dp)
                                else Modifier
                            )
                    ) {
                        SectionLabel(text = stringResource(R.string.settings_style_palette_title))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_style_palette_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        Spacer(Modifier.height(10.dp))

                        // 2 × 2 配色卡片
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PaletteOptionCard(
                                palette = ThemePalette.INDIGO,
                                name = stringResource(R.string.settings_palette_indigo),
                                selected = style.palette == ThemePalette.INDIGO,
                                enabled = gridEnabled,
                                onClick = { save { it.copy(palette = ThemePalette.INDIGO) } },
                                modifier = Modifier.weight(1f)
                            )
                            PaletteOptionCard(
                                palette = ThemePalette.EMERALD,
                                name = stringResource(R.string.settings_palette_emerald),
                                selected = style.palette == ThemePalette.EMERALD,
                                enabled = gridEnabled,
                                onClick = { save { it.copy(palette = ThemePalette.EMERALD) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PaletteOptionCard(
                                palette = ThemePalette.AMBER,
                                name = stringResource(R.string.settings_palette_amber),
                                selected = style.palette == ThemePalette.AMBER,
                                enabled = gridEnabled,
                                onClick = { save { it.copy(palette = ThemePalette.AMBER) } },
                                modifier = Modifier.weight(1f)
                            )
                            PaletteOptionCard(
                                palette = ThemePalette.ROSE,
                                name = stringResource(R.string.settings_palette_rose),
                                selected = style.palette == ThemePalette.ROSE,
                                enabled = gridEnabled,
                                onClick = { save { it.copy(palette = ThemePalette.ROSE) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (!gridEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_style_palette_locked),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            // ========== 分组二：动效 ==========
            EntranceBlock(delayMillis = 120) {
                Column {
                    SectionLabel(text = stringResource(R.string.settings_style_section_effects))
                    Spacer(Modifier.height(8.dp))

                    // ---------- 减少模糊 ----------
                    SettingSwitchRow(
                        icon = {
                            Icon(
                                Icons.Rounded.BlurOn,
                                null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        },
                        title = stringResource(R.string.settings_style_blur_title),
                        description = stringResource(R.string.settings_style_blur_desc),
                        checked = style.reduceBlur,
                        onCheckedChange = { checked -> save { it.copy(reduceBlur = checked) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (style.reduceBlur) {
                            stringResource(R.string.settings_style_blur_state_off)
                        } else {
                            stringResource(R.string.settings_style_blur_state_on)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            Spacer(Modifier.navigationBarsPadding().height(32.dp))
        }
    }
}

/**
 * 经典配色选项卡片（v0.5.9）：
 * 色带（primary / secondary / tertiary 预览）+ 名称；选中时回弹放大 + 主色描边。
 */
@Composable
private fun PaletteOptionCard(
    palette: ThemePalette,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = palette.lightScheme
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.025f else 1f,
        animationSpec = KimiMotion.bouncy(),
        label = "paletteScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 1.8.dp else 1.dp,
        animationSpec = KimiMotion.placement(),
        label = "paletteBorder"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
        label = "paletteBorderColor"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
            )
            .border(borderWidth, borderColor, RoundedCornerShape(18.dp))
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(scheme.primary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(scheme.tertiary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(scheme.secondaryContainer, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f), CircleShape)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            // 选中指示点（弹入）
            val dotScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = KimiMotion.bouncy(),
                label = "paletteDot"
            )
            if (dotScale > 0.01f) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}

// ================= 通用组件 =================

/** 设置页顶栏（主页与各子页共用；v0.5.9 状态栏边距适配） */
@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 12.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 分类入口行（圆角卡片 + 图标 + 标题 + 描述 + 右箭头），点击进入对应子页；v0.5.9 回弹按压 */
@Composable
private fun SettingNavRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .bouncyClickable(pressedScale = 0.97f) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ================= 悬浮窗权限行（v0.5.4） =================

@Composable
private fun OverlayPermissionRow(
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (granted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Icon(
                if (granted) Icons.Rounded.Verified else Icons.Rounded.Layers,
                null,
                tint = if (granted) Color(0xFF34C77B) else MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_overlay_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (granted) stringResource(R.string.settings_overlay_granted)
                else stringResource(R.string.settings_overlay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRequest) {
                Text(stringResource(R.string.settings_overlay_grant))
            }
        }
    }
}

// ================= 通用设置行 =================

@Composable
private fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 带开关的设置行（圆角卡片 + 图标 + 标题 + 描述）；v0.5.9 支持 enabled（如低版本无动态取色） */
@Composable
private fun SettingSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/** 提醒方式选择行（v0.5.5）：审批 / 问题 / 完成 提醒统一走悬浮窗提示卡或系统通知 */
@Composable
private fun SettingChannelRow(
    title: String,
    description: String,
    channel: ReminderChannel,
    overlayGranted: Boolean,
    onChannelChange: (ReminderChannel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ModeOption(
            label = stringResource(R.string.settings_reminder_floating),
            hint = if (overlayGranted) stringResource(R.string.settings_reminder_floating_desc)
            else stringResource(R.string.settings_reminder_floating_desc_need_grant),
            selected = channel == ReminderChannel.FLOATING,
            onClick = { onChannelChange(ReminderChannel.FLOATING) }
        )
        Spacer(Modifier.height(6.dp))
        ModeOption(
            label = stringResource(R.string.settings_reminder_notification),
            hint = stringResource(R.string.settings_reminder_notification_desc),
            selected = channel == ReminderChannel.NOTIFICATION,
            onClick = { onChannelChange(ReminderChannel.NOTIFICATION) }
        )
    }
}

/** 二选一模式行：工作状态通知 · 极简通知（原来的）/ 悬浮窗面板（v0.5.2 引入） */
@Composable
private fun SettingModeRow(
    title: String,
    description: String,
    mode: WorkNotifMode,
    onModeChange: (WorkNotifMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 两个模式选项（单选卡片）
        ModeOption(
            label = stringResource(R.string.settings_work_mode_detailed),
            hint = stringResource(R.string.settings_work_mode_detailed_desc),
            selected = mode == WorkNotifMode.DETAILED,
            onClick = { onModeChange(WorkNotifMode.DETAILED) }
        )
        Spacer(Modifier.height(6.dp))
        ModeOption(
            label = stringResource(R.string.settings_work_mode_minimal),
            hint = stringResource(R.string.settings_work_mode_minimal_desc),
            selected = mode == WorkNotifMode.MINIMAL,
            onClick = { onModeChange(WorkNotifMode.MINIMAL) }
        )
    }
}

@Composable
private fun ModeOption(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // v0.5.9：选中态非线性过渡（颜色 / 描边 / 单选指示点均带弹簧）
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
        label = "modeBorderColor"
    )
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Prompt),
        label = "modeBg"
    )
    val dotBorder by animateDpAsState(
        targetValue = if (selected) 5.dp else 2.dp,
        animationSpec = KimiMotion.placement(),
        label = "modeDotBorder"
    )
    val dotScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = KimiMotion.bouncy(),
        label = "modeDotScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .bouncyClickable(pressedScale = 0.98f) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 单选指示点
        Box(
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    scaleX = dotScale
                    scaleY = dotScale
                }
                .clip(CircleShape)
                .border(
                    width = dotBorder,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .alpha(if (selected) 1f else 0.7f)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
