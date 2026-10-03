package com.shilapi.xcertplay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.shilapi.xcertplay.host.R

class DiPlayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(context, 2,
            Intent(context, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.diplay_widget).apply {
            setOnClickPendingIntent(R.id.widget_open, open)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
    companion object {
        fun requestPin(context: Context, provider: Class<out AppWidgetProvider> = DiPlayWidget::class.java) {
            val manager = context.getSystemService(AppWidgetManager::class.java)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(context, provider), null, null)
            } else Toast.makeText(context, R.string.f10_widget_hint, Toast.LENGTH_LONG).show()
        }
    }
}
