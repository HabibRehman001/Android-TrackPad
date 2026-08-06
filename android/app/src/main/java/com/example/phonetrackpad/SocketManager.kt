package com.example.phonetrackpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import android.util.Log

/**
 * Persistent TCP client to the desktop server via adb reverse (127.0.0.1:6000).
 */
class SocketManager(
    private val host: String = "127.0.0.1",
    private val port: Int = 6000,
) {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private val outbox = Channel<Packet>(capacity = Channel.UNLIMITED)
    private val frame = ByteArray(PACKET_SIZE)

    @Volatile
    var isConnected: Boolean = false
        private set

    fun start() {
        if (!job.isActive) {
            job = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + job)
        }
        scope.launch { connectionLoop() }
    }

    fun send(packet: Packet) {
        val result = outbox.trySend(packet)
        if (result.isFailure) {
            Log.w(TAG, "Dropped packet (channel closed/full): $packet")
        }
    }

    fun stop() {
        isConnected = false
        job.cancel()
    }

    private suspend fun connectionLoop() {
        while (scope.isActive) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
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
        while (scope.isActive) {
            val packet = outbox.receive()
            try {
                packet.writeTo(frame)
                out.write(frame)
                out.flush()
            } catch (e: Exception) {
                isConnected = false
                Log.w(TAG, "Write failed: ${e.message}")
                // Put packet back so it isn't lost on a brief blip
                outbox.trySend(packet)
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "SocketManager"
    }
}
