package app.screenkeeper

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Stats and rules for one app on the paired TV, edited over the network. */
class RemoteAppActivity : Activity() {
    private lateinit var store: RuleStore
    private lateinit var client: RemoteClient
    private lateinit var editor: RuleEditor
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var pkg: String
    private var label = ""
    private var icon: Drawable? = null
    private var tvName = ""
    private var detail: JSONObject? = null
    private var hasPin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore(this)
        val saved = store.savedRemote() ?: run { finish(); return }
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        icon = decodeIcon(this, intent.getStringExtra(EXTRA_ICON))
        tvName = saved.name
        client = RemoteClient(saved.host, saved.token)
        editor = RuleEditor(
            this,
            hasPin = { hasPin },
            createPin = { done ->
                PinPad.choose(this) { pin ->
                    call({ client.post("/pin", JSONObject().put("pin", pin)) }) { hasPin = true; done() }
                }
            },
            onChange = { rule -> call({ client.put("/rule/$pkg", ruleToJson(rule)) }) { load() } },
        )
    }

    override fun onResume() {
        super.onResume()
        render()
        load()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun <T> call(work: () -> T, ok: (T) -> Unit) {
        io(bg, work, { e -> info(getString(R.string.remote_error), e.message ?: "Error ${e.status}") }, ok)
    }

    private fun load() {
        call({ client.get("/app/$pkg") }) { d ->
            detail = d
            hasPin = d.optBoolean("hasPin")
            render()
        }
    }

    private fun render() {
        val (_, page) = newPage()
        val d = detail
        val rule = d?.optJSONObject("rule")?.let { ruleFromJson(pkg, it) } ?: AppRule(pkg)

        val header = hbox()
        header.addView(icon(icon, 64))
        val names = vbox()
        names.addView(text(label, 24f, bold = true))
        names.addView(text(getString(R.string.remote_on_tv, tvName), 13f, Palette.MUTED))
        header.addView(names, lp(0, WRAP, 1f).apply { marginStart = dp(16) })
        page.addView(header)

        if (d == null) {
            page.addView(text(getString(R.string.loading), 15f, Palette.MUTED), lp(top = dp(14)))
            return
        }
        val days = d.optJSONArray("days")
        val chart = if (days == null) emptyList() else (0 until days.length()).map {
            days.getJSONObject(it).optString("label") to days.getJSONObject(it).optLong("ms")
        }
        addUsageSummary(page, rule, d.optLong("today"), d.optLong("week"), chart)

        editor.addTo(page, rule)

        page.addView(text(getString(R.string.actions_heading), 20f, bold = true), lp(top = dp(20), bottom = dp(8)))
        page.addView(editor.setting("extra", getString(R.string.remote_extra_time), "") {
            call({ client.post("/override/$pkg", JSONObject().put("minutes", 15)) }) {
                info(getString(R.string.remote_extra_time), getString(R.string.remote_extra_time_d, label))
            }
        })
        page.addView(editor.setting("open", getString(R.string.remote_open_on_tv, tvName), "") {
            call({ client.post("/launch/$pkg") }) { }
        })
        if (!rule.isDefault) page.addView(editor.setting("reset", getString(R.string.reset_rules), "") {
            call({ client.put("/rule/$pkg", ruleToJson(AppRule(pkg))) }) { load() }
        })

        editor.restoreFocus(page)
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ICON = "icon"
    }
}
