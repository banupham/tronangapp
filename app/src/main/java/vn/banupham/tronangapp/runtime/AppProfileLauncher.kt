package vn.banupham.tronangapp.runtime

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

data class LaunchableAppTarget(
    val label: String,
    val packageName: String,
    val profileSerial: Long,
    val profileLabel: String
) {
    override fun toString(): String = "$label | $packageName | $profileLabel ($profileSerial)"
}

object AppProfileLauncher {
    fun listTargets(context: Context): List<LaunchableAppTarget> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val current = Process.myUserHandle()
        val output = LinkedHashMap<String, LaunchableAppTarget>()
        val profiles = runCatching { launcherApps.profiles }
            .getOrDefault(listOf(current))
        profiles.forEach { profile ->
            val serial = userManager.getSerialNumberForUser(profile)
            val profileLabel = if (profile == current) "personal" else "profile"
            runCatching { launcherApps.getActivityList(null, profile) }
                .getOrDefault(emptyList())
                .forEach { activity ->
                    val packageName = activity.applicationInfo.packageName
                    val key = "$packageName|$serial"
                    output.putIfAbsent(
                        key,
                        LaunchableAppTarget(
                            label = activity.label?.toString()?.trim().orEmpty().ifBlank { packageName },
                            packageName = packageName,
                            profileSerial = serial,
                            profileLabel = profileLabel
                        )
                    )
                }
        }
        return output.values.sortedWith(compareBy({ it.label.lowercase() }, { it.profileSerial }))
    }

    fun launch(context: Context, packageName: String, profileSerial: Long?): Boolean {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isBlank()) return false
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = runCatching { launcherApps.profiles }
            .getOrDefault(listOf(Process.myUserHandle()))
        val profile = if (profileSerial == null) {
            profiles.firstOrNull { it == Process.myUserHandle() }
        } else {
            profiles.firstOrNull { userManager.getSerialNumberForUser(it) == profileSerial }
        } ?: return false
        val component: ComponentName = launcherApps.getActivityList(cleanPackage, profile)
            .firstOrNull()?.componentName ?: return false
        return runCatching {
            launcherApps.startMainActivity(component, profile, null, null)
            true
        }.getOrDefault(false)
    }
}
