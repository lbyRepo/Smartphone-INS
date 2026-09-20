package com.example.phoneins.service

import com.example.phoneins.navigation.MasterInsReference
import com.example.phoneins.navigation.Quaternion
import com.example.phoneins.navigation.Vec3
import com.example.phoneins.protocol.MasterInsProtocol
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class MasterReferenceServerTest {
    @Test
    fun synchronizesValidatesRateAndReconnects() {
        val references = CountDownLatch(2)
        val server = MasterReferenceServer(
            port = 0,
            nowNanos = System::nanoTime,
            onReference = { _, _ -> references.countDown() },
            onStatus = {},
        )
        server.start()
        try {
            repeat(100) {
                if (server.listeningPort > 0) return@repeat
                Thread.sleep(10)
            }
            assertTrue(server.listeningPort > 0)
            sendBurst(server.listeningPort, 0)
            sendBurst(server.listeningPort, 100)
            assertTrue(references.await(2, TimeUnit.SECONDS), "Server did not resume validated references after reconnect")
        } finally {
            server.close()
        }
    }

    private fun sendBurst(port: Int, sequenceOffset: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            val request = readFrame(socket)
            val sync = MasterInsProtocol.decode(request) as MasterInsProtocol.Message.SyncRequest
            val receive = System.nanoTime()
            socket.getOutputStream().write(MasterInsProtocol.encodeSyncResponse(sync.sequence, sync.phoneSendNanos, receive, System.nanoTime()))
            repeat(32) { index ->
                val now = System.nanoTime()
                socket.getOutputStream().write(
                    MasterInsProtocol.encodeReference(
                        MasterInsReference(
                            (sequenceOffset + index).toLong(), now, 0L, 0L, MasterInsReference.REQUIRED_FLAGS,
                            1.35, 103.8, 20.0, Vec3(8.0, 0.0, 0.0), Quaternion.IDENTITY, Vec3.ZERO,
                            Vec3(0.1, 0.1, 0.15), Vec3(0.2, 0.2, 0.3), 1_000_000L,
                        ),
                    ),
                )
                socket.getOutputStream().flush()
                Thread.sleep(40)
            }
        }
    }

    private fun readFrame(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val header = input.readNBytes(MasterInsProtocol.HEADER_BYTES)
        val total = MasterInsProtocol.frameLength(header)
        return header + input.readNBytes(total - header.size)
    }
}
