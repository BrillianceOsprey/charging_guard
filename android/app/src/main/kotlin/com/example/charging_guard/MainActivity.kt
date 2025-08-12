// package com.example.charging_guard

// import androidx.annotation.NonNull
// import io.flutter.embedding.android.FlutterActivity
// import io.flutter.embedding.engine.FlutterEngine
// import io.flutter.plugin.common.MethodChannel

// class MainActivity: FlutterActivity() {
//     private val CHANNEL = "charge_guard/root"

//     override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
//         super.configureFlutterEngine(flutterEngine)
//         MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
//             .setMethodCallHandler { call, result ->
//                 when (call.method) {
//                     "isRoot" -> result.success(isRootAvailable())
//                     "runShell" -> {
//                         val cmd = call.argument<String>("cmd") ?: ""
//                         result.success(runAsRoot(cmd))
//                     }
//                     "readNode" -> result.success(readNode(call.argument<String>("path") ?: ""))
//                     "writeNode" -> {
//                         val path = call.argument<String>("path") ?: ""
//                         val value = call.argument<String>("value") ?: ""
//                         result.success(writeNode(path, value))
//                     }
//                     "startService" -> {
//                         val cap = call.argument<Int>("cap") ?: 80
//                         val hysteresis = call.argument<Int>("hyst") ?: 5
//                         val nodePref = call.argument<String>("node") ?: ""
//                         startServiceFG(cap, hysteresis, nodePref)
//                         result.success(true)
//                     }
//                     "stopService" -> { stopServiceFG(); result.success(true) }
//                     else -> result.notImplemented()
//                 }
//             }
//     }

//     private fun isRootAvailable(): Boolean = try {
//         val p = Runtime.getRuntime().exec("su -c id")
//         p.waitFor() == 0
//     } catch (_: Exception) { false }

//     private fun runAsRoot(cmd: String): String = try {
//         val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
//         val out = p.inputStream.bufferedReader().readText()
//         val err = p.errorStream.bufferedReader().readText()
//         p.waitFor()
//         (out + if (err.isNotBlank()) "\nERR:\n$err" else "").trim()
//     } catch (e: Exception) { "ERROR: ${e.message}" }

//     private fun readNode(path: String): String = runAsRoot("cat ${escape(path)}")
//     private fun writeNode(path: String, value: String): Boolean {
//         val res = runAsRoot("chmod 0666 ${escape(path)} 2>/dev/null; echo ${escape(value)} > ${escape(path)}")
//         return !res.contains("ERROR") && !res.contains("Permission denied", true)
//     }
//     private fun escape(s: String) = s.replace(" ", "\\ ").replace(";", "").replace("&", "")

//     private fun startServiceFG(cap: Int, hysteresis: Int, nodePref: String) {
//         val i = android.content.Intent(this, ChargingService::class.java).apply {
//             putExtra("cap", cap)
//             putExtra("hyst", hysteresis)
//             putExtra("nodePref", nodePref)
//         }
//         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
//             startForegroundService(i) else startService(i)
//     }
//     private fun stopServiceFG() {
//         stopService(android.content.Intent(this, ChargingService::class.java))
//     }
// }

package com.example.charging_guard

import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class MainActivity: FlutterActivity() {
    private val CHANNEL = "charge_guard/root"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isRoot" -> result.success(isRootAvailable())
                    "probeRoot" -> result.success(probeRoot())
                    "runShell" -> {
                        val cmd = call.argument<String>("cmd") ?: ""
                        result.success(runAsRoot(cmd))
                    }
                    "readNode" -> result.success(readNode(call.argument<String>("path") ?: ""))
                    "writeNode" -> {
                        val path = call.argument<String>("path") ?: ""
                        val value = call.argument<String>("value") ?: ""
                        result.success(writeNode(path, value))
                    }
                    "startService" -> {
                        val cap = call.argument<Int>("cap") ?: 80
                        val hysteresis = call.argument<Int>("hyst") ?: 5
                        val nodePref = call.argument<String>("node") ?: ""
                        startServiceFG(cap, hysteresis, nodePref)
                        result.success(true)
                    }
                    "stopService" -> { stopServiceFG(); result.success(true) }
                    else -> result.notImplemented()
                }
            }
    }

    /** Quick check via one-shot command */
    private fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su","-c","id"))
        // Wait up to 5s so Magisk dialog has time to appear
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    } catch (_: Exception) { false }

    /** Verbose probe: run multiple forms and return stdout+stderr so you can see what failed. */
    private fun probeRoot(): String {
        val lines = mutableListOf<String>()
        fun run(label: String, vararg cmd: String) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val out = p.inputStream.bufferedReader().readText().trim()
                val err = p.errorStream.bufferedReader().readText().trim()
                p.waitFor(5, TimeUnit.SECONDS)
                val code = try { p.exitValue() } catch (_: Exception) { -999 }
                lines += "[$label] code=$code\nout: $out\nerr: $err\n"
            } catch (e: Exception) {
                lines += "[$label] EXC: ${e.message}\n"
            }
        }

        // 1) Simple id
        run("su -c id", "su","-c","id")
        // 2) Echo OK (sometimes safer)
        run("su -c echo", "su","-c","echo OK")
        // 3) Version
        run("su -v", "su","-v")
        // 4) Which su from non-root
        run("which su", "sh","-c","which su || command -v su || echo 'no su in PATH'")
        // 5) Interactive su (write commands to stdin) — triggers prompt on some managers
        try {
            val p = Runtime.getRuntime().exec("su")
            val writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            writer.write("id\n")
            writer.write("exit\n")
            writer.flush()
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor(7, TimeUnit.SECONDS)
            val code = try { p.exitValue() } catch (_: Exception) { -999 }
            lines += "[interactive su] code=$code\nout: $out\nerr: $err\n"
        } catch (e: Exception) {
            lines += "[interactive su] EXC: ${e.message}\n"
        }

        return lines.joinToString("\n")
    }

    private fun runAsRoot(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(7, TimeUnit.SECONDS)
        "code=${p.exitValue()}\n$out${if (err.isNotBlank()) "\nERR:\n$err" else ""}".trim()
    } catch (e: Exception) { "ERROR: ${e.message}" }

    private fun readNode(path: String): String = runAsRoot("cat ${escape(path)}")
    private fun writeNode(path: String, value: String): Boolean {
        val res = runAsRoot("chmod 0666 ${escape(path)} 2>/dev/null; echo ${escape(value)} > ${escape(path)}")
        return res.startsWith("code=0") && !res.contains("Permission denied", true)
    }

    private fun escape(s: String) = s.replace(" ", "\\ ").replace(";", "").replace("&", "")

    private fun startServiceFG(cap: Int, hysteresis: Int, nodePref: String) {
        val i = android.content.Intent(this, ChargingService::class.java).apply {
            putExtra("cap", cap); putExtra("hyst", hysteresis); putExtra("nodePref", nodePref)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startForegroundService(i) else startService(i)
    }
    private fun stopServiceFG() {
        stopService(android.content.Intent(this, ChargingService::class.java))
    }
}
