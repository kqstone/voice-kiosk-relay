package com.voicekiosk.app.bridge

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * Lightweight, zero-dependency RFC 6455 WebSocket client for the relay bridge /ws endpoint.
 * Runs in its own thread and automatically handles handshake, ping/pong, and reconnects.
 */
class BridgeWebSocketClient(
    private val wsUri: URI,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "BridgeWebSocket"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_PAYLOAD_SIZE = 5 * 1024 * 1024 // 5MB
    }

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClose(code: Int, reason: String)
        fun onError(t: Throwable)
    }

    @Volatile
    private var isRunning = false

    private var workerThread: Thread? = null
    private var activeSocket: Socket? = null
    private val random = SecureRandom()

    @Synchronized
    fun connect() {
        if (isRunning) return
        isRunning = true
        workerThread = Thread({
            runLoop()
        }, "BridgeWebSocket-Worker").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun close() {
        isRunning = false
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
        workerThread?.interrupt()
        workerThread = null
    }

    private fun runLoop() {
        while (isRunning) {
            var socket: Socket? = null
            try {
                val isSecure = wsUri.scheme.equals("wss", ignoreCase = true)
                val host = wsUri.host ?: throw IllegalArgumentException("Missing host in WebSocket URI")
                val port = if (wsUri.port != -1) wsUri.port else if (isSecure) 443 else 80
                val path = if (wsUri.rawPath.isNullOrEmpty()) "/ws" else wsUri.rawPath

                socket = if (isSecure) {
                    SSLSocketFactory.getDefault().createSocket()
                } else {
                    Socket()
                }
                activeSocket = socket
                val s = socket ?: throw IOException("Failed to create socket")
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                // RFC 6455 Handshake
                val nonce = ByteArray(16)
                random.nextBytes(nonce)
                val secKey = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)

                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $secKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read handshake response
                val statusLine = readLine(input)
                if (statusLine == null || !statusLine.contains("101")) {
                    throw IOException("Handshake failed: $statusLine")
                }
                // Drain response headers until blank line
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }

                listener.onOpen()

                // Read WebSocket frames
                while (isRunning && !s.isClosed) {
                    val b0 = input.read()
                    if (b0 == -1) break
                    val b1 = input.read()
                    if (b1 == -1) break

                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var payloadLen = (b1 and 0x7F).toLong()

                    if (payloadLen == 126L) {
                        val b2 = input.read()
                        val b3 = input.read()
                        if (b2 == -1 || b3 == -1) break
                        payloadLen = (((b2 and 0xFF) shl 8) or (b3 and 0xFF)).toLong()
                    } else if (payloadLen == 127L) {
                        var len = 0L
                        for (i in 0 until 8) {
                            val b = input.read()
                            if (b == -1) break
                            len = (len shl 8) or (b.toLong() and 0xFF)
                        }
                        payloadLen = len
                    }

                    if (payloadLen > MAX_PAYLOAD_SIZE) {
                        throw IOException("Payload too large: $payloadLen")
                    }

                    val maskKey = if (masked) {
                        val key = ByteArray(4)
                        readFully(input, key)
                        key
                    } else null

                    val payload = ByteArray(payloadLen.toInt())
                    readFully(input, payload)

                    if (masked && maskKey != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        0x1 -> { // Text frame
                            val text = String(payload, Charsets.UTF_8)
                            listener.onMessage(text)
                        }
                        0x8 -> { // Close frame
                            sendClose(output)
                            break
                        }
                        0x9 -> { // Ping -> send Pong
                            sendPong(output, payload)
                        }
                        0xA -> { // Pong
                            // ignore
                        }
                    }
                }

                listener.onClose(1000, "Normal closure")
            } catch (e: Exception) {
                if (isRunning) {
                    listener.onError(e)
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                activeSocket = null
            }

            if (isRunning) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val curr = input.read()
            if (curr == -1) {
                return if (sb.isNotEmpty()) sb.toString() else null
            }
            if (curr == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(curr.toChar())
            prev = curr
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("Unexpected EOF reading WebSocket payload")
            offset += count
        }
    }

    private fun sendPong(output: OutputStream, payload: ByteArray) {
        try {
            synchronized(output) {
                val maskKey = ByteArray(4)
                random.nextBytes(maskKey)
                val len = payload.size.coerceAtMost(125)
                val frame = ByteArray(2 + 4 + len)
                frame[0] = 0x8A.toByte() // FIN + Pong
                frame[1] = (0x80 or len).toByte() // MASK + len
                System.arraycopy(maskKey, 0, frame, 2, 4)
                for (i in 0 until len) {
                    frame[6 + i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
                output.write(frame)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun sendClose(output: OutputStream) {
        try {
            synchronized(output) {
                val maskKey = ByteArray(4)
                random.nextBytes(maskKey)
                val frame = ByteArray(2 + 4)
                frame[0] = 0x88.toByte() // FIN + Close
                frame[1] = 0x80.toByte() // MASK + 0 len
                System.arraycopy(maskKey, 0, frame, 2, 4)
                output.write(frame)
                output.flush()
            }
        } catch (_: Exception) {}
    }
}
