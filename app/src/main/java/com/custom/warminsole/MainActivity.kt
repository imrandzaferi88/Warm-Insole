package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805f9b34fb")

    private var gattLeft: BluetoothGatt? = null
    private var gattRight: BluetoothGatt? = null

    private lateinit var btnLeft: MaterialButton
    private lateinit var btnRight: MaterialButton
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private lateinit var tvTempValue: TextView
    private lateinit var sliderTemp: Slider

    private var currentTemp = 40
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F1115")
        checkPermissions()
        setupUI()
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    private fun setupUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            isFillViewport = true
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 36, 44, 48)
        }

        // --- TOP BAR CON TITOLO E 3 PUNTINI ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 0, 36)
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "WarmInsole"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val appSubtitle = TextView(this).apply {
            text = "Controllo Termico Bluetooth"
            textSize = 13f
            setTextColor(Color.parseColor("#8E96A4"))
        }
        titleBox.addView(appTitle)
        titleBox.addView(appSubtitle)

        val btnMenu = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(28, 8, 24, 8)
            setOnClickListener { view -> showOverflowMenu(view) }
        }

        topBar.addView(titleBox)
        topBar.addView(btnMenu)
        mainContainer.addView(topBar)

        // --- CARD CONNESSIONE SOLETTE ---
        val connCard = createCard()
        val connLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }

        val lblConn = TextView(this).apply {
            text = "DISPOSITIVI BLUETOOTH"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
            setPadding(0, 0, 0, 24)
        }
        connLayout.addView(lblConn)

        // Soletta Sinistra
        statusLeft = TextView(this).apply {
            text = "● Disconnessa"
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A4"))
            setPadding(4, 0, 0, 8)
        }
        btnLeft = MaterialButton(this).apply {
            text = "Collega Soletta SINISTRA"
            textSize = 15f
            cornerRadius = 28
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
            setTextColor(Color.WHITE)
            setOnClickListener { scanAndSelect(isLeft = true) }
        }
        connLayout.addView(statusLeft)
        connLayout.addView(btnLeft, LinearLayout.LayoutParams(-1, 145).apply { bottomMargin = 24 })

        // Soletta Destra
        statusRight = TextView(this).apply {
            text = "● Disconnessa"
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A4"))
            setPadding(4, 0, 0, 8)
        }
        btnRight = MaterialButton(this).apply {
            text = "Collega Soletta DESTRA"
            textSize = 15f
            cornerRadius = 28
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
            setTextColor(Color.WHITE)
            setOnClickListener { scanAndSelect(isLeft = false) }
        }
        connLayout.addView(statusRight)
        connLayout.addView(btnRight, LinearLayout.LayoutParams(-1, 145))

        connCard.addView(connLayout)
        mainContainer.addView(connCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 32 })

        // --- CARD ACCENSIONE ---
        val powerCard = createCard()
        val powerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 36)
        }

        val lblPower = TextView(this).apply {
            text = "ALIMENTAZIONE"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
            setPadding(0, 0, 0, 20)
        }
        powerLayout.addView(lblPower)

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnOn = MaterialButton(this).apply {
            text = "ACCENDI"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = 28
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            setOnClickListener { sendPower(true) }
        }
        val btnOff = MaterialButton(this).apply {
            text = "SPEGNI"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = 28
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E3440"))
            setTextColor(Color.parseColor("#D8DEE9"))
            setOnClickListener { sendPower(false) }
        }
        switchRow.addView(btnOn, LinearLayout.LayoutParams(0, 150, 1f).apply { rightMargin = 14 })
        switchRow.addView(btnOff, LinearLayout.LayoutParams(0, 150, 1f).apply { leftMargin = 14 })
        powerLayout.addView(switchRow)

        powerCard.addView(powerLayout)
        mainContainer.addView(powerCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 32 })

        // --- CARD TEMPERATURA & SLIDER MIGLIORATO ---
        val tempCard = createCard()
        val tempLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val lblTempHeader = TextView(this).apply {
            text = "TEMPERATURA TARGET"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E96A4"))
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        tempLayout.addView(lblTempHeader)

        tvTempValue = TextView(this).apply {
            text = "40 °C"
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF8A65"))
            setPadding(0, 16, 0, 24)
        }
        tempLayout.addView(tvTempValue)

        // Riga con pulsante "-", Slider spesso, e pulsante "+"
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnMinus = MaterialButton(this).apply {
            text = "−"
            textSize = 22f
            cornerRadius = 24
            setPadding(0, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (currentTemp > 30) {
                    updateTemperatureUI(currentTemp - 1, send = true)
                }
            }
        }

        sliderTemp = Slider(this).apply {
            valueFrom = 30f
            valueTo = 60f
            stepSize = 1f
            value = 40f
            trackHeight = 22
            thumbRadius = 26
            trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#FF6D00"))
            trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#2C323D"))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            haloTintList = ColorStateList.valueOf(Color.parseColor("#44FF6D00"))

            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    updateTemperatureUI(value.toInt(), send = false)
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    sendTemperature(currentTemp)
                }
            })
        }

        val btnPlus = MaterialButton(this).apply {
            text = "+"
            textSize = 22f
            cornerRadius = 24
            setPadding(0, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (currentTemp < 60) {
                    updateTemperatureUI(currentTemp + 1, send = true)
                }
            }
        }

        sliderRow.addView(btnMinus, LinearLayout.LayoutParams(115, 115))
        sliderRow.addView(sliderTemp, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = 12
            rightMargin = 12
        })
        sliderRow.addView(btnPlus, LinearLayout.LayoutParams(115, 115))
        tempLayout.addView(sliderRow, LinearLayout.LayoutParams(-1, -2))

        // Etichette min/max sotto lo slider
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(125, 8, 125, 0)
        }
        val minLbl = TextView(this).apply {
            text = "30 °C"
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A4"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val maxLbl = TextView(this).apply {
            text = "60 °C"
            textSize = 12f
            setTextColor(Color.parseColor("#8E96A4"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        rangeRow.addView(minLbl)
        rangeRow.addView(maxLbl)
        tempLayout.addView(rangeRow, LinearLayout.LayoutParams(-1, -2))

        tempCard.addView(tempLayout)
        mainContainer.addView(tempCard)

        root.addView(mainContainer)
        setContentView(root)
    }

    private fun createCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = 36f
            cardElevation = 6f
            strokeWidth = 2
            strokeColor = Color.parseColor("#252A34")
            setCardBackgroundColor(Color.parseColor("#181B22"))
        }
    }

    private fun updateTemperatureUI(newTemp: Int, send: Boolean) {
        currentTemp = newTemp
        sliderTemp.value = newTemp.toFloat()
        tvTempValue.text = "$currentTemp °C"
        val colorHex = when {
            currentTemp < 40 -> "#4FC3F7"
            currentTemp < 50 -> "#FFB74D"
            else -> "#FF5252"
        }
        tvTempValue.setTextColor(Color.parseColor(colorHex))
        if (send) {
            sendTemperature(currentTemp)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "About Us")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                showAboutDialog()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Us — WarmInsole")
            .setMessage(
                "Imran Dzaferi ha creato questa bellissima app per offrire un controllo Bluetooth rapido, preciso e affidabile delle solette riscaldate.\n\n" +
                "Progettata con cura per gestire separatamente soletta sinistra e destra, regolare la temperatura al singolo grado e garantire la massima stabilità in ogni situazione.\n\n" +
                "Sviluppatore: Imran Dzaferi\n" +
                "Versione: 1.0"
            )
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun scanAndSelect(isLeft: Boolean) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return
        val foundDevices = mutableListOf<BluetoothDevice>()
        val deviceLabels = mutableListOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: "Dispositivo BLE"
                if (name.contains("XINKE", ignoreCase = true) && foundDevices.none { it.address == dev.address }) {
                    foundDevices.add(dev)
                    deviceLabels.add("$name (${dev.address})")
                }
            }
        }

        Toast.makeText(this, "Ricerca solette per 3 secondi...", Toast.LENGTH_SHORT).show()
        scanner.startScan(callback)

        handler.postDelayed({
            scanner.stopScan(callback)
            if (foundDevices.isEmpty()) {
                Toast.makeText(this, "Nessuna soletta trovata. Accendile prima.", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            AlertDialog.Builder(this)
                .setTitle(if (isLeft) "Scegli Soletta SINISTRA" else "Scegli Soletta DESTRA")
                .setItems(deviceLabels.toTypedArray()) { _, which ->
                    connectDevice(foundDevices[which], isLeft)
                }
                .show()
        }, 3000)
    }

    private fun connectDevice(device: BluetoothDevice, isLeft: Boolean) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                runOnUiThread {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                        if (isLeft) {
                            gattLeft = gatt
                            statusLeft.text = "● Connessa (${device.address})"
                            statusLeft.setTextColor(Color.parseColor("#66BB6A"))
                            btnLeft.text = "Soletta SINISTRA Attiva"
                            btnLeft.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
                        } else {
                            gattRight = gatt
                            statusRight.text = "● Connessa (${device.address})"
                            statusRight.setTextColor(Color.parseColor("#66BB6A"))
                            btnRight.text = "Soletta DESTRA Attiva"
                            btnRight.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (isLeft) {
                            gattLeft = null
                            statusLeft.text = "● Disconnessa"
                            statusLeft.setTextColor(Color.parseColor("#8E96A4"))
                            btnLeft.text = "Collega Soletta SINISTRA"
                            btnLeft.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
                        } else {
                            gattRight = null
                            statusRight.text = "● Disconnessa"
                            statusRight.setTextColor(Color.parseColor("#8E96A4"))
                            btnRight.text = "Collega Soletta DESTRA"
                            btnRight.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#252A34"))
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                writeBytes(gatt, byteArrayOf(0xBC.toByte(), 0xC1.toByte(), 0x05, 0xC1.toByte(), 0xBB.toByte()))
            }
        }
        device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun sendPower(on: Boolean) {
        val state: Byte = if (on) 0x01 else 0x00
        gattLeft?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        handler.postDelayed({
            gattRight?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x01, 0x07, 0x03, 0x01, state, 0xBB.toByte())) }
        }, 150)
    }

    private fun sendTemperature(temp: Int) {
        val t = temp.toByte()
        gattLeft?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
        handler.postDelayed({
            gattRight?.let { writeBytes(it, byteArrayOf(0xBC.toByte(), 0x02, 0x07, 0x03, 0x01, t, 0xBB.toByte())) }
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
}
