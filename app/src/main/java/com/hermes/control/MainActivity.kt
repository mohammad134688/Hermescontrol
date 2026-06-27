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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var controlPanel: View
    private lateinit var mcpStatusText: android.widget.TextView
    private lateinit var webUiUrlInput: TextInputEditText
    private lateinit var brightnessSlider: android.widget.SeekBar
    private lateinit var brightnessValueText: android.widget.TextView
    private lateinit var wifiSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var torchSwitch: androidx.appcompat.widget.SwitchCompat

    private var mcpPort = 9199
    private var mcpServer: McpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Shizuku - all lazy, never crash the app
    private var shizukuInitialized = false
    private var binderReceivedListener: Any? = null
    private var binderDeadListener: Any? = null
    private var permissionListener: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        startMcpServer()
        setupControls()

        mcpStatusText.text = "✅ MCP running on :$mcpPort"

        // Try Shizuku AFTER everything else is ready
        trySetupShizuku()
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

    private fun trySetupShizuku() {
        try {
            // Check if Shizuku class is even loadable
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")

            // Check if binder is already available
            val pingBinder = shizukuClass.getMethod("pingBinder")
            val available = pingBinder.invoke(null) as? Boolean ?: false

            if (available) {
                tryRequestPermission(shizukuClass)
                updateShizukuStatus("✅ Shizuku connected")
            } else {
                updateShizukuStatus("⏳ Waiting for Shizuku...")
            }

            // Register listeners safely
            setupShizukuListeners(shizukuClass)
            shizukuInitialized = true

        } catch (e: ClassNotFoundException) {
            updateShizukuStatus("⚠️ Shizuku not installed")
        } catch (e: Exception) {
            updateShizukuStatus("⚠️ Shizuku: ${e.message?.take(30)}")
        }
    }

    private fun setupShizukuListeners(shizukuClass: Class<*>) {
        try {
            val binderReceivedClass = Class.forName("rikka.shizuku.Shizuku\$OnBinderReceivedListener")
            val binderDeadClass = Class.forName("rikka.shizuku.Shizuku\$OnBinderDeadListener")
            val permResultClass = Class.forName("rikka.shizuku.Shizuku\$OnRequestPermissionResultListener")

            // Create listener instances via proxy
            val binderReceived = java.lang.reflect.Proxy.newProxyInstance(
                shizukuClass.classLoader,
                arrayOf(binderReceivedClass)
            ) { _, _, _ ->
                runOnUiThread { updateShizukuStatus("✅ Shizuku connected") }
                null
            }

            val binderDead = java.lang.reflect.Proxy.newProxyInstance(
                shizukuClass.classLoader,
                arrayOf(binderDeadClass)
            ) { _, _, _ ->
                runOnUiThread { updateShizukuStatus("❌ Shizuku disconnected") }
                null
            }

            val permResult = java.lang.reflect.Proxy.newProxyInstance(
                shizukuClass.classLoader,
                arrayOf(permResultClass)
            ) { _, method, args ->
                if (method?.name == "onRequestPermissionResult") {
                    val granted = (args?.get(1) as? Int) == 0
                    runOnUiThread {
                        updateShizukuStatus(if (granted) "✅ Shizuku ready (ADB)" else "❌ Permission denied")
                    }
                }
                null
            }

            // Register listeners via reflection
            val addMethod1 = shizukuClass.getMethod("addBinderReceivedListener", binderReceivedClass)
            addMethod1.invoke(null, binderReceived)

            val addMethod2 = shizukuClass.getMethod("addBinderDeadListener", binderDeadClass)
            addMethod2.invoke(null, binderDead)

            val addMethod3 = shizukuClass.getMethod("addRequestPermissionResultListener", permResultClass)
            addMethod3.invoke(null, permResult)

            binderReceivedListener = binderReceived
            binderDeadListener = binderDead
            permissionListener = permResult

        } catch (e: Exception) {
            // Listeners optional - app works without them
        }
    }

    private fun tryRequestPermission(shizukuClass: Class<*>) {
        try {
            val checkPerm = shizukuClass.getMethod("checkSelfPermission")
            val result = checkPerm.invoke(null) as? Int ?: -1
            if (result != 0) {
                val reqPerm = shizukuClass.getMethod("requestPermission", Int::class.javaPrimitiveType)
                reqPerm.invoke(null, 1001)
            }
        } catch (_: Exception) {}
    }

    private fun updateShizukuStatus(status: String) {
        runOnUiThread {
            mcpStatusText.text = "✅ MCP on :$mcpPort | $status"
        }
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
        // Cleanup Shizuku listeners via reflection
        try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            binderReceivedListener?.let {
                val m = shizukuClass.getMethod("removeBinderReceivedListener", it.javaClass.interfaces[0])
                m.invoke(null, it)
            }
            binderDeadListener?.let {
                val m = shizukuClass.getMethod("removeBinderDeadListener", it.javaClass.interfaces[0])
                m.invoke(null, it)
            }
            permissionListener?.let {
                val m = shizukuClass.getMethod("removeRequestPermissionResultListener", it.javaClass.interfaces[0])
                m.invoke(null, it)
            }
        } catch (_: Exception) {}

        mcpServer?.stop()
        super.onDestroy()
    }
}
