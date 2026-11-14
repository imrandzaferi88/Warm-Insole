package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.*
import kotlin.math.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val LOW_BATT_CHANNEL_ID = "warminsole_low_battery_channel"

    private var gatt1: BluetoothGatt? = null
    private var gatt2: BluetoothGatt? = null
    private var dev1: BluetoothDevice? = null
    private var dev2: BluetoothDevice? = null
    private var connecting1 = false
    private var connecting2 = false
    private var manualDisconnect = false

    private var lowBattAlerted1 = false
    private var lowBattAlerted2 = false

    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var isScanning = false

    private lateinit var btnConnectAuto: MaterialButton
    private lateinit var btnDisconnectAll: MaterialButton
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var tvStatus1: TextView
    private lateinit var tvStatus2: TextView
    private lateinit var tvBatt1: TextView
    private lateinit var tvBatt2: TextView
    private lateinit var rotaryDial: RotaryThermostatView
    private lateinit var btnBoost: MaterialButton
    private lateinit var tvBoostStatus: TextView
    private lateinit var tvTimerStatus: TextView

    // Nessuna memoria temperatura: parte sempre da 40°
    private var currentTemp = 40
    private val handler = Handler(Looper.getMainLooper())

    private var timerRunnable: Runnable? = null
    private var remainingSeconds = 0

    private var boostRunnable: Runnable? = null
    private var boostRemainingSeconds = 0
    private var isBoostActive = false

    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            gatt1?.let { queryBattery(it, 1) }
            handler.postDelayed({ gatt2?.let { queryBattery(it, 2) } }, 400)
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F1115")
        createLowBatteryChannel()
        setupUI()
        handler.postDelayed(batteryPollRunnable, 3500)

        if (hasBlePermissions()) {
            triggerAutoConnectIfEnabled()
        } else {
            checkPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        cancelBoost(updateUi = false)
        handler.removeCallbacks(batteryPollRunnable)
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
                if (!manualDisconnect && gatt1 == null && gatt2 == null && !isScanning) {
                    autoConnectBoth()
                }
            }, 400)
        }
    }

    private fun createLowBatteryChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOW_BATT_CHANNEL_ID,
                "Avviso Batteria Scarica Solette",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avvisa quando una soletta raggiunge l'ultima tacchetta di batteria"
                enableVibration(true)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun triggerLowBatteryAlert(slot: Int, rawEle: Int) {
        val alreadyAlerted = if (slot == 1) lowBattAlerted1 else lowBattAlerted2
        if (alreadyAlerted) return

        if (slot == 1) lowBattAlerted1 = true else lowBattAlerted2 = true

        val sideName = if (slot == 1) "Soletta 1 (SX)" else "Soletta 2 (DX)"
        val msg = "$sideName all'ultima tacchetta ($rawEle/9)! Prepara il cambio batteria."

        // Vibrazione telefono
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        } catch (_: Exception) {
        }

        // Notifica sonora/visiva
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(this, LOW_BATT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Batteria Scarica — $sideName")
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(200 + slot, notification)
        }

        Toast.makeText(this, "⚠️ $msg", Toast.LENGTH_LONG).show()
    }

    private fun startKeepAliveService(status: String) {
        val intent = Intent(this, InsoleKeepAliveService::class.java).apply {
            putExtra("status", status)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopKeepAliveService() {
        stopService(Intent(this, InsoleKeepAliveService::class.java))
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

    private fun eleLevelToPercent(raw: Int): Int {
        return when (raw) {
            9 -> 100
            8 -> 89
            7 -> 78
            6 -> 67
            5 -> 56
            4 -> 44
            3 -> 33
            2 -> 22
            1 -> 11
            0 -> 0
            in 10..100 -> raw
            else -> -1
        }
    }

    private fun updateBatteryUI(slot: Int, rawEle: Int) {
        val pct = eleLevelToPercent(rawEle)
        if (pct in 0..100) {
            runOnUiThread {
                val label = "Batteria: $pct% (${rawEle.coerceIn(0, 9)}/9)"
                if (slot == 1) {
                    tvBatt1.text = label
                    tvBatt1.setTextColor(if (rawEle <= 1) Color.parseColor("#FF5252") else Color.parseColor("#B0B8C4"))
                } else {
                    tvBatt2.text = label
                    tvBatt2.setTextColor(if (rawEle <= 1) Color.parseColor("#FF5252") else Color.parseColor("#B0B8C4"))
                }
                // Ultima tacchetta: livello <= 1 (1/9 o 0/9)
                if (rawEle in 0..1) {
                    triggerLowBatteryAlert(slot, rawEle)
                } else if (rawEle >= 3) {
                    if (slot == 1) lowBattAlerted1 = false else lowBattAlerted2 = false
                }
            }
        }
    }

    private fun updateScanButtonState() {
        runOnUiThread {
            val connectedCount = (if (gatt1 != null) 1 else 0) + (if (gatt2 != null) 1 else 0)
            if (connectedCount >= 2) {
                stopBleScan()
                btnConnectAuto.text = "COLLEGA ENTRAMBE"
                btnConnectAuto.isEnabled = true
            } else if (isScanning) {
                btnConnectAuto.text = "RICERCA ($connectedCount/2)..."
                btnConnectAuto.isEnabled = false
            } else {
                btnConnectAuto.text = "COLLEGA ENTRAMBE"
                btnConnectAuto.isEnabled = true
            }
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

        // --- TOP BAR ---
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
            setPadding(0, 0, 0, 24)
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
        box1.addView(tvStatus1)
        box1.addView(tvBatt1)

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
        box2.addView(tvStatus2)
        box2.addView(tvBatt2)

        battRow.addView(box1, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 10 })
        battRow.addView(box2, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 })
        connLayout.addView(battRow)

        val btnConnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnConnectAuto = styleCompactButton("COLLEGA ENTRAMBE", "#1976D2", Color.WHITE, 14f, true).apply {
            setOnClickListener { autoConnectBoth() }
        }
        btnDisconnectAll = styleCompactButton("SCOLLEGA", "#252A34", Color.parseColor("#B0B8C4"), 13f, false).apply {
            setOnClickListener { disconnectAll() }
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
                sendPower(true)
            }
        }
        val btnOff = styleCompactButton("SPEGNI (OFF)", "#2E3440", Color.parseColor("#D8DEE9"), 15f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                cancelBoost(updateUi = true)
                sendPower(false)
            }
        }
        powerLayout.addView(btnOn, LinearLayout.LayoutParams(0, 135, 1f).apply { rightMargin = 12 })
        powerLayout.addView(btnOff, LinearLayout.LayoutParams(0, 135, 1f).apply { leftMargin = 12 })
        powerCard.addView(powerLayout)
        mainContainer.addView(powerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })

        // --- CARD GHIERA ROTANTE & MODALITÀ BOOST ---
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
                if (isBoostActive) cancelBoost(updateUi = true)
                currentTemp = newTemp
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onRelease = { finalTemp ->
                currentTemp = finalTemp
                sendTemperature(finalTemp)
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
                    if (isBoostActive) cancelBoost(updateUi = true)
                    currentTemp = p
                    rotaryDial.setTemperature(p)
                    sendTemperature(p)
                }
            }
            presetRow.addView(btnPreset, LinearLayout.LayoutParams(0, 120, 1f).apply {
                leftMargin = 8
                rightMargin = 8
            })
        }
        dialLayout.addView(presetRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })

        // Pulsante Modalità Boost (60° per 10 minuti -> poi 45°)
        btnBoost = styleCompactButton("🔥 BOOST 10m (60° → 45°)", "#B71C1C", Color.WHITE, 14f, true).apply {
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (isBoostActive) {
                    cancelBoost(updateUi = true)
                } else {
                    startBoostMode()
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
                    setAutoOffTimer(mins)
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

    private fun startBoostMode() {
        cancelBoost(updateUi = false)
        isBoostActive = true
        boostRemainingSeconds = 10 * 60 // 10 minuti esatti

        currentTemp = 60
        rotaryDial.setTemperature(60)
        sendPower(true)
        handler.postDelayed({ sendTemperature(60) }, 250)

        btnBoost.text = "ANNULLA BOOST"
        btnBoost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#37474F"))
        tvBoostStatus.visibility = View.VISIBLE

        boostRunnable = object : Runnable {
            override fun run() {
                if (!isBoostActive) return
                if (boostRemainingSeconds <= 0) {
                    isBoostActive = false
                    btnBoost.text = "🔥 BOOST 10m (60° → 45°)"
                    btnBoost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                    tvBoostStatus.text = "Boost completato: assestato a 45 °C"
                    currentTemp = 45
                    rotaryDial.setTemperature(45)
                    sendTemperature(45)
                } else {
                    val m = boostRemainingSeconds / 60
                    val s = boostRemainingSeconds % 60
                    tvBoostStatus.text = String.format("BOOST 60 °C attivo — %02d:%02d al passaggio a 45 °C", m, s)
                    boostRemainingSeconds--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(boostRunnable!!)
    }

    private fun cancelBoost(updateUi: Boolean) {
        boostRunnable?.let { handler.removeCallbacks(it) }
        boostRunnable = null
        isBoostActive = false
        boostRemainingSeconds = 0
        if (updateUi && ::btnBoost.isInitialized) {
            btnBoost.text = "🔥 BOOST 10m (60° → 45°)"
            btnBoost.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
            tvBoostStatus.visibility = View.GONE
        }
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

    private fun setAutoOffTimer(minutes: Int) {
        timerRunnable?.let { handler.removeCallbacks(it) }
        if (minutes <= 0) {
            remainingSeconds = 0
            tvTimerStatus.text = "Disattivo"
            return
        }
        remainingSeconds = minutes * 60
        timerRunnable = object : Runnable {
            override fun run() {
                if (remainingSeconds <= 0) {
                    tvTimerStatus.text = "Spente!"
                    cancelBoost(updateUi = true)
                    sendPower(false)
                } else {
                    val m = remainingSeconds / 60
                    val s = remainingSeconds % 60
                    tvTimerStatus.text = String.format("%02d:%02d rimanenti", m, s)
                    remainingSeconds--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(timerRunnable!!)
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
                "Dotata di Auto-Connect all'apertura, aggancio continuo per entrambe le solette, modalità Boost 60°→45°, allerta batteria ultima tacchetta, servizio background permanente e ghiera termica aptica.\n\n" +
                "Sviluppatore: Imran Dzaferi\n" +
                "Versione: 3.0 Pro"
            )
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun stopBleScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        activeScanCallback?.let {
            try {
                adapter?.bluetoothLeScanner?.stopScan(it)
            } catch (_: Exception) {
            }
        }
        activeScanCallback = null
        isScanning = false
    }

    private fun autoConnectBoth() {
        if (gatt1 != null && gatt2 != null) {
            Toast.makeText(this, "Entrambe le solette sono già collegate!", Toast.LENGTH_SHORT).show()
            return
        }

        manualDisconnect = false
        stopBleScan()

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return

        isScanning = true
        updateScanButtonState()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: ""
                if (!name.contains("XINKE", ignoreCase = true)) return

                if (dev1?.address == dev.address && (gatt1 != null || connecting1)) return
                if (dev2?.address == dev.address && (gatt2 != null || connecting2)) return

                val rawBytes = result.scanRecord?.bytes
                val sideByte = if (rawBytes != null && rawBytes.size > 13) rawBytes[13].toInt() and 0xFF else 0
                val scanEle = if (rawBytes != null && rawBytes.size > 15) rawBytes[15].toInt() and 0xFF else -1

                val slot1Free = (gatt1 == null && !connecting1)
                val slot2Free = (gatt2 == null && !connecting2)

                val targetSlot = when {
                    sideByte == 1 && slot1Free -> 1
                    sideByte == 2 && slot2Free -> 2
                    dev1?.address == dev.address && slot1Free -> 1
                    dev2?.address == dev.address && slot2Free -> 2
                    slot1Free && dev2?.address != dev.address -> 1
                    slot2Free && dev1?.address != dev.address -> 2
                    else -> 0
                }

                if (targetSlot == 1) {
                    dev1 = dev
                    connecting1 = true
                    if (scanEle in 0..9) updateBatteryUI(1, scanEle)
                    tvStatus1.text = "● Soletta 1 (SX): Connessione..."
                    tvStatus1.setTextColor(Color.parseColor("#FFB74D"))
                    val delay = if (connecting2) 450L else 0L
                    handler.postDelayed({ connectSlot(dev, slot = 1) }, delay)
                } else if (targetSlot == 2) {
                    dev2 = dev
                    connecting2 = true
                    if (scanEle in 0..9) updateBatteryUI(2, scanEle)
                    tvStatus2.text = "● Soletta 2 (DX): Connessione..."
                    tvStatus2.setTextColor(Color.parseColor("#FFB74D"))
                    val delay = if (connecting1 || gatt1 != null) 450L else 0L
                    handler.postDelayed({ connectSlot(dev, slot = 2) }, delay)
                }
            }
        }

        activeScanCallback = callback
        scanner.startScan(null, settings, callback)

        scanTimeoutRunnable = Runnable {
            if (isScanning) {
                stopBleScan()
                updateScanButtonState()
                val connectedCount = (if (gatt1 != null) 1 else 0) + (if (gatt2 != null) 1 else 0)
                if (connectedCount < 2) {
                    Toast.makeText(
                        this,
                        "Collegate $connectedCount su 2. Controlla che entrambe siano accese.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, 30000L)
    }

    private fun disconnectAll() {
        manualDisconnect = true
        stopBleScan()
        cancelBoost(updateUi = true)
        stopKeepAliveService()

        lowBattAlerted1 = false
        lowBattAlerted2 = false

        connecting1 = false
        gatt1?.disconnect()
        gatt1?.close()
        gatt1 = null
        dev1 = null

        connecting2 = false
        gatt2?.disconnect()
        gatt2?.close()
        gatt2 = null
        dev2 = null

        tvStatus1.text = "● Soletta 1 (SX): Off"
        tvStatus1.setTextColor(Color.parseColor("#8E96A4"))
        tvBatt1.text = "Batteria: --%"
        tvBatt1.setTextColor(Color.parseColor("#B0B8C4"))

        tvStatus2.text = "● Soletta 2 (DX): Off"
        tvStatus2.setTextColor(Color.parseColor("#8E96A4"))
        tvBatt2.text = "Batteria: --%"
        tvBatt2.setTextColor(Color.parseColor("#B0B8C4"))

        updateScanButtonState()
    }

    private fun connectSlot(device: BluetoothDevice, slot: Int) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnUiThread {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                        startKeepAliveService("Solette collegate — Temperatura: $currentTemp °C")
                        if (slot == 1) {
                            connecting1 = false
                            gatt1 = gatt
                            tvStatus1.text = "● Soletta 1 (SX): Attiva"
                            tvStatus1.setTextColor(Color.parseColor("#66BB6A"))
                        } else {
                            connecting2 = false
                            gatt2 = gatt
                            tvStatus2.text = "● Soletta 2 (DX): Attiva"
                            tvStatus2.setTextColor(Color.parseColor("#66BB6A"))
                        }
                        updateScanButtonState()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                        if (slot == 1) {
                            connecting1 = false
                            gatt1 = null
                            tvStatus1.text = "● Soletta 1 (SX): Off"
                            tvStatus1.setTextColor(Color.parseColor("#8E96A4"))
                            if (!manualDisconnect) {
                                handler.postDelayed({
                                    if (!manualDisconnect && gatt1 == null && !connecting1) {
                                        dev1?.let {
                                            connecting1 = true
                                            connectSlot(it, 1)
                                        }
                                    }
                                }, 1800)
                            }
                        } else {
                            connecting2 = false
                            gatt2 = null
                            tvStatus2.text = "● Soletta 2 (DX): Off"
                            tvStatus2.setTextColor(Color.parseColor("#8E96A4"))
                            if (!manualDisconnect) {
                                handler.postDelayed({
                                    if (!manualDisconnect && gatt2 == null && !connecting2) {
                                        dev2?.let {
                                            connecting2 = true
                                            connectSlot(it, 2)
                                        }
                                    }
                                }, 1800)
                            }
                        }
                        updateScanButtonState()
                        if (gatt1 == null && gatt2 == null && manualDisconnect) {
                            stopKeepAliveService()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                enableNotifyFFF4(gatt)
                handler.postDelayed({
                    writeBytes(gatt, byteArrayOf(0xBC.toByte(), 0xC1.toByte(), 0x05, 0xC1.toByte(), 0xBB.toByte()))
                }, 300)
                handler.postDelayed({ queryBattery(gatt, slot) }, 750)
            }

            private fun handleIncomingBytes(data: ByteArray) {
                if (data.isEmpty()) return
                if (data.size >= 15 && (data[1].toInt() and 0xFF) == 1 && (data[3].toInt() and 0xFF) == 3) {
                    val rawEle = data[13].toInt() and 0xFF
                    val sideSlot = if ((data[4].toInt() and 0xFF) == 2) 2 else slot
                    updateBatteryUI(sideSlot, rawEle)
                    return
                }
                if ((data[0].toInt() and 0xFF) == 0xBC && data.size >= 6) {
                    val candidate = data[data.size - 2].toInt() and 0xFF
                    if (candidate in 1..9) {
                        updateBatteryUI(slot, candidate)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleIncomingBytes(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                characteristic.value?.let { handleIncomingBytes(it) }
            }
        }
        device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun enableNotifyFFF4(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val notifyChar = service.getCharacteristic(NOTIFY_UUID) ?: return
        gatt.setCharacteristicNotification(notifyChar, true)
        val desc = notifyChar.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }
    }

    private fun queryBattery(gatt: BluetoothGatt, slot: Int) {
        val sideByte: Byte = if (slot == 2) 0x02 else 0x01
        writeBytes(gatt, byteArrayOf(0xBC.toByte(), 0xCC.toByte(), 0x07, 0x03, sideByte, 0xCC.toByte(), 0xBB.toByte()))
    }

    private fun sendPower(on: Boolean) {
        val state: Byte = if (on) 0x01 else 0x00
        if (gatt1 != null || gatt2 != null) {
            startKeepAliveService(if (on) "Riscaldamento ON — $currentTemp °C" else "Solette collegate — Standby (OFF)")
        }
        gatt1?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        handler.postDelayed({
            gatt2?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        }, 150)
    }

    private fun sendTemperature(temp: Int) {
        val t = temp.toByte()
        if (gatt1 != null || gatt2 != null) {
            startKeepAliveService("Solette attive — Temperatura: $temp °C")
        }
        gatt1?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
        handler.postDelayed({
            gatt2?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
        }, 150)
    }

    private fun writeBytes(gatt: BluetoothGatt, data: ByteArray) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(WRITE_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    // --- COMPONENTE GHIERA TERMOSTATO ROTANTE ---
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
            temp = newTemp.coerceIn(minTemp, maxTemp)
            invalidate()
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
