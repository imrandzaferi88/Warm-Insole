package com.custom.warminsole

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.slider.Slider
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805f9b34fb")

    private var gattLeft: BluetoothGatt? = null
    private var gattRight: BluetoothGatt? = null

    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var tvTemp: TextView
    private lateinit var sliderTemp: Slider

    private var currentTemp = 40
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Controllo Solette"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 48)
        }
        layout.addView(title)

        btnLeft = Button(this).apply {
            text = "1. Collega Soletta SINISTRA"
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setTextColor(Color.WHITE)
            setOnClickListener { scanAndSelect(isLeft = true) }
        }
        layout.addView(btnLeft, LinearLayout.LayoutParams(-1, 150).apply { bottomMargin = 24 })

        btnRight = Button(this).apply {
            text = "2. Collega Soletta DESTRA"
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setTextColor(Color.WHITE)
            setOnClickListener { scanAndSelect(isLeft = false) }
        }
        layout.addView(btnRight, LinearLayout.LayoutParams(-1, 150).apply { bottomMargin = 64 })

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnOn = Button(this).apply {
            text = "ACCENDI (ON)"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setOnClickListener { sendPower(true) }
        }
        val btnOff = Button(this).apply {
            text = "SPEGNI (OFF)"
            setBackgroundColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            setOnClickListener { sendPower(false) }
        }
        switchRow.addView(btnOn, LinearLayout.LayoutParams(0, 160, 1f).apply { rightMargin = 16 })
        switchRow.addView(btnOff, LinearLayout.LayoutParams(0, 160, 1f).apply { leftMargin = 16 })
        layout.addView(switchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 64 })

        tvTemp = TextView(this).apply {
            text = "Temperatura: 40 °C"
            textSize = 22f
            setTextColor(Color.parseColor("#FF7043"))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvTemp)

        sliderTemp = Slider(this).apply {
            valueFrom = 30f
            valueTo = 60f
            stepSize = 1f
            value = 40f
            addOnChangeListener { _, value, _ ->
                currentTemp = value.toInt()
                tvTemp.text = "Temperatura: $currentTemp °C"
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    sendTemperature(currentTemp)
                }
            })
        }
        layout.addView(sliderTemp)

        setContentView(layout)
    }

    private fun scanAndSelect(isLeft: Boolean) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return
        val foundDevices = mutableListOf<BluetoothDevice>()
        val deviceLabels = mutableListOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: "Sconosciuto"
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
                            btnLeft.text = "SINISTRA Connessa (${device.address})"
                            btnLeft.setBackgroundColor(Color.parseColor("#2E7D32"))
                        } else {
                            gattRight = gatt
                            btnRight.text = "DESTRA Connessa (${device.address})"
                            btnRight.setBackgroundColor(Color.parseColor("#2E7D32"))
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (isLeft) {
                            gattLeft = null
                            btnLeft.text = "1. Collega Soletta SINISTRA"
                            btnLeft.setBackgroundColor(Color.parseColor("#2C2C2C"))
                        } else {
                            gattRight = null
                            btnRight.text = "2. Collega Soletta DESTRA"
                            btnRight.setBackgroundColor(Color.parseColor("#2C2C2C"))
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
