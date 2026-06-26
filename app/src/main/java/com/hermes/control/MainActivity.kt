package com.hermes.control

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.rikka.shizuku.Shizuku
import dev.rikka.shizuku.ShizukuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var controlPanel: View
    private lateinit var mcpStatusText: android.widget.TextView
    private lateinit var webUiUrlInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var brightnessSlider: android.widget.SeekBar
    private lateinit var brightnessValueText: android.widget.TextView
    private lateinit var wifiSwitch: SwitchMaterial
    private lateinit var torchSwitch: SwitchMaterial

    private var mcpPort = 9199
    private var mcpServer: McpServer? = null
    private var isShizukuReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupShizuku()
        startMcpServer()
        setupControls()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)
        controlPanel = findViewById(R.id.controlPanel)
        mcpStatusText = findViewById(R.id.mcpStatusText)
        webUiUrlInput = findViewById(R.id.webUiUrlInput)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        brightnessValueText = findViewById(R.id.brightnessValueText)
        wifiSwitch = findViewById(R.id.wifiSwitch)
        torchSwitch = findViewById(R.id.torchSwitch)

        webUiUrlInput.setText("http://127.0.0.1:9119")
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
            userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) " +
                    "AppleWebKit/537.36 HermesControl/1.0"
        }

        // Enable dark mode for WebView content
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings,
                WebSettingsCompat.FORCE_DARK_AUTO)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadingView.visibility = View.GONE
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    fun connectWebUi(view: View) {
        val url = webUiUrlInput.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Enter Web UI URL", Toast.LENGTH_SHORT).show()
            return
        }
        loadingView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    fun toggleControlPanel(view: View) {
        if (controlPanel.visibility == View.VISIBLE) {
            controlPanel.visibility = View.GONE
        } else {
            controlPanel.visibility = View.VISIBLE
        }
    }

    // ─── Shizuku ────────────────────────────────────────────────

    private fun setupShizuku() {
        if (!Shizuku.pingBinder()) {
            mcpStatusText.text = "⚠️ Shizuku not running — install from lsposed.github.io"
            return
        }

        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 1001 && grantResult == 0) {
                isShizukuReady = true
                mcpStatusText.text = "✅ Shizuku ready (PID: ${android.os.Process.myPid()})"
            }
        }

        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
            mcpStatusText.text = "⚠️ Shizuku v13+ needed"
            return
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Shizuku Permission")
                .setMessage("This app needs Shizuku to control system settings")
                .setPositiveButton("Grant") { _, _ -> grantShizuku() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            grantShizuku()
        }
    }

    private fun grantShizuku() {
        try {
            Shizuku.requestPermission(1001)
            isShizukuReady = true
            mcpStatusText.text = "✅ Shizuku ready"
        } catch (e: Exception) {
            mcpStatusText.text = "❌ Shizuku error: ${e.message}"
        }
    }

    // ─── MCP Server ─────────────────────────────────────────────

    private fun startMcpServer() {
        mcpServer = McpServer(mcpPort, this)
        mcpServer?.start()
        mcpStatusText.text = "✅ MCP running on :$mcpPort | ${if (isShizukuReady) "Shizuku OK" else "No Shizuku"}"
    }

    fun getShizukuCommands(): McpCommands = McpCommands(this, isShizukuReady)

    // ─── Quick Controls ─────────────────────────────────────────

    private fun setupControls() {
        // Brightness slider
        brightnessSlider.max = 255
        try {
            val current = Settings.System.getInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS)
            brightnessSlider.progress = current
            brightnessValueText.text = "☀️ $current"
        } catch (_: Exception) {}

        brightnessSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                brightnessValueText.text = "☀️ $value"
                if (fromUser) setBrightness(value)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // WiFi
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiSwitch.isChecked = wifiManager.isWifiEnabled
        wifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            wifiManager.isWifiEnabled = isChecked
        }

        // Torch
        torchSwitch.setOnCheckedChangeListener { _, isChecked ->
            setTorch(isChecked)
        }
    }

    private fun setBrightness(value: Int) {
        try {
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value)
        } catch (_: Exception) {}
    }

    private fun setTorch(on: Boolean) {
        try {
            val camera = android.hardware.camera2.CameraManager::class.java
                .getDeclaredConstructor(Context::class.java)
                .newInstance(this)
            val cameraManager = getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
        } catch (_: Exception) {
            Toast.makeText(this, "Torch not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        mcpServer?.stop()
        super.onDestroy()
    }
}
