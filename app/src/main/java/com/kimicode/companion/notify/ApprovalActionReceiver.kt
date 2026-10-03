package com.kimicode.companion.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kimicode.companion.KimiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知上的审批快捷操作接收器：允许 / 拒绝。
 * 通过 REST POST /sessions/{sid}/approvals/{aid} 落地。
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_APPROVE = "com.kimicode.companion.action.APPROVE"
        const val ACTION_REJECT = "com.kimicode.companion.action.REJECT"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_NOTIF_ID = "notif_id"

        fun pending(
            context: Context,
            action: String,
            sessionId: String,
            approvalId: String,
            notifId: Int
        ): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_APPROVAL_ID, approvalId)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                context,
                (action + approvalId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_APPROVE && action != ACTION_REJECT) return

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val app = KimiApp.of(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val decision = if (action == ACTION_APPROVE) "approved" else "rejected"
                // approved + scope=session：本会话内同类操作自动放行
                val ok = runCatching {
                    app.api.decideApproval(sessionId, approvalId, decision)
                }.isSuccess
                if (ok) {
                    app.repository.removeApproval(approvalId)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    if (notifId > 0) nm.cancel(notifId)
                    else Notifications.cancelApproval(context, approvalId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
