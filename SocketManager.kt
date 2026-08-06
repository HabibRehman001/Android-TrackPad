package com.example.phonetrackpad

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Owns the TCP connection to the desktop server.
 *
 * Two latency-critical details here vs. a naive socket:
 * 1. tcpNoDelay = true - disables Nagle's algorithm. Without this, the
 *    kernel can hold small writes (like a 5-byte move packet) for up to
 *    ~40ms hoping to bundle them with more data. For input events, that
 *    delay is pure lag with zero upside.
 * 2. A single reused 5-byte buffer for every packet - no per-event
 *    allocation, which matters when this can fire ~240 times/sec.
 *
 * Still using adb reverse tcp:PORT tcp:PORT, so we always talk to
 * 127.0.0.1 - adb tunnels it over USB to the same port on the PC.
 */
class SocketManager(
    private val host: String = "127.0.0.1",
    private val port: Int = 6000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outbox = Channel<Packet>(capacity = Channel.UNLIMITED)
    private val frame = ByteArray(PACKET_SIZE)

    @Volatile
    var isConnected: Boolean = false
        private set

    fun start() {
        scope.launch { connectionLoop() }
    }

    fun send(packet: Packet) {
        // Non-blocking: the input thread never waits on network I/O.
        outbox.trySend(packet)
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun connectionLoop() {
        while (coroutineContext.isActive) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true // disable Nagle before connecting
                    socket.connect(InetSocketAddress(host, port), 3000)
                    isConnected = true
                    Log.i(TAG, "Connected to $host:$port (TCP_NODELAY on)")
                    writeLoop(socket.getOutputStream())
                }
            } catch (e: Exception) {
                isConnected = false
                Log.w(TAG, "Connection failed: ${e.message}, retrying in 2s")
                delay(2000)
            }
        }
    }

    private suspend fun writeLoop(out: OutputStream) {
        for (packet in outbox) {
            try {
                packet.writeTo(frame)
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                isConnected = false
                Log.w(TAG, "Write failed: ${e.message}")
                throw e // bubble up so connectionLoop reconnects
            }
        }
    }

    companion object {
        private const val TAG = "SocketManager"
    }
}
