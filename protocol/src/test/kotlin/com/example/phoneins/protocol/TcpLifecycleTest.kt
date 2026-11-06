package com.example.phoneins.protocol

import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TcpLifecycleTest {
    @Test fun idleConnectionCanShutDownAndRestart() {
        val server = TcpInsServer(0)
        repeat(3) {
            server.start()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (server.boundPort == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            Socket("127.0.0.1", server.boundPort).use {
                while (!server.connected && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(server.connected)
                server.close()
                assertFalse(server.connected)
            }
        }
        server.close()
    }
}
