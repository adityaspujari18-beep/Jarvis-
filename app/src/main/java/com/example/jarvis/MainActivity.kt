package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private var on = false
    private var speaking = false
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF04101C.toInt())
            setPadding(40, 96, 40, 40)
        }
        logView = TextView(this).apply { setTextColor(0xFFD6F4FF.toInt()); textSize = 17f }
        scroll = ScrollView(this).apply { addView(logView) }
        btn = Button(this).apply { text = "Start listening"; setOnClickListener { toggle() } }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        tts = TextToSpeech(this, this)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS), 1)
        log("Jarvis: Tap start, then say \"Jarvis, call mom\" or \"Jarvis, set a timer for 5 minutes\".")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts.language = Locale.getDefault()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { resumeAfterSpeech() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { resumeAfterSpeech() }
        })
    }

    private fun resumeAfterSpeech() { ui.post { speaking = false; listen() } }

    private fun log(s: String) {
        logView.append(s + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun say(s: String) {
        log("Jarvis: $s")
        rec?.cancel()
        if (!ttsReady) { speaking = false; ui.postDelayed({ listen() }, 300); return }
        speaking = true
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun toggle() {
        on = !on
        btn.text = if (on) "Stop listening" else "Start listening"
        if (on) say("Jarvis online.") else { rec?.cancel(); tts.stop(); speaking = false }
    }

    private fun listen() {
        if (!on || speaking) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Allow the microphone permission, then tap start again.")
            on = false; btn.text = "Start listening"; return
        }
        if (rec == null) rec = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        rec?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text != null) handle(text)
            ui.postDelayed({ listen() }, 300)
        }
        override fun onError(e: Int) {
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { on = false; btn.text = "Start listening"; return }
            ui.postDelayed({ listen() }, 500)
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun go(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { say("I could not do that on this phone.") }
    }

    private fun findNumber(name: String): String? {
        val digits = name.replace(" ", "")
        if (digits.matches(Regex("[+\\d]{5,}"))) return digits
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun handle(raw: String) {
        var t = raw.lowercase(Locale.getDefault())
        if (!t.contains("jarvis")) return
        t = t.replace(Regex("(hey |ok |okay )?jarvis,?"), "").trim()
        log("You: $raw")
        if (t.isEmpty()) return say("Yes?")

        if (Regex("what('s| is) the time|time is it").containsMatchIn(t))
            return say("It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
        if (Regex("what('s| is) (the )?date|what day").containsMatchIn(t))
            return say("Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date()))

        Regex("timer (?:for )?(\\d+) (second|minute|hour)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val secs = n * when (m.groupValues[2]) { "hour" -> 3600; "minute" -> 60; else -> 1 }
            go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            return say("Timer set for $n ${m.groupValues[2]}${if (n > 1) "s" else ""}.")
        }

        Regex("alarm (?:for|at) (\\d{1,2})(?::(\\d{2}))? ?([ap])\\.?m").find(t)?.let { m ->
            val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3] == "p") 12 else 0
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            return say("Alarm set for ${m.groupValues[1]}:${"%02d".format(min)} ${m.groupValues[3].uppercase()}M.")
        }

        Regex("^(?:call|phone|dial) (.+)").find(t)?.let { m ->
            val who = m.groupValues[1]
            val num = findNumber(who) ?: return say("I could not find $who in your contacts.")
            go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
            return say("Calling $who. Tap the green button to dial.")
        }

        Regex("^(?:text|message|sms) (\\S+) (?:saying |that )?(.+)").find(t)?.let { m ->
            val num = findNumber(m.groupValues[1]) ?: return say("I could not find ${m.groupValues[1]} in your contacts.")
            go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", m.groupValues[2]))
            return say("Text ready for ${m.groupValues[1]}. Tap send.")
        }

        if (Regex("flashlight|torch").containsMatchIn(t)) {
            val enable = !t.contains("off")
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try { cm.setTorchMode(cm.cameraIdList[0], enable) } catch (e: Exception) { return say("I could not reach the flashlight.") }
            return say(if (enable) "Flashlight on." else "Flashlight off.")
        }

        Regex("^(?:open|launch|start) (.+)").find(t)?.let { m ->
            val name = m.groupValues[1]
            val pm = packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.getDefault()).contains(name) }
            val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                ?: return say("I could not find an app called $name.")
            say("Opening $name.")
            return go(launch)
        }

        Regex("^(?:play|watch) (.+)").find(t)?.let { m ->
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.groupValues[1]))))
            return say("Searching YouTube for ${m.groupValues[1]}.")
        }

        Regex("^(?:navigate to|directions to|take me to) (.+)").find(t)?.let { m ->
            go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(m.groupValues[1]))))
            return say("Showing ${m.groupValues[1]} on the map.")
        }

        go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t))
        say("Searching for $t.")
    }

    override fun onPause() { super.onPause(); rec?.cancel() }
    override fun onResume() { super.onResume(); if (on) listen() }
    override fun onDestroy() { rec?.destroy(); tts.shutdown(); super.onDestroy() }
}
