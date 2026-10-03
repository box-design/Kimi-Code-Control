package com.kimicode.companion.widget

import android.content.Context
import com.kimicode.companion.data.WidgetStatus
import com.kimicode.companion.data.WidgetUiState

/**
 * 小组件状态存储：SharedPreferences（同步可读，Glance provideGlance 直接取）。
 * MonitorService 写入后触发 StatusWidget().update() 刷新。
 * v0.5.2：新增实时细节文本（工具调用 / 命令 / 文件路径）与 todo 进度。
 */
object WidgetStore {

    private const val PREFS = "kimi_widget_state"
    private const val KEY_STATUS = "status_ordinal"
    private const val KEY_TOOL_LABEL = "tool_label"
    private const val KEY_DETAIL = "detail_text"
    private const val KEY_TODO_DONE = "todo_done"
    private const val KEY_TODO_TOTAL = "todo_total"
    private const val KEY_TASKS = "running_tasks"
    private const val KEY_SINCE = "since_ms"

    fun read(context: Context): WidgetUiState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = WidgetStatus.entries.getOrElse(prefs.getInt(KEY_STATUS, 6)) { WidgetStatus.OFFLINE }
        val todoDone = prefs.getInt(KEY_TODO_DONE, -1)
        val todoTotal = prefs.getInt(KEY_TODO_TOTAL, -1)
        return WidgetUiState(
            status = status,
            toolLabel = prefs.getString(KEY_TOOL_LABEL, null),
            detailText = prefs.getString(KEY_DETAIL, null),
            todoDone = if (todoDone >= 0) todoDone else null,
            todoTotal = if (todoTotal >= 0) todoTotal else null,
            runningTasks = prefs.getInt(KEY_TASKS, 0),
            sinceMs = prefs.getLong(KEY_SINCE, 0)
        )
    }

    fun write(context: Context, state: WidgetUiState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STATUS, state.status.ordinal)
            .putString(KEY_TOOL_LABEL, state.toolLabel)
            .putString(KEY_DETAIL, state.detailText)
            .putInt(KEY_TODO_DONE, state.todoDone ?: -1)
            .putInt(KEY_TODO_TOTAL, state.todoTotal ?: -1)
            .putInt(KEY_TASKS, state.runningTasks)
            .putLong(KEY_SINCE, state.sinceMs)
            .apply()
    }
}
