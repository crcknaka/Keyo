package com.keyo.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.json.JSONObject

class OpenAppTool : Tool {
    override val name = "open_app"
    override val uiLabel = "📱 Open app"
    override val uiExample = "Open Telegram"
    override val description = "Open an app by name. Searches all installed apps on the device. Accepts app name in any language (Russian, English, etc)."
    override val parameters = JSONObject("""
        {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "App name to open (e.g. 'Telegram', 'Slack', 'Camera')"
                }
            },
            "required": ["app_name"]
        }
    """)

    override suspend fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.getString("app_name").lowercase().trim()

        val pm = context.packageManager
        val launchables = getLaunchableApps(pm)

        // 1. Exact match on label
        val exact = launchables.find {
            it.loadLabel(pm).toString().lowercase() == query
        }
        if (exact != null) {
            return launchApp(context, pm, exact)
        }

        // 2. Contains match
        val contains = launchables.filter {
            it.loadLabel(pm).toString().lowercase().contains(query)
        }
        if (contains.size == 1) {
            return launchApp(context, pm, contains[0])
        }

        // 3. Package name contains match
        val pkgMatch = launchables.find {
            it.activityInfo.packageName.lowercase().contains(query)
        }
        if (pkgMatch != null) {
            return launchApp(context, pm, pkgMatch)
        }

        // 4. Fuzzy — try removing common suffixes/prefixes
        val simplified = query.replace("the app ", "").replace("app ", "").trim()
        val fuzzy = launchables.filter {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(simplified) || simplified.contains(label)
        }
        if (fuzzy.size == 1) {
            return launchApp(context, pm, fuzzy[0])
        }

        // Multiple matches — list them
        if (contains.size > 1) {
            val names = contains.take(5).joinToString(", ") { it.loadLabel(pm).toString() }
            return ToolResult(false, "Multiple matches: $names. Please be more specific.")
        }

        return ToolResult(false, "App '$query' not found on this device")
    }

    private fun getLaunchableApps(pm: PackageManager): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
    }

    private fun launchApp(context: Context, pm: PackageManager, app: ResolveInfo): ToolResult {
        val packageName = app.activityInfo.packageName
        val label = app.loadLabel(pm).toString()
        return try {
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: return ToolResult(false, "Couldn't open $label")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(true, "Opening $label")
        } catch (e: Exception) {
            ToolResult(false, "Error: ${e.message}")
        }
    }
}

