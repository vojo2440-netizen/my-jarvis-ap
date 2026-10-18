package com.victor.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()

    // 🔑 PASTE YOUR GEMINI API KEY INSIDE THE QUOTES BELOW:
    private val apiKey = "AQ.Ab8RN6KpzojlIjicEutUIeee6VhUPUfoKGT9qEw0GtxPEka2fA"

    // Optional Picovoice Wake Word key (leave as-is if using button tap only)
    private val picovoiceKey = "YOUR_PICOVOICE_ACCESS_KEY"
    private var porcupineManager: ai.picovoice.porcupine.PorcupineManager? = null

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val heard = results?.get(0) ?: return@registerForActivityResult
        statusText.text = "You said: $heard"
        askGemini(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        tts = TextToSpeech(this, this)

        val listenButton: Button = findViewById(R.id.listenButton)
        listenButton.setOnClickListener { startListening() }

        requestPermissions()
        startWakeWordListening()
    }

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        ).filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, 100)
        }
    }

    private fun startWakeWordListening() {
        if (picovoiceKey == "YOUR_PICOVOICE_ACCESS_KEY") return
        try {
            porcupineManager = ai.picovoice.porcupine.PorcupineManager.Builder()
                .setAccessKey(picovoiceKey)
                .setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE)
                .build(applicationContext) {
                    runOnUiThread {
                        statusText.text = "Yes?"
                        startListening()
                    }
                }
            porcupineManager?.start()
            statusText.text = "Listening for the wake word..."
        } catch (e: Exception) {
            statusText.text = "Wake word error: ${e.message}"
        }
    }

    private fun startListening() {
        porcupineManager?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        }
        speechLauncher.launch(intent)
    }

    private fun askGemini(heard: String) {
        statusText.text = "Thinking..."

        val systemInstruction = "You control an Android phone. Reply ONLY with compact JSON, no markdown formatting, no backticks, no other words, shaped like: " +
                "{\"action\": \"open_app\", \"target\": \"whatsapp\"} or " +
                "{\"action\": \"send_sms\", \"target\": \"+1234567890\", \"message\": \"hello\"} or " +
                "{\"action\": \"call\", \"target\": \"+1234567890\"} or " +
                "{\"action\": \"open_url\", \"target\": \"https://example.com\"} or " +
                "{\"action\": \"speak\", \"message\": \"your reply to say out loud\"}. " +
                "Pick exactly one action for the user's request."

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONObject().apply {
                    put("text", systemInstruction)
                })
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", heard)
                }))
            }))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), body.toString()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { statusText.text = "Network error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string() ?: return
                try {
                    val json = JSONObject(raw)
                    val text = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()

                    val cleanText = text.replace("```json", "").replace("```", "").trim()
                    val action = JSONObject(cleanText)

                    runOnUiThread { performAction(action) }
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = "Couldn't parse reply" }
                }
            }
        })
    }

    private fun performAction(action: JSONObject) {
        when (action.optString("action")) {
            "open_app" -> openApp(action.optString("target"))
            "send_sms" -> sendSms(action.optString("target"), action.optString("message"))
            "call" -> makeCall(action.optString("target"))
            "open_url" -> openUrl(action.optString("target"))
            "speak" -> {
                speak(action.optString("message"))
                statusText.text = "Still listening..."
                startListening()
                return
            }
            else -> statusText.text = "Didn't understand that"
        }
        porcupineManager?.start()
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            startActivity(launchIntent)
            statusText.text = "Opening $name"
        } else {
            statusText.text = "Couldn't find an app called $name"
        }
    }

    private fun sendSms(number: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
        }
        startActivity(intent)
        statusText.text = "Texting $number"
    }

    private fun makeCall(number: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun speak(message: String) {
        statusText.text = message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }
}
