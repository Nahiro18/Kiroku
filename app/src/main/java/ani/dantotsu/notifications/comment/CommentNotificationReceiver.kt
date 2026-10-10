package ani.dantotsu.notifications.comment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CommentNotificationTask().execute(context)
            } finally {
                pending.finish()
            }
        }
        // FIX: Reprogramar la siguiente alarma (antes la cadena se rompía aquí)
        val commentInterval =
            CommentNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.CommentNotificationInterval)]
        AlarmManagerScheduler(context).scheduleRepeatingTask(
            TaskScheduler.TaskType.COMMENT_NOTIFICATION,
            commentInterval
        )
    }
}
