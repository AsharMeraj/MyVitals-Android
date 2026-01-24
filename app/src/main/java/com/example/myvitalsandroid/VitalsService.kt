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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jstyle.blesdk2208a.Util.BleSDK
import com.jstyle.blesdk2208a.Util.ResolveUtil
import okhttp3.OkHttpClient
import java.util.*
import java.util.concurrent.TimeUnit
import java.text.NumberFormat

class VitalsService : Service() {

    private val TAG = "VitalsService"

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var wristbandMac: String = ""
    private val wristbandServiceUUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val wristbandNotifyUUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private lateinit var bridgeHandler: KioskBridgeHandler
    private lateinit var gattCallback: BluetoothGattCallback
    private var ignoreDataUntil: Long = 0

    private var pendingSpo2: Int? = null
    private var pendingHr: String? = null
    private var pendingTemp: String? = null
    private var isManualDisconnect = false

    // Session tracking to distinguish between active measurement and background sync
    private var isSessionActive = false

    // Watchdog to detect when packets stop coming
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        transmitFinalVitals()
    }

    /**
     * Resets the 2.5-second timer. If this function isn't called for 2.5s,
     * we assume the sensor has finished.
     */
    private fun resetWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 2500)
    }

    /**
     * Transmits the final captured values once the stream has ended.
     */
    private fun transmitFinalVitals() {
        // Guard: Only process if we were actually expecting a measurement
        if (!isSessionActive) return

        val h = pendingHr ?: "0"
        val t = pendingTemp ?: "0.0"
        val s = pendingSpo2 ?: 0

        if (h != "0" && s > 0 && t != "0.0") {
            Log.i(TAG, "🏁 Sensor stream stopped. Transmitting final result: HR:$h, SpO2:$s, Temp:$t")

            // Mark session as finished BEFORE sending to avoid race conditions with trailing packets
            isSessionActive = false
            watchdogHandler.removeCallbacks(watchdogRunnable)

            // Notify UI that we are back to IDLE
            bridgeHandler.sendSignalToWeb("IDLE")

            // Send the final snapshot
            bridgeHandler.sendVitalsToWeb(s.toString(), h, t, System.currentTimeMillis().toString())

            // Clear buffers for the next session
            pendingHr = null
            pendingTemp = null
            pendingSpo2 = null
        } else {
            // Only log warning and reset if we were actually in a session
            Log.w(TAG, "⚠️ Stream stopped but no valid vitals were captured.")
            isSessionActive = false
            bridgeHandler.sendSignalToWeb("IDLE")
        }
    }

    fun updateWristbandMacAndScan(mac: String) {
        this.wristbandMac = mac
        Log.d(TAG, "🔎 Updating MAC to $mac and starting scan...")
        if (hasScanPermission() && hasConnectPermission()) {
            startScanSafe()
        } else {
            Log.e(TAG, "❌ Bluetooth permissions missing!")
        }
    }

    fun startManualVitals() {
        bluetoothGatt?.let { gatt ->
            Log.i(TAG, "⚡ Manual Trigger: Connection active, starting sensors...")
            bridgeHandler.sendSignalToWeb("Measuring")
            triggerRealTimeVitals(gatt)
        } ?: run {
            Log.e(TAG, "❌ Manual Trigger Failed: No active GATT connection.")
            bridgeHandler.sendSignalToWeb("Disconnected")
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerRealTimeVitals(gatt: BluetoothGatt) {
        // Mark session as active
        isSessionActive = true

        pendingSpo2 = null
        pendingHr = null
        pendingTemp = null

        ignoreDataUntil = System.currentTimeMillis() + 2000

        writeChar?.let { wChar ->
            Log.d(TAG, "📤 Step 1: Enabling Real-Time HR + Temperature...")
            wChar.value = BleSDK.RealTimeStep(true, true)
            gatt.writeCharacteristic(wChar)

            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "📤 Step 2: Triggering Device Measurement Type 3...")
                wChar.value = BleSDK.StartDeviceMeasurementWithType(3, true, 60)
                gatt.writeCharacteristic(wChar)
            }, 1200)

            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "📤 Step 3: Explicitly requesting Oxygen reading...")
                wChar.value = BleSDK.GetBloodOxygen(0x01.toByte(), "00000000")
                gatt.writeCharacteristic(wChar)
            }, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 SERVICE CREATED")
        bridgeHandler = KioskBridgeHandler(this, client)
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupGattCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")
        val macFromWeb = intent?.getStringExtra("wristbandId")

        when (action) {
            "DISCONNECT" -> {
                isManualDisconnect = true
                performManualDisconnect()
                stopSelf()
            }
            "START_MEASUREMENT" -> {
                startManualVitals()
            }
            else -> {
                if (!macFromWeb.isNullOrEmpty()) {
                    updateWristbandMacAndScan(macFromWeb)
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun setupGattCallback() {
        gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.close()
                    bluetoothGatt = null
                    if (!isManualDisconnect) {
                        Handler(Looper.getMainLooper()).postDelayed({ startScanSafe() }, 3000)
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "✅ GATT Connected.")
                    bridgeHandler.sendSignalToWeb("Connected")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "🔌 GATT Disconnected.")
                    gatt.close()
                    bluetoothGatt = null
                    resetVitalsAndNotifyWeb()
                    if (!isManualDisconnect) retryConnection(gatt.device)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val targetService = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID } ?: return

                writeChar = targetService.characteristics.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                }

                val notifyChar = targetService.characteristics.firstOrNull {
                    it.uuid == wristbandNotifyUUID && (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                }

                if (writeChar != null && notifyChar != null) {
                    gatt.setCharacteristicNotification(notifyChar, true)
                    notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(this)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val rawData = characteristic.value ?: return
                val data = if (rawData.size < 30) rawData.copyOf(30) else rawData
                val packetType = data[0].toInt() and 0xFF

                if (System.currentTimeMillis() < ignoreDataUntil) return

                // Only reset watchdog if we are currently in an active measurement session
                if (isSessionActive) {
                    resetWatchdog()
                }

                try {
                    when (packetType) {
                        0x09 -> handleActivityData(data)
                        0x28 -> handleMeasurementResponse(data)
                        0x60 -> handleSpo2Data(data)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Error parsing packet: ${e.message}")
                }
            }
        }
    }

    private fun handleMeasurementResponse(data: ByteArray) {
        val subType = data[1].toInt() and 0xFF
        if (subType == 3) {
            val hr = ResolveUtil.getValue(data[2], 0).toString()
            val spo2 = ResolveUtil.getValue(data[3], 0)
            val tempRaw = ResolveUtil.getValue(data[8], 0) + ResolveUtil.getValue(data[9], 1)
            val nf = NumberFormat.getNumberInstance()
            nf.maximumFractionDigits = 1
            val tempStr = nf.format(tempRaw * 0.1)
            updateVitalsBuffer(spo2, hr, tempStr)
        }
    }

    private fun handleActivityData(data: ByteArray) {
        val result = ResolveUtil.getActivityData(data)
        val dic = result["dicData"] as? Map<String, String> ?: return
        val hr = dic["heartRate"]
        val spo2 = dic["Blood_oxygen"]?.toIntOrNull()
        val temp = dic["TempData"]
        updateVitalsBuffer(spo2, hr, temp)
    }

    private fun handleSpo2Data(data: ByteArray) {
        val result = ResolveUtil.getBloodoxygen(data)
        val dicList = result["dicData"] as? List<Map<String, String>> ?: return
        if (dicList.isEmpty()) return
        val lastEntry = dicList.last()
        val spo2 = lastEntry["Blood_oxygen"]?.toIntOrNull()
        updateVitalsBuffer(spo2, null, null)
    }

    /**
     * Simply updates the internal buffer without sending data to the UI yet.
     */
    private fun updateVitalsBuffer(spo2: Int?, hr: String?, temp: String?) {
        if (spo2 != null && spo2 in 50..100) pendingSpo2 = spo2
        val hrInt = hr?.toIntOrNull() ?: 0
        if (hrInt in 30..220) pendingHr = hr
        if (!temp.isNullOrEmpty() && temp != "0.0" && temp != "0") pendingTemp = temp
    }

    private fun resetVitalsAndNotifyWeb() {
        isSessionActive = false
        pendingSpo2 = null
        pendingHr = null
        pendingTemp = null
        watchdogHandler.removeCallbacks(watchdogRunnable)
        bridgeHandler.sendVitalsToWeb("0", "0", "0", "Disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun startScanSafe() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        bluetoothLeScanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address.equals(wristbandMac, ignoreCase = true)) {
                bluetoothLeScanner?.stopScan(this)
                bluetoothGatt = result.device.connectGatt(this@VitalsService, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retryConnection(device: BluetoothDevice) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (bluetoothGatt == null) {
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun performManualDisconnect() {
        isSessionActive = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothGatt?.apply {
            disconnect()
            close()
        }
        bluetoothGatt = null
        bridgeHandler.sendSignalToWeb("Disconnected")
    }

    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
    private fun hasConnectPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("vitals_channel", "Vitals Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "vitals_channel")
        .setContentTitle("VitalSync Active")
        .setContentText("Monitoring health data...")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        performManualDisconnect()
        super.onDestroy()
    }
}