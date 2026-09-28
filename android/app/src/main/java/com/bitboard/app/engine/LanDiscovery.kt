package com.bitboard.app.engine

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * LAN discovery — a faithful port of the desktop engine's UDP multicast
 * beacon (src/engine.js startDiscovery/_sendBeacon).
 *
 * Wire format (identical to desktop):
 *   {"app":"bitboard","peerId":"…","host":"…","boards":[{"name":"…","infoHash":"…"}]}
 * Sent to 239.255.66.66:45666 every 3 s; incoming beacons from other devices
 * trigger auto-join of boards we are missing.
 */
class LanDiscovery(private val engine: BitBoardEngine) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: MulticastSocket? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { run() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        try {
            val group = InetAddress.getByName(Protocol.MULTICAST_ADDR)
            val sock = MulticastSocket(Protocol.MULTICAST_PORT)
            socket = sock
            try {
                // Acquire multicast lock (required on many Android devices).
                val wifi = engine.context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lock = wifi?.createMulticastLock("bitboard-mdns")
                lock?.setReferenceCounted(false)
                lock?.acquire()
            } catch (_: Exception) {}

            sock.joinGroup(InetSocketAddress(group, Protocol.MULTICAST_PORT), null)
            engine.log("LAN discovery active on ${Protocol.MULTICAST_ADDR}:${Protocol.MULTICAST_PORT}")

            // Receiver loop
            launch {
                val buf = ByteArray(8192)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        handleBeacon(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
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

    private fun sendBeacon(sock: MulticastSocket, group: InetAddress) {
        try {
            val arr = JSONArray()
            for ((name, infoHash) in engine.beaconBoards()) {
                arr.put(JSONObject().put("name", name).put("infoHash", infoHash))
            }
            val msg = JSONObject()
                .put("app", "bitboard")
                .put("peerId", engine.peerIdValue())
                .put("host", engine.hostName())
                .put("boards", arr)
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, group, Protocol.MULTICAST_PORT))
        } catch (_: Exception) {}
    }

    private fun handleBeacon(text: String) {
        try {
            val msg = JSONObject(text)
            if (msg.optString("app") != "bitboard") return
            if (msg.optString("peerId") == engine.peerIdValue()) return // our own
            val boardsArr = msg.optJSONArray("boards") ?: return
            val list = ArrayList<Pair<String, String>>(boardsArr.length())
            for (i in 0 until boardsArr.length()) {
                val b = boardsArr.getJSONObject(i)
                val name = b.optString("name")
                val infoHash = b.optString("infoHash")
                if (name.isNotEmpty()) list.add(name to infoHash)
            }
            engine.onLanBoards(list)
        } catch (_: Exception) {}
    }
}
