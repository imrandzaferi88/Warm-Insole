package com.custom.warminsole

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class WarmInsoleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CONNECT = "com.custom.warminsole.WIDGET_CONNECT"
        const val ACTION_ON = "com.custom.warminsole.WIDGET_ON"
        const val ACTION_LOW = "com.custom.warminsole.WIDGET_LOW"
        const val ACTION_MED = "com.custom.warminsole.WIDGET_MED"
        const val ACTION_MAX = "com.custom.warminsole.WIDGET_MAX"
        const val ACTION_OFF = "com.custom.warminsole.WIDGET_OFF"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WarmInsoleWidgetProvider::class.java))
            for (id in ids) {
                updateSingleWidget(context, manager, id)
            }
        }

        private fun updateSingleWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_warminsole)

            val temp = InsoleKeepAliveService.currentTemp
            val isHeating = InsoleKeepAliveService.isHeatingOn
            val c1 = InsoleKeepAliveService.isConnected1
            val c2 = InsoleKeepAliveService.isConnected2
            val scanning = InsoleKeepAliveService.isScanning

            val stateLabel = when {
                !c1 && !c2 -> "Scollegate"
                isHeating -> "${temp}°C • ON"
                else -> "${temp}°C • Standby"
            }
            views.setTextViewText(R.id.tv_widget_header, "WarmInsole • $stateLabel")

            val sxText = if (c1 && InsoleKeepAliveService.rawEle1 in 0..9) {
                val pct = InsoleKeepAliveService.eleLevelToPercent(InsoleKeepAliveService.rawEle1)
                val est = InsoleKeepAliveService.formatRemainingTime(
                    InsoleKeepAliveService.estimateRemainingMinutes(InsoleKeepAliveService.rawEle1, temp)
                )
                "SX: $pct% ($est)"
            } else if (c1) {
                "SX: Attiva"
            } else {
                "SX: Off"
            }

            val dxText = if (c2 && InsoleKeepAliveService.rawEle2 in 0..9) {
                val pct = InsoleKeepAliveService.eleLevelToPercent(InsoleKeepAliveService.rawEle2)
                val est = InsoleKeepAliveService.formatRemainingTime(
                    InsoleKeepAliveService.estimateRemainingMinutes(InsoleKeepAliveService.rawEle2, temp)
                )
                "DX: $pct% ($est)"
            } else if (c2) {
                "DX: Attiva"
            } else {
                "DX: Off"
            }
            views.setTextViewText(R.id.tv_widget_batt, "$sxText   •   $dxText")

            val connectLabel = when {
                c1 && c2 -> "2/2 OK"
                scanning -> "CERCO..."
                c1 || c2 -> "1/2 +"
                else -> "COLLEGA"
            }
            views.setTextViewText(R.id.btn_widget_connect, connectLabel)

            // Apri app toccando il riquadro batteria/stato
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context, 10, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_info_box, openAppPending)

            views.setOnClickPendingIntent(R.id.btn_widget_connect, buildActionPendingIntent(context, ACTION_CONNECT, 1))
            views.setOnClickPendingIntent(R.id.btn_widget_on, buildActionPendingIntent(context, ACTION_ON, 2))
            views.setOnClickPendingIntent(R.id.btn_widget_low, buildActionPendingIntent(context, ACTION_LOW, 3))
            views.setOnClickPendingIntent(R.id.btn_widget_med, buildActionPendingIntent(context, ACTION_MED, 4))
            views.setOnClickPendingIntent(R.id.btn_widget_max, buildActionPendingIntent(context, ACTION_MAX, 5))
            views.setOnClickPendingIntent(R.id.btn_widget_off, buildActionPendingIntent(context, ACTION_OFF, 6))

            manager.updateAppWidget(widgetId, views)
        }

        private fun buildActionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, WarmInsoleWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val act = intent.action ?: return
        if (act in listOf(ACTION_CONNECT, ACTION_ON, ACTION_LOW, ACTION_MED, ACTION_MAX, ACTION_OFF)) {
            val serviceIntent = Intent(context, InsoleKeepAliveService::class.java).apply {
                action = act
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
