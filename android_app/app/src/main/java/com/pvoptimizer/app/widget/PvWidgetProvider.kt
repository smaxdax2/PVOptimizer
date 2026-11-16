package com.pvoptimizer.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pvoptimizer.app.MainActivity
import com.pvoptimizer.app.R
import com.pvoptimizer.app.data.AppPreferences
import com.pvoptimizer.app.data.PvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PvWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.pvoptimizer.app.ACTION_REFRESH_WIDGET") {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PvWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            updateAllWidgets(context, appWidgetManager, allWidgetIds)
        }
    }

    private fun updateAllWidgets(context: Context, appWidgetManager: AppWidgetManager, widgetIds: IntArray) {
        val prefs = AppPreferences(context)
        val client = PvApiClient(prefs)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        CoroutineScope(Dispatchers.IO).launch {
            val statusResult = client.getStatus()
            for (widgetId in widgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_pv)

                // Intent to open App on click
                val clickIntent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                views.setTextViewText(R.id.widget_time, timeStr)

                if (statusResult.isSuccess) {
                    val status = statusResult.getOrNull()
                    if (status != null) {
                        views.setTextViewText(R.id.widget_pv_value, "%.0f W".format(status.pvPowerW))

                        val isExport = status.gridPowerW < 0
                        views.setTextViewText(R.id.widget_grid_label, if (isExport) "Einspeisung" else "Netzbezug")
                        views.setTextViewText(R.id.widget_grid_value, "%.0f W".format(abs(status.gridPowerW)))

                        views.setTextViewText(R.id.widget_battery_value, "%.0f %%".format(status.batteryPercent))
                        views.setTextViewText(R.id.widget_load_value, "%.0f W".format(status.loadPowerW))
                    }
                } else {
                    views.setTextViewText(R.id.widget_pv_value, "-- W")
                    views.setTextViewText(R.id.widget_grid_value, "-- W")
                    views.setTextViewText(R.id.widget_battery_value, "-- %")
                    views.setTextViewText(R.id.widget_load_value, "-- W")
                }

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
