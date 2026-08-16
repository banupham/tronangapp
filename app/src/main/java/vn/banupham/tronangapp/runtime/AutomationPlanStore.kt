package vn.banupham.tronangapp.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AutomationPlanItem(val workflowName: String, val repetitions: Int)

data class AutomationPlan(
    val name: String,
    val items: List<AutomationPlanItem>,
    val scheduleType: String,
    val runAtMillis: Long? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val enabled: Boolean = true
)

object AutomationPlanStore {
    fun list(context: Context): List<AutomationPlan> = runCatching {
        val array = JSONArray(preferences(context).getString(KEY_PLANS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                parse(array.getJSONObject(index))?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun find(context: Context, name: String): AutomationPlan? =
        list(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun save(context: Context, plan: AutomationPlan): Boolean {
        if (!isValid(plan)) return false
        val clean = plan.copy(name = plan.name.trim())
        val updated = list(context).filterNot { it.name.equals(clean.name, true) } + clean
        persist(context, updated.sortedBy { it.name.lowercase() })
        return true
    }

    fun remove(context: Context, name: String): Boolean {
        val old = list(context)
        val updated = old.filterNot { it.name.equals(name.trim(), true) }
        if (updated.size == old.size) return false
        persist(context, updated)
        return true
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean): AutomationPlan? {
        val plan = find(context, name) ?: return null
        val updated = plan.copy(enabled = enabled)
        save(context, updated)
        return updated
    }

    fun toJson(plan: AutomationPlan): JSONObject = JSONObject().apply {
        put("name", plan.name)
        put("enabled", plan.enabled)
        put("schedule_type", plan.scheduleType)
        put("run_at_ms", plan.runAtMillis ?: JSONObject.NULL)
        put("hour", plan.hour ?: JSONObject.NULL)
        put("minute", plan.minute ?: JSONObject.NULL)
        put("items", JSONArray().apply {
            plan.items.forEach { item ->
                put(JSONObject().apply {
                    put("workflow", item.workflowName)
                    put("repeat", item.repetitions)
                })
            }
        })
    }

    fun parse(obj: JSONObject): AutomationPlan? = runCatching {
        val itemsJson = obj.getJSONArray("items")
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                add(AutomationPlanItem(item.getString("workflow").trim(), item.optInt("repeat", 1)))
            }
        }
        AutomationPlan(
            name = obj.getString("name").trim(),
            items = items,
            scheduleType = obj.optString("schedule_type", "manual").lowercase(),
            runAtMillis = obj.optLong("run_at_ms", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
            hour = obj.optInt("hour", -1).takeIf { it >= 0 },
            minute = obj.optInt("minute", -1).takeIf { it >= 0 },
            enabled = obj.optBoolean("enabled", true)
        ).takeIf(::isValid)
    }.getOrNull()

    private fun isValid(plan: AutomationPlan): Boolean {
        if (plan.name.isBlank() || plan.items.isEmpty()) return false
        if (plan.items.any { it.workflowName.isBlank() || it.repetitions !in 1..100 }) return false
        if (plan.items.sumOf { it.repetitions } > 1_000) return false
        return when (plan.scheduleType) {
            "manual" -> true
            "once" -> plan.runAtMillis != null && plan.runAtMillis > 0L
            "daily" -> plan.hour in 0..23 && plan.minute in 0..59
            else -> false
        }
    }

    private fun persist(context: Context, plans: List<AutomationPlan>) {
        val array = JSONArray().apply { plans.forEach { put(toJson(it)) } }
        preferences(context).edit().putString(KEY_PLANS, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "automation_plans"
    private const val KEY_PLANS = "plans_v1"
}
