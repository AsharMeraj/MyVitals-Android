
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
import okhttp3.OkHttpClient
import java.util.*
import java.util.concurrent.TimeUnit

class VitalsService : Service() {

    companion object {
        const val SAMPLES_REQUIRED = 10
        const val STALE_FILTER_DURATION = 1500L
        const val DEFAULT_SESSION_TIMEOUT = 60
    }

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

    private var pendingSpo2: Int? = null
    private var pendingHr: String? = null
    private var pendingTemp: String? = null

    private var spo2Counter = 0
    private var hrCounter = 0
    private var tempCounter = 0

    private var isSessionActive = false
    private var isManualDisconnect = false
    private var ignoreDataUntil: Long = 0

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var sessionTimeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    private fun stopDeviceSensors() {
        val gatt = bluetoothGatt ?: return
        writeChar?.let { wChar ->
            Log.i(TAG, "📤 Sending STOP commands to sensors...")

            // Stop real-time data stream
            wChar.value = BleSDK.RealTimeStep(false, false)
            gatt.writeCharacteristic(wChar)

            // Close measurement UI on watch screen
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "📤 Closing watch measurement UI...")
                wChar.value = BleSDK.StartDeviceMeasurementWithType(3, false, 30)
                gatt.writeCharacteristic(wChar)
            }, 500)
        }
    }

    private fun transmitFinalVitalsAndStop(reason: String = "STABILIZED") {
        if (!isSessionActive) return
        Log.i(TAG, "🏁 Session Ending. Reason: $reason")

        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        sessionTimeoutRunnable = null
        isSessionActive = false

        val h = pendingHr ?: "0"
        val t = pendingTemp ?: "0.0"
        val s = pendingSpo2 ?: 0

        Log.i(TAG, "📊 FINAL DATA -> HR: $h, SpO2: $s%, Temp: $t°C")

        stopDeviceSensors()
        bridgeHandler.sendVitalsToWeb(s.toString(), h, t, System.currentTimeMillis().toString())
        bridgeHandler.sendSignalToWeb("IDLE")

        spo2Counter = 0
        hrCounter = 0
        tempCounter = 0
    }

    fun updateWristbandMacAndScan(mac: String) {
        Log.i(TAG, "📍 New Target MAC: $mac. Preparing scan...")
        this.wristbandMac = mac
        if (hasScanPermission() && hasConnectPermission()) {
            startScanSafe()
        } else {
            Log.e(TAG, "❌ Missing Bluetooth permissions for scanning.")
        }
    }

    fun startManualVitals(timeoutSec: Int = DEFAULT_SESSION_TIMEOUT) {
        bluetoothGatt?.let { gatt ->
            Log.i(TAG, "⚡ Starting Measurement Session ($timeoutSec seconds timeout)")

            spo2Counter = 0
            hrCounter = 0
            tempCounter = 0
            pendingHr = null
            pendingTemp = null
            pendingSpo2 = null
            ignoreDataUntil = System.currentTimeMillis() + STALE_FILTER_DURATION

            bridgeHandler.sendVitalsToWeb("0", "0", "0", "0")
            isSessionActive = true
            bridgeHandler.sendSignalToWeb("Measuring")

            sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
            val timeoutTask = Runnable {
                Log.w(TAG, "⏰ Session timeout reached. Forcing transmission.")
                transmitFinalVitalsAndStop("TIMEOUT")
            }
            sessionTimeoutRunnable = timeoutTask
            timeoutHandler.postDelayed(timeoutTask, timeoutSec * 1000L)

            triggerRealTimeVitals(gatt)
        } ?: run {
            Log.e(TAG, "❌ Cannot start measurement: Device not connected.")
            bridgeHandler.sendSignalToWeb("Disconnected")
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerRealTimeVitals(gatt: BluetoothGatt) {
        writeChar?.let { wChar ->
            Log.d(TAG, "📤 Step 1: Enabling RealTimeStep data stream...")
            wChar.value = BleSDK.RealTimeStep(true, true)
            gatt.writeCharacteristic(wChar)

            Handler(Looper.getMainLooper()).postDelayed({
                if (isSessionActive) {
                    Log.d(TAG, "📤 Step 2: Explicitly requesting Blood Oxygen...")
                    wChar.value = BleSDK.GetBloodOxygen(0x01.toByte(), "00000000")
                    gatt.writeCharacteristic(wChar)
                }
            }, 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 VitalsService Created")
        bridgeHandler = KioskBridgeHandler(this, client)
        setupGattCallback()
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")
        val macFromWeb = intent?.getStringExtra("wristbandId")
        Log.d(TAG, "📥 onStartCommand Action: $action | MAC: $macFromWeb")

        when (action) {
            "DISCONNECT" -> {
                isManualDisconnect = true
                performManualDisconnect()
                stopSelf()
            }
            "START_MEASUREMENT" -> startManualVitals()
            else -> if (!macFromWeb.isNullOrEmpty()) updateWristbandMacAndScan(macFromWeb)
        }
        return START_STICKY
    }

    private fun setupGattCallback() {
        gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val deviceAddr = gatt.device.address
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "🔴 Connection Failed [Status: $status] for $deviceAddr. Closing GATT.")
                    gatt.close()
                    bluetoothGatt = null
                    if (!isManualDisconnect) {
                        Log.i(TAG, "🔄 Retrying scan in 3 seconds...")
                        Handler(Looper.getMainLooper()).postDelayed({ startScanSafe() }, 3000)
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "🟢 Connected to $deviceAddr. Discovering services...")
                    bridgeHandler.sendSignalToWeb("Connected")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "🟡 Disconnected from $deviceAddr.")
                    gatt.close()
                    bluetoothGatt = null
                    resetVitalsAndNotifyWeb()
                    if (!isManualDisconnect) {
                        Log.i(TAG, "🔄 Attempting reconnection to $deviceAddr...")
                        retryConnection(gatt.device)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "🔍 Services Discovered [Status: $status]")
                val targetService = gatt.services.firstOrNull { it.uuid == wristbandServiceUUID }
                if (targetService == null) {
                    Log.e(TAG, "❌ Wristband Service NOT FOUND on this device.")
                    return
                }

                writeChar = targetService.characteristics.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                }
                Log.d(TAG, "🖋️ Write Characteristic: ${if (writeChar != null) "FOUND" else "NOT FOUND"}")

                val notifyChar = targetService.characteristics.firstOrNull { it.uuid == wristbandNotifyUUID }
                notifyChar?.let {
                    Log.d(TAG, "🔔 Enabling notifications for Vitals Data...")
                    gatt.setCharacteristicNotification(it, true)
                    it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.apply {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(this)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val rawData = characteristic.value ?: return
                val data = if (rawData.size < 30) rawData.copyOf(30) else rawData
                val packetType = data[0].toInt() and 0xFF

                if (System.currentTimeMillis() < ignoreDataUntil) {
                    Log.v(TAG, "⚠️ Ignoring stale packet 0x${Integer.toHexString(packetType)}")
                    return
                }

                try {
                    when (packetType) {
                        0x28 -> {
                            Log.v(TAG, "📦 Received Combined Packet (0x28)")
                            handleCombinedPacket(data)
                        }
                        0x60 -> {
                            Log.v(TAG, "📦 Received Dedicated SpO2 Packet (0x60)")
                            handleDedicatedSpo2Packet(data)
                        }
                        0x09 -> {
                            Log.v(TAG, "📦 Received Background Activity Packet (0x09)")
                            handleBackgroundActivityPacket(data)
                        }
                        else -> Log.v(TAG, "📦 Received Unknown Packet Type: 0x${Integer.toHexString(packetType)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Parsing Error: ${e.message}")
                }
            }
        }
    }

    private fun handleCombinedPacket(data: ByteArray) {
        if ((data[1].toInt() and 0xFF) == 3) {
            val hrVal = ResolveUtil.getValue(data[2], 0)
            val spo2Val = ResolveUtil.getValue(data[3], 0)
            val tempRaw = ResolveUtil.getValue(data[8], 0) + ResolveUtil.getValue(data[9], 1)
            val tempStr = String.format("%.1f", tempRaw * 0.1)
            Log.d(TAG, "📥 [0x28] HR: $hrVal, SpO2: $spo2Val, Temp: $tempStr")
            processVitalsUpdate(spo2Val, hrVal.toString(), tempStr)
        }
    }

    private fun handleDedicatedSpo2Packet(data: ByteArray) {
        val res = ResolveUtil.getBloodoxygen(data)
        val dicList = res["dicData"] as? List<Map<String, String>> ?: return
        if (dicList.isNotEmpty()) {
            val entry = dicList.last()
            val oxygen = entry["Blood_oxygen"]?.toIntOrNull()
            val hr = entry["heartRate"]
            Log.d(TAG, "📥 [0x60] HR: $hr, SpO2: $oxygen")
            processVitalsUpdate(oxygen, hr, null)
        }
    }

    private fun handleBackgroundActivityPacket(data: ByteArray) {
        val dic = ResolveUtil.getActivityData(data)["dicData"] as? Map<String, String> ?: return
        val oxygen = dic["Blood_oxygen"]?.toIntOrNull()
        val hr = dic["heartRate"]
        val temp = dic["TempData"]
        Log.d(TAG, "📥 [0x09] HR: $hr, SpO2: $oxygen, Temp: $temp")
        processVitalsUpdate(oxygen, hr, temp)
    }

    private fun processVitalsUpdate(spo2: Int?, hr: String?, temp: String?) {
        val hrInt = hr?.toIntOrNull() ?: 0
        if (hrInt > 30) pendingHr = hr

        val tempDbl = temp?.toDoubleOrNull() ?: 0.0
        if (tempDbl > 32.0) pendingTemp = temp

        val oxygen = spo2 ?: 0
        if (oxygen in 70..100) pendingSpo2 = oxygen

        if (isSessionActive) {
            if (hrInt > 40) hrCounter++
            if (oxygen in 71..100) spo2Counter++
            if (tempDbl > 34.0) tempCounter++

            val h = pendingHr ?: "0"
            val t = pendingTemp ?: "0.0"
            val s = pendingSpo2?.toString() ?: "0"

            // Log real-time status and stability counters
            Log.i(TAG, "📉 Live -> HR:$h ($hrCounter), SpO2:$s% ($spo2Counter), Temp:$t°C ($tempCounter) | Target: $SAMPLES_REQUIRED")

            bridgeHandler.sendVitalsToWeb(s, h, t, System.currentTimeMillis().toString())

            if (hrCounter >= SAMPLES_REQUIRED && spo2Counter >= SAMPLES_REQUIRED && tempCounter >= SAMPLES_REQUIRED) {
                transmitFinalVitalsAndStop("STABILIZED")
            }
        }
    }

    private fun resetVitalsAndNotifyWeb() {
        Log.d(TAG, "🧹 Resetting session counters and notifying web.")
        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        isSessionActive = false
        spo2Counter = 0
        hrCounter = 0
        tempCounter = 0
        bridgeHandler.sendSignalToWeb("Disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun startScanSafe() {
        Log.i(TAG, "🔎 Starting BLE Scan for Mac: $wristbandMac")
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        bluetoothLeScanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.address.equals(wristbandMac, ignoreCase = true)) {
                Log.i(TAG, "🎯 Found target device: ${device.name} [${device.address}]")
                bluetoothLeScanner?.stopScan(this)
                bluetoothGatt = device.connectGatt(this@VitalsService, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retryConnection(device: BluetoothDevice) {
        Log.i(TAG, "🔄 Retrying connection to ${device.address}...")
        Handler(Looper.getMainLooper()).postDelayed({
            if (bluetoothGatt == null) {
                Log.d(TAG, "🔌 Initiating reconnect connectGatt...")
                bluetoothGatt = device.connectGatt(this, false, gattCallback)
            }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun performManualDisconnect() {
        Log.i(TAG, "🔌 Manual Disconnect Requested.")
        sessionTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        isSessionActive = false
        spo2Counter = 0
        hrCounter = 0
        tempCounter = 0
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothGatt?.apply {
            Log.d(TAG, "🔌 Closing GATT Connection...")
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
            val channel = NotificationChannel("vitals_channel", "Vitals Sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "vitals_channel")
        .setContentTitle("VitalSync Pro Active").setContentText("Monitoring health data...").setSmallIcon(android.R.drawable.stat_notify_sync).build()

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.i(TAG, "💀 VitalsService Destroyed")
        performManualDisconnect()
        super.onDestroy()
    }
}
