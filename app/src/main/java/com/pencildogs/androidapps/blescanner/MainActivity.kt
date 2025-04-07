package com.pencildogs.androidapps.blescanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission

class MainActivity : Activity(), LocationListener {

    private val TAG = "MainActivity"
    private lateinit var locationManager: LocationManager
    private lateinit var tvLocation: TextView
    private lateinit var btnReload: Button
    private lateinit var btnUpdateServer: Button
    private lateinit var lvDevices: ListView
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private lateinit var handler: Handler

    private val locationPermissionCode = 2
    private val bluetoothPermissionCode = 3
    private val scanPeriod: Long = 10000 // 10 seconds
    private val deviceList = ArrayList<String>()
    private val discoveredDevices = HashMap<String, BluetoothDevice>()
    private val deviceServiceMap = HashMap<String, ArrayList<String>>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var isScanning = false

    private lateinit var beaconManager: BeaconManager

    // New fields for server updates
    private lateinit var apiService: BeaconApiService
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var scannerId: String = "android-scanner-${System.currentTimeMillis()}"
    private var scannerName: String = "Android Mobile Scanner"
    private var apiUrl: String = "http://3.133.160.226"
    private var authToken: String? = null
    private val updateIntervalMs: Long = 10 * 60 * 1000 // 10 minutes in milliseconds

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize beaconManager first
        beaconManager = BeaconManager.getInstance(this)

        // Load saved API configuration
        loadSavedConfig()

        // Initialize UI elements - using safe version to avoid crashes
        tvLocation = findViewById<TextView>(R.id.tv_location) ?: throw IllegalStateException("tv_location not found in layout")
        btnReload = findViewById<Button>(R.id.btn_reload) ?: throw IllegalStateException("btn_reload not found in layout")

        // Initialize BeaconManager first
        beaconManager = BeaconManager.getInstance(applicationContext)

        // Initialize bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("Bluetooth not supported on this device")
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // Initialize device list adapter
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList)
        lvDevices = findViewById<ListView>(R.id.lv_devices) ?: throw IllegalStateException("lv_devices not found in layout")
        lvDevices.adapter = deviceAdapter

        // Initialize handler for delayed tasks
        handler = Handler()

        // Initialize API service and login
        apiService = BeaconApiService(apiUrl, authToken)
        loginToServer()

        // Set up reload button
        btnReload.setOnClickListener {
            // Stop any ongoing scan first
            if (isScanning) {
                try {
                    bluetoothLeScanner.stopScan(bleScanCallback)
                    isScanning = false
                } catch (e: SecurityException) {
                    Log.e(TAG, "Error stopping scan", e)
                }
            }

            // Clear previous device lists
            deviceList.clear()
            beaconManager.clear()
            deviceServiceMap.clear()
            deviceAdapter.notifyDataSetChanged()

            // Start a new scan
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                startBleScan()
                Toast.makeText(this, "Starting BLE scan...", Toast.LENGTH_SHORT).show()
            } else {
                // If permissions aren't granted, request them
                checkAndRequestPermissions()
            }
        }

        // Check if btn_update_server exists in layout
        val btnUpdateServer = findViewById<Button>(R.id.btn_update_server)
        btnUpdateServer?.setOnClickListener {
            updateServerWithBeacons()
        }

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Check and request permissions
        checkAndRequestPermissions()

        // Start the periodic scanning
        startPeriodicScanning()

        // Start the periodic server updates if the update button exists
        if (btnUpdateServer != null) {
            setupPeriodicUpdates()
        }
    }

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences("BeaconTrackerPrefs", Context.MODE_PRIVATE)
        apiUrl = prefs.getString("apiUrl", apiUrl) ?: apiUrl
        authToken = prefs.getString("authToken", null)
        scannerId = prefs.getString("scannerId", scannerId) ?: scannerId
        scannerName = prefs.getString("scannerName", scannerName) ?: scannerName
    }

    private fun loginToServer() {
        apiService.login { success, message ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Logged in to server successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Login failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = ArrayList<String>()

        // Check location permissions
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Check Bluetooth permissions (needed for BLE scanning)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        // Request permissions if needed
        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            requestPermissions(permissionsToRequest.toTypedArray(), bluetoothPermissionCode)
        } else {
            // All permissions granted
            Log.d(TAG, "All permissions already granted")
            getLocation()
            startBleScan()
        }
    }

    private fun getLocation() {
        try {
            // Request location updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000,   // 5 seconds between updates
                5f,     // 5 meters minimum distance change
                this
            )

            // Try to get last known location immediately
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnownLocation != null) {
                updateLocationText(lastKnownLocation)
                currentLatitude = lastKnownLocation.latitude
                currentLongitude = lastKnownLocation.longitude
            } else {
                // Try network provider if GPS provider returns null
                val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (networkLocation != null) {
                    updateLocationText(networkLocation)
                    currentLatitude = networkLocation.latitude
                    currentLongitude = networkLocation.longitude
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
            Toast.makeText(this, "Location permission error", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBleScan() {
        if (!isScanning) {
            // Clear previous lists
            deviceList.clear()
            beaconManager.clear()  // Use beaconManager instead of discoveredDevices
            deviceServiceMap.clear()
            deviceAdapter.notifyDataSetChanged()

            // Start scanning
            isScanning = true
            try {
                bluetoothLeScanner.startScan(bleScanCallback)
                // Stop scanning after delay
                handler.postDelayed({
                    stopBleScan()
                }, scanPeriod)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Bluetooth permission error", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
                isScanning = false
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (isScanning) {
            isScanning = false
            try {
                bluetoothLeScanner.stopScan(bleScanCallback)

                // After stopping scan, connect to discovered devices to get UUIDs
                for ((address, device) in beaconManager.getAllBeacons()) {  // Use beaconManager
                    connectToDevice(device)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Successfully connected to the GATT Server
                    try {
                        // Discover services
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                        gatt.close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Disconnected from the GATT Server
                    gatt.close()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val deviceAddress = gatt.device.address
                val services = gatt.services
                val uuidList = ArrayList<String>()

                // Extract UUIDs from discovered services
                for (service in services) {
                    uuidList.add(service.uuid.toString())

                    // Also get characteristic UUIDs
                    for (characteristic in service.characteristics) {
                        uuidList.add("  └ ${characteristic.uuid}")
                    }
                }

                // Store UUIDs for this device
                deviceServiceMap[deviceAddress] = uuidList

                // Update UI with new UUID info
                updateDeviceList()

                // Close the connection when done
                gatt.close()
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateDeviceList() {
        runOnUiThread {
            deviceList.clear()

            // Rebuild the list with updated UUID information
            for ((address, device) in beaconManager.getAllBeacons()) {  // Use beaconManager
                val deviceInfo = StringBuilder()
                deviceInfo.append("Device: $address")

                // Add name if available
                val name = device.name
                if (name != null && name.isNotEmpty()) {
                    deviceInfo.append(" ($name)")
                }

                // Add UUIDs if available
                val uuids = deviceServiceMap[address]
                if (uuids != null && uuids.isNotEmpty()) {
                    deviceInfo.append("\nUUIDs:")
                    for (uuid in uuids) {
                        deviceInfo.append("\n- $uuid")
                    }
                } else {
                    deviceInfo.append("\nScanning for services...")
                }

                deviceList.add(deviceInfo.toString())
            }

            deviceAdapter.notifyDataSetChanged()
        }
    }

    private fun startPeriodicScanning() {
        handler.postDelayed(object : Runnable {
            @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
            override fun run() {
                if (!isScanning) {
                    startBleScan()
                }
                handler.postDelayed(this, scanPeriod + 5000) // Add 5s buffer to allow service discovery
            }
        }, scanPeriod)
    }

    // BLE scan callback
    private val bleScanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val address = device.address

            // Store the device if not already discovered
            beaconManager.addBeacon(address, device)  // Use beaconManager
            updateDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Scan failed with error code: $errorCode", Toast.LENGTH_SHORT).show()
                isScanning = false
            }
        }
    }

    // New methods for server update functionality
    private fun setupPeriodicUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateServerWithBeacons()
                handler.postDelayed(this, updateIntervalMs)
            }
        }, updateIntervalMs)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    private fun updateServerWithBeacons() {
        // Make sure we have a valid location
        if (currentLatitude == 0.0 && currentLongitude == 0.0) {
            Toast.makeText(this, "Waiting for valid location...", Toast.LENGTH_SHORT).show()
            return
        }

        // Get all discovered beacon addresses
        val beaconAddresses = beaconManager.getAllBeaconAddresses().toMutableList()

        // If list is empty, just add our test beacon
        if (beaconAddresses.isEmpty()) {
            beaconAddresses.add("PNCLDGS9-9999-9999-9999-999999999999")
        } else {
            // Always include our test beacon
            if (!beaconAddresses.contains("PNCLDGS9-9999-9999-9999-999999999999")) {
                beaconAddresses.add("PNCLDGS9-9999-9999-9999-999999999999")
            }
        }

        // Update the server
        apiService.updateScannerAndBeacons(
            scannerId,
            scannerName,
            currentLatitude,
            currentLongitude,
            beaconAddresses
        ) { success, message ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Server updated successfully", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Server update successful")

                    // CLEAR THE BEACON LIST AFTER SUCCESSFUL UPDATE
                    beaconManager.clear()
                    deviceList.clear()
                    deviceServiceMap.clear()
                    deviceAdapter.notifyDataSetChanged()

                    // Optionally restart scanning to find devices again
                    if (!isScanning) {
                        startBleScan()
                    }
                } else {
                    Toast.makeText(this, "Server update failed: $message", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Server update failed: $message")
                }
            }
        }
    }

    // Menu for API configuration
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Configure API")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                showConfigDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showConfigDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30, 30, 30, 30)

        val tvApiUrl = TextView(this)
        tvApiUrl.text = "API URL:"
        layout.addView(tvApiUrl)

        val etApiUrl = EditText(this)
        etApiUrl.setText(apiUrl)
        layout.addView(etApiUrl)

        val tvAuthToken = TextView(this)
        tvAuthToken.text = "Auth Token:"
        layout.addView(tvAuthToken)

        val etAuthToken = EditText(this)
        etAuthToken.setText(authToken ?: "")
        layout.addView(etAuthToken)

        val tvScannerId = TextView(this)
        tvScannerId.text = "Scanner ID:"
        layout.addView(tvScannerId)

        val etScannerId = EditText(this)
        etScannerId.setText(scannerId)
        layout.addView(etScannerId)

        val tvScannerName = TextView(this)
        tvScannerName.text = "Scanner Name:"
        layout.addView(tvScannerName)

        val etScannerName = EditText(this)
        etScannerName.setText(scannerName)
        layout.addView(etScannerName)

        AlertDialog.Builder(this)
            .setTitle("API Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                apiUrl = etApiUrl.text.toString()
                authToken = etAuthToken.text.toString().takeIf { it.isNotEmpty() }
                scannerId = etScannerId.text.toString()
                scannerName = etScannerName.text.toString()

                // Save to shared preferences
                val prefs = getSharedPreferences("BeaconTrackerPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("apiUrl", apiUrl)
                    .putString("authToken", authToken)
                    .putString("scannerId", scannerId)
                    .putString("scannerName", scannerName)
                    .apply()

                // Recreate the API service with new URL and token
                apiService = BeaconApiService(apiUrl, authToken)

                Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            locationPermissionCode, bluetoothPermissionCode -> {
                // Print debug information
                for (i in permissions.indices) {
                    Log.d(TAG, "Permission ${permissions[i]}: ${if (grantResults[i] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
                }

                // Check which critical permissions we have
                val hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                val hasBluetoothPermission = checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED

                // Try to proceed with whatever permissions we have
                if (hasLocationPermission) {
                    getLocation()
                } else {
                    Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
                }

                if (hasBluetoothPermission) {
                    startBleScan()
                } else {
                    Toast.makeText(this, "Bluetooth permission is required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        // Update the current coordinates
        currentLatitude = location.latitude
        currentLongitude = location.longitude

        // Update the UI
        updateLocationText(location)
    }

    private fun updateLocationText(location: Location) {
        tvLocation.text = "Latitude: ${location.latitude}\nLongitude: ${location.longitude}"
    }

    // These methods must be implemented for LocationListener
    override fun onProviderDisabled(provider: String) {
        Toast.makeText(this, "$provider disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onProviderEnabled(provider: String) {
        Toast.makeText(this, "$provider enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Not used in API 24, but required for LocationListener interface
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onDestroy() {
        super.onDestroy()
        // Remove location updates when activity is destroyed
        locationManager.removeUpdates(this)
        // Stop BLE scan if active
        stopBleScan()
        // Remove any pending handlers
        handler.removeCallbacksAndMessages(null)
    }
}