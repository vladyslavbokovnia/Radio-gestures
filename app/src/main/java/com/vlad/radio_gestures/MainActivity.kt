package com.vlad.radio_gestures

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
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
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var selectedAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopScan = Runnable { stopScanning() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handle(result.device, result.rssi) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handle(it.device, it.rssi) } }
        override fun onScanFailed(errorCode: Int) { statusText.text = "Ошибка сканирования: $errorCode" }
    }

    private val permissionCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!hasPermissions()) ActivityCompat.requestPermissions(this, permissions(), permissionCode)
        else loadDevices()
    }

    private fun permissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = permissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(requestCode, p, r)
        if (requestCode == permissionCode && hasPermissions()) loadDevices()
        else statusText.text = "Нужны разрешения Bluetooth"
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,10) }
        val title = TextView(this).apply { text = "Bluetooth Radio Monitor"; textSize = 24f; gravity = Gravity.CENTER; setPadding(0,0,0,12) }
        spinner = Spinner(this)
        valueText = TextView(this).apply { text = "— dBm"; textSize = 36f; gravity = Gravity.CENTER; setPadding(0,12,0,2) }
        deltaText = TextView(this).apply { text = "Изменение: —"; textSize = 18f; gravity = Gravity.CENTER }
        statusText = TextView(this).apply { text = "Выберите устройство и нажмите Старт"; textSize = 14f; gravity = Gravity.CENTER; setPadding(0,5,0,5) }
        graph = SignalGraph(this)
        val start = Button(this).apply { text = "СТАРТ / СТОП"; setOnClickListener { if (scanning) stopScanning() else startScanning() } }
        val help = TextView(this).apply { text = "График показывает RSSI. Закрывайте наушник рукой и наблюдайте форму изменения сигнала."; textSize = 15f; setPadding(4,8,4,4) }
        root.addView(title); root.addView(spinner); root.addView(valueText); root.addView(deltaText)
        root.addView(graph, LinearLayout.LayoutParams(-1,0,1f)); root.addView(statusText); root.addView(start); root.addView(help)
        setContentView(root)
    }

    private fun loadDevices() {
        if (!adapter.isEnabled) { statusText.text = "Включите Bluetooth"; return }
        val devices = try { adapter.bondedDevices.toList().sortedBy { it.name ?: it.address } } catch (_: SecurityException) { emptyList() }
        val labels = if (devices.isEmpty()) listOf("BLE-устройства появятся при сканировании") else devices.map { "${it.name ?: "Без имени"}\n${it.address}" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) { selectedAddress = null }
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { selectedAddress = devices.getOrNull(pos)?.address; graph.clear(); valueText.text = "— dBm"; deltaText.text = "Изменение: —" }
        }
    }

    private fun startScanning() {
        if (!hasPermissions()) { ActivityCompat.requestPermissions(this, permissions(), permissionCode); return }
        if (!adapter.isEnabled) { statusText.text = "Включите Bluetooth"; return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText.text = "BLE-сканер недоступен"; return }
        try {
            scanner?.startScan(callback); scanning = true; statusText.text = "Сканирование BLE…"; handler.postDelayed(stopScan, 600_000)
        } catch (e: SecurityException) { statusText.text = "Нет разрешения Bluetooth" }
    }

    private fun stopScanning() {
        if (!scanning) return
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {}
        scanning = false; statusText.text = "Остановлено"
    }

    private fun handle(device: BluetoothDevice, rssi: Int) {
        val wanted = selectedAddress ?: return
        if (device.address != wanted) return
        runOnUiThread {
            graph.add(rssi)
            valueText.text = String.format(Locale.US, "%d dBm", rssi)
            deltaText.text = String.format(Locale.US, "Изменение: %+d dB", graph.delta())
            statusText.text = "Получен сигнал: ${device.name ?: "устройство"}"
        }
    }

    override fun onDestroy() { stopScanning(); super.onDestroy() }
}
