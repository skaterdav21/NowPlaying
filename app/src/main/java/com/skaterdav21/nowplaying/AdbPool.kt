package com.skaterdav21.nowplaying

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one ADB-over-TCP connection per TV. The app's RSA key lives in its private files dir,
 * so after you tick "Always allow from this computer" on the TV once, it never prompts again.
 */
object AdbPool {
    private val conns = ConcurrentHashMap<String, Dadb>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var keyPair: AdbKeyPair? = null

    fun keyPair(ctx: Context): AdbKeyPair = synchronized(this) {
        keyPair ?: run {
            val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
            val priv = File(dir, "adbkey")
            val pub = File(dir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
            AdbKeyPair.read(priv, pub).also { keyPair = it }
        }
    }

    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 1200): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun drop(key: String) {
        conns.remove(key)?.let { runCatching { it.close() } }
    }

    private fun exec(ctx: Context, dev: AdbDevice, cmd: String): String {
        val d = conns.getOrPut(dev.key) { Dadb.create(dev.host, dev.port, keyPair(ctx)) }
        return try {
            d.shell(cmd).output
        } catch (e: Exception) {
            drop(dev.key)
            throw e
        }
    }

    /**
     * Run a shell command on the TV. Returns null only when [skipIfBusy] is set and a previous
     * command on the same device is still running (so slow TVs don't pile up polls).
     */
    suspend fun shell(
        ctx: Context,
        dev: AdbDevice,
        cmd: String,
        timeoutMs: Long = 8000,
        skipIfBusy: Boolean = false,
    ): String? {
        val mutex = locks.getOrPut(dev.key) { Mutex() }
        if (skipIfBusy) {
            if (!mutex.tryLock()) return null
        } else {
            mutex.lock()
        }
        try {
            val app = ctx.applicationContext
            val job = scope.async { exec(app, dev, cmd) }
            val res = withTimeoutOrNull(timeoutMs) { job.await() }
            if (res == null) {
                // Closing the socket unblocks the stuck read on the IO thread.
                drop(dev.key)
                job.cancel()
                throw IOException("ADB timed out. If the TV shows an 'Allow USB debugging?' prompt, tick Always allow and accept it.")
            }
            return res
        } finally {
            mutex.unlock()
        }
    }

    fun closeAll() {
        conns.keys.toList().forEach { drop(it) }
    }
}
