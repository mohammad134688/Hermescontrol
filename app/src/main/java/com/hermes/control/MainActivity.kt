package com.hermes.control

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var controlPanel: View
    private lateinit var mcpStatusText: android.widget.TextView
    private lateinit var webUiUrlInput: TextInputEditText
    private lateinit var brightnessSlider: android.widget.SeekBar
    private lateinit var brightnessValueText: android.widget.TextView
    private lateinit var wifiSwitch: android.widget.Switch
    private lateinit var torchSwitch: android.widget.Switch

    private var mcpPort = 9199
    private var mcpServer: McpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Shizuku
    private var shizukuAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupShizuku()
        startMcpServer()
        setupControls()

        mcpStatusText.text = "✅ MCP running on :$mcpPort"
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

    private fun setupShizuku() {
        try {
            // Check if Shizuku is available
            shizukuAvailable = Shizuku.pingBinder()

            // Add binder received listener
            Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

            if (shizukuAvailable) {
                // Check permission
                if (Shizuku.checkSelfPermission() != 0) {
                    Shizuku.requestPermission(1001)
                }
                updateShizukuStatus("✅ Shizuku connected")
            } else {
                updateShizukuStatus("⚠️ Shizuku not running")
            }
        } catch (e: Exception) {
            shizukuAvailable = false
            updateShizukuStatus("❌ Shizuku not installed")
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuAvailable = true
        runOnUiThread {
            updateShizukuStatus("✅ Shizuku connected")
        }
        if (Shizuku.checkSelfPermission() != 0) {
            Shizuku.requestPermission(1001)
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuAvailable = false
        runOnUiThread {
            updateShizukuStatus("❌ Shizuku disconnected")
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == 0
            runOnUiThread {
                updateShizukuStatus(if (granted) "✅ Shizuku ready (ADB access)" else "❌ Shizuku permission denied")
                if (!granted) {
                    Toast.makeText(this, "Shizuku permission needed for shell commands", Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun updateShizukuStatus(status: String) {
        val mcpText = "✅ MCP on :$mcpPort | $status"
        mcpStatusText.text = mcpText
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
                    "AppleWebKit/537.36 HermesControl/2.0"
        }

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
        controlPanel.visibility = if (controlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun startMcpServer() {
        mcpServer = McpServer(mcpPort, this)
        mcpServer?.start()
    }

    private fun setupControls() {
        brightnessSlider.max = 255
        try {
            val current = android.provider.Settings.System.getInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS)
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

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiSwitch.isChecked = wifiManager.isWifiEnabled
        wifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            wifiManager.isWifiEnabled = isChecked
        }

        torchSwitch.setOnCheckedChangeListener { _, isChecked ->
            setTorch(isChecked)
        }
    }

    private fun setBrightness(value: Int) {
        try {
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, value)
        } catch (_: Exception) {}
    }

    private fun setTorch(on: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
        } catch (_: Exception) {
            Toast.makeText(this, "Torch not available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // Cleanup Shizuku listeners
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}

        mcpServer?.stop()
        super.onDestroy()
    }
}
