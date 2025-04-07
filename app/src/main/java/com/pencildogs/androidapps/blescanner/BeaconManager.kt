package com.pencildogs.androidapps.blescanner

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class BeaconManager private constructor(private val context: Context) {
    private val discoveredBeacons = ConcurrentHashMap<String, BluetoothDevice>()

    fun addBeacon(address: String, device: BluetoothDevice) {
        discoveredBeacons[address] = device
    }

    fun getAllBeaconAddresses(): List<String> {
        return discoveredBeacons.keys.toList()
    }

    fun getAllBeacons(): Map<String, BluetoothDevice> {
        return discoveredBeacons.toMap()
    }

    fun clear() {
        discoveredBeacons.clear()
    }

    companion object {
        @Volatile
        private var instance: BeaconManager? = null

        fun getInstance(context: Context): BeaconManager {
            return instance ?: synchronized(this) {
                instance ?: BeaconManager(context.applicationContext).also { instance = it }
            }
        }
    }
}