package com.example.charging_guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo

class ChargingService : Service() {

    private val CHANNEL_ID = "charge_guard_channel"
    private var cap = 80
    private var hyst = 5
    private var nodePref: String = ""

    private var currentNode: String? = null
    private var lastAppliedEnable: Boolean? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", 100)
            val status = intent.getIntExtra("status", 0) // 2=charging, 5=full
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            if (percent < 0) return

            ensureNodeProbed()

            val shouldDisable = percent >= cap && (status == 2 || status == 5)
            val shouldEnable = percent <= (cap - hyst)

            if (shouldDisable && lastAppliedEnable != false) setChargingEnabled(false)
            else if (shouldEnable && lastAppliedEnable != true) setChargingEnabled(true)
            else updateNotif("Monitoring… $percent% (cap $cap, h$hyst)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this, 1, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cap = intent?.getIntExtra("cap", 80) ?: 80
        hyst = intent?.getIntExtra("hyst", 5) ?: 5
        nodePref = intent?.getStringExtra("nodePref") ?: ""
        updateNotif("Target cap: $cap% (hyst $hyst%)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        setChargingEnabled(true) // fail-safe
    }

    override fun onBind(intent: Intent?) = null

    private fun ensureNodeProbed() {
        if (currentNode != null) return
        val candidates = mutableListOf<String>()
        if (nodePref.isNotBlank()) candidates.add(nodePref)
        candidates.addAll(listOf(
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/store_mode",
            "/sys/class/power_supply/battery/batt_slate_mode",
            "/sys/class/power_supply/battery/input_suspend"
        ))
        for (p in candidates.distinct()) {
            if (testWritable(p)) { currentNode = p; updateNotif("Using node: $p"); break }
        }
        if (currentNode == null) updateNotif("No writable node found. Set one manually.")
    }

    private fun testWritable(path: String): Boolean = writeNode(path, peekCurrent(path))

    private fun peekCurrent(path: String): String {
        val out = runAsRoot("cat ${esc(path)} 2>/dev/null || echo 0")
        return out.trim().lineSequence().firstOrNull()?.trim().ifNullOrBlank("0")
    }

    private fun setChargingEnabled(enable: Boolean) {
        val node = currentNode ?: return
        val v = when (node.substringAfterLast("/")) {
            "charging_enabled" -> if (enable) "1" else "0"
            "input_suspend"    -> if (enable) "0" else "1"
            "store_mode"       -> if (enable) "0" else "1"
            "batt_slate_mode"  -> if (enable) "0" else "1"
            else               -> if (enable) "1" else "0"
        }
        val ok = writeNode(node, v)
        if (ok) {
            lastAppliedEnable = enable
            updateNotif("Charging ${if (enable) "ENABLED" else "DISABLED"} @ $cap%")
        } else updateNotif("Failed to write node")
    }

    private fun esc(s: String) = s.replace(" ", "\\ ").replace(";", "").replace("&", "")

    private fun runAsRoot(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        (out + if (err.isNotBlank()) "\nERR:\n$err" else "").trim()
    } catch (e: Exception) { "ERROR: ${e.message}" }

    private fun writeNode(path: String, value: String): Boolean {
        val res = runAsRoot("chmod 0666 ${esc(path)} 2>/dev/null; echo ${esc(value)} > ${esc(path)}")
        return !res.contains("ERROR") && !res.contains("Permission denied", true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Charge Guard", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Charge Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun String?.ifNullOrBlank(default: String) = if (this.isNullOrBlank()) default else this
}
