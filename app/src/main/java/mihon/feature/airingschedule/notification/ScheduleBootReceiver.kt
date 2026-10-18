package mihon.feature.airingschedule.notification

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager forgets every alarm on reboot, app update and when the exact-alarm permission is
 * re-granted. This restores the Schedule tab alerts in those cases so a followed series keeps
 * notifying without the user having to open the app.
 */
class ScheduleBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { ScheduleNotifications.restore(appContext) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
