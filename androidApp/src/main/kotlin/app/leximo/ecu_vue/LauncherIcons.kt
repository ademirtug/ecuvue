package app.leximo.ecu_vue

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import app.leximo.ecuvue.core.LauncherAppearance

/** Stage appearance changes while visible; switch aliases only after the app leaves the foreground. */
class LauncherIcons(context: Context) : LauncherAppearance {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("launcher_icon", Context.MODE_PRIVATE)
    private val manager = context.applicationContext.packageManager
    private val packageName = context.packageName
    private val aliases = mapOf(
        "system" to ComponentName(packageName, "$packageName.launcher.System"),
        "monochrome_light" to ComponentName(packageName, "$packageName.launcher.Light"),
        "monochrome_dark" to ComponentName(packageName, "$packageName.launcher.Dark"),
        "iceBlue_light" to ComponentName(packageName, "$packageName.launcher.IceBlueLight"),
        "iceBlue_dark" to ComponentName(packageName, "$packageName.launcher.IceBlueDark"),
        "racingGreen_light" to ComponentName(packageName, "$packageName.launcher.RacingGreenLight"),
        "racingGreen_dark" to ComponentName(packageName, "$packageName.launcher.RacingGreenDark"),
        "sunset_light" to ComponentName(packageName, "$packageName.launcher.SunsetLight"),
        "sunset_dark" to ComponentName(packageName, "$packageName.launcher.SunsetDark"),
    )
    private val themes = setOf("system", "light", "dark")
    private val palettes = setOf("monochrome", "iceBlue", "racingGreen", "sunset")

    @Synchronized
    override fun schedule(theme: String, palette: String) {
        require(theme in themes) { "Unknown launcher theme: $theme" }
        require(palette in palettes) { "Unknown launcher palette: $palette" }
        preferences.edit().putString("theme", theme).putString("palette", palette).apply()
    }

    /** Disabling the alias used to launch an activity finishes its task, even with DONT_KILL_APP. */
    @Synchronized
    override fun refresh() {
        val theme = preferences.getString("theme", "system").takeIf { it in themes } ?: "system"
        val palette = preferences.getString("palette", "monochrome").takeIf { it in palettes } ?: "monochrome"
        val resolved = if (theme == "system") {
            if (application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        } else theme
        val target = aliases.getValue(if (theme == "system" && palette == "monochrome") "system" else "${palette}_${resolved}")
        val enabled = aliases.filter { (name, component) ->
            when (manager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == "system"
                else -> false
            }
        }.values.toList()
        if (enabled == listOf(target)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.setComponentEnabledSettings(aliases.values.map { component ->
                PackageManager.ComponentEnabledSetting(
                    component,
                    if (component == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            })
        } else {
            // Never leave older Android versions without an enabled launcher entry.
            manager.setComponentEnabledSetting(
                target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
            aliases.values.filter { it != target }.forEach { component ->
                manager.setComponentEnabledSetting(
                    component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
