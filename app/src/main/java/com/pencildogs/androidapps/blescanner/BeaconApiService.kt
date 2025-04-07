package com.pencildogs.androidapps.blescanner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class BeaconApiService(private val baseUrl: String, private var authToken: String? = null) {
    private val executor = Executors.newSingleThreadExecutor()
    private val TAG = "BeaconApiService"

    // Hardcoded credentials
    private val username = "support@pencildogs.com"
    private val password = "Pencil2025"

    // Login to get auth token
    fun login(callback: (Boolean, String?) -> Unit) {
        executor.execute {
            try {
                val url = URL("$baseUrl/api/auth/login")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val loginData = JSONObject().apply {
                    put("email", username)
                    put("password", password)
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(loginData.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    authToken = jsonResponse.getString("token")
                    Log.d(TAG, "Successfully obtained auth token")
                    callback(true, "Login successful")
                } else {
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val response = errorStream.bufferedReader().use { it.readText() }
                    Log.e(TAG, "Login failed with code $responseCode: $response")
                    callback(false, "Login failed: $response")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                callback(false, "Login error: ${e.message}")
            }
        }
    }

    // Update detector (scanner) status and beacon locations
    fun updateScannerAndBeacons(
        scannerId: String,
        scannerName: String,
        latitude: Double,
        longitude: Double,
        beaconUuids: List<String>,
        callback: (Boolean, String?) -> Unit
    ) {
        // If we don't have a token, try to login first
        if (authToken == null) {
            login { success, message ->
                if (success) {
                    // Now make the update call with the new token
                    updateScannerAndBeacons(scannerId, scannerName, latitude, longitude, beaconUuids, callback)
                } else {
                    callback(false, "Authentication failed: $message")
                }
            }
            return
        }

        executor.execute {
            try {
                val url = URL("$baseUrl/api/location_history/batch")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $authToken")
                connection.doOutput = true

                // Format the beacons as objects with beacon_uuid property
                val formattedBeacons = JSONArray()
                beaconUuids.forEach { uuid ->
                    formattedBeacons.put(JSONObject().apply {
                        put("beacon_uuid", uuid)
                    })
                }

                // Add our special test beacon if not already included
                if (!beaconUuids.contains("PNCLDGS9-9999-9999-9999-999999999999")) {
                    formattedBeacons.put(JSONObject().apply {
                        put("beacon_uuid", "PNCLDGS9-9999-9999-9999-999999999999")
                    })
                }

                // Create the JSON body with all required fields
                val data = JSONObject().apply {
                    put("detector_uuid", scannerId)
                    put("name", scannerName)
                    put("location_type", "mobile")
                    put("location_name", "Android Scanner $scannerId")
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("beacons", formattedBeacons)
                    put("timestamp", System.currentTimeMillis())
                }

                // Write the data to the connection
                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(data.toString())
                writer.flush()
                writer.close()

                // Check response
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    callback(true, "Update successful")
                } else {
                    val errorStream = connection.errorStream ?: connection.inputStream
                    val response = errorStream.bufferedReader().use { it.readText() }

                    // Check if token expired
                    if (responseCode == 401) {
                        // Token expired, try to get a new one
                        authToken = null
                        login { success, loginMessage ->
                            if (success) {
                                // Retry with new token
                                updateScannerAndBeacons(scannerId, scannerName, latitude, longitude, beaconUuids, callback)
                            } else {
                                callback(false, "Token expired and re-authentication failed")
                            }
                        }
                    } else {
                        callback(false, "Error $responseCode: $response")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API error", e)
                callback(false, "Exception: ${e.message}")
            }
        }
    }
}