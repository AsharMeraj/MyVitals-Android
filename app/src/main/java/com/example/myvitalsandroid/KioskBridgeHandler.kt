package com.example.myvitalsandroid

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.content.Intent
import android.util.Log
import okhttp3.*
import org.json.JSONObject

class KioskBridgeHandler(private val service: VitalsService, private val client: OkHttpClient) {

    // This reference MUST be set from MainActivity to work
    companion object {
        @SuppressLint("StaticFieldLeak")
        var activeWebView: WebView? = null
    }

    /**
     * DIRECT SCAN: Skips the API and starts scanning for the MAC immediately
     */
    fun startDirectWristbandScan(macAddress: String) {
        Log.d("KioskBridgeHandler", "Direct Scan Requested for MAC: $macAddress")
        service.updateWristbandMacAndScan(macAddress)
    }

    /**
     * NEW: Sends simple UI signals (Connected, Disconnected, etc.) directly to the WebView
     */
    fun sendSignalToWeb(status: String) {
        try {
            val json = JSONObject()
                .put("type", "SIGNAL")
                .put("status", status)
            val jsonString = json.toString()

            Handler(Looper.getMainLooper()).post {
                // Use the static reference here
                if (activeWebView != null) {
                    activeWebView?.evaluateJavascript("javascript:onVitalsReceived('$jsonString')", null)
                    Log.d("KioskBridgeHandler", "Signal Sent to WebView: $status")
                } else {
                    Log.e("KioskBridgeHandler", "WebView Reference is MISSING in Static Member!")
                }
            }
        } catch (e: Exception) {
            Log.e("KioskBridgeHandler", "Signal Error: ${e.message}")
        }
    }

    /**
     * Sends data back to Web via Android Broadcast (Internal logic)
     */
    fun sendVitalsToWeb(spo2: String, heartRate: String, temperature: String, timestamp: String) {
        try {
            // 1. Create the Javascript call string
            // We pass the data as a JSON string so JS can easily parse it
            val jsonString = JSONObject()
                .put("spo2", spo2)
                .put("heartRate", heartRate)
                .put("temperature", temperature)
                .put("timestamp", timestamp)
                .toString()

            val jsCode = "if(window.receiveVitals) { window.receiveVitals($jsonString); }"

            // 2. Execute on the UI Thread (WebView MUST be updated on Main Thread)
            Handler(Looper.getMainLooper()).post {
                // Replace 'yourWebViewInstance' with the actual webView variable in this class
                activeWebView?.evaluateJavascript(jsCode, null)
            }

        } catch (e: Exception) {
            Log.e("KioskBridgeHandler", "Failed to send to WebView: ${e.message}")
        }
    }

    @JavascriptInterface // CRITICAL: This allows JS to see this function
    fun triggerMeasurement() {
        Log.d("KioskBridgeHandler", "🖱️ Start Measurement clicked in Web App")
        // Call the service directly instead of sending an Intent
        service.startManualVitals()
    }


}