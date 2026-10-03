package com.kimicode.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kimicode.companion.service.MonitorService
import com.kimicode.companion.ui.AppRoot
import com.kimicode.companion.ui.KimiTheme
import com.kimicode.companion.ui.LocalGlassEffects
import com.kimicode.companion.data.AppSettings
import kotlinx.coroutines.runBlocking

/**
 * 主入口：连接页 / 控制台（v0.5：连接成功后进入单页控制台，
 * 点击进行中的任务进入任务详情页）。
 * 小组件与通知点击会带 EXTRA_OPEN_APPROVAL 直达审批卡片。
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_APPROVAL = "open_approval"
        /** v0.5.2：任务通知点击直达会话详情页 */
        const val EXTRA_SESSION_ID = "session_id"
        private const val TAG = "MainActivity"
    }

    private var setupDoneSnapshot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = KimiApp.of(this)

        if (intent?.getBooleanExtra(EXTRA_OPEN_APPROVAL, false) == true) {
            app.autoOpenApproval.set(true)
        }
        // v0.5.2：任务通知点击 → 直达对应会话详情页
        intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let {
            app.openSessionRequests.tryEmit(it)
        }

        // v0.5 闪退修复 #1：DataStore 读取加异常保护。
        // v0.4 在此处直接 runBlocking，DataStore 损坏 / IO 异常会直接把 onCreate 炸掉 → 白屏闪退。
        val initial = runCatching { runBlocking { app.settings.current() } }
            .getOrElse {
                Log.w(TAG, "settings read failed, fallback to defaults", it)
                AppSettings()
            }
        setupDoneSnapshot = initial.setupDone

        setContent {
            // v0.5.9：收集样式设置（动态取色 / 经典配色 / 减少模糊），主题即时切换
            val liveSettings by app.settings.settings.collectAsStateWithLifecycle(initialValue = initial)
            KimiTheme(style = liveSettings.style) {
                CompositionLocalProvider(LocalGlassEffects provides !liveSettings.style.reduceBlur) {
                    AppRoot(activity = this, initialSettings = initial)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_APPROVAL, false)) {
            KimiApp.of(this).autoOpenApproval.set(true)
        }
        // v0.5.2：任务通知点击 → 直达对应会话详情页（singleTask 热启动路径）
        intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }?.let {
            KimiApp.of(this).openSessionRequests.tryEmit(it)
        }
    }

    override fun onStart() {
        super.onStart()
        // 打开 App 即确保监控服务在跑（通知 / 小组件持续更新）
        if (setupDoneSnapshot) {
            runCatching { MonitorService.start(this) }
        }
    }
}
