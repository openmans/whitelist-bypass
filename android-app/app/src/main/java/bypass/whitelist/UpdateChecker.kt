package bypass.whitelist

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Проверка обновлений с нашего сервера.
 *
 * version.json:
 * {"versionCode":4005,"versionName":"0.4.5","url":"https://.../app.apk","force":true,"notes":"..."}
 *
 * Если versionCode сервера больше установленного — показываем обязательное обновление,
 * качаем APK и отдаём системному установщику.
 */
object UpdateChecker {

    private val running = AtomicBoolean(false)

    fun check(activity: Activity) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val info = fetchInfo() ?: return@Thread
                val current = BuildConfig.VERSION_CODE
                val remote = info.optInt("versionCode", 0)
                if (remote <= current) return@Thread
                val url = info.optString("url", "")
                if (url.isEmpty()) return@Thread
                val name = info.optString("versionName", remote.toString())
                val notes = info.optString("notes", "")
                activity.runOnUiThread { showDialog(activity, name, notes, url) }
            } catch (_: Throwable) {
                // сеть недоступна — молча работаем дальше
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun fetchInfo(): JSONObject? {
        val conn = (URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun showDialog(activity: Activity, version: String, notes: String, url: String) {
        if (activity.isFinishing) return
        val text = buildString {
            append("Вышла версия ")
            append(version)
            append(". Обновление обязательно: без него туннель не подключится.")
            if (notes.isNotBlank()) {
                append("\n\n")
                append(notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Обновление приложения")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("Обновить") { _, _ -> downloadAndInstall(activity, url) }
            .setNegativeButton("Позже") { _, _ -> activity.finish() }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, url: String) {
        val apk = File(activity.cacheDir, "update.apk")
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                }
                conn.inputStream.use { input ->
                    apk.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                conn.disconnect()
                activity.runOnUiThread { install(activity, apk) }
            } catch (_: Throwable) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Не удалось скачать обновление")
                        .setMessage("Проверьте интернет и попробуйте ещё раз.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + activity.packageName))
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
