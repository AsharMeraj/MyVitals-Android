package com.example.myvitalsandroid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
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

/**
 * Merged MainActivity: Manages WebView UI with a Top Brand Bar,
 * Connectivity Status, and Bluetooth Permissions.
 */
class MainActivity : ComponentActivity() {

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

    // JavaScript Bridge to communicate with the Web App
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun onKioskSelected(wristbandId: String) {
            Log.d("MainActivity", "Bridge: Connecting to MAC $wristbandId")
            val intent = Intent(context, VitalsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                val scanIntent = Intent(context, VitalsService::class.java)
                scanIntent.putExtra("wristbandId", wristbandId)
                context.startService(scanIntent)
            }, 500)
        }

        @JavascriptInterface
        fun disconnectWristband() {
            Log.d("MainActivity", "Bridge: Disconnect requested")
            val intent = Intent(context, VitalsService::class.java)
            intent.putExtra("action", "DISCONNECT")
            context.startService(intent)
        }

        @JavascriptInterface
        fun triggerMeasurement() {
            Log.d("MainActivity", "Bridge: Manual Measurement Triggered")
            val intent = Intent(context, VitalsService::class.java)
            intent.putExtra("action", "START_MEASUREMENT")
            context.startService(intent)
        }
    }

    private val vitalsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonData = intent?.getStringExtra("data") ?: return
            KioskBridgeHandler.activeWebView?.evaluateJavascript(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        enableEdgeToEdge()

        setContent {
            // --- UI Setup: System Status Bar Coloring ---
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    window.statusBarColor = android.graphics.Color.parseColor("#0088d6")
                    // Ensure status bar icons (clock/battery) are white
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                }
            }

            MyVitalsAppWebView(
                url = "https://child-life-project-git-v120-ezshifas-projects.vercel.app/",
                status = currentStatus
            )
        }

        if (hasAllPermissions()) {
            checkBluetoothAndLocation()
        } else {
            requestAllPermissions()
        }
    }

    // --- Permission Logic ---
    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestAllPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
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
        val locEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }

        if (!locEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startVitalsService()
    }

    private fun startVitalsService() {
        val intent = Intent(this, VitalsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.example.myvitalsandroid.CONNECTION_STATUS")
        val vitalsFilter = IntentFilter("com.example.myvitalsandroid.VITALS_DATA")

        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        registerReceiver(connectionReceiver, filter, receiverFlags)
        registerReceiver(vitalsReceiver, vitalsFilter, receiverFlags)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(connectionReceiver)
            unregisterReceiver(vitalsReceiver)
        } catch (e: Exception) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MyVitalsAppWebView(url: String, status: String) {
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var isOffline by remember { mutableStateOf(false) }

        BackHandler {
            if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
            else finish()
        }

        // --- Main UI Structure ---
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. Top Brand Bar (Fills the Status Bar area)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(Color(0x0088d6))
            )

            // 2. Main Content (WebView)
            Box(modifier = Modifier.weight(1f)) {
                if (isOffline) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = {
                            isOffline = false
                            isLoading = true
                            webViewRef?.reload()
                        }) {
                            Text("Retry Connection")
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                KioskBridgeHandler.activeWebView = this
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                addJavascriptInterface(WebAppInterface(ctx), "Android")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { isLoading = true }
                                    override fun onPageFinished(v: WebView?, u: String?) { isLoading = false }
                                    override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                                        if (req?.isForMainFrame == true) {
                                            isOffline = true
                                            isLoading = false
                                        }
                                    }
                                }
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF007EAF))
                    }
                }
            }
        }
    }
}