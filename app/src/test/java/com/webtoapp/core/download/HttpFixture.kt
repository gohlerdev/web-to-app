package com.webtoapp.core.download

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/**
 * Minimal HTTP/1.1 server for download-engine tests, recording what the client
 * actually asked for.
 *
 * Real sockets and hand-written responses, because the contracts under test
 * (Range / If-Range / 206 / 416 / a connection dropped mid-body) live in the
 * interaction between the engine's temp-file state and real wire replies — a
 * mocked client would only replay the assumptions being questioned.
 */
internal class HttpFixture : AutoCloseable {

    data class Hit(val path: String, val range: String?, val ifRange: String?)

    private val socket = ServerSocket(0, 32, InetAddress.getLoopbackAddress())

    val hits: MutableList<Hit> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    var respond: (Hit, OutputStream) -> Unit = { _, _ -> }

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                client.use { connection ->
                    try {
                        val reader = connection.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        var range: String? = null
                        var ifRange: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val name = line.substringBefore(':').trim().lowercase()
                            val value = line.substringAfter(':').trim()
                            when (name) {
                                "range" -> range = value
                                "if-range" -> ifRange = value
                            }
                        }
                        val hit = Hit(requestLine.split(' ').getOrElse(1) { "/" }, range, ifRange)
                        hits.add(hit)
                        val out = connection.getOutputStream()
                        respond(hit, out)
                        out.flush()
                    } catch (_: Exception) {
                        // The engine hanging up mid-body is part of the fixture.
                    }
                }
            }
        }.apply { isDaemon = true; name = "wta-test-http"; start() }
    }

    fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

    override fun close() {
        runCatching { socket.close() }
    }
}

internal fun writeHead(out: OutputStream, status: String, headers: List<String>) {
    val head = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        headers.forEach { append(it).append("\r\n") }
        append("Connection: close\r\n\r\n")
    }
    out.write(head.toByteArray())
}

/**
 * Serves [body] as a 200. Writing fewer than [sendBytes] of the announced
 * length and closing is how a genuine interrupted transfer is produced.
 */
internal fun serveFull(
    out: OutputStream,
    body: ByteArray,
    lastModified: String?,
    sendBytes: Int = body.size,
) {
    val headers = mutableListOf(
        "Content-Type: application/octet-stream",
        "Content-Length: ${body.size}",
        "Accept-Ranges: bytes",
    )
    lastModified?.let { headers += "Last-Modified: $it" }
    writeHead(out, "200 OK", headers)
    out.write(body, 0, sendBytes)
}

internal fun servePartial(out: OutputStream, body: ByteArray, start: Int, lastModified: String?) {
    val headers = mutableListOf(
        "Content-Type: application/octet-stream",
        "Content-Length: ${body.size - start}",
        "Content-Range: bytes $start-${body.size - 1}/${body.size}",
        "Accept-Ranges: bytes",
    )
    lastModified?.let { headers += "Last-Modified: $it" }
    writeHead(out, "206 Partial Content", headers)
    out.write(body, start, body.size - start)
}

internal fun serveUnsatisfiable(out: OutputStream, size: Int) {
    writeHead(out, "416 Range Not Satisfiable", listOf("Content-Range: bytes */$size", "Content-Length: 0"))
}

/** Honours whatever Range the engine actually asked for. */
internal fun serveRange(out: OutputStream, body: ByteArray, hit: HttpFixture.Hit, lastModified: String?) {
    val start = hit.range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
    if (start <= 0) serveFull(out, body, lastModified) else servePartial(out, body, start, lastModified)
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
