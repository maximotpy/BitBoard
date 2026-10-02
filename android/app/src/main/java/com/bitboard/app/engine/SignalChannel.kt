package com.bitboard.app.engine

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet rendezvous for BitBoard (mirror of src/signal.js).
 *
 * Every device seeds its OWN torrent per board and its infohash differs on
 * every device, so trackers / DHT can never match two devices on different
 * networks. Infohashes used to travel only in the LAN beacon, which is why
 * sync worked on one Wi-Fi and never across the internet.
 *
 * This channel lets devices tell each other "for board X my current torrent is
 * <infohash>" through a tiny pub/sub relay (ntfy protocol, https://ntfy.sh by
 * default). One topic per board, derived from a hash of the board name. Only
 * infohashes are sent, never images, IPs or file names. Once a peer's infohash
 * is known, libtorrent finds the peer through trackers / DHT / PEX as usual.
 */
class SignalChannel(
    private val baseUrl: String = Protocol.SIGNAL_URL,
    private val log: (String) -> Unit = {}
) {
    private val base = baseUrl.trimEnd('/')

    class Subscription internal constructor() {
        @Volatile internal var closed = false
        @Volatile internal var conn: HttpURLConnection? = null
        @Volatile internal var thread: Thread? = null

        fun close() {
            closed = true
            try { conn?.disconnect() } catch (_: Exception) {}
            try { thread?.interrupt() } catch (_: Exception) {}
        }
    }

    /** Publish one JSON message to [topic]. BLOCKING: call from a background thread. */
    @Throws(IOException::class)
    fun publish(topic: String, payload: JSONObject) {
        val conn = URL("$base/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("signal relay answered HTTP $code")
            try { conn.inputStream.use { it.readBytes() } } catch (_: Exception) {}
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Subscribe to [topic] on a daemon thread. Reconnects forever with
     * back-off. [onMessage] receives each parsed message (the JSON we
     * published) on that thread.
     */
    fun subscribe(topic: String, onMessage: (JSONObject) -> Unit): Subscription {
        val sub = Subscription()
        val t = Thread({
            var backoff = 2_000L
            var lastId: String? = null
            while (!sub.closed) {
                var conn: HttpURLConnection? = null
                try {
                    val since = lastId ?: "30m"
                    val url = URL("$base/$topic/json?since=" + URLEncoder.encode(since, "UTF-8"))
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        // The relay sends a keepalive every ~45 s: two minutes of
                        // silence means a dead connection (phone changed network).
                        readTimeout = 120_000
                    }
                    sub.conn = conn
                    val code = conn.responseCode
                    if (code != 200) {
                        if (code == 400) lastId = null // unknown / expired id
                        throw IOException("signal relay answered HTTP $code")
                    }
                    backoff = 2_000L
                    log("relay connected (${topic.take(17)}…)")
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                        while (!sub.closed) {
                            val line = r.readLine() ?: break
                            if (line.isBlank()) continue
                            try {
                                val ev = JSONObject(line)
                                if (ev.optString("event") != "message") continue
                                ev.optString("id").takeIf { it.isNotEmpty() }?.let { lastId = it }
                                onMessage(JSONObject(ev.optString("message")))
                            } catch (_: Exception) {
                                // malformed line / message, or a handler error: keep the stream alive
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (sub.closed) break
                    log("signal relay: ${e.message}, retrying")
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
                if (sub.closed) break
                val until = System.currentTimeMillis() + backoff
                while (!sub.closed && System.currentTimeMillis() < until) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                }
                backoff = minOf(backoff * 2, 60_000L)
            }
        }, "bitboard-signal")
        t.isDaemon = true
        sub.thread = t
        t.start()
        return sub
    }
}
