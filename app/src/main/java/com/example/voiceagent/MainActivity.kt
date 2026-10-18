/*
 * On-device Voice Agent (English + Hinglish)
 * STT: Whisper (sherpa-onnx, on-device)   TTS: Piper VITS (sherpa-onnx, on-device)   LLM: Gemini (cloud, streamed)
 *
 * ===================== SETUP (10 min) =====================
 * 1. Android Studio -> New Project -> "Empty Views Activity" (Kotlin), package com.example.voiceagent, minSdk 26.
 *    Replace MainActivity.kt with this file. Delete activity_main.xml usage (UI is built in code).
 *
 * 2. Download sherpa-onnx Android AAR (https://github.com/k2-fsa/sherpa-onnx/releases ->
 *    sherpa-onnx-<version>.aar) into app/libs/ and add to app/build.gradle(.kts):x
 *        implementation(files("libs/sherpa-onnx-<version>.aar"))
 *
 * 3. Download models (same releases page: "asr-models" and "tts-models"), extract, and copy the
 *    folders into app/src/main/assets/ :
 *        sherpa-onnx-whisper-base/      (base-encoder.int8.onnx, base-decoder.int8.onnx, base-tokens.txt)
 *        vits-piper-en_US-amy-low/      (en_US-amy-low.onnx, tokens.txt, espeak-ng-data/)
 *        vits-piper-hi_IN-pratham-medium/ (hi_IN-pratham-medium.onnx, tokens.txt, espeak-ng-data/)
 *    (For better accuracy use whisper "small" and change WHISPER/file names below; slower.)
 *
 * 4. AndroidManifest.xml (inside <manifest>):
 *        <uses-permission android:name="android.permission.RECORD_AUDIO"/>
 *        <uses-permission android:name="android.permission.INTERNET"/>
 *    In <application> add: android:usesCleartextTraffic="false" (default) and keep Activity as launcher.
 *    In app/build.gradle(.kts) android{}: aaptOptions/androidResources { noCompress += listOf("onnx") }
 *
 * 5. Put your free Gemini key in GEMINI_KEY below (https://aistudio.google.com/apikey). Run on a real phone.
 *
 * How it works (latency tricks): tap-to-talk -> Whisper decodes right after you stop -> Gemini streams tokens ->
 * text is split into sentences/clauses and spoken immediately while the LLM is still generating.
 * Hinglish replies are generated in Devanagari so the Hindi Piper voice pronounces them naturally;
 * English replies use the English voice. Voice is picked per sentence by script.
 */
package com.example.voiceagent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val GEMINI_KEY = ""
        const val LLM_MODEL = "gemini-2.5-flash" // faster: "gemini-2.5-flash-lite"
        const val WHISPER = "sherpa-onnx-whisper-base"
        const val EN_DIR = "vits-piper-en_US-amy-low"
        const val HI_DIR = "vits-piper-hi_IN-pratham-medium"
        const val SR = 16000
        const val SYSTEM_PROMPT =
            "You are a concise voice assistant on a phone. Reply in 1-2 short spoken sentences, no markdown, no emojis, no lists. " +
                    "If the user speaks English, reply in English. If the user speaks Hindi/Hinglish (Hindi written in Latin or Devanagari), " +
                    "reply in Hindi written ONLY in Devanagari script, writing English loanwords in Devanagari too " +
                    "(e.g. अलार्म, रिमाइंडर, मीटिंग, शेड्यूल). Write numbers and times as words in the reply language " +
                    "(e.g. शाम पाँच बजे / five PM). You cannot access live data or the device; if asked for weather, schedule or to set " +
                    "alarms/reminders, briefly confirm what you would do or say you can't fetch live data, and offer the next step."
    }

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button
    private lateinit var sw: Switch

    private var ttsEn: OfflineTts? = null
    private var ttsHi: OfflineTts? = null
    private val recognizers = HashMap<String, OfflineRecognizer>()

    @Volatile private var recording = false
    private val pcm = ArrayList<ShortArray>()
    private val history = JSONArray()

    private val END = "\u0000END"
    private val speakQ = LinkedBlockingQueue<String>()
    @Volatile private var tSpeechEnd = 0L
    @Volatile private var firstAudioLogged = true
    private var track: AudioTrack? = null
    private var trackRate = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        thread { loadModels() }
        thread { speakerLoop() }
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32)
        }
        status = TextView(this).apply { text = "Loading models…"; textSize = 14f }
        sw = Switch(this).apply { text = "Hinglish mode (Hindi STT)  "; textSize = 16f }
        log = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT }
        scroll = ScrollView(this).apply { addView(log) }
        btn = Button(this).apply {
            text = "🎤 Tap to talk"; isEnabled = false; textSize = 20f
            setOnClickListener { toggleRecord() }
        }
        root.addView(status)
        root.addView(sw)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 220).apply { gravity = Gravity.BOTTOM })
        setContentView(root)
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun append(s: String) = ui { log.append(s); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    // ---------------- Model loading ----------------
    private fun loadModels() {
        try {
            ttsEn = makeTts(EN_DIR, "en_US-amy-low.onnx")
            ttsHi = makeTts(HI_DIR, "hi_IN-pratham-medium.onnx")
            recognizer("en"); recognizer("hi")
            ui { status.text = "Ready"; btn.isEnabled = true }
        } catch (e: Throwable) {
            ui { status.text = "Model load failed: ${e.message}" }
        }
    }

    private fun makeTts(dir: String, model: String): OfflineTts {
        val vits = OfflineTtsVitsModelConfig(
            model = "$dir/$model", tokens = "$dir/tokens.txt", dataDir = "$dir/espeak-ng-data"
        )
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 4, provider = "cpu"))
        return OfflineTts(assetManager = assets, config = cfg)
    }

    @Synchronized
    private fun recognizer(lang: String): OfflineRecognizer = recognizers.getOrPut(lang) {
        val w = OfflineWhisperModelConfig(
            encoder = "$WHISPER/base-encoder.int8.onnx",
            decoder = "$WHISPER/base-decoder.int8.onnx",
            language = lang, task = "transcribe", tailPaddings = 1000
        )
        val mc = OfflineModelConfig(whisper = w, tokens = "$WHISPER/base-tokens.txt", numThreads = 4, modelType = "whisper")
        OfflineRecognizer(assetManager = assets, config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SR, featureDim = 80), modelConfig = mc))
    }

    // ---------------- Recording + STT ----------------
    private fun toggleRecord() {
        if (!recording) startRecording() else stopRecording()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        stopPlayback()
        recording = true
        pcm.clear()
        btn.text = "⏹ Stop"
        status.text = "Listening…"
        thread {
            val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR))
            val buf = ShortArray(SR / 10)
            rec.startRecording()
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.add(buf.copyOf(n)) }
            }
            rec.stop(); rec.release()
        }
    }

    private fun stopRecording() {
        recording = false
        tSpeechEnd = System.currentTimeMillis()
        btn.text = "🎤 Tap to talk"
        btn.isEnabled = false
        status.text = "Transcribing…"
        val lang = if (sw.isChecked) "hi" else "en"
        thread {
            Thread.sleep(150) // let recorder thread flush last buffer
            val samples: FloatArray
            synchronized(pcm) {
                val total = pcm.sumOf { it.size }
                samples = FloatArray(total)
                var i = 0
                for (c in pcm) for (s in c) samples[i++] = s / 32768f
            }
            if (samples.size < SR / 4) { ui { status.text = "Too short"; btn.isEnabled = true }; return@thread }
            val t0 = System.currentTimeMillis()
            val r = recognizer(lang)
            val stream = r.createStream()
            stream.acceptWaveform(samples, SR)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()
            val sttMs = System.currentTimeMillis() - t0
            if (text.isEmpty()) { ui { status.text = "Didn't catch that"; btn.isEnabled = true }; return@thread }
            append("\nYou: $text\n")
            ui { status.text = "STT ${sttMs}ms · thinking…" }
            askLlm(text, sttMs)
        }
    }

    // ---------------- LLM (Groq, streamed) ----------------
    private fun askLlm(userText: String, sttMs: Long) {
        history.put(JSONObject().put("role", "user").put("content", userText))
        while (history.length() > 10) history.remove(0)
        val contents = JSONArray()
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            contents.put(JSONObject().put("role", if (m.getString("role") == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", m.getString("content")))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 200)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))

        val full = StringBuilder()
        val buf = StringBuilder()
        var first = true
        val tReq = System.currentTimeMillis()
        firstAudioLogged = false
        append("Agent: ")
        try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$LLM_MODEL:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("x-goog-api-key", GEMINI_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) {
                val err = c.errorStream?.bufferedReader()?.readText()
                append("[LLM error ${c.responseCode}] $err\n"); ui { btn.isEnabled = true }; return
            }
            val br = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
            while (true) {
                val line = br.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                val parts = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                val delta = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text", "") }
                if (delta.isEmpty()) continue
                if (first) { first = false; val ms = System.currentTimeMillis() - tReq; ui { status.text = "STT ${sttMs}ms · LLM first token ${ms}ms" } }
                full.append(delta); append(delta)
                for (ch in delta) {
                    buf.append(ch)
                    val boundary = ch in ".?!।\n" && !(ch == '.' && buf.length > 1 && buf[buf.length - 2].isDigit())
                    val clause = ch in ",،;" && buf.length >= 28
                    if ((boundary && buf.length >= 8) || clause) { enqueue(buf.toString()); buf.clear() }
                }
            }
            if (buf.isNotBlank()) enqueue(buf.toString())
        } catch (e: Exception) {
            append("[error: ${e.message}]")
        }
        append("\n")
        history.put(JSONObject().put("role", "assistant").put("content", full.toString()))
        speakQ.put(END)
    }

    private fun enqueue(s: String) {
        val clean = s.replace(Regex("[*#`_~>\\p{So}\\p{Cs}]"), "").trim()
        if (clean.any { it.isLetterOrDigit() }) speakQ.put(clean)
    }

    // ---------------- TTS playback ----------------
    private fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

    private fun speakerLoop() {
        while (true) {
            val s = speakQ.take()
            if (s == END) { ui { status.text = status.text.toString().substringBefore(" · Ready") + " · Ready"; btn.isEnabled = true }; continue }
            val tts = (if (hasDevanagari(s)) ttsHi else ttsEn) ?: continue
            val t = ensureTrack(tts.sampleRate())
            tts.generateWithCallback(text = s, sid = 0, speed = 1.0f) { samples ->
                if (!firstAudioLogged) {
                    firstAudioLogged = true
                    val total = System.currentTimeMillis() - tSpeechEnd
                    ui { status.text = "${status.text} · first audio ${total}ms after you stopped" }
                }
                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                1
            }
        }
    }

    @Synchronized
    private fun ensureTrack(rate: Int): AudioTrack {
        if (track != null && trackRate == rate) return track!!
        track?.release()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        track!!.play(); trackRate = rate
        return track!!
    }

    private fun stopPlayback() {
        speakQ.clear()
        track?.pause(); track?.flush(); track?.play()
    }

    override fun onDestroy() {
        recording = false
        track?.release()
        super.onDestroy()
    }
}