package com.vlad.radio_gestures

import android.Manifest
import android.app.AlertDialog
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
import rikka.shizuku.Shizuku

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
    private lateinit var shizukuStatusText: TextView
    private var shizukuPollRunning = false
    private val shizukuPollIntervalMs = 1500L
    private val shizukuPollTask: Runnable = object : Runnable {
        override fun run() {
            pollShizukuOnce()
            if (scanning) handler.postDelayed(this, shizukuPollIntervalMs)
        }
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuRssi.REQUEST_CODE) {
                runOnUiThread { updateShizukuStatus() }
            }
        }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuStatus() }
    }

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

            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            updateShizukuStatus()
            if (ShizukuRssi.isBinderAlive() && !ShizukuRssi.hasPermission()) {
                ShizukuRssi.requestPermission()
            }
        } catch (e: Exception) {
            showStartupError(e)
        }
    }

    private fun updateShizukuStatus() {
        if (!::shizukuStatusText.isInitialized) return
        shizukuStatusText.text = when {
            !ShizukuRssi.isBinderAlive() -> "Shizuku: сервис не запущен"
            !ShizukuRssi.hasPermission() -> "Shizuku: запущен, разрешение не выдано"
            else -> "Shizuku: готов (dumpsys RSSI подключённых устройств)"
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
        val outer = ScrollView(this)
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
        val graphHeightPx = (220 * resources.displayMetrics.density).toInt()
        val start = Button(this).apply {
            text = "СТАРТ / СТОП"
            setOnClickListener { if (scanning) stopScanning() else startScanning() }
        }
        val help = TextView(this).apply {
            text = "Измеряем BLE-рекламу, Classic Bluetooth discovery и (если доступен Shizuku) RSSI уже подключённых устройств через dumpsys."
            textSize = 15f
            setPadding(4, 8, 4, 4)
        }
        shizukuStatusText = TextView(this).apply {
            text = "Shizuku: проверка…"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        val rawDumpButton = Button(this).apply {
            text = "Показать сырой dumpsys"
            setOnClickListener { showRawDumpsys() }
        }
        root.addView(title)
        root.addView(spinner)
        root.addView(valueText)
        root.addView(deltaText)
        root.addView(graph, LinearLayout.LayoutParams(-1, graphHeightPx))
        root.addView(statusText)
        root.addView(shizukuStatusText)
        root.addView(start)
        root.addView(rawDumpButton)
        root.addView(help)
        outer.addView(root)
        setContentView(outer)
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

        if (ShizukuRssi.hasPermission() && !shizukuPollRunning) {
            shizukuPollRunning = true
            handler.post(shizukuPollTask)
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        try { scanner?.stopScan(bleCallback) } catch (_: SecurityException) {}
        try { btAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
        handler.removeCallbacks(stopScan)
        handler.removeCallbacks(shizukuPollTask)
        shizukuPollRunning = false
        scanning = false
        if (::statusText.isInitialized) statusText.text = "Остановлено"
    }

    private fun pollShizukuOnce() {
        val wanted = selectedAddress ?: return
        if (!ShizukuRssi.hasPermission()) return
        Thread {
            try {
                val (out, _) = ShizukuRssi.exec(arrayOf("sh", "-c", "dumpsys bluetooth_manager"))
                val rssi = ShizukuRssi.extractRssiForAddress(out, wanted)
                if (rssi != null) handleAddress(wanted, rssi, "Shizuku/dumpsys")
            } catch (e: Exception) {
                runOnUiThread { updateShizukuStatus() }
            }
        }.start()
    }

    private fun showRawDumpsys() {
        if (!ShizukuRssi.hasPermission()) {
            statusText.text = "Сначала выдай разрешение приложению в Shizuku"
            ShizukuRssi.requestPermission()
            return
        }
        Toast.makeText(this, "Выполняю dumpsys…", Toast.LENGTH_SHORT).show()
        Thread {
            val (out, err) = try {
                ShizukuRssi.exec(arrayOf("sh", "-c", "dumpsys bluetooth_manager"))
            } catch (e: Exception) {
                "" to "Ошибка: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                val scroll = ScrollView(this).apply {
                    minimumWidth = (280 * resources.displayMetrics.density).toInt()
                    minimumHeight = (200 * resources.displayMetrics.density).toInt()
                }
                val text = TextView(this).apply {
                    setText(if (out.isNotBlank()) out else err.ifBlank { "(пусто — команда не вернула ничего)" })
                    textSize = 11f
                    setPadding(20, 20, 20, 20)
                    setTextIsSelectable(true)
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
                scroll.addView(text)
                val fullText = if (out.isNotBlank()) out else err
                AlertDialog.Builder(this)
                    .setTitle("dumpsys (stdout: ${out.length} симв., stderr: ${err.length} симв.)")
                    .setView(scroll)
                    .setPositiveButton("Копировать") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("dumpsys", fullText))
                        Toast.makeText(this, "Скопировано (${fullText.length} симв.)", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }.start()
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

    private fun handleAddress(address: String, rssi: Int, source: String) {
        val wanted = selectedAddress ?: return
        if (!address.equals(wanted, ignoreCase = true)) return
        runOnUiThread {
            graph.add(rssi)
            valueText.text = String.format(Locale.US, "%d dBm", rssi)
            deltaText.text = String.format(Locale.US, "Изменение: %+d dB", graph.delta())
            statusText.text = "$source: $address"
        }
    }

    override fun onDestroy() {
        stopScanning()
        if (receiverRegistered) {
            try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
