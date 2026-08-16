package vn.banupham.tronangapp.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import vn.banupham.tronangapp.receiver.AutomationPlanReceiver

object AutomationPlanScheduler {
    fun schedule(context: Context, plan: AutomationPlan): Boolean {
        cancel(context, plan.name)
        if (!plan.enabled || plan.scheduleType == "manual") return true
        val triggerAt = nextTrigger(plan) ?: return false
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(context, plan.name)
        )
        return true
    }

    fun cancel(context: Context, name: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, name))
    }

    fun rescheduleAll(context: Context) {
        AutomationPlanStore.list(context).filter { it.enabled }.forEach { schedule(context, it) }
    }

    fun nextTrigger(plan: AutomationPlan, now: Long = System.currentTimeMillis()): Long? =
        when (plan.scheduleType) {
            "once" -> plan.runAtMillis?.takeIf { it > now }
            "daily" -> Calendar.getInstance().run {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, plan.hour ?: return null)
                set(Calendar.MINUTE, plan.minute ?: return null)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
                timeInMillis
            }
            else -> null
        }

    private fun pendingIntent(context: Context, name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        name.hashCode(),
        Intent(context, AutomationPlanReceiver::class.java).apply {
            action = AutomationPlanReceiver.ACTION_RUN_PLAN
            putExtra(AutomationPlanReceiver.EXTRA_PLAN_NAME, name)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
