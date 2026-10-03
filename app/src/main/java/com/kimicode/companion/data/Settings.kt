package com.kimicode.companion.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kimi_settings")

/** 通知设置（v0.5.2）：横幅常驻通知 / 工作通知模式 / 各类提醒开关 */
enum class WorkNotifMode { MINIMAL, DETAILED }

/**
 * v0.5.5：提醒通道 —— 审批 / 问题 / 任务完成提醒的统一呈现方式。
 */
enum class ReminderChannel { FLOATING, NOTIFICATION }

/**
 * v0.5.9：经典主题配色（动态取色关闭时可选，共四种）。
 * - INDIGO：Kimi 靛蓝（品牌默认）
 * - EMERALD：翠绿
 * - AMBER：琥珀金
 * - ROSE：玫瑰
 */
enum class ThemePalette { INDIGO, EMERALD, AMBER, ROSE }

/**
 * v0.5.9：样式设置（设置 → 样式）。
 * - dynamicColor：MD3 动态取色（Material You，Android 12+ 跟随系统壁纸配色）；
 *   关闭时使用 [palette] 指定的经典配色
 * - reduceBlur：减少模糊 —— 开启时维持现状；关闭时在此基础上为顶栏、
 *   界面切换等加入更多动态模糊 / 毛玻璃效果（Android 12+ 生效）
 */
data class StyleSettings(
    val dynamicColor: Boolean = true,
    val palette: ThemePalette = ThemePalette.INDIGO,
    val reduceBlur: Boolean = true
)

data class NotifySettings(
    /** 横幅常驻通知：任务运行时在屏幕顶部展示实时状态（多任务时最早任务上横幅） */
    val bannerEnabled: Boolean = true,
    /** 工作状态常驻通知（FGS）的展示模式：极简（原来的）/ 详细（实时状态） */
    val workMode: WorkNotifMode = WorkNotifMode.DETAILED,
    /** v0.5.5：提醒通道（审批 / 问题 / 完成 提醒统一走悬浮窗或通知） */
    val reminderChannel: ReminderChannel = ReminderChannel.FLOATING,
    /** 审批强提醒开关 */
    val approvalEnabled: Boolean = true,
    /** 问题提醒开关 */
    val questionEnabled: Boolean = true,
    /** 任务完成提醒开关 */
    val completionEnabled: Boolean = true
)

/** 一次连接的完整配置 */
data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 58627,
    val token: String = "",
    /** 防打扰：App 在前台时静默任务完成提醒 */
    val dndForegroundSilence: Boolean = true,
    /** 防打扰：任务运行低于该分钟数不提醒 */
    val dndMinDurationMin: Int = 5
) {
    val baseUrl: String get() = "http://$host:$port"
    val wsUrl: String get() = "ws://$host:$port/api/v1/ws"
}

data class AppSettings(
    val setupDone: Boolean = false,
    val config: ServerConfig = ServerConfig(),
    /** 最近一次连接成功时的服务器版本（控制台展示用） */
    val serverVersion: String? = null,
    /** v0.5.2：通知设置 */
    val notify: NotifySettings = NotifySettings(),
    /** v0.5.9：样式设置（主题配色 / 动态取色 / 减少模糊） */
    val style: StyleSettings = StyleSettings()
)

/**
 * 应用设置存储：DataStore（Preferences）。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val DND_FOREGROUND = booleanPreferencesKey("dnd_foreground_silence")
        val DND_MIN_MINUTES = intPreferencesKey("dnd_min_duration_min")
        val SERVER_VERSION = stringPreferencesKey("server_version")

        // v0.5.2 通知设置
        val NOTIFY_BANNER = booleanPreferencesKey("notify_banner_enabled")
        val NOTIFY_WORK_MODE = stringPreferencesKey("notify_work_mode")
        // v0.5.5 提醒通道
        val NOTIFY_REMINDER_CHANNEL = stringPreferencesKey("notify_reminder_channel")
        val NOTIFY_APPROVAL = booleanPreferencesKey("notify_approval_enabled")
        val NOTIFY_QUESTION = booleanPreferencesKey("notify_question_enabled")
        val NOTIFY_COMPLETION = booleanPreferencesKey("notify_completion_enabled")

        // v0.5.9 样式设置
        val STYLE_DYNAMIC_COLOR = booleanPreferencesKey("style_dynamic_color")
        val STYLE_PALETTE = stringPreferencesKey("style_palette")
        val STYLE_REDUCE_BLUR = booleanPreferencesKey("style_reduce_blur")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            setupDone = p[Keys.SETUP_DONE] ?: false,
            config = ServerConfig(
                host = p[Keys.HOST] ?: "127.0.0.1",
                port = p[Keys.PORT] ?: 58627,
                token = p[Keys.TOKEN] ?: "",
                dndForegroundSilence = p[Keys.DND_FOREGROUND] ?: true,
                dndMinDurationMin = p[Keys.DND_MIN_MINUTES] ?: 5
            ),
            serverVersion = p[Keys.SERVER_VERSION],
            notify = NotifySettings(
                bannerEnabled = p[Keys.NOTIFY_BANNER] ?: true,
                workMode = when (p[Keys.NOTIFY_WORK_MODE]) {
                    "minimal" -> WorkNotifMode.MINIMAL
                    else -> WorkNotifMode.DETAILED
                },
                reminderChannel = when (p[Keys.NOTIFY_REMINDER_CHANNEL]) {
                    "notification" -> ReminderChannel.NOTIFICATION
                    else -> ReminderChannel.FLOATING
                },
                approvalEnabled = p[Keys.NOTIFY_APPROVAL] ?: true,
                questionEnabled = p[Keys.NOTIFY_QUESTION] ?: true,
                completionEnabled = p[Keys.NOTIFY_COMPLETION] ?: true
            ),
            style = StyleSettings(
                dynamicColor = p[Keys.STYLE_DYNAMIC_COLOR] ?: true,
                palette = when (p[Keys.STYLE_PALETTE]) {
                    "emerald" -> ThemePalette.EMERALD
                    "amber" -> ThemePalette.AMBER
                    "rose" -> ThemePalette.ROSE
                    else -> ThemePalette.INDIGO
                },
                reduceBlur = p[Keys.STYLE_REDUCE_BLUR] ?: true
            )
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveServer(host: String, port: Int, token: String) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = host
            p[Keys.PORT] = port
            p[Keys.TOKEN] = token
        }
    }

    suspend fun setSetupDone(done: Boolean = true) {
        context.dataStore.edit { it[Keys.SETUP_DONE] = done }
    }

    suspend fun saveDnd(foregroundSilence: Boolean, minMinutes: Int) {
        context.dataStore.edit { p ->
            p[Keys.DND_FOREGROUND] = foregroundSilence
            p[Keys.DND_MIN_MINUTES] = minMinutes
        }
    }

    /** v0.5.2：保存通知设置（设置页写入） */
    suspend fun saveNotifySettings(notify: NotifySettings) {
        context.dataStore.edit { p ->
            p[Keys.NOTIFY_BANNER] = notify.bannerEnabled
            p[Keys.NOTIFY_WORK_MODE] = if (notify.workMode == WorkNotifMode.MINIMAL) "minimal" else "detailed"
            p[Keys.NOTIFY_REMINDER_CHANNEL] = when (notify.reminderChannel) {
                ReminderChannel.NOTIFICATION -> "notification"
                ReminderChannel.FLOATING -> "floating"
            }
            p[Keys.NOTIFY_APPROVAL] = notify.approvalEnabled
            p[Keys.NOTIFY_QUESTION] = notify.questionEnabled
            p[Keys.NOTIFY_COMPLETION] = notify.completionEnabled
        }
    }

    /** v0.5.9：保存样式设置（设置页「样式」子页写入） */
    suspend fun saveStyleSettings(style: StyleSettings) {
        context.dataStore.edit { p ->
            p[Keys.STYLE_DYNAMIC_COLOR] = style.dynamicColor
            p[Keys.STYLE_PALETTE] = when (style.palette) {
                ThemePalette.EMERALD -> "emerald"
                ThemePalette.AMBER -> "amber"
                ThemePalette.ROSE -> "rose"
                ThemePalette.INDIGO -> "indigo"
            }
            p[Keys.STYLE_REDUCE_BLUR] = style.reduceBlur
        }
    }

    /** 记录最近一次连接成功时服务器上报的版本（控制台展示用） */
    suspend fun saveServerVersion(version: String?) {
        context.dataStore.edit { p ->
            if (version.isNullOrBlank()) p.remove(Keys.SERVER_VERSION)
            else p[Keys.SERVER_VERSION] = version
        }
    }
}
