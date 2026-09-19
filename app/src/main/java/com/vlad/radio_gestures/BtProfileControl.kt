package com.vlad.radio_gestures

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context

object BtProfileControl {
    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    fun init(context: Context, adapter: BluetoothAdapter, onReady: () -> Unit) {
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dpProxy = proxy
                onReady()
            }
            override fun onServiceDisconnected(profile: Int) { a2dpProxy = null }
        }, BluetoothProfile.A2DP)

        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                headsetProxy = proxy
                onReady()
            }
            override fun onServiceDisconnected(profile: Int) { headsetProxy = null }
        }, BluetoothProfile.HEADSET)
    }

    private fun callHidden(target: Any, methodName: String, device: BluetoothDevice): Boolean {
        return try {
            val m = target.javaClass.getMethod(methodName, BluetoothDevice::class.java)
            m.isAccessible = true
            (m.invoke(target, device) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun disconnect(device: BluetoothDevice): Boolean {
        var ok = false
        a2dpProxy?.let { ok = callHidden(it, "disconnect", device) || ok }
        headsetProxy?.let { ok = callHidden(it, "disconnect", device) || ok }
        return ok
    }

    fun connect(device: BluetoothDevice): Boolean {
        var ok = false
        a2dpProxy?.let { ok = callHidden(it, "connect", device) || ok }
        headsetProxy?.let { ok = callHidden(it, "connect", device) || ok }
        return ok
    }

    fun isReady(): Boolean = a2dpProxy != null || headsetProxy != null
}
