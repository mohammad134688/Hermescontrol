package com.hermes.control

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP + JSON-RPC 2.0 MCP Server.
 * Runs inside the app process — Hermes connects to it via `hermes mcp add`.
 */
class McpServer(private val port: Int, private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream()))

            // Read HTTP request
            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }

            // Read headers
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isBlank()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                reader.read(buffer, 0, contentLength)
                String(buffer)
            } else ""

            val response: String = when {
                method == "GET" -> handleGet()
                method == "POST" && path == "/" -> handlePost(body)
                else -> errorResponse(404, "Not Found")
            }

            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Access-Control-Allow-Origin: *\r\n")
            writer.write("Content-Length: ${response.toByteArray().size}\r\n")
            writer.write("\r\n")
            writer.write(response)
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleGet(): String {
        val info = JSONObject()
        info.put("name", "Hermes Android App MCP")
        info.put("version", "1.0.0")
        info.put("tools", getToolDefinitions().length())
        return info.toString()
    }

    private fun handlePost(body: String): String {
        return try {
            val request = JSONObject(body)
            val reqId = request.opt("id")
            val method = request.optString("method", "")

            when (method) {
                "initialize" -> {
                    val result = JSONObject()
                    result.put("protocolVersion", "2025-03-26")
                    val serverInfo = JSONObject()
                    serverInfo.put("name", "Hermes Android App MCP")
                    serverInfo.put("version", "1.0.0")
                    result.put("serverInfo", serverInfo)
                    val caps = JSONObject()
                    caps.put("tools", JSONObject())
                    result.put("capabilities", caps)
                    jsonRpcResponse(reqId, result)
                }
                "tools/list" -> {
                    val result = JSONObject()
                    result.put("tools", getToolDefinitions())
                    jsonRpcResponse(reqId, result)
                }
                "tools/call" -> {
                    val params = request.getJSONObject("params")
                    val toolName = params.getString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val result = executeTool(toolName, args)
                    jsonRpcResponse(reqId, result)
                }
                "ping" -> jsonRpcResponse(reqId, "pong")
                "notifications/initialized" -> jsonRpcResponse(reqId, JSONObject())
                else -> {
                    val err = JSONObject()
                    err.put("code", -32601)
                    err.put("message", "Unknown method: $method")
                    jsonRpcError(reqId, err)
                }
            }
        } catch (e: Exception) {
            val err = JSONObject()
            err.put("code", -32700)
            err.put("message", e.message ?: "Parse error")
            jsonRpcError(null, err)
        }
    }

    private fun getToolDefinitions(): JSONArray {
        val tools = MCP_TOOLS
        val arr = JSONArray()
        for (t in tools) {
            val tool = JSONObject()
            tool.put("name", t[0])
            tool.put("description", t[1])
            tool.put("inputSchema", t[2])
            arr.put(tool)
        }
        return arr
    }

    private fun executeTool(name: String, args: JSONObject): JSONObject {
        val cmds = McpCommands(context)
        return when (name) {
            "brightness_get"      -> resultOf("brightness", cmds.getBrightness())
            "brightness_set"      -> resultOf("success", cmds.setBrightness(args.optInt("value", 128)))
            "brightness_auto"     -> resultOf("success", cmds.setAutoBrightness(true))
            "volume_set"          -> resultOf("success", cmds.setVolume(args.optString("stream", "music"), args.optInt("level", 10)))
            "volume_get"          -> mapResult(cmds.getVolumes())
            "wifi_enable"         -> resultOf("success", cmds.setWifi(true))
            "wifi_disable"        -> resultOf("success", cmds.setWifi(false))
            "wifi_status"         -> mapResult(cmds.getWifiStatus())
            "battery"             -> mapResult(cmds.getBatteryStatus())
            "torch_on"            -> resultOf("success", cmds.setTorch(true))
            "torch_off"           -> resultOf("success", cmds.setTorch(false))
            "notify"              -> resultOf("sent", cmds.sendNotification(args.optString("title", ""), args.optString("content", "")))
            "screenshot"          -> resultMap("error", "Use termux-screenshot via MCP python server")
            "app_launch"          -> resultOf("success", cmds.launchApp(args.optString("package", "")))
            "app_list"            -> resultMap("data", "Use pm list packages via adb/shizuku shell")
            else                  -> resultOf("error", Result.failure(Exception("Unknown tool: $name")))
        }
    }

    private fun resultOf(key: String, result: Result<Any>): JSONObject {
        val r = JSONObject()
        result.onSuccess { r.put(key, it.toString()) }.onFailure { r.put("error", it.message ?: "Failed") }
        return r
    }

    private fun resultMap(key: String, value: String): JSONObject {
        val r = JSONObject()
        r.put(key, value)
        return r
    }

    private fun mapResult(result: Result<Any>): JSONObject {
        val r = JSONObject()
        result.onSuccess { r.put("data", it.toString()) }.onFailure { r.put("error", it.message ?: "Failed") }
        return r
    }

    private fun jsonRpcResponse(id: Any?, result: Any): String {
        val r = JSONObject()
        r.put("jsonrpc", "2.0")
        if (id != null) r.put("id", id)
        r.put("result", result)
        return r.toString()
    }

    private fun jsonRpcError(id: Any?, error: JSONObject): String {
        val r = JSONObject()
        r.put("jsonrpc", "2.0")
        if (id != null) r.put("id", id)
        r.put("error", error)
        return r.toString()
    }

    private fun errorResponse(code: Int, msg: String): String {
        val r = JSONObject()
        r.put("error", msg)
        r.put("code", code)
        return r.toString()
    }

    companion object {
        val MCP_TOOLS = arrayOf(
            arrayOf("brightness_get", "Get current screen brightness (0-255)", JSONObject("{}")),
            arrayOf("brightness_set", "Set screen brightness", JSONObject("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\",\"description\":\"0-255\"}},\"required\":[\"value\"]}")),
            arrayOf("brightness_auto", "Enable auto-brightness", JSONObject("{}")),
            arrayOf("volume_get", "Get all audio stream volumes", JSONObject("{}")),
            arrayOf("volume_set", "Set volume for a stream", JSONObject("{\"type\":\"object\",\"properties\":{\"stream\":{\"type\":\"string\"},\"level\":{\"type\":\"integer\"}},\"required\":[\"stream\",\"level\"]}")),
            arrayOf("wifi_enable", "Turn WiFi on", JSONObject("{}")),
            arrayOf("wifi_disable", "Turn WiFi off", JSONObject("{}")),
            arrayOf("wifi_status", "Get WiFi connection info", JSONObject("{}")),
            arrayOf("battery", "Get battery percentage and status", JSONObject("{}")),
            arrayOf("torch_on", "Turn flashlight on", JSONObject("{}")),
            arrayOf("torch_off", "Turn flashlight off", JSONObject("{}")),
            arrayOf("notify", "Send system notification", JSONObject("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"title\",\"content\"]}")),
            arrayOf("screenshot", "Take a screenshot", JSONObject("{}")),
            arrayOf("app_launch", "Launch an app by package name", JSONObject("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}")),
            arrayOf("app_list", "List installed apps", JSONObject("{}")),
        )
}
