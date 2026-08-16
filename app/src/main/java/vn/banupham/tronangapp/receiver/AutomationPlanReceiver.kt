package vn.banupham.tronangapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.runtime.AutomationPlanScheduler
import vn.banupham.tronangapp.runtime.AutomationPlanStore

class AutomationPlanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutomationPlanScheduler.rescheduleAll(context)
            return
        }
        if (intent.action != ACTION_RUN_PLAN) return
        val name = intent.getStringExtra(EXTRA_PLAN_NAME)?.trim().orEmpty()
        val plan = AutomationPlanStore.find(context, name) ?: return
        if (!plan.enabled) return

        if (plan.scheduleType == "daily") {
            AutomationPlanScheduler.schedule(context, plan)
        } else if (plan.scheduleType == "once") {
            AutomationPlanStore.setEnabled(context, plan.name, false)
        }
        PendingAutomationPlanStore.enqueue(context, plan.name)
        GenericAccessibilityService.instance?.consumePendingAutomationPlans()
    }

    companion object {
        const val ACTION_RUN_PLAN = "vn.banupham.tronangapp.action.RUN_AUTOMATION_PLAN"
        const val EXTRA_PLAN_NAME = "plan_name"
    }
}

object PendingAutomationPlanStore {
    fun enqueue(context: Context, name: String) {
        val prefs = preferences(context)
        val queue = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        queue += name
        prefs.edit().putStringSet(KEY_PENDING, queue).apply()
    }

    fun drain(context: Context): List<String> {
        val prefs = preferences(context)
        val values = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toList()
        prefs.edit().remove(KEY_PENDING).apply()
        return values
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("automation_plan_pending", Context.MODE_PRIVATE)

    private const val KEY_PENDING = "pending_names"
}
