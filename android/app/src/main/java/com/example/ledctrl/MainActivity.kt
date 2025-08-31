
package com.example.ledctrl

import android.graphics.Color.HSVToColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppUI() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUI() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ledctrl", 0)

    var baseUrl by remember {
        mutableStateOf(prefs.getString("baseUrl", "http://led.local") ?: "http://led.local")
    }
    fun saveBaseUrl() = prefs.edit().putString("baseUrl", baseUrl.normalizeBaseUrl()).apply()

    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    var numLeds by remember { mutableStateOf<Int?>(null) }
    var globalBright by remember { mutableStateOf(128f) }
    var statusText by remember { mutableStateOf("—") }
    var selectionActive by remember { mutableStateOf(false) }
    var selectionDirty by remember { mutableStateOf(false) }

    var startIdx by remember { mutableStateOf("0") }
    var endIdx by remember { mutableStateOf("0") }

    var hue by remember { mutableStateOf(30f) }
    var sat by remember { mutableStateOf(1f) }
    var localBright by remember { mutableStateOf(255f) }

    val colorDebouncer = remember { Debouncer(scope, 120) }
    val lbrightDebouncer = remember { Debouncer(scope, 80) }
    val gbrightDebouncer = remember { Debouncer(scope, 80) }

    val colorInt = remember(hue, sat) { HSVToColor(floatArrayOf(hue, sat, 1f)) }
    val colorRGB = Triple((colorInt shr 16) and 0xFF, (colorInt shr 8) and 0xFF, (colorInt) and 0xFF)
    val hex = String.format("%02X%02X%02X", colorRGB.first, colorRGB.second, colorRGB.third)

    fun show(e: Throwable) { statusText = "Ошибка: ${e.javaClass.simpleName}: ${e.message ?: ""}" }

    suspend fun httpGet(path: String, params: Map<String, String> = emptyMap()): String {
        val url = buildString {
            append(baseUrl.normalizeBaseUrl().trimEnd('/'))
            append(path)
            if (params.isNotEmpty()) {
                append("?")
                append(params.entries.joinToString("&") { (k, v) -> "${k.urlEnc()}=${v.urlEnc()}" })
            }
        }
        val req = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }
        }
    }

    suspend fun refreshState() {
        runCatching {
            val txt = httpGet("/state")
            val js = JSONObject(txt)
            numLeds = js.optInt("num_leds", -1).takeIf { it >= 0 }
            globalBright = js.optDouble("bright", 128.0).toFloat()
            val sel = js.optJSONObject("selection")
            selectionActive = sel?.optBoolean("active") == true
            selectionDirty = sel?.optBoolean("dirty") == true
            statusText = "OK"
        }.onFailure { show(it) }
    }
    LaunchedEffect(Unit) { refreshState() }

    Scaffold(topBar = { SmallTopAppBar(title = { Text("LED Ctrl (ESP8266)") }) }) { pad ->
        Column(
            modifier = Modifier.padding(pad).padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Контроллер (http://led.local или http://IP)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveBaseUrl(); scope.launch { refreshState() } }) { Text("Сохранить / Тест") }
                Text("Статус: $statusText", modifier = Modifier.align(Alignment.CenterVertically))
            }

            Text("Выбор диапазона", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startIdx, { if (it.length<=5) startIdx = it.filterDigits() },
                    label={ Text("start") }, singleLine=true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                OutlinedTextField(endIdx, { if (it.length<=5) endIdx = it.filterDigits() },
                    label={ Text("end") }, singleLine=true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val s = startIdx.toIntOrNull() ?: 0
                    val e = endIdx.toIntOrNull() ?: 0
                    scope.launch {
                        runCatching { httpGet("/select", mapOf("start" to "$s","end" to "$e","blink" to "1")) }
                            .onSuccess { selectionActive = true; selectionDirty = false; statusText="Selected $s..$e" }
                            .onFailure { show(it) }
                    }
                }) { Text("Выбрать и моргнуть") }

                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { httpGet("/cancel") }
                            .onSuccess { selectionActive=false; selectionDirty=false; statusText="Откат" }
                            .onFailure { show(it) }
                    }
                }, enabled = selectionActive) { Text("Cancel") }
            }

            Text("Цвет (колесо HSV, яркость отдельно)", style = MaterialTheme.typography.titleMedium)
            ColorWheel(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                hue = hue, sat = sat
            ) { h, s ->
                hue = h; sat = s
                if (selectionActive) {
                    colorDebouncer.submit {
                        runCatching { httpGet("/set", mapOf("hex" to hex)) }
                            .onSuccess { selectionDirty = true; statusText = "Preview $hex" }
                            .onFailure { show(it) }
                    }
                }
            }

            Text("Локальная яркость выделенного диапазона: ${localBright.roundToInt()}")
            Slider(
                value = localBright,
                onValueChange = { v ->
                    localBright = v
                    if (selectionActive) {
                        lbrightDebouncer.submit {
                            runCatching { httpGet("/lbright", mapOf("value" to localBright.roundToInt().toString())) }
                                .onSuccess { selectionDirty = true; statusText = "Preview lbright ${localBright.roundToInt()}" }
                                .onFailure { show(it) }
                        }
                    }
                },
                valueRange = 0f..255f
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        runCatching { httpGet("/save") }
                            .onSuccess { selectionDirty = false; statusText = "Saved" }
                            .onFailure { show(it) }
                    }
                }, enabled = selectionDirty) { Text("Save") }

                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { httpGet("/load") }
                            .onSuccess { selectionActive = false; selectionDirty = false; statusText = "Loaded"; refreshState() }
                            .onFailure { show(it) }
                    }
                }) { Text("Load") }
            }

            Text("Глобальная яркость: ${globalBright.roundToInt()}")
            Slider(
                value = globalBright,
                onValueChange = { v ->
                    globalBright = v
                    gbrightDebouncer.submit {
                        runCatching { httpGet("/brightness", mapOf("value" to v.roundToInt().toString())) }
                            .onSuccess { statusText = "Global ${v.roundToInt()}" }
                            .onFailure { show(it) }
                    }
                },
                valueRange = 0f..255f
            )

            Text("num_leds: ${numLeds ?: "?"}  •  selection: ${if (selectionActive) "active" else "none"}${if (selectionDirty) " (preview)" else ""}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class Debouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long
) {
    private var job: Job? = null
    fun submit(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            block()
        }
    }
}

private fun String.filterDigits(): String = this.filter { it.isDigit() }
private fun String.normalizeBaseUrl(): String {
    var s = this.trim()
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    return s.trimEnd('/')
}
private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
