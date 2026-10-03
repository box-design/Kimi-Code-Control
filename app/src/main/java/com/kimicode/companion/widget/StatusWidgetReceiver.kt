package com.kimicode.companion.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.kimicode.companion.KimiApp
import com.kimicode.companion.data.ConnState
import com.kimicode.companion.data.WidgetStatus
import com.kimicode.companion.data.WidgetUiState
import com.kimicode.companion.service.MonitorService
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 小组件接收器：
 * - 常规 Glance 渲染（读取 WidgetStore 快照）
 * - 修复更新迟滞：系统周期 APPWIDGET_UPDATE（30 分钟，系统允许的最小值）到达时，
 *   1) App 在前台 → 拉起 MonitorService 恢复 WS 实时推送；
 *   2) 无论服务状态如何，再走一次轻量 REST 快照刷新（会话列表直接推导状态），
 *      不依赖监控服务存活——服务被空闲回收后小组件也能周期性自愈，不再长期冻结。
 */
class StatusWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = StatusWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            // 前台时优先恢复常驻监控（WS 实时推送；后台启动前台服务受系统限制）
            runCatching {
                if (KimiApp.instance.isAppInForeground) {
                    MonitorService.start(context)
                }
            }
            runCatching { refreshFromRest(context) }
        }
    }

    /** 轻量刷新：REST 会话列表 → 粗粒度状态 → WidgetStore + Glance 更新 */
    private suspend fun refreshFromRest(context: Context) {
        val app = KimiApp.of(context)

        // 监控服务在线且 WS 已连接时，精细状态（执行命令/写文件等 phase）由实时
        // 推送维护，粗粒度快照不覆盖，避免小组件状态倒退。
        if (app.repository.connection.value == ConnState.CONNECTED) return

        // 进程冷启动时 API 配置可能尚未从 DataStore 同步完成，先同步一次
        runCatching { app.settings.current().config }.getOrNull()
            ?.let { app.api.updateConfig(it) }

        val sessions = runCatching { app.api.listSessions() }.getOrNull() ?: return

        val busyCount = sessions.count { it.busy }
        val anyApproval = sessions.any { it.pending_interaction == "approval" }
        val now = System.currentTimeMillis()
        val state = when {
            anyApproval -> WidgetUiState(
                status = WidgetStatus.WAITING_APPROVAL,
                runningTasks = busyCount,
                sinceMs = now
            )
            busyCount > 0 -> WidgetUiState(
                status = WidgetStatus.THINKING,
                runningTasks = busyCount,
                sinceMs = now
            )
            else -> WidgetUiState(
                status = WidgetStatus.IDLE,
                runningTasks = 0,
                sinceMs = 0
            )
        }

        WidgetStore.write(context, state)
        GlanceAppWidgetManager(context)
            .getGlanceIds(StatusWidget::class.java)
            .forEach { StatusWidget().update(context, it) }
    }
}
