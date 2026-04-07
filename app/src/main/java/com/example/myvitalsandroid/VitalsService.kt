package com.example.myvitalsandroid

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jstyle.blesdk2208a.Util.BleSDK
import com.jstyle.blesdk2208a.Util.ResolveUtil
import java.util.*

class VitalsService : Service() {

    companion object {
        const val SAMPLES_REQUIRED        = 10
        const val STALE_FILTER_DURATION   = 1500L
        const val DEFAULT_SESSION_TIMEOUT = 60
    }

    private val TAG = "VitalsService"

    // ── BLE ──────────────────────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var wristbandMac = ""
    private val wristbandServiceUUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val wristbandNotifyUUID  = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")

    // ── Session state ─────────────────────────────────────────────────────────
    private var pendingSpo2: Int?    = null
    private var pendingHr:   String? = null
    private var pendingTemp: String? = null

    private var spo2Counter  = 0
    private var hrCounter    = 0
    private var tempCounter  = 0

    private var isSessionActive    = false
    private var isManualDisconnect = false
    private var ignoreDataUntil    = 0L

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 VitalsService Created")
        setupGattCallback()
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action     = intent?.getStringExtra("action")
        val macFromWeb = intent?.getStringExtra("wristbandId")
        Log.d(TAG, "📥 onStartCommand action=$action mac=$macFromWeb")

        when (action) {
            "DISCONNECT"        -> { isManualDisconnect = true; performManualDisconnect(); stopSelf() }
            "START_MEASUREMENT" -> startManualVitals()
            else                -> if (!macFromWeb.isNullOrEmpty()) updateWristbandMacAndScan(macFromWeb)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.i(TAG, "💀 VitalsService Destroyed")
        performManualDisconnect()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────
    fun updateWristbandMacAndScan(mac: String) {
        Log.i(TAG, "📍 New target MAC: $mac")
        this.wristbandMac = mac
        if (hasScanPermission() && hasConnectPermission()) {
            MainActivity.sendSignalToWeb("Searching")
            startScanSafe()
        } else {
            Log.e(TAG, "❌ Missing Bluetooth permissions")
        }
    }

    fun startManualVitals(timeoutSec: Int = DEFAULT_SESSION_TIMEOUT) {
        bluetoothGatt?.let { gatt ->
            Log.i(TAG, "⚡ Starting session (${timeoutSec}s timeout)")

            spo2Counter     = 0
            hrCounter       = 0
            tempCounter     = 0
            pendingHr       = null
            pendingTemp     = null
            pendingSpo2     = null
            ignoreDataUntil = System.currentTimeMillis() + STALE_FILTER_DURATION

            MainActivity.sendVitalsToWeb("0", "0", "0", "0")
            isSessionActive = true
            MainActivity.sendSignalToWeb("Measuring")

            sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
            val task = Runnable {
                Log.w(TAG, "⏰ Session timeout — forcing transmission")
                transmitFinalVitalsAndStop("TIMEOUT")
            }
            sessionTimeoutRunnable = task
            timeoutHandler.postDelayed(task, timeoutSec * 1000L)

            triggerRealTimeVitals(gatt)
        } ?: run {
            Log.e(TAG, "❌ Cannot start: device not connected")
            MainActivity.sendSignalToWeb("Disconnected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE — Scanning
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startScanSafe() {
        if (bluetoothGatt != null) {
            Log.w(TAG, "⚠️ Already connected, skipping scan")
            return
        }
        Log.i(TAG, "🔎 Scanning for $wristbandMac")
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        bluetoothLeScanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.address.equals(wristbandMac, ignoreCase = true)) {
                Log.i(TAG, "🎯 Found: ${device.name} [${device.address}]")
                bluetoothLeScanner?.stopScan(this)
                bluetoothLeScanner = null
                if (bluetoothGatt == null) {
                    bluetoothGatt = device.connectGatt(
                        this@VitalsService, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    Log.w(TAG, "⚠️ GATT already exists — ignoring duplicate scan result")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE — GATT callback
    // ─────────────────────────────────────────────────────────────────────────
    private lateinit var gattCallback: BluetoothGattCallback

    private fun setupGattCallback() {
        gattCallback = object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val addr = gatt.device.address

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "🔴 Connection failed [status=$status] for $addr")
                    gatt.close()
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    if (!isManualDisconnect) {
                        Handler(Looper.getMainLooper()).postDelayed({ startScanSafe() }, 3000)
                    }
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "🟢 Connected to $addr")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this@VitalsService, "Wristband Connected", Toast.LENGTH_SHORT).show()
                        }
                        MainActivity.sendSignalToWeb("Connected")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "🟡 Disconnected from $addr")
                        gatt.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        resetSessionAndNotify()
                        if (!isManualDisconnect) retryConnection(gatt.device)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "🔍 Services discovered [status=$status]")
                val service = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID } ?: run {
                    Log.e(TAG, "❌ Wristband service not found")
                    return
                }

                writeChar = service.characteristics.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                }
                Log.d(TAG, "🖋️ writeChar: ${if (writeChar != null) "FOUND" else "NOT FOUND"}")

                service.characteristics
                    .firstOrNull { it.uuid == wristbandNotifyUUID }
                    ?.let { notifyChar ->
                        Log.d(TAG, "🔔 Enabling notifications")
                        gatt.setCharacteristicNotification(notifyChar, true)
                        notifyChar.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )?.apply {
                            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(this)
                        }
                    }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val rawData = characteristic.value ?: return
                val data    = if (rawData.size < 30) rawData.copyOf(30) else rawData
                val type    = data[0].toInt() and 0xFF

                if (System.currentTimeMillis() < ignoreDataUntil) {
                    Log.v(TAG, "⚠️ Ignoring stale packet 0x${type.toString(16)}")
                    return
                }

                try {
                    when (type) {
                        0x09 -> handleBackgroundActivityPacket(data)
                        0x28 -> handleLiveMeasurementPacket(data)
                        else -> Log.v(TAG, "📦 Unknown packet 0x${type.toString(16)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Parsing error: ${e.message}")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 0x09 — Background activity packet from RealTimeStep.
     * Provides HR and Temp continuously while waiting for SpO2.
     * SpO2 is always 0 here — ignored.
     */
    private fun handleBackgroundActivityPacket(data: ByteArray) {
        val dic  = ResolveUtil.getActivityData(data)["dicData"] as? Map<String, String> ?: return
        val hr   = dic["heartRate"]
        val temp = dic["TempData"]
        Log.d(TAG, "📥 [0x09] HR=$hr Temp=$temp")

        if (!isSessionActive) return

        val hrInt   = hr?.toIntOrNull()      ?: 0
        val tempDbl = temp?.toDoubleOrNull() ?: 0.0

        if (hrInt   > 30)   pendingHr   = hr
        if (tempDbl > 32.0) pendingTemp = temp

        if (hrInt   > 40)       hrCounter++
        if (tempDbl > 34.0)     tempCounter++

        val h = pendingHr   ?: "0"
        val t = pendingTemp ?: "0.0"
        val s = pendingSpo2?.toString() ?: "0"

        Log.i(TAG, "📉 Live → HR:$h ($hrCounter) Temp:$t ($tempCounter) SpO2:$s% — waiting for SpO2...")
        MainActivity.sendVitalsToWeb(s, h, t, System.currentTimeMillis().toString())
    }

    /**
     * 0x28 — Live SpO2 measurement from StartDeviceMeasurementWithType(3).
     * HR and Temp keep updating via 0x09 until this fires with a valid SpO2.
     * The moment SpO2 is valid (70-100), we finalize immediately.
     */
    private fun handleLiveMeasurementPacket(data: ByteArray) {
        val subType = data[1].toInt() and 0xFF
        if (subType != 3) {
            Log.v(TAG, "📦 [0x28] subType=$subType — not SpO2, skipping")
            return
        }

        val hr      = ResolveUtil.getValue(data[2], 0)
        val spo2    = ResolveUtil.getValue(data[3], 0)
        val tempRaw = ResolveUtil.getValue(data[8], 0) + ResolveUtil.getValue(data[9], 1)
        val temp    = String.format("%.1f", tempRaw * 0.1)

        Log.i(TAG, "✅ [0x28 type=3] LIVE → HR=$hr SpO2=$spo2% Temp=$temp°C")

        if (!isSessionActive) return

        // Finalize the moment first valid SpO2 arrives
        if (spo2 in 70..100) {
            pendingSpo2 = spo2
            // Also grab the latest HR/Temp from this packet as a final update
            if (hr > 30)                         pendingHr   = hr.toString()
            if ((temp.toDoubleOrNull() ?: 0.0) > 32.0) pendingTemp = temp
            Log.i(TAG, "🩸 Valid SpO2=$spo2% received — finalizing session now")
            transmitFinalVitalsAndStop("SPO2_RECEIVED")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session control
    // ─────────────────────────────────────────────────────────────────────────
    private fun transmitFinalVitalsAndStop(reason: String = "SPO2_RECEIVED") {
        if (!isSessionActive) return
        Log.i(TAG, "🏁 Session ending — $reason")

        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        sessionTimeoutRunnable = null
        isSessionActive = false

        val h = pendingHr   ?: "0"
        val t = pendingTemp ?: "0.0"
        val s = pendingSpo2 ?: 0

        Log.i(TAG, "📊 Final → HR:$h SpO2:$s% Temp:$t°C")

        stopDeviceSensors()
        MainActivity.sendVitalsToWeb(s.toString(), h, t, System.currentTimeMillis().toString())
        MainActivity.sendSignalToWeb("IDLE")

        spo2Counter = 0
        hrCounter   = 0
        tempCounter = 0
    }

    @SuppressLint("MissingPermission")
    private fun triggerRealTimeVitals(gatt: BluetoothGatt) {
        writeChar?.let { wChar ->
            // Step 1: RealTimeStep — streams HR + Temp via 0x09 every ~1s
            Log.d(TAG, "📤 Step 1: RealTimeStep ON (HR + Temp)")
            wChar.value = BleSDK.RealTimeStep(true, true)
            gatt.writeCharacteristic(wChar)

            // Step 2: StartDeviceMeasurementWithType(3) — triggers live SpO2 sensor
            // Re-triggered every 25s so the 30s window never expires before SpO2 arrives
            scheduleSpO2Trigger(gatt, wChar, delayMs = 3000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleSpO2Trigger(
        gatt: BluetoothGatt,
        wChar: BluetoothGattCharacteristic,
        delayMs: Long
    ) {
        timeoutHandler.postDelayed({
            if (!isSessionActive) return@postDelayed
            Log.d(TAG, "📤 StartDeviceMeasurementWithType(3) — live SpO2")
            wChar.value = BleSDK.StartDeviceMeasurementWithType(3, true, 30)
            gatt.writeCharacteristic(wChar)
            // Re-schedule before the 30s window expires
            scheduleSpO2Trigger(gatt, wChar, delayMs = 25_000)
        }, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopDeviceSensors() {
        val gatt = bluetoothGatt ?: return
        writeChar?.let { wChar ->
            Log.i(TAG, "📤 Sending STOP to sensors")
            wChar.value = BleSDK.StartDeviceMeasurementWithType(3, false, 30)
            gatt.writeCharacteristic(wChar)
            Handler(Looper.getMainLooper()).postDelayed({
                wChar.value = BleSDK.RealTimeStep(false, false)
                gatt.writeCharacteristic(wChar)
            }, 500)
        }
    }

    private fun resetSessionAndNotify() {
        Log.d(TAG, "🧹 Resetting session")
        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        isSessionActive = false
        spo2Counter     = 0
        hrCounter       = 0
        tempCounter     = 0
        pendingHr       = null
        pendingTemp     = null
        pendingSpo2     = null
        MainActivity.sendSignalToWeb("Disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun retryConnection(device: BluetoothDevice) {
        Log.i(TAG, "🔄 Retrying connection to ${device.address}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (bluetoothGatt == null) {
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun performManualDisconnect() {
        Log.i(TAG, "🔌 Manual disconnect")
        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        isSessionActive = false
        spo2Counter     = 0
        hrCounter       = 0
        tempCounter     = 0
        pendingHr       = null
        pendingTemp     = null
        pendingSpo2     = null
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothLeScanner = null
        bluetoothGatt?.apply { disconnect(); close() }
        bluetoothGatt = null
        MainActivity.sendSignalToWeb("Disconnected")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@VitalsService, "Wristband Disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasScanPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    private fun hasConnectPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vitals_channel", "Vitals Sync", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, "vitals_channel")
            .setContentTitle("VitalSync Pro Active")
            .setContentText("Monitoring health data...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
}
