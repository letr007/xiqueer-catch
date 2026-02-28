package com.example.xiquercatch.capture

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import com.example.xiquercatch.debug.AppLog

class ScheduleHttpProxyServer(
    private val protectSocket: (Socket) -> Boolean,
    private val forceIpv4Hosts: Set<String> = emptySet(),
    private val onCaptured: (host: String, path: String, requestBody: String, responseBody: String) -> Unit
) {
    private val tag = "ScheduleHttpProxy"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val ioPool: ExecutorService = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    fun start(port: Int = 0): Int {
        if (!running.compareAndSet(false, true)) return serverSocket?.localPort ?: -1
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = socket
        AppLog.i(tag, "Proxy listening at 127.0.0.1:${socket.localPort}")

        acceptThread = thread(start = true, name = "schedule-proxy-accept") {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                ioPool.execute {
                    client.use { handleClient(it) }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        AppLog.i(tag, "Proxy stopping")
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        ioPool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 10_000
        val request = runCatching { readHttpMessage(client.getInputStream(), isRequest = true) }.getOrElse {
            writeBadGateway(client.getOutputStream(), "请求读取失败")
            AppLog.w(tag, "Failed to read client request", it)
            return
        }
        if (request == null) return

        val startParts = request.startLine.split(" ")
        if (startParts.size < 3) {
            writeBadGateway(client.getOutputStream(), "请求行无效")
            return
        }
        val method = startParts[0]
        val uriPart = startParts[1]
        val version = startParts[2]

        if (method.equals("CONNECT", ignoreCase = true)) {
            AppLog.i(tag, "CONNECT tunnel: $uriPart")
            handleConnectTunnel(client, uriPart, version)
            return
        }

        val hostHeader = request.headerValue("host").orEmpty()
        val normalized = normalizeTarget(uriPart, hostHeader)
        if (normalized.host.isBlank()) {
            writeBadGateway(client.getOutputStream(), "Host 为空")
            AppLog.w(tag, "Host empty, startLine=${request.startLine}")
            return
        }
        AppLog.i(
            tag,
            "HTTP request $method ${normalized.host}:${normalized.port}${normalized.pathWithQuery}"
        )

        var remote: Socket? = null
        try {
            remote = connectUpstream(normalized.host, normalized.port)
            val connectedRemote = remote ?: throw SocketException("Upstream connect returned null socket")
            connectedRemote.soTimeout = 12_000

            val forwardHeaders = request.headers
                .filterNot {
                    it.name.equals("proxy-connection", ignoreCase = true) ||
                        it.name.equals("connection", ignoreCase = true)
                }
                .toMutableList()
            forwardHeaders.add(HttpHeader("Connection", "close"))

            val forwarded = HttpWireMessage(
                startLine = "$method ${normalized.pathWithQuery} $version",
                headers = forwardHeaders,
                body = request.body
            )
            val remoteOut = connectedRemote.getOutputStream()
            remoteOut.write(forwarded.toByteArray())
            remoteOut.flush()

            val response = readHttpMessage(connectedRemote.getInputStream(), isRequest = false)
            if (response != null) {
                val responseBytes = response.toByteArray()
                client.getOutputStream().write(responseBytes)
                client.getOutputStream().flush()
                val reqCookieLen = request.headerValue("cookie")?.length ?: 0
                val setCookieCount = response.headers.count {
                    it.name.equals("set-cookie", ignoreCase = true)
                }
                AppLog.i(
                    tag,
                    "HTTP response from ${normalized.host}:${normalized.port} bytes=${response.body.size} " +
                        "setCookie=$setCookieCount reqCookieLen=$reqCookieLen"
                )
                onCaptured(
                    normalized.host.lowercase(Locale.US),
                    normalized.pathWithQuery,
                    String(request.body, Charsets.UTF_8),
                    String(response.body, Charsets.UTF_8)
                )
            } else {
                writeBadGateway(client.getOutputStream(), "上游无响应")
            }
        } catch (_: SocketTimeoutException) {
            writeBadGateway(client.getOutputStream(), "上游超时")
            AppLog.w(tag, "Upstream timeout ${normalized.host}:${normalized.port}")
        } catch (_: Exception) {
            writeBadGateway(client.getOutputStream(), "上游请求失败")
            AppLog.w(tag, "Upstream request failed ${normalized.host}:${normalized.port}")
        } finally {
            runCatching { remote?.close() }
        }
    }

    private fun handleConnectTunnel(client: Socket, uriPart: String, version: String) {
        val host = uriPart.substringBefore(':').trim()
        val port = uriPart.substringAfter(':', "443").toIntOrNull() ?: 443
        if (host.isBlank()) {
            writeBadGateway(client.getOutputStream(), "CONNECT host 为空")
            AppLog.w(tag, "CONNECT host empty: $uriPart")
            return
        }

        var remote: Socket? = null
        try {
            remote = connectUpstream(host, port)
            val connectedRemote = remote ?: throw SocketException("CONNECT upstream socket is null")
            connectedRemote.soTimeout = 0
            client.soTimeout = 0

            val ok = "$version 200 Connection Established\r\n\r\n"
            client.getOutputStream().write(ok.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()

            val upstream = thread(start = true, isDaemon = true, name = "proxy-connect-upstream") {
                safePipe(client, connectedRemote)
            }
            val downstream = thread(start = true, isDaemon = true, name = "proxy-connect-downstream") {
                safePipe(connectedRemote, client)
            }
            upstream.join()
            downstream.join()
        } catch (_: Exception) {
            runCatching { writeBadGateway(client.getOutputStream(), "CONNECT 失败") }
            AppLog.w(tag, "CONNECT failed: $host:$port")
        } finally {
            runCatching { client.close() }
            runCatching { remote?.close() }
        }
    }

    private fun writeBadGateway(output: OutputStream, reason: String) {
        val body = "Bad Gateway: $reason"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val message = buildString {
            append("HTTP/1.1 502 Bad Gateway\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        runCatching {
            output.write(message.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun normalizeTarget(uriPart: String, hostHeader: String): NormalizedTarget {
        return runCatching {
            val uri = URI(uriPart)
            if (uri.scheme?.startsWith("http") == true && !uri.host.isNullOrBlank()) {
                val host = uri.host
                val port = if (uri.port > 0) {
                    uri.port
                } else if (uri.scheme.equals("https", ignoreCase = true)) {
                    443
                } else {
                    80
                }
                val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                return NormalizedTarget(host, port, path.ifBlank { "/" })
            }
            throw IllegalArgumentException("not absolute")
        }.getOrElse {
            val host = hostHeader.substringBefore(':').trim()
            val port = hostHeader.substringAfter(':', "80").toIntOrNull() ?: 80
            val path = uriPart.ifBlank { "/" }
            NormalizedTarget(host, port, path)
        }
    }

    private fun connectUpstream(host: String, port: Int): Socket {
        val normalizedHost = host.lowercase(Locale.US)
        val forceIpv4 = forceIpv4Hosts.contains(normalizedHost)
        val candidates = resolveAddresses(host, forceIpv4)
        if (candidates.isEmpty()) {
            throw SocketException(
                if (forceIpv4) "No IPv4 address for $host"
                else "No resolved address for $host"
            )
        }
        val candidateText = candidates.joinToString(",") { it.hostAddress.orEmpty() }
        AppLog.i(
            tag,
            "Upstream resolve $host:$port forceIpv4=$forceIpv4 -> $candidateText"
        )
        var lastError: Exception? = null
        candidates.forEach { addr ->
            val socket = Socket()
            runCatching {
                protectSocket(socket)
                socket.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
            }.onSuccess {
                AppLog.i(
                    tag,
                    "Connected upstream $host:$port -> ${addr.hostAddress.orEmpty()}:$port"
                )
                return socket
            }.onFailure {
                runCatching { socket.close() }
                lastError = it as? Exception ?: SocketException(it.message ?: "connect failed")
                AppLog.w(
                    tag,
                    "Connect candidate failed $host:$port -> ${addr.hostAddress.orEmpty()}"
                )
            }
        }
        throw lastError ?: SocketException("Connect failed: $host:$port")
    }

    private fun resolveAddresses(host: String, forceIpv4: Boolean): List<InetAddress> {
        val resolved = InetAddress.getAllByName(host).toList()
        if (forceIpv4) {
            return resolved.filterIsInstance<Inet4Address>()
        }
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        val ipv6 = resolved.filterNot { it is Inet4Address }
        return ipv4 + ipv6
    }

    private fun safePipe(source: Socket, target: Socket) {
        try {
            pipe(source, target)
        } catch (_: SocketException) {
            // Expected when either side closes CONNECT tunnel.
        } catch (_: InterruptedIOException) {
            // Expected during service stop/shutdown.
        } catch (_: EOFException) {
            // Expected end of stream.
        } catch (_: Exception) {
            // Do not crash process on background pipe thread.
            AppLog.w(tag, "Pipe unexpected error")
        }
    }

    private fun pipe(source: Socket, target: Socket) {
        val input = source.getInputStream()
        val output = target.getOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            output.flush()
        }
        runCatching { target.shutdownOutput() }
    }

    private fun readHttpMessage(input: InputStream, isRequest: Boolean): HttpWireMessage? {
        val headerBytes = readUntilHeaderEnd(input) ?: return null
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val startLine = lines.first()
        val headers = mutableListOf<HttpHeader>()
        lines.drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                headers.add(HttpHeader(key, value))
            }
        }
        val body = when {
            headers.any {
                it.name.equals("transfer-encoding", ignoreCase = true) &&
                    it.value.contains("chunked", ignoreCase = true)
            } ->
                readChunkedBody(input)

            else -> {
                val length = headers.firstOrNull {
                    it.name.equals("content-length", ignoreCase = true)
                }?.value?.toIntOrNull() ?: 0
                if (length > 0) readExactly(input, length) else ByteArray(0)
            }
        }
        return HttpWireMessage(startLine = startLine, headers = headers, body = body)
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() == 0) null else out.toByteArray()
            }
            out.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
            if (out.size() > MAX_HEADER_BYTES) throw IllegalStateException("Header too large")
        }
        return out.toByteArray()
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input).trim()
            val size = line.substringBefore(';').toInt(16)
            if (size == 0) {
                readLine(input)
                break
            }
            out.write(readExactly(input, size))
            readExactly(input, 2)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("EOF while reading line")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_HEADER_BYTES) throw IllegalStateException("Line too large")
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val buffer = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = input.read(buffer, offset, len - offset)
            if (read < 0) throw EOFException("Unexpected EOF")
            offset += read
        }
        return buffer
    }

    private data class NormalizedTarget(
        val host: String,
        val port: Int,
        val pathWithQuery: String
    )

    companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}

private data class HttpWireMessage(
    val startLine: String,
    val headers: List<HttpHeader>,
    val body: ByteArray
) {
    fun headerValue(name: String): String? {
        return headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    }

    fun toByteArray(): ByteArray {
        val hasChunked = headers.any {
            it.name.equals("transfer-encoding", ignoreCase = true) &&
                it.value.contains("chunked", ignoreCase = true)
        }
        val normalizedHeaders = headers
            .filterNot {
                it.name.equals("content-length", ignoreCase = true) ||
                    (hasChunked && it.name.equals("transfer-encoding", ignoreCase = true))
            }
            .toMutableList()
        normalizedHeaders.add(HttpHeader("Content-Length", body.size.toString()))

        val out = ByteArrayOutputStream()
        out.write("$startLine\r\n".toByteArray(Charsets.ISO_8859_1))
        normalizedHeaders.forEach { h ->
            out.write("${h.name}: ${h.value}\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }
}

private data class HttpHeader(
    val name: String,
    val value: String
)
