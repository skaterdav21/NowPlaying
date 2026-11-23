package com.skaterdav21.nowplaying

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger

/** Finds devices on the local /24 with network ADB (port 5555) open. */
object NetworkScanner {
    @Suppress("DEPRECATION")
    fun localIp(ctx: Context): Inet4Address? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.let { return it }
        }
        return null
    }

    suspend fun scan(ctx: Context, port: Int = 5555, onProgress: (Int, Int) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val ip = localIp(ctx) ?: throw IllegalStateException("Not connected to Wi-Fi")
        val b = ip.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        val hosts = (1..254).filter { it != self }.map { prefix + it }
        val done = AtomicInteger(0)
        val gate = Semaphore(48)
        coroutineScope {
            hosts.map { h ->
                async {
                    gate.withPermit {
                        val ok = AdbPool.reachable(h, port, 450)
                        onProgress(done.incrementAndGet(), hosts.size)
                        if (ok) h else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
