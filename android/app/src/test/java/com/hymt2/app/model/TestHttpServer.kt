package com.hymt2.app.model

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** Minimal single-purpose HTTP/1.1 server for downloader tests: GET only, optional `Range: bytes=N-`. */
class TestHttpServer(private val body: () -> ByteArray, private val honorRange: () -> Boolean = { true }) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())

    private val acceptor = thread(isDaemon = true) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                thread(isDaemon = true) { client.use { serve(it) } }
            } catch (_: SocketException) {
                return@thread
            }
        }
    }

    private fun serve(client: java.net.Socket) {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        ranges += range
        val all = body()
        val start = if (honorRange() && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val slice = all.copyOfRange(start, all.size)
        val status = if (start > 0) "206 Partial Content" else "200 OK"
        val head = "HTTP/1.1 $status\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(slice); flush() }
    }

    override fun close() = socket.close()
}
