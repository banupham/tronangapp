package vn.banupham.tronangapp.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedWorkflow(
    val name: String,
    val script: String,
    val packageName: String? = null,
    val profileSerial: Long? = null
) {
    fun compiledScript(): String {
        val cleanScript = script.trim().trim(';')
        val targetPackage = packageName?.trim().orEmpty()
        if (targetPackage.isBlank()) return cleanScript
        val target = if (profileSerial != null) "$targetPackage|$profileSerial" else targetPackage
        return "OPEN_APP:$target;$cleanScript"
    }
}

object SavedWorkflowStore {
    fun list(context: Context): List<SavedWorkflow> = runCatching {
        val array = JSONArray(preferences(context).getString(KEY_WORKFLOWS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val name = item.getString("name").trim()
                val script = item.getString("script").trim()
                if (name.isBlank() || script.isBlank()) continue
                add(
                    SavedWorkflow(
                        name = name,
                        script = script,
                        packageName = item.optString("package_name").takeIf { it.isNotBlank() },
                        profileSerial = item.optLong("profile_serial", Long.MIN_VALUE)
                            .takeIf { it != Long.MIN_VALUE }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun find(context: Context, name: String): SavedWorkflow? =
        list(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun save(context: Context, workflow: SavedWorkflow): Boolean {
        val clean = workflow.copy(name = workflow.name.trim(), script = workflow.script.trim())
        if (clean.name.isBlank() || clean.script.isBlank()) return false
        val workflows = list(context).filterNot { it.name.equals(clean.name, ignoreCase = true) } + clean
        persist(context, workflows.sortedBy { it.name.lowercase() })
        return true
    }

    fun remove(context: Context, name: String): Boolean {
        val old = list(context)
        val updated = old.filterNot { it.name.equals(name.trim(), ignoreCase = true) }
        if (updated.size == old.size) return false
        persist(context, updated)
        return true
    }

    private fun persist(context: Context, workflows: List<SavedWorkflow>) {
        val array = JSONArray().apply {
            workflows.forEach { workflow ->
                put(JSONObject().apply {
                    put("name", workflow.name)
                    put("script", workflow.script)
                    put("package_name", workflow.packageName ?: "")
                    if (workflow.profileSerial != null) put("profile_serial", workflow.profileSerial)
                })
            }
        }
        preferences(context).edit().putString(KEY_WORKFLOWS, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "saved_workflows"
    private const val KEY_WORKFLOWS = "workflows_v1"
}
