package com.pencildogs.androidapps.blescanner

import android.Manifest
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Get the last known location
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    // Send location data back to MainActivity
                    val latitude = location.latitude
                    val longitude = location.longitude

                    // Use a custom Intent to send location data to MainActivity
                    val mainActivityIntent = Intent("com.example.locationdemo.LOCATION_UPDATE")
                    mainActivityIntent.putExtra("latitude", latitude)
                    mainActivityIntent.putExtra("longitude", longitude)
                    sendBroadcast(mainActivityIntent)  // Notify MainActivity
                } else {
                    // Handle the case where location is null
                    val mainActivityIntent = Intent("com.example.locationdemo.LOCATION_UPDATE")
                    mainActivityIntent.putExtra("latitude", "Not Available")
                    mainActivityIntent.putExtra("longitude", "Not Available")
                    sendBroadcast(mainActivityIntent)
                }
            }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}