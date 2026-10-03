package com.kimicode.companion.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kimicode.companion.MainActivity
import com.kimicode.companion.R
import com.kimicode.companion.data.WidgetStatus
import com.kimicode.companion.data.WidgetUiState

/**
 * 3×2（3 列 2 行）桌面状态小组件（v0.5.2 增强）：
 * 核心视觉是一条胶囊（圆角横条）状态栏——
 * - 第一行：当前动作（思考中 / 执行命令 / 写文件 / 执行工具 / 等待审批）
 * - 第二行：实时细节（命令片段 / 文件路径 / 工具名），有任务时才显示
 * - 第三行：运行任务数 + Todo 进度
 * 有待审批时胶囊高亮，点击直达 App 审批弹层。
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: androidx.glance.GlanceId) {
        val state = WidgetStore.read(context)
        provideContent {
            WidgetContent(context, state)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, state: WidgetUiState) {
        val statusText = context.getString(state.primaryTextRes)
        val fullStatusText = when {
            state.status == WidgetStatus.RUNNING_TOOL && !state.toolLabel.isNullOrBlank() ->
                context.getString(R.string.widget_state_tool, state.toolLabel)
            else -> statusText
        }
        // v0.5.2：副文案 = 任务数 + Todo 进度
        val subText = when {
            state.status == WidgetStatus.WAITING_APPROVAL ->
                context.getString(R.string.widget_tap_to_open)
            state.runningTasks > 0 && state.todoTotal != null && state.todoDone != null ->
                context.getString(
                    R.string.widget_tasks_running_todo,
                    state.runningTasks, state.todoDone, state.todoTotal
                )
            state.runningTasks > 0 ->
                context.getString(R.string.widget_tasks_running, state.runningTasks)
            else -> context.getString(R.string.widget_no_tasks)
        }

        val capsuleBg = when (state.status) {
            WidgetStatus.WAITING_APPROVAL -> Color(0xFFD9480F)      // 醒目橙
            WidgetStatus.OFFLINE -> Color(0xFF94A3B8)               // 中性灰
            WidgetStatus.IDLE -> Color(0xFF475569)                  // 深灰蓝
            else -> Color(0xFF1F3A8F)                               // Kimi 靛蓝
        }
        val iconRes = when (state.status) {
            WidgetStatus.THINKING -> R.drawable.ic_stat_thinking
            WidgetStatus.RUNNING_SHELL -> R.drawable.ic_stat_shell
            WidgetStatus.WRITING_FILES -> R.drawable.ic_stat_write
            WidgetStatus.WAITING_APPROVAL -> R.drawable.ic_stat_approval
            WidgetStatus.IDLE -> R.drawable.ic_stat_idle
            WidgetStatus.OFFLINE -> R.drawable.ic_stat_offline
            else -> R.drawable.ic_stat_kimi
        }

        Box(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(capsuleBg)
                        .cornerRadius(24.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clickableOpen(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(iconRes),
                        contentDescription = null,
                        modifier = GlanceModifier.size(22.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Column {
                        Text(
                            text = fullStatusText,
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        // v0.5.2：实时细节行（命令片段 / 文件路径 / 工具名）
                        val detail = state.detailText
                        if (state.status != WidgetStatus.OFFLINE &&
                            state.status != WidgetStatus.IDLE &&
                            state.status != WidgetStatus.WAITING_APPROVAL &&
                            !detail.isNullOrBlank()
                        ) {
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            Text(
                                text = detail,
                                maxLines = 1,
                                style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)))
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        Text(
                            text = subText,
                            style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)))
                        )
                    }
                }
            }
        }
    }
}

/** 点击胶囊 → 打开 App（有待审批时 App 会自动弹出审批层） */
private fun GlanceModifier.clickableOpen(): GlanceModifier =
    this.clickable(actionStartActivity(MainActivity::class.java))
