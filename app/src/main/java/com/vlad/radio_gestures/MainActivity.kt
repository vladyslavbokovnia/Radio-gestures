package com.vlad.radio_gestures

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var graph: SignalGraph
    private lateinit var valueText: TextView
    private lateinit var deltaText: TextView
    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var selectedAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopScan = Runnable { stopScanning() }
    private var receiverRegistered = false

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (device != null && rssi != Short.MIN_VALUE.toInt()) handle(device, rssi, "Classic")
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (scanning && hasPermissions()) {
                            try { btAdapter?.startDiscovery() } catch (_: SecurityException) {}
                        }
                    }
                }
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            try { handle(result.device, result.rssi, "BLE") } catch (e: Exception) { showError(e) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            try { results.forEach { handle(it.device, it.rssi, "BLE") } } catch (e: Exception) { showError(e) }
        }
        override fun onScanFailed(errorCode: Int) {
            if (::statusText.isInitialized) runOnUiThread { statusText.text = "BLE ошибка: $errorCode (Classic продолжается)" }
        }
    }

    private val permissionCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            if (btAdapter == null) {
                statusText.text = "Bluetooth не поддерживается этим телефоном"
                return
            }
            registerClassicReceiver()
            if (!hasPermissions()) ActivityCompat.requestPermissions(this, permissions(), permissionCode)
            else loadDevices()
        } catch (e: Exception) {
            showStartupError(e)
        }
    }

    private fun showStartupError(e: Throwable) {
        val message = "Ошибка запуска:\n${e.javaClass.simpleName}\n${e.message ?: "без сообщения"}"
        setContentView(TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(30, 50, 30, 30)
        })
    }

    private fun showError(e: Throwable) {
        if (::statusText.isInitialized) runOnUiThread {
            statusText.text = "Ошибка: ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
        }
    }

    private fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermissions(): Boolean = permissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(requestCode, p, r)
        if (requestCode == permissionCode && hasPermissions()) loadDevices()
        else if (::statusText.isInitialized) statusText.text = "Нужны разрешения Bluetooth/геолокации для сканирования"
    }

    private fun registerClassicReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(classicReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 10)
        }
        val title = TextView(this).apply {
            text = "Bluetooth Radio Monitor"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        spinner = Spinner(this)
        valueText = TextView(this).apply {
            text = "— dBm"
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 2)
        }
        deltaText = TextView(this).apply {
            text = "Изменение: —"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Выберите устройство и нажмите Старт"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 5)
        }
        graph = SignalGraph(this)
        val start = Button(this).apply {
            text = "СТАРТ / СТОП"
            setOnClickListener { if (scanning) stopScanning() else startScanning() }
        }
        val help = TextView(this).apply {
            text = "Измеряем BLE-рекламу и Classic Bluetooth discovery. Если закрыть наушник рукой, смотрите на изменение RSSI."
            textSize = 15f
            setPadding(4, 8, 4, 4)
        }
        root.addView(title)
        root.addView(spinner)
        root.addView(valueText)
        root.addView(deltaText)
        root.addView(graph, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(statusText)
        root.addView(start)
        root.addView(help)
        setContentView(root)
    }

    private fun loadDevices() {
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) {
            statusText.text = "Включите Bluetooth"
            return
        }
        val devices = try {
            adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        } catch (_: SecurityException) {
            emptyList()
        }
        val labels = if (devices.isEmpty()) {
            listOf("Нет сопряжённых устройств — BLE найдётся во время сканирования")
        } else {
            devices.map { "${it.name ?: "Без имени"}\n${it.address}" }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) { selectedAddress = null }
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                selectedAddress = devices.getOrNull(pos)?.address
                graph.clear()
                valueText.text = "— dBm"
                deltaText.text = "Изменение: —"
            }
        }
    }

    private fun startScanning() {
        val adapter = btAdapter ?: return
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions(), permissionCode)
            return
        }
        if (!adapter.isEnabled) {
            statusText.text = "Включите Bluetooth"
            return
        }
        scanning = true
        graph.clear()
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.startScan(bleCallback)
        } catch (e: SecurityException) {
            scanner = null
            showError(e)
        }
        try {
            adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            showError(e)
        }
        statusText.text = "BLE + Classic discovery сканирование…"
        handler.removeCallbacks(stopScan)
        handler.postDelayed(stopScan, 600_000)
    }

    private fun stopScanning() {
        if (!scanning) return
        try { scanner?.stopScan(bleCallback) } catch (_: SecurityException) {}
        try { btAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
        handler.removeCallbacks(stopScan)
        scanning = false
        if (::statusText.isInitialized) statusText.text = "Остановлено"
    }

    private fun handle(device: BluetoothDevice, rssi: Int, source: String) {
        val wanted = selectedAddress ?: return
        if (device.address != wanted) return
        runOnUiThread {
            graph.add(rssi)
            valueText.text = String.format(Locale.US, "%d dBm", rssi)
            deltaText.text = String.format(Locale.US, "Изменение: %+d dB", graph.delta())
            statusText.text = "$source: ${device.name ?: "устройство"}"
        }
    }

    override fun onDestroy() {
        stopScanning()
        if (receiverRegistered) {
            try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
