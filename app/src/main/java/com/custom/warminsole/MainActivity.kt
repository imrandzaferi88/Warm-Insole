package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import java.util.*
import kotlin.math.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt1: BluetoothGatt? = null
    private var gatt2: BluetoothGatt? = null
    private var dev1: BluetoothDevice? = null
    private var dev2: BluetoothDevice? = null
    private var manualDisconnect = false

    private lateinit var btnConnectAuto: MaterialButton
    private lateinit var btnDisconnectAll: MaterialButton
    private lateinit var tvStatus1: TextView
    private lateinit var tvStatus2: TextView
    private lateinit var tvBatt1: TextView
    private lateinit var tvBatt2: TextView
    private lateinit var rotaryDial: RotaryThermostatView
    private lateinit var tvTimerStatus: TextView

    private var currentTemp = 40
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var remainingSeconds = 0

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
        checkPermissions()
        setupUI()
        handler.postDelayed(batteryPollRunnable, 3500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(batteryPollRunnable)
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
        // Scala originale XINKE/WarmLife: 0..9 -> 0..100%
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
                if (slot == 1) tvBatt1.text = label else tvBatt2.text = label
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
            setPadding(32, 28, 32, 32)
        }

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
                sendPower(false)
            }
        }
        powerLayout.addView(btnOn, LinearLayout.LayoutParams(0, 135, 1f).apply { rightMargin = 12 })
        powerLayout.addView(btnOff, LinearLayout.LayoutParams(0, 135, 1f).apply { leftMargin = 12 })
        powerCard.addView(powerLayout)
        mainContainer.addView(powerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 24 })

        // --- CARD GHIERA ROTANTE ---
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
        dialLayout.addView(presetRow, LinearLayout.LayoutParams(-1, -2))

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
                "Dotata di connessione automatica istantanea, servizio background permanente, ghiera termica aptica, telemetria batteria nativa e timer intelligente.\n\n" +
                "Sviluppatore: Imran Dzaferi\n" +
                "Versione: 2.4 Pro"
            )
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun autoConnectBoth() {
        manualDisconnect = false
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return
        val found = mutableListOf<BluetoothDevice>()

        btnConnectAuto.text = "RICERCA..."
        btnConnectAuto.isEnabled = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: ""
                if (name.contains("XINKE", ignoreCase = true) && found.none { it.address == dev.address }) {
                    found.add(dev)
                    val rawBytes = result.scanRecord?.bytes
                    // Dal protocollo WarmLife: scanRecord[13] = lato (1=SX, 2=DX), scanRecord[15] = batteria (0..9)
                    val side = if (rawBytes != null && rawBytes.size > 13 && rawBytes[13].toInt() == 2) 2 else 1
                    val scanEle = if (rawBytes != null && rawBytes.size > 15) rawBytes[15].toInt() and 0xFF else -1

                    val targetSlot = when {
                        side == 1 && gatt1 == null && dev1 == null -> 1
                        side == 2 && gatt2 == null && dev2 == null -> 2
                        gatt1 == null && dev1 == null -> 1
                        gatt2 == null && dev2 == null -> 2
                        else -> 0
                    }

                    if (targetSlot == 1) {
                        dev1 = dev
                        if (scanEle in 0..9) updateBatteryUI(1, scanEle)
                        connectSlot(dev, slot = 1)
                    } else if (targetSlot == 2) {
                        dev2 = dev
                        if (scanEle in 0..9) updateBatteryUI(2, scanEle)
                        connectSlot(dev, slot = 2)
                    }
                }
            }
        }

        scanner.startScan(callback)
        handler.postDelayed({
            scanner.stopScan(callback)
            btnConnectAuto.text = "COLLEGA ENTRAMBE"
            btnConnectAuto.isEnabled = true
            if (found.isEmpty()) {
                Toast.makeText(this, "Nessuna soletta trovata. Accendile prima.", Toast.LENGTH_SHORT).show()
            }
        }, 3500)
    }

    private fun disconnectAll() {
        manualDisconnect = true
        stopKeepAliveService()

        gatt1?.disconnect()
        gatt1?.close()
        gatt1 = null
        dev1 = null

        gatt2?.disconnect()
        gatt2?.close()
        gatt2 = null
        dev2 = null

        tvStatus1.text = "● Soletta 1 (SX): Off"
        tvStatus1.setTextColor(Color.parseColor("#8E96A4"))
        tvBatt1.text = "Batteria: --%"

        tvStatus2.text = "● Soletta 2 (DX): Off"
        tvStatus2.setTextColor(Color.parseColor("#8E96A4"))
        tvBatt2.text = "Batteria: --%"
    }

    private fun connectSlot(device: BluetoothDevice, slot: Int) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnUiThread {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                        startKeepAliveService("Solette collegate — Temperatura: $currentTemp °C")
                        if (slot == 1) {
                            gatt1 = gatt
                            tvStatus1.text = "● Soletta 1 (SX): Attiva"
                            tvStatus1.setTextColor(Color.parseColor("#66BB6A"))
                        } else {
                            gatt2 = gatt
                            tvStatus2.text = "● Soletta 2 (DX): Attiva"
                            tvStatus2.setTextColor(Color.parseColor("#66BB6A"))
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (slot == 1) {
                            gatt1 = null
                            tvStatus1.text = "● Soletta 1 (SX): Off"
                            tvStatus1.setTextColor(Color.parseColor("#8E96A4"))
                            if (!manualDisconnect) handler.postDelayed({ dev1?.let { connectSlot(it, 1) } }, 2000)
                        } else {
                            gatt2 = null
                            tvStatus2.text = "● Soletta 2 (DX): Off"
                            tvStatus2.setTextColor(Color.parseColor("#8E96A4"))
                            if (!manualDisconnect) handler.postDelayed({ dev2?.let { connectSlot(it, 2) } }, 2000)
                        }
                        if (gatt1 == null && gatt2 == null && manualDisconnect) {
                            stopKeepAliveService()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // 1. Abilita notifica su 0000FFF4 (canale esatto di WarmLife)
                enableNotifyFFF4(gatt)
                // 2. Handshake iniziale BC C1 05 C1 BB
                handler.postDelayed({
                    writeBytes(gatt, byteArrayOf(0xBC.toByte(), 0xC1.toByte(), 0x05, 0xC1.toByte(), 0xBB.toByte()))
                }, 300)
                // 3. Query telemetria esatta WarmLife (BC CC 07 03 <side> CC BB)
                handler.postDelayed({ queryBattery(gatt, slot) }, 750)
            }

            private fun handleIncomingBytes(data: ByteArray) {
                if (data.isEmpty()) return
                // Pacchetto telemetria originale WarmLife: length >= 15, data[1] == 1, data[3] == 3, data[13] = batteria (0..9)
                if (data.size >= 15 && (data[1].toInt() and 0xFF) == 1 && (data[3].toInt() and 0xFF) == 3) {
                    val rawEle = data[13].toInt() and 0xFF
                    val sideSlot = if ((data[4].toInt() and 0xFF) == 2) 2 else slot
                    updateBatteryUI(sideSlot, rawEle)
                    return
                }
                // Fallback nel caso di risposta compatta con valore 1..9 o 10..100
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
        // Comando originale WarmLife sendGetMes: BC CC 07 03 <1=SX / 2=DX> CC BB
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
