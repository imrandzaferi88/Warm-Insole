package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class InsoleKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "warminsole_keepalive_channel"
        const val LOW_BATT_CHANNEL_ID = "warminsole_low_battery_channel"
        const val NOTIF_ID = 101

        val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FFF4-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        var gatt1: BluetoothGatt? = null
        var gatt2: BluetoothGatt? = null
        var dev1: BluetoothDevice? = null
        var dev2: BluetoothDevice? = null
        var connecting1 = false
        var connecting2 = false
        var manualDisconnect = false
        var isScanning = false

        val isConnected1: Boolean get() = gatt1 != null
        val isConnected2: Boolean get() = gatt2 != null

        var currentTemp = 40
        var isHeatingOn = false
        var rawEle1 = -1
        var rawEle2 = -1

        var isEcoStepEnabled = false
        var ecoStepTriggered1 = false
        var ecoStepTriggered2 = false
        var lowBattAlerted1 = false
        var lowBattAlerted2 = false

        var isBoostActive = false
        var boostRemainingSeconds = 0

        var timerRemainingSeconds = 0
        var isTimerActive = false

        var onStateChangedListener: (() -> Unit)? = null

        fun eleLevelToPercent(raw: Int): Int {
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

        fun estimateRemainingMinutes(rawEle: Int, tempCelsius: Int): Int {
            val pct = eleLevelToPercent(rawEle)
            if (pct <= 0) return 0
            val clampedTemp = tempCelsius.coerceIn(30, 60)
            val ratio = (clampedTemp - 30).toDouble() / 30.0
            val fullChargeMinutes = 780.0 - ratio * (780.0 - 270.0)
            return (fullChargeMinutes * (pct / 100.0)).roundToInt()
        }

        fun formatRemainingTime(minutes: Int): String {
            if (minutes <= 0) return "<15m"
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) String.format("%dh%02dm", h, m) else String.format("%dm", m)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var boostRunnable: Runnable? = null
    private var timerRunnable: Runnable? = null

    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            gatt1?.let { queryBattery(it, 1) }
            handler.postDelayed({ gatt2?.let { queryBattery(it, 2) } }, 400)
            WarmInsoleWidgetProvider.updateAllWidgets(this@InsoleKeepAliveService)
            onStateChangedListener?.invoke()
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildNotification("WarmInsole pronto"))
        handler.postDelayed(batteryPollRunnable, 2000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        handler.removeCallbacks(batteryPollRunnable)
        boostRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(currentStatusText()))

        when (intent?.action) {
            WarmInsoleWidgetProvider.ACTION_CONNECT -> {
                autoConnectBoth()
            }
            WarmInsoleWidgetProvider.ACTION_ON -> {
                cancelBoost()
                currentTemp = 40
                sendPower(true)
                handler.postDelayed({ sendTemperature(40) }, 220)
            }
            WarmInsoleWidgetProvider.ACTION_LOW -> {
                cancelBoost()
                currentTemp = 40
                if (!isHeatingOn) {
                    sendPower(true)
                    handler.postDelayed({ sendTemperature(40) }, 220)
                } else {
                    sendTemperature(40)
                }
            }
            WarmInsoleWidgetProvider.ACTION_MED -> {
                cancelBoost()
                currentTemp = 48
                if (!isHeatingOn) {
                    sendPower(true)
                    handler.postDelayed({ sendTemperature(48) }, 220)
                } else {
                    sendTemperature(48)
                }
            }
            WarmInsoleWidgetProvider.ACTION_MAX -> {
                cancelBoost()
                currentTemp = 57
                if (!isHeatingOn) {
                    sendPower(true)
                    handler.postDelayed({ sendTemperature(57) }, 220)
                } else {
                    sendTemperature(57)
                }
            }
            WarmInsoleWidgetProvider.ACTION_OFF -> {
                cancelBoost()
                sendPower(false)
            }
            "CMD_SCAN_BOTH" -> autoConnectBoth()
            "CMD_DISCONNECT_ALL" -> disconnectAll()
            "CMD_POWER_ON" -> sendPower(true)
            "CMD_POWER_OFF" -> {
                cancelBoost()
                sendPower(false)
            }
            "CMD_SET_TEMP" -> {
                val t = intent.getIntExtra("temp", 40)
                cancelBoost()
                currentTemp = t
                sendTemperature(t)
            }
            "CMD_START_BOOST" -> startBoostMode()
            "CMD_CANCEL_BOOST" -> cancelBoost()
            "CMD_SET_TIMER" -> {
                val mins = intent.getIntExtra("minutes", 0)
                setAutoOffTimer(mins)
            }
        }

        notifyAllObservers()
        return START_STICKY
    }

    private fun currentStatusText(): String {
        val count = (if (gatt1 != null) 1 else 0) + (if (gatt2 != null) 1 else 0)
        return if (count == 0) {
            "Solette scollegate"
        } else if (isHeatingOn) {
            "Riscaldamento ON ($count/2) — $currentTemp °C"
        } else {
            "Solette collegate ($count/2) — Standby ($currentTemp °C)"
        }
    }

    private fun notifyAllObservers() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(currentStatusText()))
        WarmInsoleWidgetProvider.updateAllWidgets(this)
        onStateChangedListener?.invoke()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WarmInsole Background", NotificationManager.IMPORTANCE_LOW)
            )
            nm?.createNotificationChannel(
                NotificationChannel(LOW_BATT_CHANNEL_ID, "Avviso Batteria Scarica Solette", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WarmInsole Pro")
            .setContentText(status)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    fun startBoostMode() {
        cancelBoost()
        isBoostActive = true
        boostRemainingSeconds = 10 * 60
        currentTemp = 60
        sendPower(true)
        handler.postDelayed({ sendTemperature(60) }, 250)
        notifyAllObservers()

        boostRunnable = object : Runnable {
            override fun run() {
                if (!isBoostActive) return
                if (boostRemainingSeconds <= 0) {
                    isBoostActive = false
                    currentTemp = 45
                    sendTemperature(45)
                    notifyAllObservers()
                } else {
                    boostRemainingSeconds--
                    onStateChangedListener?.invoke()
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(boostRunnable!!)
    }

    fun cancelBoost() {
        boostRunnable?.let { handler.removeCallbacks(it) }
        boostRunnable = null
        isBoostActive = false
        boostRemainingSeconds = 0
        notifyAllObservers()
    }

    fun setAutoOffTimer(minutes: Int) {
        timerRunnable?.let { handler.removeCallbacks(it) }
        if (minutes <= 0) {
            isTimerActive = false
            timerRemainingSeconds = 0
            notifyAllObservers()
            return
        }
        isTimerActive = true
        timerRemainingSeconds = minutes * 60
        timerRunnable = object : Runnable {
            override fun run() {
                if (timerRemainingSeconds <= 0) {
                    isTimerActive = false
                    cancelBoost()
                    sendPower(false)
                    notifyAllObservers()
                } else {
                    timerRemainingSeconds--
                    onStateChangedListener?.invoke()
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(timerRunnable!!)
    }

    fun stopBleScan() {
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

    fun autoConnectBoth() {
        if (gatt1 != null && gatt2 != null) {
            notifyAllObservers()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        manualDisconnect = false
        stopBleScan()

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return

        isScanning = true
        notifyAllObservers()

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
                    if (scanEle in 0..9) handleBatteryUpdate(1, scanEle)
                    notifyAllObservers()
                    val delay = if (connecting2) 450L else 0L
                    handler.postDelayed({ connectSlot(dev, 1) }, delay)
                } else if (targetSlot == 2) {
                    dev2 = dev
                    connecting2 = true
                    if (scanEle in 0..9) handleBatteryUpdate(2, scanEle)
                    notifyAllObservers()
                    val delay = if (connecting1 || gatt1 != null) 450L else 0L
                    handler.postDelayed({ connectSlot(dev, 2) }, delay)
                }
            }
        }

        activeScanCallback = callback
        scanner.startScan(null, settings, callback)

        scanTimeoutRunnable = Runnable {
            if (isScanning) {
                stopBleScan()
                notifyAllObservers()
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, 30000L)
    }

    fun disconnectAll() {
        manualDisconnect = true
        stopBleScan()
        cancelBoost()

        isHeatingOn = false
        rawEle1 = -1
        rawEle2 = -1
        lowBattAlerted1 = false
        lowBattAlerted2 = false
        ecoStepTriggered1 = false
        ecoStepTriggered2 = false

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

        notifyAllObservers()
    }

    private fun connectSlot(device: BluetoothDevice, slot: Int) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (slot == 1) {
                            connecting1 = false
                            gatt1 = gatt
                        } else {
                            connecting2 = false
                            gatt2 = gatt
                        }
                        if (gatt1 != null && gatt2 != null) {
                            stopBleScan()
                        }
                        gatt.discoverServices()
                        notifyAllObservers()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                        if (slot == 1) {
                            connecting1 = false
                            gatt1 = null
                            if (!manualDisconnect) {
                                handler.postDelayed({
                                    if (!manualDisconnect && gatt1 == null && !connecting1) {
                                        dev1?.let {
                                            connecting1 = true
                                            connectSlot(it, 1)
                                        }
                                    }
                                }, 1800L)
                            }
                        } else {
                            connecting2 = false
                            gatt2 = null
                            if (!manualDisconnect) {
                                handler.postDelayed({
                                    if (!manualDisconnect && gatt2 == null && !connecting2) {
                                        dev2?.let {
                                            connecting2 = true
                                            connectSlot(it, 2)
                                        }
                                    }
                                }, 1800L)
                            }
                        }
                        if (gatt1 == null && gatt2 == null) {
                            isHeatingOn = false
                        }
                        notifyAllObservers()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                enableNotifyFFF4(gatt)
                handler.postDelayed({
                    writeBytes(gatt, byteArrayOf(0xBC.toByte(), 0xC1.toByte(), 0x05, 0xC1.toByte(), 0xBB.toByte()))
                }, 300L)
                handler.postDelayed({ queryBattery(gatt, slot) }, 750L)
            }

            private fun handleIncomingBytes(data: ByteArray) {
                if (data.isEmpty()) return
                if (data.size >= 15 && (data[1].toInt() and 0xFF) == 1 && (data[3].toInt() and 0xFF) == 3) {
                    val rawEle = data[13].toInt() and 0xFF
                    val sideSlot = if ((data[4].toInt() and 0xFF) == 2) 2 else slot
                    handleBatteryUpdate(sideSlot, rawEle)
                    return
                }
                if ((data[0].toInt() and 0xFF) == 0xBC && data.size >= 6) {
                    val candidate = data[data.size - 2].toInt() and 0xFF
                    if (candidate in 1..9) {
                        handleBatteryUpdate(slot, candidate)
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

    private fun handleBatteryUpdate(slot: Int, rawEle: Int) {
        handler.post {
            val clampedRaw = rawEle.coerceIn(0, 9)
            if (slot == 1) rawEle1 = clampedRaw else rawEle2 = clampedRaw

            if (clampedRaw in 1..2 && isEcoStepEnabled) {
                val triggered = if (slot == 1) ecoStepTriggered1 else ecoStepTriggered2
                if (!triggered) {
                    if (slot == 1) ecoStepTriggered1 = true else ecoStepTriggered2 = true
                    cancelBoost()
                    val reduced = (currentTemp - 5).coerceAtLeast(35)
                    if (reduced < currentTemp) {
                        currentTemp = reduced
                        sendTemperature(currentTemp)
                        Toast.makeText(this, "🌿 Eco-Step: temperatura ridotta a $currentTemp °C", Toast.LENGTH_LONG).show()
                    }
                }
            } else if (clampedRaw >= 4) {
                if (slot == 1) ecoStepTriggered1 = false else ecoStepTriggered2 = false
            }

            if (clampedRaw in 0..1) {
                val alerted = if (slot == 1) lowBattAlerted1 else lowBattAlerted2
                if (!alerted) {
                    if (slot == 1) lowBattAlerted1 = true else lowBattAlerted2 = true
                    triggerLowBatteryNotification(slot, clampedRaw)
                }
            } else if (clampedRaw >= 3) {
                if (slot == 1) lowBattAlerted1 = false else lowBattAlerted2 = false
            }

            notifyAllObservers()
        }
    }

    private fun triggerLowBatteryNotification(slot: Int, rawEle: Int) {
        val sideName = if (slot == 1) "Soletta 1 (SX)" else "Soletta 2 (DX)"
        val msg = "$sideName all'ultima tacchetta ($rawEle/9)! Prepara il cambio batteria."
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

    fun sendPower(on: Boolean) {
        isHeatingOn = on
        val state: Byte = if (on) 0x01 else 0x00
        gatt1?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        handler.postDelayed({
            gatt2?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        }, 150L)
        notifyAllObservers()
    }

    fun sendTemperature(temp: Int) {
        currentTemp = temp
        val t = temp.toByte()
        gatt1?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
        handler.postDelayed({
            gatt2?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
        }, 150L)
        notifyAllObservers()
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
}
