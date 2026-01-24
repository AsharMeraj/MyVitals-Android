
package com.example.myvitalsandroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ApplicationInfo
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.material3.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jstyle.blesdk2208a.Util.BleSDK
import com.jstyle.blesdk2208a.Util.ResolveUtil
import com.jstyle.blesdk2208a.model.AutoMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Merged MainActivity: Manages WebView UI, Connectivity Status, and Permissions.
 * Fixed to correctly reference VitalsService within the same package.
 */
class MainActivity : ComponentActivity() {

    private val PERMISSION_REQ = 2001



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

    // This class receives the data from your Next.js "window.Android.onKioskSelected"
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

        // ADD THIS METHOD - This is what your "Get Vitals" button calls!
        @JavascriptInterface
        fun triggerMeasurement() {
            Log.d("MainActivity", "Bridge: Manual Measurement Triggered")
            val intent = Intent(context, VitalsService::class.java)
            intent.putExtra("action", "START_MEASUREMENT")
            context.startService(intent)
        }
    }

    private var currentStatus by mutableStateOf("Disconnected")

    private val vitalsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonData = intent?.getStringExtra("data") ?: return
            Log.d("MainActivity", "Sending to WebView: $jsonData")

            // This pushes the data into the window.receiveVitals() function in your Web App
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
            // Using a fallback URL if needed, but keeping your original URL as requested.
            MyVitalsAppWebView("https://child-life-project-git-v120-ezshifas-projects.vercel.app/", currentStatus)
        }

        // Initial permission check and connection attempt
        if (hasAllPermissions()) {
            checkBluetoothAndLocation()
        } else {
            requestAllPermissions()
        }
    }

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestAllPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBluetoothAndLocation()
            } else {
                Toast.makeText(this, "Permissions are required for wristband sync!", Toast.LENGTH_LONG).show()
            }
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
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (!locEnabled) {
            Toast.makeText(this, "Please enable Location Services", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        startVitalsService()
    }

    private fun startVitalsService() {
        // Explicitly ensuring VitalsService is found by staying in the same package
        val intent = Intent(this, VitalsService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start service: ${e.message}")
        }
    }

    private fun stopVitalsService() {
        val intent = Intent(this, VitalsService::class.java)
        stopService(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.example.myvitalsandroid.CONNECTION_STATUS")
        // Filter for Vitals Data
        val vitalsFilter = IntentFilter("com.example.myvitalsandroid.VITALS_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(connectionReceiver)
            unregisterReceiver(vitalsReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
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

        Box(modifier = Modifier.fillMaxSize()) {
            if (isOffline) {
                // Simplified UI for when offline if R.layout.no_internet_view is missing in your resources
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        isOffline = false
                        isLoading = true
                        webViewRef?.reload()
                    }) {
                        androidx.compose.material3.Text("Retry Connection")
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
                                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                    isLoading = true
                                }
                                override fun onPageFinished(v: WebView?, u: String?) {
                                    isLoading = false
                                }
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
                                cacheMode = WebSettings.LOAD_DEFAULT
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                // Enable mixed content if necessary for some assets
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                    CircularProgressIndicator(color = Color(0xFF2563EB))
                }
            }
        }
    }
}
