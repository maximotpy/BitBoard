package com.bitboard.app.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * LAN discovery, UDP beacon compatible with the desktop engine.
 *
 * Wire format:
 *   {"app":"bitboard","v":2,"peerId":"…","host":"…","port":6881,"reply":false,
 *    "boards":[{"name":"…","infoHash":"…"}]}
 *
 * What makes this robust on real phones:
 *  - the multicast group is joined, and beacons are sent, on EVERY usable
 *    network interface (a null interface = "whatever the OS picks", which is
 *    often not wlan0);
 *  - each beacon also goes to the subnet BROADCAST address, because many
 *    routers/APs filter multicast to Wi-Fi clients but pass broadcast;
 *  - a beacon from a new device is answered with a direct UNICAST beacon, so
 *    discovery works even if multicast only flows one way;
 *  - the beacon carries our BitTorrent port so the peer can connect straight
 *    to us instead of depending on trackers / DHT;
 *  - the MulticastLock (and a Wi-Fi lock) are held in FIELDS. A lock kept in a
 *    local variable can be garbage-collected, and the OS then silently drops
 *    multicast packets again.
 */
class LanDiscovery(private val engine: BitBoardEngine) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var running = false

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val joined = HashSet<String>()                    // interface name + address
    private val repliedAt = HashMap<String, Long>()           // ip -> last unicast reply

    fun start() {
        if (running) return
        running = true
        scope.launch { run() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        wifiLock = null
        scope.cancel()
    }

    private fun acquireLocks() {
        try {
            val wifi = engine.context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("bitboard-multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
            // Keep the Wi-Fi radio out of power-save so packets still arrive
            // with the screen off (needs WAKE_LOCK).
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "bitboard-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            engine.log("could not acquire Wi-Fi locks: ${e.message}")
        }
    }

    /** Usable IPv4 interfaces (up, multicast-capable, not loopback). */
    private fun lanInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
            try {
                ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                    ni.interfaceAddresses.any { a ->
                        val ad = a.address
                        ad is Inet4Address && !ad.isLinkLocalAddress
                    }
            } catch (_: Exception) { false }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun ensureMemberships(sock: MulticastSocket, group: InetAddress, ifaces: List<NetworkInterface>) {
        for (ni in ifaces) {
            val key = ni.name + ni.interfaceAddresses.joinToString { it.address.hostAddress ?: "" }
            if (key in joined) continue
            try {
                sock.joinGroup(InetSocketAddress(group, Protocol.MULTICAST_PORT), ni)
                joined.add(key)
            } catch (e: Exception) {
                engine.log("multicast join failed on ${ni.name}: ${e.message}")
            }
        }
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        try {
            acquireLocks()

            val group = InetAddress.getByName(Protocol.MULTICAST_ADDR)
            val sock = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.MULTICAST_PORT))
                soTimeout = 2000        // lets the loop notice stop()
                timeToLive = 1
                broadcast = true
            }
            socket = sock

            val ifaces = lanInterfaces()
            ensureMemberships(sock, group, ifaces)
            if (ifaces.isEmpty()) {
                // Nothing usable yet (Wi-Fi still connecting); the sender loop
                // keeps re-checking every few seconds.
                try { sock.joinGroup(group) } catch (_: Exception) {}
            }
            engine.log(
                "LAN discovery active on ${Protocol.MULTICAST_ADDR}:${Protocol.MULTICAST_PORT} " +
                    "[${ifaces.joinToString { it.name }.ifEmpty { "no LAN interface yet" }}]"
            )

            // Receiver loop
            launch {
                val buf = ByteArray(16384)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        handleBeacon(
                            String(pkt.data, 0, pkt.length, Charsets.UTF_8),
                            pkt.address.hostAddress ?: continue,
                            sock
                        )
                    } catch (_: SocketTimeoutException) {
                        // loop
                    } catch (_: Exception) {
                        if (running) delay(500)
                    }
                }
            }

            // Beacon sender loop
            while (running) {
                sendBeacon(sock, group)
                delay(Protocol.BEACON_INTERVAL_MS)
            }
        } catch (e: Exception) {
            engine.log("discovery error: ${e.message}")
        }
    }

    private fun beaconBytes(isReply: Boolean): ByteArray {
        val arr = JSONArray()
        for ((name, infoHash) in engine.beaconBoards()) {
            arr.put(JSONObject().put("name", name).put("infoHash", infoHash))
        }
        return JSONObject()
            .put("app", "bitboard")
            .put("v", 2)
            .put("peerId", engine.peerIdValue())
            .put("host", engine.hostName())
            .put("port", engine.listenPort())
            .put("reply", isReply)
            .put("boards", arr)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** Multicast + subnet broadcast on every interface. Sequential because
     *  setNetworkInterface() is socket-wide state. */
    private fun sendBeacon(sock: MulticastSocket, group: InetAddress) {
        try {
            val bytes = beaconBytes(false)
            val ifaces = lanInterfaces()
            ensureMemberships(sock, group, ifaces)

            if (ifaces.isEmpty()) {
                try { sock.send(DatagramPacket(bytes, bytes.size, group, Protocol.MULTICAST_PORT)) } catch (_: Exception) {}
                return
            }
            for (ni in ifaces) {
                try {
                    sock.networkInterface = ni
                    sock.send(DatagramPacket(bytes, bytes.size, group, Protocol.MULTICAST_PORT))
                } catch (_: Exception) {}
                for (ia in ni.interfaceAddresses) {
                    val bc = ia.broadcast ?: continue      // IPv4 only
                    try {
                        sock.send(DatagramPacket(bytes, bytes.size, bc, Protocol.MULTICAST_PORT))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleBeacon(text: String, fromAddress: String, sock: MulticastSocket) {
        try {
            val msg = JSONObject(text)
            if (msg.optString("app") != "bitboard") return
            val peerId = msg.optString("peerId")
            if (peerId == engine.peerIdValue()) return // our own

            // Answer directly (unicast) so discovery also works when multicast
            // only flows in one direction. Replies are never replied to.
            if (!msg.optBoolean("reply", false)) {
                val now = System.currentTimeMillis()
                val last = repliedAt[fromAddress] ?: 0L
                if (now - last > 2000) {
                    repliedAt[fromAddress] = now
                    try {
                        val bytes = beaconBytes(true)
                        sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(fromAddress), Protocol.MULTICAST_PORT))
                    } catch (_: Exception) {}
                }
            }

            val boardsArr = msg.optJSONArray("boards") ?: return
            val list = ArrayList<Pair<String, String>>(boardsArr.length())
            for (i in 0 until boardsArr.length()) {
                val b = boardsArr.optJSONObject(i) ?: continue
                val name = b.optString("name")
                val infoHash = b.optString("infoHash")
                if (name.isNotBlank() && Protocol.isInfoHash(infoHash)) list.add(name to infoHash.lowercase())
            }
            engine.onLanBoards(list, fromAddress, msg.optInt("port", 0), peerId)
        } catch (_: Exception) {}
    }
}
