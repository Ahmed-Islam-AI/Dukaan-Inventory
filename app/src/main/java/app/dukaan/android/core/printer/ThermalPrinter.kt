package app.dukaan.android.core.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PairedPrinter(val name: String, val address: String)

/** Why a print failed. Each has its own message and fallback (PLAN.md § Error handling). */
sealed class PrintError(message: String) : Exception(message) {
    class PermissionDenied : PrintError("BLUETOOTH_CONNECT not granted")
    class BluetoothOff : PrintError("Bluetooth is off")
    class NotPaired : PrintError("printer is not paired")
    class ConnectionFailed(cause: IOException) : PrintError("could not reach printer: ${cause.message}")
}

/**
 * Prints receipt bitmaps on a paired Bluetooth ESC/POS printer over the classic serial profile
 * (D-56). Lists only printers already paired in Android Settings, so no device scanning and no
 * location permission (PLAN.md M3: BLUETOOTH_CONNECT only, on Android 12+).
 */
class ThermalPrinter(private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired devices, printers first by name. Throws [PrintError] if Bluetooth can't be read. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun pairedDevices(): List<PairedPrinter> {
        if (!hasPermission()) throw PrintError.PermissionDenied()
        val bt = adapter ?: throw PrintError.BluetoothOff()
        if (!bt.isEnabled) throw PrintError.BluetoothOff()
        return bt.bondedDevices.orEmpty()
            .map { PairedPrinter(it.name ?: it.address, it.address) }
            .sortedWith(compareByDescending<PairedPrinter> { it.name.contains("print", ignoreCase = true) || it.name.contains("pos", ignoreCase = true) }.thenBy { it.name })
    }

    /** Sends [bitmap] (already rendered at the paper width) to the printer at [address]. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun print(address: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw PrintError.PermissionDenied()
        val bt = adapter ?: throw PrintError.BluetoothOff()
        if (!bt.isEnabled) throw PrintError.BluetoothOff()
        val device = bt.bondedDevices.orEmpty().firstOrNull { it.address == address } ?: throw PrintError.NotPaired()
        val bytes = encode(bitmap)
        var socket: BluetoothSocket? = null
        try {
            bt.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP)
            socket.connect()
            socket.outputStream.apply {
                // Small chunks: cheap printers drop data when their buffer overflows.
                bytes.asList().chunked(CHUNK).forEach { write(it.toByteArray()); flush(); Thread.sleep(CHUNK_PAUSE_MS) }
            }
            Thread.sleep(SETTLE_MS) // let the last band print before closing the link
        } catch (e: IOException) {
            throw PrintError.ConnectionFailed(e)
        } finally {
            try { socket?.close() } catch (_: IOException) { }
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return EscPos.raster(w, h) { x, y -> EscPos.isDark(pixels[y * w + x]) }
    }

    private companion object {
        /** Serial Port Profile, used by virtually all Bluetooth receipt printers. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CHUNK = 1024
        const val CHUNK_PAUSE_MS = 20L
        const val SETTLE_MS = 400L
    }
}
