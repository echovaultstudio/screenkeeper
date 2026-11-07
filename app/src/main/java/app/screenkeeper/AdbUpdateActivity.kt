package app.screenkeeper

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Phone side: install or update an APK on the TV over adb (the TV's "Network debugging"),
 * without a computer. By default it sends this phone's own Screenkeeper, so updating the
 * phone and then pressing one button brings the TV to the same version. Rules are kept.
 */
class AdbUpdateActivity : Activity() {
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var address: EditText
    private lateinit var log: TextView
    private lateinit var tvVersion: TextView
    private val actionButtons = mutableListOf<View>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RuleStore(this)
        val saved = store.savedRemote()

        val (_, page) = newPage()
        page.addView(text(getString(R.string.adb_update_title), 24f, bold = true))
        page.addView(text(getString(R.string.adb_update_intro), 14f, Palette.MUTED), lp(top = dp(6), bottom = dp(14)))

        page.addView(text(getString(R.string.adb_update_address), 14f, Palette.MUTED))
        address = EditText(this).apply {
            setText(store.adbAddress() ?: saved?.host?.let { "$it:${AdbClient.DEFAULT_PORT}" } ?: "")
            hint = "192.168.1.50:5555"
            textSize = 18f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = focusBg(radiusDp = 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        page.addView(address, lp(top = dp(4)))

        tvVersion = text(getString(R.string.adb_update_phone_version, BuildConfig.VERSION_NAME), 13f, Palette.MUTED)
        page.addView(tvVersion, lp(top = dp(8)))
        saved?.let { checkTvVersion(it.host) }

        val sendSelf = button(getString(R.string.adb_update_send_self, BuildConfig.VERSION_NAME)) {
            val apk = File(applicationInfo.sourceDir)
            run(getString(R.string.adb_update_installing)) { adb -> install(adb, apk) }
        }
        val pick = button(getString(R.string.adb_update_pick)) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            try { startActivityForResult(i, PICK_APK) } catch (_: Exception) {}
        }
        val grant = button(getString(R.string.adb_update_grant)) {
            run(getString(R.string.adb_update_granting)) { adb -> grantPermissions(adb) }
        }
        actionButtons += listOf(sendSelf, pick, grant)
        page.addView(sendSelf, lp(top = dp(14)))
        page.addView(buttonRow(pick, grant), lp(top = dp(8)))

        log = text("", 13f, Palette.TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        page.addView(log, lp(top = dp(16)))

        page.addView(text(getString(R.string.adb_update_help), 13f, Palette.MUTED), lp(top = dp(18)))
        sendSelf.requestFocus()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_APK && resultCode == RESULT_OK && uri != null) {
            run(getString(R.string.adb_update_installing)) { adb -> install(adb, copyToCache(uri)) }
        }
    }

    private fun checkTvVersion(host: String) {
        io(bg, { RemoteClient.probe(host) }, { }) { info ->
            val v = info?.optString("version").orEmpty()
            if (v.isNotEmpty()) {
                tvVersion.text = getString(R.string.adb_update_phone_version, BuildConfig.VERSION_NAME) + " · " +
                    getString(R.string.adb_update_tv_version, v)
            }
        }
    }

    /** Connect, run [work], show the result. One job at a time; the screen stays on meanwhile. */
    private fun run(title: String, work: (AdbClient) -> String) {
        if (busy) return
        val addr = AdbClient.parseAddress(address.text.toString())
        if (addr == null) { log.text = getString(R.string.adb_update_bad_address); return }
        RuleStore(this).setAdbAddress(address.text.toString().trim())
        setBusy(true)
        log.text = getString(R.string.adb_update_connecting, "${addr.first}:${addr.second}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bg.execute {
            val result = try {
                AdbClient.connect(this, addr.first, addr.second) { say(getString(R.string.adb_update_allow)) }.use { adb ->
                    say(title)
                    work(adb)
                }
            } catch (e: Exception) {
                getString(R.string.adb_update_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                log.text = log.text.toString() + "\n" + result
                setBusy(false)
                RuleStore(this).savedRemote()?.let { checkTvVersion(it.host) }
            }
        }
    }

    private fun install(adb: AdbClient, apk: File): String {
        val size = apk.length()
        var lastPct = -1
        val out = apk.inputStream().use { ins ->
            adb.install(ins, size) { sent ->
                val pct = (sent * 100 / size).toInt()
                if (pct / 10 != lastPct / 10) { lastPct = pct; say(getString(R.string.adb_update_progress, pct)) }
            }
        }
        if (out.contains("Success")) return getString(R.string.adb_update_done)
        val hint = when {
            out.contains("UPDATE_INCOMPATIBLE") || out.contains("signatures do not match") -> getString(R.string.adb_update_sig_mismatch)
            out.contains("VERSION_DOWNGRADE") -> getString(R.string.adb_update_downgrade)
            else -> ""
        }
        return getString(R.string.adb_update_failed, out.ifBlank { "no output" }) + if (hint.isNotEmpty()) "\n\n$hint" else ""
    }

    /** The same grants as the manual adb commands, plus the phone keyboard (added next to any other accessibility services). */
    private fun grantPermissions(adb: AdbClient): String {
        val pkg = packageName // same package id on phone and TV
        for (c in Perms.adbCommands(pkg).lines()) adb.shell(c.removePrefix("adb shell ").trim())
        TvAdb.enableKeyboard(adb, pkg)
        return getString(R.string.adb_update_granted)
    }

    private fun copyToCache(uri: Uri): File {
        val f = File(cacheDir, "picked.apk")
        contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            ?: throw AdbException("Can't read that file.")
        return f
    }

    private fun say(line: String) = runOnUiThread {
        if (!isFinishing) log.text = if (log.text.isEmpty()) line else log.text.toString() + "\n" + line
    }

    private fun setBusy(b: Boolean) {
        busy = b
        actionButtons.forEach { it.isEnabled = !b; it.alpha = if (b) 0.5f else 1f }
    }

    companion object {
        private const val PICK_APK = 41
    }
}
