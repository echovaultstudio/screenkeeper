package app.screenkeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Remote-control protocol: small JSON-over-HTTP API served by the controlled device (the TV)
 * on the local network and used by the phone. Pairing is done once with a 6-digit code shown
 * on the TV; after that the phone sends its token in the X-Token header.
 *
 *   GET  /info                 no auth       {app, name, version, hasPin}
 *   POST /pair                 {code, name}  {token, name}
 *   GET  /state                              {name, hasPin, today, apps:[...]}
 *   GET  /app/{pkg}                          {pkg, label, today, week, days:[{label, ms}], rule}
 *   PUT  /rule/{pkg}           rule json     {rule}
 *   POST /pin                  {pin, current?}
 *   POST /pin/clear            {current}
 *   POST /pin/check            {pin}         {result: OK|WRONG|LOCKED_OUT}
 *   POST /lock
 *   POST /override/{pkg}       {minutes}
 *   POST /launch/{pkg}
 *   POST /unpair
 *   GET  /pinschedule                        {schedule:{enabled, from, to, allApps, pkgs}, hasPin}
 *   PUT  /pinschedule          schedule json {schedule}
 *   GET  /history/{month|year}/{key}         usage statistics for a month (2026-11) or year (2026), see UsageHistory.summary
 *   GET  /keyboard                           {enabled, field:{text, hint, password, app}|null}
 *   POST /keyboard/text        {text}        {field}   replaces the focused field's text on the TV
 *   POST /keyboard/enter                     {field}   presses the keyboard's Enter/Search key
 *        (keyboard errors: 409 "keyboard off" | "no text field", 422 "not accepted", 501 "needs Android 11")
 */
object Remote {
    const val PORT = 47317
    const val SERVICE_TYPE = "_screenkeeper._tcp."
    const val HEADER_TOKEN = "X-Token"
    const val APP_ID = "screenkeeper"

    fun deviceName(ctx: Context): String {
        val fromSettings = try {
            Settings.Global.getString(ctx.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return fromSettings?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** First non-loopback IPv4 address, for showing on the TV so the phone can be pointed at it manually. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun newCode(): String = String.format(java.util.Locale.US, "%06d", SecureRandom().nextInt(1_000_000))

    fun iconToBase64(d: Drawable?, sizePx: Int): String? {
        if (d == null) return null
        return try {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(c)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }
}

class HttpError(val status: Int, message: String) : Exception(message)

/** Tiny single-purpose HTTP/1.1 server. Runs on the controlled device inside the guard service. */
class RemoteServer(private val ctx: Context, private val store: RuleStore) {
    private val tracker = UsageTracker(ctx)
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    // Pairing brute-force protection.
    private var pairFails = 0
    private var pairBlockedUntil = 0L

    val running: Boolean get() = socket != null

    @Synchronized
    fun start() {
        if (socket != null) return
        try {
            val s = ServerSocket(Remote.PORT)
            s.reuseAddress = true
            socket = s
            val p = Executors.newCachedThreadPool()
            pool = p
            Thread({
                while (!s.isClosed) {
                    try {
                        val client = s.accept()
                        p.execute { serve(client) }
                    } catch (_: Exception) {
                        if (s.isClosed) break
                    }
                }
            }, "sk-remote-accept").start()
            registerService()
            Log.i(TAG, "remote server listening on ${Remote.PORT}")
        } catch (t: Throwable) {
            Log.w(TAG, "could not start remote server", t)
            socket = null
        }
    }

    @Synchronized
    fun stop() {
        unregisterService()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun registerService() {
        try {
            val m = ctx.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "Screenkeeper " + Remote.deviceName(ctx).take(40)
                serviceType = Remote.SERVICE_TYPE
                port = Remote.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "nsd register failed $e") }
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            registration = l
        } catch (t: Throwable) {
            Log.w(TAG, "nsd unavailable", t)
        }
    }

    private fun unregisterService() {
        val l = registration ?: return
        try { nsd?.unregisterService(l) } catch (_: Exception) {}
        registration = null
    }

    // ---- HTTP plumbing ----

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = 10_000
            val out = c.getOutputStream()
            try {
                val req = parse(c.getInputStream()) ?: return
                val (status, body) = try {
                    200 to route(req)
                } catch (e: HttpError) {
                    e.status to JSONObject().put("error", e.message ?: "error")
                } catch (t: Throwable) {
                    Log.w(TAG, "request failed: ${req.method} ${req.path}", t)
                    500 to JSONObject().put("error", t.toString())
                }
                write(out, status, body.toString())
            } catch (t: Throwable) {
                Log.w(TAG, "bad request", t)
                try { write(out, 400, "{\"error\":\"bad request\"}") } catch (_: Exception) {}
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val ins = input.buffered()
        val requestLine = readLine(ins) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(ins) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 1_000_000) throw HttpError(413, "too large")
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = ins.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        return Request(parts[0].uppercase(), path, headers, String(buf, 0, read, Charsets.UTF_8))
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw HttpError(431, "header too long")
        }
        return sb.toString()
    }

    private fun write(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ---- Routing ----

    private fun route(r: Request): JSONObject {
        val seg = r.path.trim('/').split('/')
        val head = seg.getOrNull(0) ?: ""
        if (r.method == "GET" && head == "info") return info()
        if (r.method == "POST" && head == "pair") return pair(json(r.body))

        val token = r.headers[Remote.HEADER_TOKEN.lowercase()] ?: throw HttpError(401, "not paired")
        if (!store.isValidToken(token)) throw HttpError(401, "not paired")
        store.touchToken(token)

        return when {
            r.method == "GET" && head == "state" -> state()
            r.method == "GET" && head == "app" && seg.size == 2 -> appDetail(seg[1])
            r.method == "PUT" && head == "rule" && seg.size == 2 -> setRule(seg[1], json(r.body))
            r.method == "POST" && head == "pin" && seg.size == 1 -> setPin(json(r.body))
            r.method == "POST" && head == "pin" && seg.getOrNull(1) == "clear" -> clearPin(json(r.body))
            r.method == "POST" && head == "pin" && seg.getOrNull(1) == "check" -> checkPin(json(r.body))
            r.method == "POST" && head == "lock" -> { Admin.lock(); ok() }
            r.method == "POST" && head == "override" && seg.size == 2 -> override(seg[1], json(r.body))
            r.method == "POST" && head == "launch" && seg.size == 2 -> ok().put("launched", Apps.launch(ctx, seg[1]))
            r.method == "POST" && head == "unpair" -> { store.removeToken(token); ok() }
            r.method == "GET" && head == "pinschedule" -> pinScheduleJson()
            r.method == "PUT" && head == "pinschedule" -> {
                val s = pinScheduleFromJson(json(r.body))
                if (s.enabled && !store.hasPin()) throw HttpError(400, "set a PIN first")
                store.setPinSchedule(s)
                pinScheduleJson()
            }
            r.method == "GET" && head == "history" && seg.size >= 2 ->
                UsageHistory(ctx).summary(seg[1], seg.getOrNull(2), withIcons = true)
            r.method == "GET" && head == "keyboard" && seg.size == 1 -> keyboardState()
            r.method == "POST" && head == "keyboard" && seg.getOrNull(1) == "text" -> keyboardText(json(r.body))
            r.method == "POST" && head == "keyboard" && seg.getOrNull(1) == "enter" -> keyboardResult(keyboard().enter())
            else -> throw HttpError(404, "no such endpoint")
        }
    }

    private fun json(s: String): JSONObject = if (s.isBlank()) JSONObject() else try {
        JSONObject(s)
    } catch (_: Exception) {
        throw HttpError(400, "invalid json")
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun info(): JSONObject = JSONObject()
        .put("app", Remote.APP_ID)
        .put("name", Remote.deviceName(ctx))
        .put("version", BuildConfig.VERSION_NAME)
        .put("hasPin", store.hasPin())

    @Synchronized
    private fun pair(body: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        if (now < pairBlockedUntil) throw HttpError(429, "too many attempts, wait a minute")
        val code = body.optString("code")
        val expected = store.pairingCode()
        if (code.length != 6 || code != expected) {
            pairFails++
            if (pairFails >= 5) { pairFails = 0; pairBlockedUntil = now + 60_000L }
            throw HttpError(403, "wrong code")
        }
        pairFails = 0
        val name = body.optString("name").ifBlank { "Phone" }.take(40)
        val token = Remote.newToken()
        store.addToken(token, name)
        store.newPairingCode() // one code per phone
        return JSONObject().put("token", token).put("name", Remote.deviceName(ctx))
    }

    private fun state(): JSONObject {
        val days = tracker.dailyTotals(7)
        val apps = Apps.launchable(ctx)
        val rules = store.allRules()
        val pkgs = apps.map { it.pkg }.toSet()
        val today = days.last()
        val week = HashMap<String, Long>()
        days.forEach { d -> d.perApp.forEach { (k, v) -> week[k] = (week[k] ?: 0L) + v } }
        val iconPx = (48 * ctx.resources.displayMetrics.density).toInt().coerceIn(48, 96)
        val arr = JSONArray()
        for (a in apps) {
            arr.put(
                JSONObject()
                    .put("pkg", a.pkg)
                    .put("label", a.label)
                    .put("today", today.perApp[a.pkg] ?: 0L)
                    .put("week", week[a.pkg] ?: 0L)
                    .put("rule", rules[a.pkg]?.let { ruleToJson(it) } ?: JSONObject.NULL)
                    .put("icon", Remote.iconToBase64(a.icon, iconPx) ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("name", Remote.deviceName(ctx))
            .put("hasPin", store.hasPin())
            .put("today", today.total(pkgs))
            .put("week", days.sumOf { it.total(pkgs) })
            .put("days", daysJson(days) { it.total(pkgs) })
            .put("apps", arr)
    }

    private fun appDetail(pkg: String): JSONObject {
        val info = Apps.info(ctx, pkg) ?: throw HttpError(404, "app not installed")
        val days = tracker.dailyTotals(7)
        return JSONObject()
            .put("pkg", pkg)
            .put("label", info.label)
            .put("today", days.last().perApp[pkg] ?: 0L)
            .put("week", days.sumOf { it.perApp[pkg] ?: 0L })
            .put("days", daysJson(days) { it.perApp[pkg] ?: 0L })
            .put("rule", ruleToJson(store.ruleOrDefault(pkg)))
            .put("hasPin", store.hasPin())
    }

    private fun daysJson(days: List<UsageTracker.Snapshot>, value: (UsageTracker.Snapshot) -> Long): JSONArray {
        val arr = JSONArray()
        for (d in days) arr.put(JSONObject().put("label", Time.dayLabel(d.start)).put("ms", value(d)))
        return arr
    }

    private fun setRule(pkg: String, body: JSONObject): JSONObject {
        val rule = sanitizeRule(ruleFromJson(pkg, body))
        if (rule.pinLocked && !store.hasPin()) throw HttpError(400, "set a PIN first")
        store.save(rule)
        return JSONObject().put("rule", ruleToJson(store.ruleOrDefault(pkg)))
    }

    private fun setPin(body: JSONObject): JSONObject {
        val pin = body.optString("pin")
        if (pin.length != 4 || !pin.all { it.isDigit() }) throw HttpError(400, "pin must be 4 digits")
        if (store.hasPin()) verifyCurrent(body)
        store.setPin(pin)
        return ok()
    }

    private fun clearPin(body: JSONObject): JSONObject {
        if (store.hasPin()) verifyCurrent(body)
        store.clearPin()
        return ok()
    }

    private fun verifyCurrent(body: JSONObject) {
        when (store.checkPin(body.optString("current"))) {
            PinResult.OK -> {}
            PinResult.WRONG -> throw HttpError(403, "wrong")
            PinResult.LOCKED_OUT -> throw HttpError(403, "locked")
        }
    }

    private fun checkPin(body: JSONObject): JSONObject =
        JSONObject().put("result", store.checkPin(body.optString("pin")).name)

    private fun override(pkg: String, body: JSONObject): JSONObject {
        val minutes = body.optInt("minutes", 15).coerceIn(0, 24 * 60)
        store.setOverride(pkg, if (minutes == 0) 0L else System.currentTimeMillis() + minutes * Time.MINUTE)
        return ok()
    }

    private fun pinScheduleJson(): JSONObject =
        JSONObject().put("schedule", pinScheduleToJson(store.pinSchedule())).put("hasPin", store.hasPin())

    // ---- Phone keyboard ----

    /** The running service, switching it back on first if it was dropped (e.g. by an update). */
    private fun keyboardOrNull(): KeyboardService? {
        KeyboardService.instance?.let { return it }
        if (KeyboardService.wanted(ctx) || KeyboardService.isListed(ctx)) {
            if (KeyboardService.selfEnable(ctx)) {
                repeat(20) { Thread.sleep(100); KeyboardService.instance?.let { return it } }
            }
        }
        return null
    }

    private fun keyboard(): KeyboardService = keyboardOrNull() ?: throw HttpError(409, KB_OFF)

    private fun keyboardState(): JSONObject {
        val kb = keyboardOrNull() ?: return JSONObject().put("enabled", false)
            .put("listed", KeyboardService.isListed(ctx))
            .put("canSelfEnable", KeyboardService.canSelfEnable(ctx))
            .put("field", JSONObject.NULL)
        val out = kb.current()
        return JSONObject().put("enabled", true)
            .put("field", if (out is KeyboardService.Outcome.Ok) fieldJson(out.field) else JSONObject.NULL)
    }

    private fun keyboardText(body: JSONObject): JSONObject {
        if (!body.has("text")) throw HttpError(400, "text missing")
        val text = body.optString("text").take(KeyboardService.MAX_TEXT)
        return keyboardResult(keyboard().setText(text))
    }

    private fun keyboardResult(o: KeyboardService.Outcome): JSONObject = when (o) {
        is KeyboardService.Outcome.Ok -> ok().put("field", fieldJson(o.field))
        KeyboardService.Outcome.NoField -> throw HttpError(409, KB_NO_FIELD)
        KeyboardService.Outcome.Failed -> throw HttpError(422, KB_FAILED)
        KeyboardService.Outcome.Unsupported -> throw HttpError(501, KB_UNSUPPORTED)
    }

    private fun fieldJson(f: KeyboardService.Field) = JSONObject()
        .put("text", f.text)
        .put("hint", f.hint)
        .put("password", f.password)
        .put("app", f.app)

    companion object {
        private const val TAG = "Screenkeeper"
        const val KB_OFF = "keyboard off"
        const val KB_NO_FIELD = "no text field"
        const val KB_FAILED = "not accepted"
        const val KB_UNSUPPORTED = "needs Android 11"
    }
}
