package com.example.charging_guard

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = context.getSharedPreferences("cg_prefs", Context.MODE_PRIVATE)
        if (!p.getBoolean("svc_enabled", false)) return
        val i = Intent(context, ChargingService::class.java).apply {
            putExtra("cap", p.getInt("cap", 80))
            putExtra("hyst", p.getInt("hyst", 5))
            putExtra("nodePref", p.getString("nodePref", "") ?: "")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            context.startForegroundService(i) else context.startService(i)
    }
}
