package app.screenkeeper

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

class AdbException(message: String) : IOException(message)

/**
 * Minimal ADB client over TCP — just enough to install an APK on a TV that has
 * "Network debugging" turned on (classic adb on port 5555, RSA auth, no TLS pairing).
 *
 * The first connection makes the TV ask "Allow network debugging?"; once the user ticks
 * "Always allow", later connections go straight through with the stored key.
 * All calls block; run them off the UI thread.
 */
class AdbClient private constructor(private val socket: Socket, private val keys: KeyPair) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream().buffered()
    private var maxData = 4096
    private var nextLocalId = 1

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    // ---------- Connection and auth ----------

    private fun handshake(onApprovalNeeded: () -> Unit) {
        send(CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val m = try {
                read()
            } catch (e: SocketTimeoutException) {
                throw AdbException(if (sentPublicKey) "The TV didn't allow the connection (no answer to the prompt)." else "The TV didn't answer.")
            }
            when (m.cmd) {
                CNXN -> { maxData = m.arg1.coerceIn(1024, MAX_PAYLOAD); return }
                AUTH -> if (m.arg0 == AUTH_TOKEN) {
                    when {
                        !sentSignature -> { send(AUTH, AUTH_SIGNATURE, 0, sign(m.data)); sentSignature = true }
                        !sentPublicKey -> {
                            send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyString(keys.public as RSAPublicKey))
                            sentPublicKey = true
                            socket.soTimeout = 90_000 // time for someone to walk to the TV and press Allow
                            onApprovalNeeded()
                        }
                        else -> throw AdbException("The TV rejected this phone's key.")
                    }
                }
                STLS -> throw AdbException("This port needs wireless-debugging pairing. Use the TV's \"Network debugging\" (port 5555) instead.")
            }
        }
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    private fun sign(token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keys.private)
        c.update(SHA1_DIGEST_INFO)
        return c.doFinal(token)
    }

    // ---------- Streams ----------

    private inner class Stream(val localId: Int, val remoteId: Int) {
        val out = StringBuilder()
        var closed = false

        /** Send one chunk and wait for the TV's OKAY, collecting any output that arrives meanwhile. */
        fun write(data: ByteArray, len: Int) {
            send(WRTE, localId, remoteId, data, len)
            while (true) {
                val m = read()
                if (m.arg1 != localId && m.cmd != CNXN) continue
                when (m.cmd) {
                    OKAY -> return
                    WRTE -> { out.append(String(m.data, Charsets.UTF_8)); send(OKAY, localId, remoteId) }
                    CLSE -> { closed = true; throw AdbException(out.toString().trim().ifBlank { "The TV closed the connection." }) }
                }
            }
        }

        /** Read output until the TV closes the stream. */
        fun readAll(): String {
            while (!closed) {
                val m = read()
                if (m.arg1 != localId) continue
                when (m.cmd) {
                    WRTE -> { out.append(String(m.data, Charsets.UTF_8)); send(OKAY, localId, remoteId) }
                    CLSE -> { closed = true; send(CLSE, localId, remoteId) }
                }
            }
            return out.toString()
        }
    }

    private fun open(service: String): Stream {
        val local = nextLocalId++
        send(OPEN, local, 0, (service + "\u0000").toByteArray())
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.cmd) {
                OKAY -> return Stream(local, m.arg0)
                CLSE -> throw AdbException("The TV refused: $service")
            }
        }
    }

    /** Run a shell command and return what it printed. */
    fun shell(command: String): String = open("exec:$command").readAll()

    /**
     * Install (or update, keeping data) the APK streamed from [apk] of [size] bytes.
     * Returns the package manager's output; it contains "Success" when it worked.
     */
    fun install(apk: InputStream, size: Long, progress: (sent: Long) -> Unit): String {
        val s = open("exec:cmd package install -r -S $size")
        val buf = ByteArray(minOf(maxData, 64 * 1024))
        var sent = 0L
        socket.soTimeout = 60_000
        while (sent < size) {
            val n = apk.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
            if (n < 0) break
            s.write(buf, n)
            sent += n
            progress(sent)
        }
        socket.soTimeout = 120_000 // the package manager can take a while to verify and install
        return s.readAll().trim()
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    // ---------- Wire format ----------

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY, len: Int = data.size) {
        var sum = 0
        for (i in 0 until len) sum += data[i].toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd.inv())
        output.write(h.array())
        if (len > 0) output.write(data, 0, len)
        output.flush()
    }

    private fun read(): Msg {
        val head = ByteArray(24)
        input.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        b.int // checksum (not verified; newer adb doesn't fill it either)
        if (b.int != cmd.inv()) throw AdbException("Not an adb connection.")
        if (len < 0 || len > 16 * 1024 * 1024) throw AdbException("Bad adb message.")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    companion object {
        const val DEFAULT_PORT = 5555

        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private val EMPTY = ByteArray(0)
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Connect and authenticate. [onApprovalNeeded] runs (on this thread) when the TV is showing its Allow prompt. */
        fun connect(ctx: Context, host: String, port: Int, onApprovalNeeded: () -> Unit): AdbClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5_000)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw AdbException("Can't reach $host:$port. Is Network debugging on?")
            }
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val c = AdbClient(socket, keyPair(ctx))
            try {
                c.handshake(onApprovalNeeded)
                socket.soTimeout = 15_000
            } catch (e: Exception) {
                c.close()
                throw e as? AdbException ?: AdbException(e.message ?: "Connection failed")
            }
            return c
        }

        /** This phone's adb key, made once and kept in app storage (the TV remembers it after "Always allow"). */
        @Synchronized
        fun keyPair(ctx: Context): KeyPair {
            val priv = File(ctx.filesDir, "adbkey")
            val pub = File(ctx.filesDir, "adbkey.pub")
            if (priv.exists() && pub.exists()) {
                try {
                    val kf = KeyFactory.getInstance("RSA")
                    return KeyPair(
                        kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                        kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                    )
                } catch (_: Exception) {
                    // corrupt: make a new one below
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val kp = gen.generateKeyPair()
            priv.writeBytes(kp.private.encoded)
            pub.writeBytes(kp.public.encoded)
            return kp
        }

        /** adb's own public-key format: a little-endian struct with Montgomery helpers, base64, then a name. */
        fun publicKeyString(key: RSAPublicKey): ByteArray {
            val words = 64 // 2048-bit
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n = key.modulus
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong().toInt()
            val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
            val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words).putInt(n0inv)
            for (i in 0 until words) b.putInt(n.shiftRight(32 * i).mod(r32).toLong().toInt())
            for (i in 0 until words) b.putInt(rr.shiftRight(32 * i).mod(r32).toLong().toInt())
            b.putInt(key.publicExponent.toInt())
            val name = "screenkeeper@" + Build.MODEL.replace(' ', '_')
            return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + name + "\u0000").toByteArray()
        }

        /** Split "192.168.1.50" or "192.168.1.50:5555" into host and port. */
        fun parseAddress(s: String): Pair<String, Int>? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val i = t.lastIndexOf(':')
            if (i > 0 && t.indexOf(':') == i) {
                val port = t.substring(i + 1).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                return t.substring(0, i) to port
            }
            return t to DEFAULT_PORT
        }
    }
}
