package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var btnConnectAuto: MaterialButton
    private lateinit var btnDisconnectAll: MaterialButton
    private lateinit var btnEcoStep: MaterialButton
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var tvStatus1: TextView
    private lateinit var tvStatus2: TextView
    private lateinit var tvBatt1: TextView
    private lateinit var tvBatt2: TextView
    private lateinit var tvEst1: TextView
    private lateinit var tvEst2: TextView
    private lateinit var rotaryDial: RotaryThermostatView
    private lateinit var btnBoost: MaterialButton
    private lateinit var tvBoostStatus: TextView
    private lateinit var tvTimerStatus: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F1115")
        setupUI()

        InsoleKeepAliveService.onStateChangedListener = {
            runOnUiThread { syncUIFromService() }
        }

        if (hasBlePermissions()) {
            triggerAutoConnectIfEnabled()
        } else {
            checkPermissions()
        }
        syncUIFromService()
    }

    override fun onResume() {
        super.onResume()
        syncUIFromService()
    }

    override fun onDestroy() {
        super.onDestroy()
        InsoleKeepAliveService.onStateChangedListener = null
    }

    private fun sendServiceCommand(action: String, configure: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, InsoleKeepAliveService::class.java).apply {
            this.action = action
            configure?.invoke(this)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && hasBlePermissions()) {
            triggerAutoConnectIfEnabled()
        }
    }

    private fun isAutoConnectOnStartEnabled(): Boolean {
        return getSharedPreferences("warminsole_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_connect_on_start", true)
    }

    private fun setAutoConnectOnStartEnabled(enabled: Boolean) {
        getSharedPreferences("warminsole_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_connect_on_start", enabled)
            .apply()
    }

    private fun triggerAutoConnectIfEnabled() {
        if (isAutoConnectOnStartEnabled()) {
            handler.postDelayed({
                if (!InsoleKeepAliveService.isConnected1 && !InsoleKeepAliveService.isConnected2 && !InsoleKeepAliveService.isScanning) {
                    sendServiceCommand("CMD_SCAN_BOTH")
                }
            }, 350L)
        }
    }

    private fun requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "Ottimizzazione batteria già disattivata (Non limitata)!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncUIFromService() {
        val temp = InsoleKeepAliveService.currentTemp
        rotaryDial.setTemperature(temp)

        val c1 = InsoleKeepAliveService.isConnected1
        val c2 = InsoleKeepAliveService.isConnected2
        val scanning = InsoleKeepAliveService.isScanning

        if (c1) {
            tvStatus1.text = "● Soletta 1 (SX): Attiva"
            tvStatus1.setTextColor(Color.parseColor("#66BB6A"))
        } else if (InsoleKeepAliveService.connecting1) {
            tvStatus1.text = "● Soletta 1 (SX): Connessione..."
            tvStatus1.setTextColor(Color.parseColor("#FFB74D"))
        } else {
            tvStatus1.text = "● Soletta 1 (SX): Off"
            tvStatus1.setTextColor(Color.parseColor("#8E96A4"))
        }

        if (c2) {
            tvStatus2.text = "● Soletta 2 (DX): Attiva"
            tvStatus2.setTextColor(Color.parseColor("#66BB6A"))
        } else if (InsoleKeepAliveService.connecting2) {
            tvStatus2.text = "● Soletta 2 (DX): Connessione..."
            tvStatus2.setTextColor(Color.parseColor("#FFB74D"))
        } else {
            tvStatus2.text = "● Soletta 2 (DX): Off"
            tvStatus2.setTextColor(Color.parseColor("#8E96A4"))
        }

        val e1 = InsoleKeepAliveService.rawEle1
        if (e1 in 0..9) {
            val pct = InsoleKeepAliveService.eleLevelToPercent(e1)
            val mins = InsoleKeepAliveService.estimateRemainingMinutes(e1, temp)
            tvBatt1.text = "Batteria: $pct% ($e1/9)"
            tvBatt1.setTextColor(if (e1 <= 1) Color.parseColor("#FF5252") else Color.parseColor("#B0B8C4"))
            tvEst1.text = "Stima: ~${InsoleKeepAliveService.formatRemainingTime(mins)} a $temp°"
        } else {
            tvBatt1.text = "Batteria: --%"
            tvBatt1.setTextColor(Color.parseColor("#B0B8C4"))
            tvEst1.text = "Stima: --"
        }

        val e2 = InsoleKeepAliveService.rawEle2
        if (e2 in 0..9) {
            val pct = InsoleKeepAliveService.eleLevelToPercent(e2)
            val mins = InsoleKeepAliveService.estimateRemainingMinutes(e2, temp)
            tvBatt2.text = "Batteria: $pct% ($e2/9)"
            tvBatt2.setTextColor(if (e2 <= 1) Color.parseColor("#FF5252") else Color.parseColor("#B0B8C4"))
            tvEst2.text = "Stima: ~${InsoleKeepAliveService.formatRemainingTime(mins)} a $temp°"
        } else {
            tvBatt2.text = "Batteria: --%"
            tvBatt2.setTextColor(Color.parseColor("#B0B8C4"))
            tvEst2.text = "Stima: --"
        }

        val count = (if (c1) 1 else 0) + (if (c2) 1 else 0)
        if (count >= 2 || !scanning) {
            btnConnectAuto.text = "COLLEGA ENTRAMBE"
            btnConnectAuto.isEnabled = true
        } else {
            btnConnectAuto.text = "RICERCA ($count/2)..."
            btnConnectAuto.isEnabled = false
        }

        if (InsoleKeepAliveService.isEcoStepEnabled) {
            btnEcoStep.text = "🌿 ECO-STEP ATTIVO (-5° sotto 22%)"
            btnEcoStep.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
            btnEcoStep.setTextColor(Color.WHITE)
        } else {
            btnEcoStep.text = "🌿 ECO-STEP: OFF (-5° sotto 22%)"
            btnEcoStep.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
            btnEcoStep.setTextColor(Color.parseColor("#B0B8C4"))
        }

        if (InsoleKeepAliveService.isBoostActive) {
            btnBoost.text = "ANNULLA BOOST"
            btnBoost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#37474F"))
            val m = InsoleKeepAliveService.boostRemainingSeconds / 60
            val s = InsoleKeepAliveService.boostRemainingSeconds % 60
            tvBoostStatus.visibility = View.VISIBLE
            tvBoostStatus.text = String.format("BOOST 60 °C attivo — %02d:%02d al passaggio a 45 °C", m, s)
        } else {
            btnBoost.text = "🔥 BOOST 10m (60° → 45°)"
            btnBoost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
            tvBoostStatus.visibility = View.GONE
        }

        if (InsoleKeepAliveService.isTimerActive) {
            val m = InsoleKeepAliveService.timerRemainingSeconds / 60
            val s = InsoleKeepAliveService.timerRemainingSeconds % 60
            tvTimerStatus.text = String.format("%02d:%02d rimanenti", m, s)
        } else {
            tvTimerStatus.text = "Disattivo"
        }
    }

    private fun setupUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            isFillViewport = true
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 48)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 0, 28)
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleBox.addView(TextView(this).apply {
            text = "WarmInsole"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        titleBox.addView(TextView(this).apply {
            text = "Controllo Termico Pro"
            textSize = 13f
            setTextColor(Color.parseColor("#8E96A4"))
        })

        val btnMenu = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(28, 8, 24, 8)
            setOnClickListener { showOverflowMenu(it) }
        }
        topBar.addView(titleBox)
        topBar.addView(btnMenu)
        mainContainer.addView(topBar)

        // --- CARD CONNESSIONE & BATTERIA ---
        val connCard = createCard()
        val connLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 18)
        }
        autoRow.addView(TextView(this).apply {
            text = "Auto-Connect all'apertura"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D8DEE9"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        switchAutoStart = SwitchMaterial(this).apply {
            isChecked = isAutoConnectOnStartEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                setAutoConnectOnStartEnabled(isChecked)
            }
        }
        autoRow.addView(switchAutoStart)
        connLayout.addView(autoRow)

        val battRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 18)
        }

        val box1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151B"))
            setPadding(24, 20, 24, 20)
        }
        tvStatus1 = TextView(this).apply {
            text = "● Soletta 1 (SX): Off"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
        }
        tvBatt1 = TextView(this).apply {
            text = "Batteria: --%"
            textSize = 12f
            setTextColor(Color.parseColor("#B0B8C4"))
            setPadding(0, 6, 0, 0)
        }
        tvEst1 = TextView(this).apply {
            text = "Stima: --"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 4, 0, 0)
        }
        box1.addView(tvStatus1)
        box1.addView(tvBatt1)
        box1.addView(tvEst1)

        val box2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151B"))
            setPadding(24, 20, 24, 20)
        }
        tvStatus2 = TextView(this).apply {
            text = "● Soletta 2 (DX): Off"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
        }
        tvBatt2 = TextView(this).apply {
            text = "Batteria: --%"
            textSize = 12f
            setTextColor(Color.parseColor("#B0B8C4"))
            setPadding(0, 6, 0, 0)
        }
        tvEst2 = TextView(this).apply {
            text = "Stima: --"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 4, 0, 0)
        }
        box2.addView(tvStatus2)
        box2.addView(tvBatt2)
        box2.addView(tvEst2)

        battRow.addView(box1, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        battRow.addView(box2, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })
        connLayout.addView(battRow)

        btnEcoStep = styleCompactButton("🌿 ECO-STEP: OFF (-5° sotto 22%)", "#252A34", Color.parseColor("#B0B8C4"), 13f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                InsoleKeepAliveService.isEcoStepEnabled = !InsoleKeepAliveService.isEcoStepEnabled
                if (!InsoleKeepAliveService.isEcoStepEnabled) {
                    InsoleKeepAliveService.ecoStepTriggered1 = false
                    InsoleKeepAliveService.ecoStepTriggered2 = false
                }
                syncUIFromService()
            }
        }
        connLayout.addView(btnEcoStep, LinearLayout.LayoutParams(-1, 110).apply { bottomMargin = 18 })

        val btnConnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnConnectAuto = styleCompactButton("COLLEGA ENTRAMBE", "#1976D2", Color.WHITE, 14f, true).apply {
            setOnClickListener { sendServiceCommand("CMD_SCAN_BOTH") }
        }
        btnDisconnectAll = styleCompactButton("SCOLLEGA", "#252A34", Color.parseColor("#B0B8C4"), 13f, false).apply {
            setOnClickListener { sendServiceCommand("CMD_DISCONNECT_ALL") }
        }
        btnConnRow.addView(btnConnectAuto, LinearLayout.LayoutParams(0, 130, 1.6f).apply { rightMargin = 12 })
        btnConnRow.addView(btnDisconnectAll, LinearLayout.LayoutParams(0, 130, 1f))
        connLayout.addView(btnConnRow)

        connCard.addView(connLayout)
        mainContainer.addView(connCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })

        // --- CARD ACCENSIONE ---
        val powerCard = createCard()
        val powerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 24, 28, 24)
            gravity = Gravity.CENTER
        }
        val btnOn = styleCompactButton("ACCENDI (ON)", "#E65100", Color.WHITE, 15f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendServiceCommand("CMD_POWER_ON")
            }
        }
        val btnOff = styleCompactButton("SPEGNI (OFF)", "#2E3440", Color.parseColor("#D8DEE9"), 15f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendServiceCommand("CMD_POWER_OFF")
            }
        }
        powerLayout.addView(btnOn, LinearLayout.LayoutParams(0, 135, 1f).apply { rightMargin = 12 })
        powerLayout.addView(btnOff, LinearLayout.LayoutParams(0, 135, 1f).apply { leftMargin = 12 })
        powerCard.addView(powerLayout)
        mainContainer.addView(powerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })

        // --- CARD GHIERA ROTANTE & BOOST ---
        val dialCard = createCard()
        val dialLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        dialLayout.addView(TextView(this).apply {
            text = "GHIERA TEMPERATURA (30° – 60°)"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })

        rotaryDial = RotaryThermostatView(this).apply {
            onTick = { newTemp ->
                InsoleKeepAliveService.currentTemp = newTemp
                syncUIFromService()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onRelease = { finalTemp ->
                sendServiceCommand("CMD_SET_TEMP") { putExtra("temp", finalTemp) }
            }
        }
        dialLayout.addView(rotaryDial, LinearLayout.LayoutParams(680, 680).apply {
            topMargin = 12
            bottomMargin = 16
        })

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val presets = listOf(40, 48, 57)
        for (p in presets) {
            val btnPreset = styleCompactButton("$p °C", "#252A34", Color.WHITE, 15f, true).apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    sendServiceCommand("CMD_SET_TEMP") { putExtra("temp", p) }
                }
            }
            presetRow.addView(btnPreset, LinearLayout.LayoutParams(0, 120, 1f).apply {
                leftMargin = 8
                rightMargin = 8
            })
        }
        dialLayout.addView(presetRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })

        btnBoost = styleCompactButton("🔥 BOOST 10m (60° → 45°)", "#B71C1C", Color.WHITE, 14f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (InsoleKeepAliveService.isBoostActive) {
                    sendServiceCommand("CMD_CANCEL_BOOST")
                } else {
                    sendServiceCommand("CMD_START_BOOST")
                }
            }
        }
        dialLayout.addView(btnBoost, LinearLayout.LayoutParams(-1, 125).apply {
            leftMargin = 8
            rightMargin = 8
        })

        tvBoostStatus = TextView(this).apply {
            text = ""
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF8A65"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, 10, 0, 0)
        }
        dialLayout.addView(tvBoostStatus)

        dialCard.addView(dialLayout)
        mainContainer.addView(dialCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })

        // --- CARD TIMER SPEGNIMENTO ---
        val timerCard = createCard()
        val timerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
        }

        val timerHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 18)
        }
        timerHeaderRow.addView(TextView(this).apply {
            text = "TIMER AUTO-OFF"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        tvTimerStatus = TextView(this).apply {
            text = "Disattivo"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFB74D"))
        }
        timerHeaderRow.addView(tvTimerStatus)
        timerLayout.addView(timerHeaderRow)

        val timerBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val timers = listOf(30 to "30m", 60 to "60m", 90 to "90m", 0 to "OFF")
        for ((mins, label) in timers) {
            val btnT = styleCompactButton(label, "#252A34", Color.WHITE, 14f, true).apply {
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    sendServiceCommand("CMD_SET_TIMER") { putExtra("minutes", mins) }
                }
            }
            timerBtnsRow.addView(btnT, LinearLayout.LayoutParams(0, 115, 1f).apply {
                leftMargin = 6
                rightMargin = 6
            })
        }
        timerLayout.addView(timerBtnsRow, LinearLayout.LayoutParams(-1, -2))
        timerCard.addView(timerLayout)
        mainContainer.addView(timerCard)

        root.addView(mainContainer)
        setContentView(root)
    }

    private fun styleCompactButton(
        label: String,
        bgHex: String,
        textColor: Int,
        sizeSp: Float,
        bold: Boolean
    ): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            textSize = sizeSp
            if (bold) typeface = Typeface.DEFAULT_BOLD
            cornerRadius = 22
            insetTop = 0
            insetBottom = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(8, 0, 8, 0)
            gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgHex))
            setTextColor(textColor)
        }
    }

    private fun createCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = 34f
            cardElevation = 6f
            strokeWidth = 2
            strokeColor = Color.parseColor("#252A34")
            setCardBackgroundColor(Color.parseColor("#181B22"))
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Mantieni attiva (Batteria)")
        popup.menu.add(0, 2, 1, "About Us")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    requestBatteryUnrestricted()
                    true
                }
                2 -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Us — WarmInsole")
            .setMessage(
                "Imran Dzaferi ha creato questa bellissima app per offrire un controllo Bluetooth rapido, preciso e affidabile delle solette riscaldate.\n\n" +
                "Dotata di Widget 4x2 trasparente stile vetro, Auto-Connect all'apertura, stima dinamica dell'autonomia residua, Eco-Step automatico, modalità Boost 60°→45° e ghiera termica aptica.\n\n" +
                "Sviluppatore: Imran Dzaferi\n" +
                "Versione: 3.3 Pro"
            )
            .setPositiveButton("Chiudi", null)
            .show()
    }

    class RotaryThermostatView(context: Context) : View(context) {
        private val minTemp = 30
        private val maxTemp = 60
        private var temp = 40

        var onTick: ((Int) -> Unit)? = null
        var onRelease: ((Int) -> Unit)? = null

        private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 34f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#252A34")
        }
        private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 34f
            strokeCap = Paint.Cap.ROUND
        }
        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#13161C")
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(12f, 0f, 4f, Color.BLACK)
        }
        private val textTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 110f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        private val textSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 30f
            color = Color.parseColor("#8E96A4")
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isHapticFeedbackEnabled = true
        }

        fun setTemperature(newTemp: Int) {
            val clamped = newTemp.coerceIn(minTemp, maxTemp)
            if (temp != clamped) {
                temp = clamped
                invalidate()
            }
        }

        private fun getAccentColor(): Int {
            return when {
                temp < 40 -> Color.parseColor("#29B6F6")
                temp < 50 -> Color.parseColor("#FF9100")
                else -> Color.parseColor("#FF3D00")
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (min(width, height) / 2f) - 55f
            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            canvas.drawCircle(cx, cy, radius - 35f, innerCirclePaint)

            val startAngle = 135f
            val sweepTotal = 270f
            val steps = maxTemp - minTemp
            val accent = getAccentColor()

            for (i in 0..steps) {
                val angleDeg = startAngle + (i.toFloat() / steps) * sweepTotal
                val rad = Math.toRadians(angleDeg.toDouble())
                val isReached = (minTemp + i) <= temp
                tickPaint.color = if (isReached) accent else Color.parseColor("#353C4A")
                val rIn = radius - 68f
                val rOut = if (i % 5 == 0) radius - 45f else radius - 53f
                canvas.drawLine(
                    (cx + cos(rad) * rIn).toFloat(),
                    (cy + sin(rad) * rIn).toFloat(),
                    (cx + cos(rad) * rOut).toFloat(),
                    (cy + sin(rad) * rOut).toFloat(),
                    tickPaint
                )
            }

            canvas.drawArc(rect, startAngle, sweepTotal, false, bgRingPaint)
            val progress = (temp - minTemp).toFloat() / steps
            val currentSweep = progress * sweepTotal
            activeRingPaint.color = accent
            canvas.drawArc(rect, startAngle, currentSweep, false, activeRingPaint)

            val knobRad = Math.toRadians((startAngle + currentSweep).toDouble())
            val kx = (cx + cos(knobRad) * radius).toFloat()
            val ky = (cy + sin(knobRad) * radius).toFloat()
            canvas.drawCircle(kx, ky, 24f, knobPaint)

            textTempPaint.color = accent
            canvas.drawText("$temp°", cx, cy + 32f, textTempPaint)
            canvas.drawText("RUOTA PER REGOLARE", cx, cy + 82f, textSubPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val cx = width / 2f
            val cy = height / 2f
            val dx = event.x - cx
            val dy = event.y - cy

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (angle < 0) angle += 360f
                    var rel = angle - 135f
                    if (rel < 0) rel += 360f
                    val clamped = when {
                        rel <= 270f -> rel
                        rel < 315f -> 270f
                        else -> 0f
                    }
                    val newTemp = minTemp + ((clamped / 270f) * (maxTemp - minTemp)).roundToInt()
                    if (newTemp != temp) {
                        temp = newTemp
                        invalidate()
                        onTick?.invoke(temp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onRelease?.invoke(temp)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
