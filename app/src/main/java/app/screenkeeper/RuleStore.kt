package app.screenkeeper

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

enum class PinResult { OK, WRONG, LOCKED_OUT }

/** All persistent state: per-app rules, PIN, overrides, breaks. Backed by SharedPreferences (in-memory cached). */
class RuleStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("screenkeeper", Context.MODE_PRIVATE)

    // ---- Rules ----

    fun rule(pkg: String): AppRule? = prefs.getString(RULE + pkg, null)?.let { parse(pkg, it) }

    fun ruleOrDefault(pkg: String): AppRule = rule(pkg) ?: AppRule(pkg)

    fun save(rule: AppRule) {
        val e = prefs.edit()
        if (rule.isDefault) e.remove(RULE + rule.pkg) else e.putString(RULE + rule.pkg, toJson(rule))
        e.apply()
    }

    fun allRules(): Map<String, AppRule> {
        val out = HashMap<String, AppRule>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(RULE) && v is String) {
                val pkg = k.removePrefix(RULE)
                parse(pkg, v)?.let { out[pkg] = it }
            }
        }
        return out
    }

    private fun toJson(r: AppRule): String = ruleToJson(r).toString()

    private fun parse(pkg: String, s: String): AppRule? = try {
        ruleFromJson(pkg, JSONObject(s))
    } catch (_: Exception) {
        null
    }

    // ---- PIN ----

    fun hasPin(): Boolean = prefs.contains(PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit()
            .putString(PIN_SALT, salt)
            .putString(PIN_HASH, hash(salt, pin))
            .putInt(PIN_FAILS, 0)
            .apply()
    }

    fun clearPin() {
        val e = prefs.edit().remove(PIN_HASH).remove(PIN_SALT).remove(PIN_FAILS).remove(PIN_LOCK_UNTIL)
        // Rules that require a PIN make no sense without one.
        for ((pkg, r) in allRules()) if (r.pinLocked) {
            val nr = r.copy(pinLocked = false)
            if (nr.isDefault) e.remove(RULE + pkg) else e.putString(RULE + pkg, toJson(nr))
        }
        e.apply()
    }

    fun checkPin(pin: String): PinResult {
        val now = System.currentTimeMillis()
        if (prefs.getLong(PIN_LOCK_UNTIL, 0) > now) return PinResult.LOCKED_OUT
        val salt = prefs.getString(PIN_SALT, null) ?: return PinResult.WRONG
        val expected = prefs.getString(PIN_HASH, null) ?: return PinResult.WRONG
        return if (MessageDigest.isEqual(hash(salt, pin).toByteArray(), expected.toByteArray())) {
            prefs.edit().putInt(PIN_FAILS, 0).apply()
            PinResult.OK
        } else {
            val fails = prefs.getInt(PIN_FAILS, 0) + 1
            val e = prefs.edit().putInt(PIN_FAILS, fails)
            if (fails >= MAX_FAILS) {
                e.putLong(PIN_LOCK_UNTIL, now + LOCKOUT_MS).putInt(PIN_FAILS, 0)
                e.apply()
                PinResult.LOCKED_OUT
            } else {
                e.apply()
                PinResult.WRONG
            }
        }
    }

    // ---- Temporary state ----

    fun overrideUntil(pkg: String): Long = prefs.getLong(OVERRIDE + pkg, 0)
    fun setOverride(pkg: String, until: Long) = prefs.edit().putLong(OVERRIDE + pkg, until).apply()

    fun breakUntil(pkg: String): Long = prefs.getLong(BREAK + pkg, 0)
    fun setBreakUntil(pkg: String, until: Long) = prefs.edit().putLong(BREAK + pkg, until).apply()

    fun warnedDay(pkg: String): Long = prefs.getLong(WARNED + pkg, 0)
    fun setWarnedDay(pkg: String, day: Long) = prefs.edit().putLong(WARNED + pkg, day).apply()

    // ---- Remote control (this device is controlled by a phone) ----

    fun remoteEnabled(): Boolean = prefs.getBoolean(REMOTE_ON, false)
    fun setRemoteEnabled(on: Boolean) = prefs.edit().putBoolean(REMOTE_ON, on).apply()

    /** Six-digit code shown on this device; a phone sends it once to pair. */
    fun pairingCode(): String = prefs.getString(PAIR_CODE, null) ?: Remote.newCode().also {
        prefs.edit().putString(PAIR_CODE, it).apply()
    }

    fun newPairingCode() = prefs.edit().putString(PAIR_CODE, Remote.newCode()).apply()

    /** Paired phones: token -> name. */
    fun pairedDevices(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val j = JSONObject(prefs.getString(TOKENS, "{}") ?: "{}")
            for (k in j.keys()) out[k] = j.optString(k)
        } catch (_: Exception) {
        }
        return out
    }

    fun addToken(token: String, name: String) {
        val j = JSONObject(pairedDevices() as Map<*, *>).put(token, name)
        prefs.edit().putString(TOKENS, j.toString()).apply()
    }

    fun removeToken(token: String) {
        val m = LinkedHashMap(pairedDevices())
        m.remove(token)
        prefs.edit().putString(TOKENS, JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun clearTokens() = prefs.edit().remove(TOKENS).apply()

    fun isValidToken(token: String): Boolean = token.length >= 32 && pairedDevices().containsKey(token)

    fun touchToken(token: String) = prefs.edit().putLong(TOKEN_SEEN + token, System.currentTimeMillis()).apply()

    // ---- Remote control (this device is the phone) ----

    class SavedRemote(val host: String, val token: String, val name: String)

    fun savedRemote(): SavedRemote? {
        val host = prefs.getString(REMOTE_HOST, null) ?: return null
        val token = prefs.getString(REMOTE_TOKEN, null) ?: return null
        return SavedRemote(host, token, prefs.getString(REMOTE_NAME, "TV") ?: "TV")
    }

    fun setSavedRemote(host: String, token: String, name: String) =
        prefs.edit().putString(REMOTE_HOST, host).putString(REMOTE_TOKEN, token).putString(REMOTE_NAME, name).apply()

    fun clearSavedRemote() = prefs.edit().remove(REMOTE_HOST).remove(REMOTE_TOKEN).remove(REMOTE_NAME).apply()

    /** Last address used for "Update TV over ADB" (host or host:port). */
    fun adbAddress(): String? = prefs.getString(ADB_ADDRESS, null)
    fun setAdbAddress(a: String) = prefs.edit().putString(ADB_ADDRESS, a).apply()

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val RULE = "rule:"
        private const val OVERRIDE = "override:"
        private const val BREAK = "break:"
        private const val WARNED = "warned:"
        private const val PIN_HASH = "pin_hash"
        private const val PIN_SALT = "pin_salt"
        private const val PIN_FAILS = "pin_fails"
        private const val PIN_LOCK_UNTIL = "pin_lock_until"
        private const val REMOTE_ON = "remote_on"
        private const val PAIR_CODE = "pair_code"
        private const val TOKENS = "remote_tokens"
        private const val TOKEN_SEEN = "token_seen:"
        private const val REMOTE_HOST = "remote_host"
        private const val REMOTE_TOKEN = "remote_token"
        private const val REMOTE_NAME = "remote_name"
        private const val ADB_ADDRESS = "adb_address"
        private const val MAX_FAILS = 5
        const val LOCKOUT_MS = 30_000L
    }
}

/** In-process state shared between the guard service and the block screen. */
object GuardState {
    /** App unlocked with the PIN for the current visit; cleared when another app comes to the front. */
    @Volatile var unlockedPkg: String? = null
    @Volatile var blockVisible: Boolean = false
    @Volatile var blockPkg: String? = null
}

/** Settings screens unlock for a while after the PIN is entered. */
object Admin {
    private var unlockedUntil = 0L
    private const val WINDOW = 10 * 60_000L
    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil
    fun unlock() { unlockedUntil = System.currentTimeMillis() + WINDOW }
    fun touch() { if (isUnlocked()) unlock() }
    fun lock() { unlockedUntil = 0L }
}

/** Rule <-> JSON, shared by local storage and the remote-control protocol. */
fun ruleToJson(r: AppRule): JSONObject = JSONObject()
    .put("limit", r.dailyLimitMin)
    .put("blocked", r.blocked)
    .put("unlimited", r.unlimited)
    .put("pin", r.pinLocked)
    .put("from", r.allowedFromMin)
    .put("to", r.allowedToMin)
    .put("breakEvery", r.breakEveryMin)
    .put("breakFor", r.breakForMin)

fun ruleFromJson(pkg: String, j: JSONObject): AppRule = AppRule(
    pkg = pkg,
    dailyLimitMin = j.optInt("limit", 0),
    blocked = j.optBoolean("blocked", false),
    unlimited = j.optBoolean("unlimited", false),
    pinLocked = j.optBoolean("pin", false),
    allowedFromMin = j.optInt("from", -1),
    allowedToMin = j.optInt("to", -1),
    breakEveryMin = j.optInt("breakEvery", 0),
    breakForMin = j.optInt("breakFor", 0),
)

/** Any time rule cancels "unlimited", since the two contradict each other. */
fun sanitizeRule(rule: AppRule): AppRule =
    if (rule.unlimited && (rule.dailyLimitMin > 0 || rule.hasWindow || rule.hasBreaks)) rule.copy(unlimited = false) else rule
