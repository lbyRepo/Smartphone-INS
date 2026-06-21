package com.example.phoneins.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface FrameTransport {
    val connected: Boolean
    val dropped: Long
    fun start()
    fun offer(frame: ByteArray)
    fun close()
}

class AoaFrameTransport(private val context: Context) : FrameTransport {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val queue = ArrayBlockingQueue<ByteArray>(512)
    private val running = AtomicBoolean(false)
    private val permissionRequested = AtomicBoolean(false)
    private val dropCounter = AtomicLong(0)
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var stream: FileOutputStream? = null
    private val permissionIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> permissionRequested.set(false)
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    permissionRequested.set(false)
                    closeDescriptor()
                }
            }
        }
    }
    override val connected get() = stream != null
    override val dropped get() = dropCounter.get()

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        context.registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        Thread({
            while (running.get()) {
                if (stream == null) {
                    val accessory = usbManager.accessoryList?.firstOrNull()
                    if (accessory != null) {
                        if (usbManager.hasPermission(accessory)) {
                            permissionRequested.set(false)
                            descriptor = usbManager.openAccessory(accessory)
                            stream = descriptor?.fileDescriptor?.let(::FileOutputStream)
                        } else if (permissionRequested.compareAndSet(false, true)) {
                            usbManager.requestPermission(accessory, permissionIntent)
                        }
                    }
                    if (stream == null) Thread.sleep(1_000)
                } else {
                    runCatching { stream?.write(queue.take()); stream?.flush() }.onFailure {
                        closeDescriptor()
                    }
                }
            }
        }, "ins-aoa-output").apply { isDaemon = true; start() }
    }

    override fun offer(frame: ByteArray) {
        if (connected && !queue.offer(frame.copyOf())) dropCounter.incrementAndGet()
    }

    override fun close() {
        running.set(false)
        closeDescriptor()
        queue.clear()
        runCatching { context.unregisterReceiver(usbReceiver) }
    }

    @Synchronized
    private fun closeDescriptor() {
        runCatching { stream?.close() }
        runCatching { descriptor?.close() }
        stream = null
        descriptor = null
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.example.phoneins.USB_PERMISSION"
    }
}
