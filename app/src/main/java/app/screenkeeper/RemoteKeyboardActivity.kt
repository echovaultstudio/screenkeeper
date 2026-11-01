package app.screenkeeper

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone side of the phone keyboard: whatever is typed here is mirrored into the focused text
 * field on the TV (see [KeyboardService]). The whole text is sent each time, so backspace,
 * autocorrect, voice typing and paste all just work.
 */
class RemoteKeyboardActivity : Activity() {
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<String?>(null)
    private lateinit var client: RemoteClient
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var tvName = ""
    private var suppress = false

    private val flush = Runnable { bg.execute { sendPending() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = RuleStore(this).savedRemote() ?: run { finish(); return }
        client = RemoteClient(saved.host, saved.token)
        tvName = saved.name

        val (_, page) = newPage()
        page.addView(text(getString(R.string.keyboard_title, saved.name), 24f, bold = true))
        page.addView(text(getString(R.string.keyboard_intro), 14f, Palette.MUTED), lp(top = dp(6), bottom = dp(14)))

        input = EditText(this).apply {
            hint = getString(R.string.keyboard_hint)
            textSize = 20f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = focusBg(radiusDp = 14f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, action, ev ->
                val isEnter = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEND ||
                    (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                if (isEnter) pressEnter()
                isEnter
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppress) return
                    pending.set(s?.toString().orEmpty())
                    // Short debounce: fast typing becomes one update, single keys still feel instant.
                    main.removeCallbacks(flush)
                    main.postDelayed(flush, 120)
                }
            })
        }
        page.addView(input, lp())

        status = text(getString(R.string.keyboard_status_waiting), 13f, Palette.MUTED)
        page.addView(status, lp(top = dp(8)))

        val enter = button(getString(R.string.keyboard_enter)) { pressEnter() }
        page.addView(
            buttonRow(
                enter,
                button(getString(R.string.keyboard_clear)) { input.setText("") },
                button(getString(R.string.keyboard_reload)) { load() },
            ),
            lp(top = dp(14)),
        )

        input.requestFocus()
        load()
    }

    override fun onResume() {
        super.onResume()
        input.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onDestroy() {
        main.removeCallbacks(flush)
        bg.shutdownNow()
        super.onDestroy()
    }

    /** Fetch the TV's focused field and copy its text here, so editing continues from what's already there. */
    private fun load() {
        status.text = getString(R.string.keyboard_status_waiting)
        status.setTextColor(Palette.MUTED)
        io(bg, { client.get("/keyboard") }, ::showError) { res ->
            if (!res.optBoolean("enabled")) {
                showProblem(getString(R.string.keyboard_not_enabled)); return@io
            }
            val field = res.optJSONObject("field")
            if (field == null) {
                showProblem(getString(R.string.keyboard_no_field)); return@io
            }
            suppress = true
            input.setText(field.optString("text"))
            input.setSelection(input.text.length)
            suppress = false
            showField(field)
        }
    }

    /** Runs on [bg]: send the newest text, skipping any that was superseded while waiting. */
    private fun sendPending() {
        val text = pending.getAndSet(null) ?: return
        val result = try {
            Result.success(client.post("/keyboard/text", JSONObject().put("text", text)))
        } catch (e: RemoteException) {
            Result.failure(e)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            result.fold({ it.optJSONObject("field")?.let(::showField) }, { showError(it as RemoteException) })
        }
    }

    private fun pressEnter() {
        main.removeCallbacks(flush)
        io(bg, {
            sendPending()
            client.post("/keyboard/enter")
        }, ::showError) { res -> res.optJSONObject("field")?.let(::showField) }
    }

    private fun showField(field: JSONObject) {
        val where = listOf(field.optString("hint"), field.optString("app"))
            .filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { tvName }
        status.text = if (field.optBoolean("password")) {
            getString(R.string.keyboard_status_field, where) + "\n" + getString(R.string.keyboard_status_password)
        } else {
            getString(R.string.keyboard_status_field, where)
        }
        status.setTextColor(Palette.ACCENT)
    }

    private fun showProblem(msg: String) {
        status.text = msg
        status.setTextColor(Palette.WARN)
    }

    private fun showError(e: RemoteException) {
        val saved = RuleStore(this).savedRemote()
        showProblem(
            when {
                e.status == 0 -> getString(R.string.remote_unreachable, saved?.host ?: "")
                e.status == 401 -> getString(R.string.remote_unpaired)
                e.status == 404 -> getString(R.string.keyboard_old_tv)
                e.message == RemoteServer.KB_OFF -> getString(R.string.keyboard_not_enabled)
                e.message == RemoteServer.KB_NO_FIELD -> getString(R.string.keyboard_no_field)
                e.message == RemoteServer.KB_FAILED -> getString(R.string.keyboard_failed)
                else -> e.message ?: "Error ${e.status}"
            }
        )
    }
}
