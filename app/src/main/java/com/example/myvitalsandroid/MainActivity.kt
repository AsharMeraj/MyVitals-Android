package com.example.myvitalsandroid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private val PERMISSION_REQ = 2001
    private var currentStatus by mutableStateOf("Disconnected")

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.FOREGROUND_SERVICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // ─────────────────────────────────────────────────────────────────────────
    // COMPANION: Static WebView reference + outbound bridge methods
    // Merged from KioskBridgeHandler
    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "MainActivity"

        @SuppressLint("StaticFieldLeak")
        var activeWebView: WebView? = null

        /**
         * Sends real-time vitals data to the WebView.
         * Called by VitalsService.
         */
        fun sendVitalsToWeb(spo2: String, heartRate: String, temperature: String, timestamp: String) {
            try {
                val jsonString = JSONObject()
                    .put("spo2", spo2)
                    .put("heartRate", heartRate)
                    .put("temperature", temperature)
                    .put("timestamp", timestamp)
                    .toString()

                // receiveVitals expects a plain JSON object, NOT a quoted string
                val jsCode = "if(window.receiveVitals) { window.receiveVitals($jsonString); }"

                Handler(Looper.getMainLooper()).post {
                    if (activeWebView != null) {
                        activeWebView?.evaluateJavascript(jsCode, null)
                        Log.d(TAG, "✅ Vitals sent to WebView: $jsonString")
                    } else {
                        Log.e(TAG, "❌ sendVitalsToWeb: activeWebView is NULL")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendVitalsToWeb error: ${e.message}")
            }
        }

        /**
         * Sends a status signal (Connected, Disconnected, Measuring, IDLE, Searching)
         * to the WebView. Called by VitalsService.
         *
         * FIX: Uses evaluateJavascript (no "javascript:" prefix) and passes
         * the JSON object directly — no extra string-wrapping.
         */
        fun sendSignalToWeb(status: String) {
            try {
                val jsonString = JSONObject()
                    .put("type", "SIGNAL")
                    .put("status", status)
                    .toString()

                // evaluateJavascript does NOT use the "javascript:" prefix.
                // onVitalsReceived expects a plain JSON object, not a quoted string.
                val jsCode = "if(window.onVitalsReceived) { window.onVitalsReceived($jsonString); }"

                Handler(Looper.getMainLooper()).post {
                    if (activeWebView != null) {
                        activeWebView?.evaluateJavascript(jsCode, null)
                        Log.d(TAG, "✅ Signal sent to WebView: $status")
                    } else {
                        Log.e(TAG, "❌ sendSignalToWeb: activeWebView is NULL — signal '$status' dropped")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendSignalToWeb error: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JavaScript Bridge (JS → Android, inbound calls)
    // ─────────────────────────────────────────────────────────────────────────
    inner class WebAppInterface(private val context: Context) {

        // Native beep support
        private var isSoundReady = false
        private val soundPool: SoundPool = run {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        }
        private val soundId: Int

        init {
            soundPool.setOnLoadCompleteListener { _, _, status ->
                isSoundReady = (status == 0)
                Log.d(TAG, "SoundPool ready: $isSoundReady")
            }
            soundId = soundPool.load(context, R.raw.beep4, 1)
        }

        @JavascriptInterface
        fun playNativeBeep() {
            Log.d(TAG, "🔊 Beep triggered, ready=$isSoundReady")
            if (isSoundReady) {
                soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isSoundReady) soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                }, 300)
            }
        }

        /**
         * Called by JS when the user submits the wristband MAC address.
         * Starts VitalsService (foreground-safe) then passes the MAC for scanning.
         */
        @JavascriptInterface
        fun onKioskSelected(wristbandId: String) {
            Log.d(TAG, "Bridge: onKioskSelected MAC=$wristbandId")

            // Step 1: Ensure the service is running (foreground-safe)
            val startIntent = Intent(context, VitalsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            // Step 2: After service has time to initialise, send the MAC
            Handler(Looper.getMainLooper()).postDelayed({
                val scanIntent = Intent(context, VitalsService::class.java)
                scanIntent.putExtra("wristbandId", wristbandId)
                context.startService(scanIntent)
            }, 500)
        }

        @JavascriptInterface
        fun disconnectWristband() {
            Log.d(TAG, "Bridge: disconnectWristband")
            val intent = Intent(context, VitalsService::class.java)
            intent.putExtra("action", "DISCONNECT")
            context.startService(intent)
        }

        @JavascriptInterface
        fun triggerMeasurement() {
            Log.d(TAG, "Bridge: triggerMeasurement")
            val intent = Intent(context, VitalsService::class.java)
            intent.putExtra("action", "START_MEASUREMENT")
            context.startService(intent)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast Receivers
    // ─────────────────────────────────────────────────────────────────────────
    private val vitalsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonData = intent?.getStringExtra("data") ?: return
            activeWebView?.evaluateJavascript(
                "if(window.receiveVitals) { window.receiveVitals($jsonData); }",
                null
            )
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            currentStatus = status
            Toast.makeText(this@MainActivity, "Status: $status", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        if (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        enableEdgeToEdge()

        setContent {
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    window.statusBarColor = android.graphics.Color.parseColor("#0088d6")
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                }
            }
            MyVitalsAppWebView(
                url = "https://child-life-app.vercel.app/",
                status = currentStatus
            )
        }

        // VitalsService is NOT started here.
        // It starts only after Bluetooth + Location are confirmed (see below).
        if (hasAllPermissions()) checkBluetoothAndLocation()
        else requestAllPermissions()
    }

    private fun hasAllPermissions() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestAllPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            checkBluetoothAndLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkBluetoothAndLocation() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { false }

        if (!locEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // ✅ Everything confirmed — safe to start the service now
        startVitalsService()
    }

    private fun startVitalsService() {
        Log.i(TAG, "Starting VitalsService")
        val intent = Intent(this, VitalsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    override fun onResume() {
        super.onResume()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_EXPORTED else 0
        registerReceiver(connectionReceiver,
            IntentFilter("com.example.myvitalsandroid.CONNECTION_STATUS"), flags)
        registerReceiver(vitalsReceiver,
            IntentFilter("com.example.myvitalsandroid.VITALS_DATA"), flags)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(connectionReceiver)
            unregisterReceiver(vitalsReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        activeWebView = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView UI
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MyVitalsAppWebView(url: String, status: String) {
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var isLoading  by remember { mutableStateOf(true) }
        var isOffline  by remember { mutableStateOf(false) }

        BackHandler {
            if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
            else finish()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Status-bar colour fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(Color(0xFF0088d6))
            )

            Box(modifier = Modifier.weight(1f)) {
                if (isOffline) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = {
                            isOffline = false
                            isLoading = true
                            webViewRef?.reload()
                        }) { Text("Retry Connection") }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // ✅ Set the static reference HERE — WebView is ready
                                activeWebView = this

                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                addJavascriptInterface(WebAppInterface(ctx), "Android")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                        isLoading = true
                                    }
                                    override fun onPageFinished(v: WebView?, u: String?) {
                                        isLoading = false
                                    }
                                    override fun onReceivedError(
                                        v: WebView?,
                                        req: WebResourceRequest?,
                                        err: WebResourceError?
                                    ) {
                                        if (req?.isForMainFrame == true) {
                                            isOffline = true
                                            isLoading = false
                                        }
                                    }
                                }

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled  = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    mediaPlaybackRequiresUserGesture = false
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                }

                                loadUrl(url)
                                webViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isLoading && !isOffline) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF007EAF))
                    }
                }
            }
        }
    }
}
