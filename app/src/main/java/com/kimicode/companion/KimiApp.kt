package com.kimicode.companion

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.util.Log
import com.kimicode.companion.data.KimiApi
import com.kimicode.companion.data.Repository
import com.kimicode.companion.data.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口 + 轻量手动依赖容器。
 * Repository / Api / Settings 全局单例，MonitorService 与 UI 共享。
 */
class KimiApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var api: KimiApi
        private set
    lateinit var repository: Repository
        private set

    /** v0.5 闪退修复 #2：全局协程异常兜底（DataStore / IO 异常不再炸掉进程） */
    private val crashGuard = CoroutineExceptionHandler { _, throwable ->
        Log.w("KimiApp", "uncaught in app scope (guarded)", throwable)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    /** 当前是否有 Activity 处于前台（用于防打扰"前台静默"判断） */
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    /** 打开 App 时是否自动弹出审批层（通知/小组件点击触发） */
    val autoOpenApproval = java.util.concurrent.atomic.AtomicBoolean(false)

    /** v0.5.2：任务通知点击 → 打开对应会话详情页的请求流 */
    val openSessionRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    override fun onCreate() {
        super.onCreate()
        _instance = this

        settings = SettingsRepository(this)
        api = KimiApi()
        repository = Repository()

        // 跟踪设置变化，同步给 API 客户端
        // v0.5 闪退修复 #3：collect 全程 try-catch——DataStore 在连接保存设置后
        // 首次密集读写，若发生 CorruptionException/IOException 直接导致"连接后闪退"
        appScope.launch {
            runCatching {
                settings.settings.collect { s ->
                    api.updateConfig(s.config)
                }
            }.onFailure { Log.w("KimiApp", "settings collector failed", it) }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                isAppInForeground = started > 0
            }

            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                isAppInForeground = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        private lateinit var _instance: KimiApp

        val instance: KimiApp get() = _instance

        /** 从任意 Context 取容器 */
        fun of(context: android.content.Context): KimiApp =
            context.applicationContext as KimiApp
    }
}
