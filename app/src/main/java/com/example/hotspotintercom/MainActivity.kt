package com.example.hotspotintercom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Alpha-Connect, powered by Geykie. Dark neumorphic UI built in code (no layout files, no AppCompat). */
class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var status: TextView
    private lateinit var pttButton: NeuButton
    private lateinit var speakerToggle: NeuToggle
    private lateinit var handsFreeToggle: NeuToggle
    private var pendingHost = false
    private var talking = false
    private var handsFree = false

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    @Suppress("DEPRECATION")
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        window.statusBarColor = Neu.BASE
        window.navigationBarColor = Neu.BASE

        val side = dp(14)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Neu.BASE)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.setPadding(side, dp(12), side, dp(12))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                top = b.top; bottom = b.bottom
            } else {
                top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(side, dp(12) + top, side, dp(12) + bottom)
            insets
        }

        // ---- header: badge + name + "Powered by Geykie"
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = "\u03B1"
            textSize = 26f
            setTextColor(Neu.ACCENT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            applyNeu(40f, padDp = 10f)
        }
        header.addView(badge, LinearLayout.LayoutParams(dp(72), dp(72)))

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "ALPHA-CONNECT"
            textSize = 22f
            setTextColor(Neu.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
        })
        val powered = SpannableString("Powered by Geykie").apply {
            setSpan(ForegroundColorSpan(Neu.ACCENT), 11, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        titleCol.addView(TextView(this).apply {
            text = powered
            textSize = 12f
            setTextColor(Neu.MUTED)
            letterSpacing = 0.08f
        })
        header.addView(titleCol, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(4)
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Turn on your hotspot on one phone and tap HOST.\nOther phones connect to it and tap JOIN."
            textSize = 12f
            setTextColor(Neu.MUTED)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        })

        // ---- Host / Join / Stop
        val hostBtn = NeuButton(this, "HOST", Neu.ACCENT)
        val joinBtn = NeuButton(this, "JOIN", Neu.GREEN)
        val stopBtn = NeuButton(this, "STOP", Neu.RED)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(hostBtn, joinBtn, stopBtn)) {
            buttons.addView(b, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
        root.addView(buttons)

        // ---- host IP field (sunken)
        ipField = EditText(this).apply {
            hint = "Host IP address"
            setText(gatewayIp())
            setSingleLine()
            textSize = 15f
            setTextColor(Neu.TEXT)
            setHintTextColor(Neu.MUTED)
            typeface = medium
            applyNeu(18f, inset = true, hPadDp = 14f, vPadDp = 10f)
        }
        root.addView(ipField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- switches card
        speakerToggle = NeuToggle(this).apply { setState(true) }
        handsFreeToggle = NeuToggle(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            applyNeu(24f, hPadDp = 12f, vPadDp = 6f)
        }
        card.addView(switchRow("Loudspeaker", "Off = earpiece", speakerToggle))
        card.addView(switchRow("Hands-free", "Talk without holding the button", handsFreeToggle))
        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- status (sunken)
        status = TextView(this).apply {
            text = "Idle"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Neu.MUTED)
            typeface = medium
            applyNeu(18f, inset = true, hPadDp = 14f, vPadDp = 8f)
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- big round talk button
        pttButton = NeuButton(this, "HOLD\nTO TALK", Neu.TEXT, 20f, 400f)
        val pttHolder = LinearLayout(this).apply { gravity = Gravity.CENTER }
        pttHolder.addView(pttButton, LinearLayout.LayoutParams(dp(240), dp(240)))
        root.addView(pttHolder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(scroll)

        // ---- actions
        hostBtn.setOnClickListener { requestAndStart(true) }
        joinBtn.setOnClickListener { requestAndStart(false) }
        stopBtn.setOnClickListener {
            startService(Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_STOP))
            setStatus("Stopped")
        }

        speakerToggle.onChange = { on -> IntercomService.engine?.setSpeaker(on) }
        handsFreeToggle.onChange = { on ->
            handsFree = on
            IntercomService.engine?.setHandsFree(on)
            updatePtt()
        }

        pttButton.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.isPressed = true
                    talking = true
                    IntercomService.engine?.setTalking(true)
                    updatePtt()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    talking = false
                    IntercomService.engine?.setTalking(false)
                    updatePtt()
                }
            }
            true
        }
    }

    private fun switchRow(title: String, desc: String, toggle: NeuToggle): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), 0, dp(2))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Neu.TEXT)
            typeface = medium
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Neu.MUTED)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(toggle)
        return row
    }

    private fun updatePtt() {
        when {
            talking -> {
                pttButton.setGlow(Neu.RED)
                pttButton.setTextColor(Neu.RED)
                pttButton.text = "TALKING"
            }
            handsFree -> {
                pttButton.setGlow(Neu.ACCENT)
                pttButton.setTextColor(Neu.ACCENT)
                pttButton.text = "HANDS-FREE\nLIVE"
            }
            else -> {
                pttButton.setGlow(0)
                pttButton.setTextColor(Neu.TEXT)
                pttButton.text = "HOLD\nTO TALK"
            }
        }
    }

    private fun setStatus(msg: String) {
        status.text = msg
        val low = msg.lowercase()
        status.setTextColor(
            when {
                low.contains("error") || low.contains("failed") || low.contains("full") ||
                    low.contains("required") -> Neu.RED
                low.startsWith("joined") || low.startsWith("hosting") -> Neu.GREEN
                else -> Neu.MUTED
            }
        )
    }

    override fun onResume() {
        super.onResume()
        IntercomService.statusListener = { msg -> runOnUiThread { setStatus(msg) } }
    }

    override fun onPause() {
        super.onPause()
        IntercomService.statusListener = null
    }

    private fun requestAndStart(host: Boolean) {
        pendingHost = host
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startIntercom(host) else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val micOk = perms.indices.any {
            perms[it] == Manifest.permission.RECORD_AUDIO && results[it] == PackageManager.PERMISSION_GRANTED
        }
        if (micOk) startIntercom(pendingHost) else setStatus("Microphone permission is required.")
    }

    private fun startIntercom(host: Boolean) {
        val i = Intent(this, IntercomService::class.java)
            .putExtra(IntercomService.EXTRA_HOST, host)
            .putExtra(IntercomService.EXTRA_IP, ipField.text.toString().trim())
            .putExtra(IntercomService.EXTRA_SPEAKER, speakerToggle.checkedState)
            .putExtra(IntercomService.EXTRA_HANDS_FREE, handsFreeToggle.checkedState)
        startForegroundService(i)
    }

    @Suppress("DEPRECATION")
    private fun gatewayIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val gw = wm.dhcpInfo?.gateway ?: 0
        if (gw == 0) return "192.168.43.1"
        return "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
