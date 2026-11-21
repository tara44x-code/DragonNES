package com.dragonnes.emu.netplay

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** A game being hosted on the local network. */
data class LanGame(val address: String, val port: Int, val hostName: String, val game: String)

/**
 * Same Wi-Fi / phone hotspot. The host listens on TCP [Protocol.LAN_PORT] and answers UDP
 * broadcasts on [Protocol.DISCOVERY_PORT], so the guest finds it without typing anything.
 */
class LanHost(
    private val context: Context,
    private val hostName: String,
    private val game: String,
) {
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jobs = mutableListOf<Job>()

    /** Waits for one guest and returns the connection. */
    fun start(scope: CoroutineScope, onGuest: (Transport) -> Unit, onError: (String) -> Unit) {
        multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("dragonnes-lan")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        jobs += scope.launch(Dispatchers.IO) {
            runCatching {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Protocol.LAN_PORT)) }
                server = s
                val socket = s.accept()
                stopListening()
                onGuest(TcpTransport(socket))
            }.onFailure { if (server?.isClosed == false || server == null) onError("تعذر فتح الاستضافة: ${it.message}") }
        }
        jobs += scope.launch(Dispatchers.IO) {
            runCatching {
                val d = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Protocol.DISCOVERY_PORT)) }
                discovery = d
                val buf = ByteArray(256)
                while (isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    d.receive(p)
                    if (String(p.data, 0, p.length) == QUERY) {
                        val reply = "$ANSWER|${Protocol.LAN_PORT}|${hostName.take(40)}|${game.take(60)}".toByteArray()
                        d.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            }
        }
    }

    private fun stopListening() {
        runCatching { discovery?.close() }
        runCatching { server?.close() }
        runCatching { multicastLock?.release() }
    }

    fun stop() {
        stopListening()
        jobs.forEach { it.cancel() }
    }

    companion object {
        const val QUERY = "DRAGONNES?"
        const val ANSWER = "DRAGONNES!"

        /** IPv4 addresses of this phone on Wi-Fi / hotspot, to show the host's address. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.startsWith("192.168.") || it.startsWith("10.") || it.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*""")) }
        }.getOrDefault(emptyList())

        /** Broadcasts a query and collects the answers for [timeoutMs]. */
        suspend fun discover(context: Context, timeoutMs: Int = 1800): List<LanGame> = withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, LanGame>()
            val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("dragonnes-find")?.apply { setReferenceCounted(false); runCatching { acquire() } }
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 300
                    val q = QUERY.toByteArray()
                    val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                    val end = System.currentTimeMillis() + timeoutMs
                    var nextSend = 0L
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() >= nextSend) {
                            targets.forEach { t -> runCatching { s.send(DatagramPacket(q, q.size, t, Protocol.DISCOVERY_PORT)) } }
                            nextSend = System.currentTimeMillis() + 500
                        }
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val parts = String(p.data, 0, p.length).split('|')
                            val ip = p.address.hostAddress
                            if (parts.size >= 4 && parts[0] == ANSWER && ip != null) {
                                found[ip] = LanGame(ip, parts[1].toIntOrNull() ?: Protocol.LAN_PORT, parts[2], parts[3])
                            }
                        } catch (_: SocketTimeoutException) { }
                    }
                }
            }
            runCatching { lock?.release() }
            found.values.toList()
        }

        private fun broadcastAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())

        /** Connects to a host (found or typed). */
        suspend fun connect(address: String, port: Int = Protocol.LAN_PORT): Transport = withContext(Dispatchers.IO) {
            val socket = Socket()
            socket.connect(InetSocketAddress(address.trim(), port), 5000)
            TcpTransport(socket)
        }
    }
}
