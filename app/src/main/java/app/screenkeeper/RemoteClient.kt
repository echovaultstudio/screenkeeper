package app.screenkeeper

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown for any failed remote call; [status] is 0 when the TV could not be reached at all. */
class RemoteException(val status: Int, message: String) : IOException(message)

/** Phone-side client for the TV's remote-control API (see [Remote]). All calls block; run them off the UI thread. */
class RemoteClient(val host: String, var token: String?) {

    fun get(path: String): JSONObject = call("GET", path, null)
    fun post(path: String, body: JSONObject? = null): JSONObject = call("POST", path, body ?: JSONObject())
    fun put(path: String, body: JSONObject): JSONObject = call("PUT", path, body)

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = try {
            URL("http://$host:${Remote.PORT}$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw RemoteException(0, e.message ?: "bad address")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            token?.let { conn.setRequestProperty(Remote.HEADER_TOKEN, it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw RemoteException(status, json.optString("error", "HTTP $status"))
            return json
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException(0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Does something that speaks our protocol answer at [host]? */
        fun probe(host: String): JSONObject? = try {
            val j = RemoteClient(host, null).get("/info")
            if (j.optString("app") == Remote.APP_ID) j else null
        } catch (_: Exception) {
            null
        }
    }
}

/** Finds Screenkeeper TVs on the local network via DNS-SD. Results arrive on the main thread. */
class RemoteDiscovery(ctx: Context, private val onFound: (name: String, host: String) -> Unit) {
    data class Found(val name: String, val host: String)

    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private var listener: NsdManager.DiscoveryListener? = null
    val found = LinkedHashMap<String, Found>()

    fun start() {
        val m = nsd ?: return
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) { Log.w(TAG, "discovery failed $e") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != Remote.SERVICE_TYPE.trimEnd('.')) return
                pending.add(info)
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try {
            m.discoverServices(Remote.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (t: Throwable) {
            Log.w(TAG, "discovery unavailable", t)
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) {}
        listener = null
    }

    private fun resolveNext() {
        val m = nsd ?: return
        if (!resolving.compareAndSet(false, true)) return
        val info = pending.poll() ?: run { resolving.set(false); return }
        @Suppress("DEPRECATION")
        m.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { resolving.set(false); resolveNext() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = i.host?.hostAddress
                val name = i.serviceName.removePrefix("Screenkeeper ").trim().ifBlank { i.serviceName }
                resolving.set(false)
                if (host != null) main.post {
                    if (found.put(host, Found(name, host)) == null) onFound(name, host)
                }
                resolveNext()
            }
        })
    }

    companion object {
        private const val TAG = "Screenkeeper"
    }
}
