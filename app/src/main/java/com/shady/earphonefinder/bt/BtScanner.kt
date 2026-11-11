package com.shady.earphonefinder.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** One sighting of a device. */
data class Sighting(
    val address: String,
    val name: String?,
    val rssi: Int,
    val tMs: Long,
    val source: Source,
) {
    enum class Source { BLE, CLASSIC }
}

/**
 * Listens for every Bluetooth device around and reports each sighting with its
 * signal strength.
 *
 * BLE: continuous low-latency scan, every advert reported (most earbuds advertise
 *      over BLE when the case is open or when they lose their phone).
 * Classic: optional discovery loop. Slow (a reading every ~10 s) but catches
 *      older buds that never advertise over BLE.
 *
 * Callers must hold the permissions first (see MainActivity).
 */
@SuppressLint("MissingPermission")
class BtScanner(
    private val context: Context,
    private val onSighting: (Sighting) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    var bleRunning = false
        private set
    var classicRunning = false
        private set

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Paired devices, address to name. Helps you spot your buds in the list. */
    fun bonded(): Map<String, String> = try {
        adapter?.bondedDevices?.associate { it.address to (it.name ?: "") } ?: emptyMap()
    } catch (e: SecurityException) {
        emptyMap()
    }

    // ---------- BLE ----------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: safeName(result.device)
            onSighting(
                Sighting(
                    address = result.device.address,
                    name = name,
                    rssi = result.rssi,
                    tMs = result.timestampNanos / 1_000_000,
                    source = Sighting.Source.BLE,
                )
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleRunning = false
            onError(
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "BLE scan already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        "Android refused the scan. Usually from restarting too often; wait 30 s"
                    else -> "BLE scan failed (code $errorCode)"
                }
            )
        }
    }

    fun startBle() {
        if (bleRunning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onError("Bluetooth is off"); return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(null, settings, bleCallback)
            bleRunning = true
        } catch (e: SecurityException) {
            onError("Missing Bluetooth permission")
        }
    }

    fun stopBle() {
        if (!bleRunning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        bleRunning = false
    }

    // ---------- Classic ----------

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = deviceFrom(intent) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (rssi == Short.MIN_VALUE) return
                    onSighting(
                        Sighting(
                            address = device.address,
                            name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: safeName(device),
                            rssi = rssi.toInt(),
                            tMs = SystemClock.elapsedRealtime(),
                            source = Sighting.Source.CLASSIC,
                        )
                    )
                }
                // Discovery stops itself after ~12 s. Keep it going while enabled.
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (classicRunning) {
                    try { adapter?.startDiscovery() } catch (_: SecurityException) {}
                }
            }
        }
    }

    fun startClassic() {
        if (classicRunning) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, classicReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        classicRunning = true
        try {
            if (adapter?.startDiscovery() != true) onError("Could not start classic discovery")
        } catch (e: SecurityException) {
            onError("Missing Bluetooth permission")
        }
    }

    fun stopClassic() {
        if (!classicRunning) return
        classicRunning = false
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
        try { context.unregisterReceiver(classicReceiver) } catch (_: IllegalArgumentException) {}
    }

    fun stopAll() {
        stopBle(); stopClassic()
    }

    // ---------- helpers ----------

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
