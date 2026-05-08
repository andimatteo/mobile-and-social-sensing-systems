package com.falldetector

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LocationService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPrefs: SharedPreferences

    private val scope = CoroutineScope(Dispatchers.IO)
    private val backendUrl = "http://10.0.2.2:5000/location"

    // 0 = GPS, 1 = Network, 2 = Google Balanced, 3 = Google Smart Passive
    private var currentMode = 0
    private var isTracking = false
    private var latestLocation: Location? = null
    
    // Configurable Params
    private var updatePeriodMs = 60000L
    private var freshnessThresholdMs = 600000L
    private var accuracyThresholdM = 1000f

    // Classic listener
    private val classicLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d("LocationService", "Classic onLocationChanged (Mode $currentMode)")
            latestLocation = location
        }
    }

    // Fused Location callback
    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            Log.d("LocationService", "Fused onLocationResult (Mode $currentMode)")
            latestLocation = location
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPrefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentMode = sharedPrefs.getInt("LOCATION_MODE", 0)
        updatePeriodMs = sharedPrefs.getLong("UPDATE_PERIOD_MS", 60000L)
        freshnessThresholdMs = sharedPrefs.getLong("FRESHNESS_THRESHOLD_MS", 600000L)
        accuracyThresholdM = sharedPrefs.getFloat("ACCURACY_THRESHOLD_M", 1000f)

        Log.d("LocationService", "Service started: Mode=$currentMode Period=${updatePeriodMs}ms Fresh=${freshnessThresholdMs}ms Acc=${accuracyThresholdM}m")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "LocationServiceChannel")
            .setContentTitle("Location Tracker")
            .setContentText(getModeName(currentMode))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(2, notification)

        stopAllUpdates()
        isTracking = true
        startUpdatesForCurrentMode()
        startPeriodicReporting()

        return START_STICKY
    }

    private fun startUpdatesForCurrentMode() {
        when (currentMode) {
            0 -> {
                // Pure GPS -> requires FINE
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("LocationService", "Missing ACCESS_FINE_LOCATION for GPS mode")
                    return
                }
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    Log.e("LocationService", "GPS provider disabled")
                    return
                }
                Log.d("LocationService", "Registering GPS provider updates")
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    updatePeriodMs,
                    0f,
                    classicLocationListener,
                    Looper.getMainLooper()
                )
            }
            1 -> {
                // Pure Network -> accepts COARSE or FINE
                val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) {
                    Log.e("LocationService", "Missing ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION for Network mode")
                    return
                }
                if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    Log.e("LocationService", "Network provider disabled")
                    return
                }
                Log.d("LocationService", "Registering Network provider updates (hasFine=$hasFine hasCoarse=$hasCoarse)")
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    updatePeriodMs,
                    0f,
                    classicLocationListener,
                    Looper.getMainLooper()
                )
            }
            2 -> {
                // Google Balanced Power
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("LocationService", "Missing ACCESS_FINE_LOCATION for Balanced fused mode")
                    return
                }
                val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, updatePeriodMs)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                Log.d("LocationService", "Requesting fused balanced updates")
                fusedLocationClient.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper())
            }
            3 -> {
                // Google Passive + Smart Refresh
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("LocationService", "Missing ACCESS_FINE_LOCATION for Passive fused mode")
                    return
                }
                val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, updatePeriodMs)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                Log.d("LocationService", "Requesting fused passive updates")
                fusedLocationClient.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper())

                // Immediately check if we need an active update
                checkAndRequestPreciseLocationIfNeeded()
            }
        }
    }

    private fun startPeriodicReporting() {
        scope.launch {
            while (isTracking) {
                if (currentMode == 3) {
                    handlePassiveSmartMode()
                } else {
                    latestLocation?.let { 
                        Log.d("LocationService", "Periodic report (Mode $currentMode)")
                        sendLocationToBackend(it) 
                    }
                }
                kotlinx.coroutines.delay(updatePeriodMs) // Dynamic wait
            }
        }
    }

    private var isRequestingPreciseRightNow = false
    private var lastSentTime = 0L

    private fun handlePassiveSmartMode() {
        val location = latestLocation
        val timeNowMs = System.currentTimeMillis()
        
        if (location == null) {
             Log.d("LocationService", "No passive location yet. Requesting active fix.")
             checkAndRequestPreciseLocationIfNeeded()
             return
        }

        val ageMs = timeNowMs - location.time
        Log.d("LocationService", "Passive check. Age: ${ageMs/1000}s, Accuracy: ${location.accuracy}m")

        if (ageMs > freshnessThresholdMs || location.accuracy > accuracyThresholdM) {
            Log.d("LocationService", "Fix is too old or inaccurate. Requesting active fix.")
            checkAndRequestPreciseLocationIfNeeded()
        } else {
            sendLocationToBackend(location)
            lastSentTime = timeNowMs
        }
    }

    private fun checkAndRequestPreciseLocationIfNeeded() {
        if (isRequestingPreciseRightNow) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        isRequestingPreciseRightNow = true
        Log.d("LocationService", "Requesting Single High Accuracy Update...")

        // Request a single, high-accuracy update.
        // FusedLocationClient handles turning GPS on and off.
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d("LocationService", "Got single high accuracy update!")
                    latestLocation = location
                    sendLocationToBackend(location)
                    lastSentTime = System.currentTimeMillis()
                } else {
                    Log.w("LocationService", "Single high accuracy update returned null.")
                }
                isRequestingPreciseRightNow = false
            }
            .addOnFailureListener {
                Log.e("LocationService", "Failed to get single update", it)
                isRequestingPreciseRightNow = false
            }
    }

    private fun sendLocationToBackend(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val freshnessMs = System.currentTimeMillis() - location.time
        Log.d("LocationService", "Sending to backend -> Mode: $currentMode, Lat: $lat, Lon: $lon, Acc: ${location.accuracy}, Freshness: ${freshnessMs}ms")

        scope.launch {
            try {
                val url = URL(backendUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonStr = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lon)
                    put("mode", currentMode)
                    put("accuracy", location.accuracy)
                    put("freshness", freshnessMs)
                }.toString()

                OutputStreamWriter(connection.outputStream).use { it.write(jsonStr) }
                connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("LocationService", "Error sending location to backend", e)
            }
        }
    }

    private fun stopAllUpdates() {
        isTracking = false
        locationManager.removeUpdates(classicLocationListener)
        fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllUpdates()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "LocationServiceChannel",
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getModeName(mode: Int): String {
        return when (mode) {
            0 -> "Tracking: Pure GPS"
            1 -> "Tracking: Network"
            2 -> "Tracking: Google Balanced"
            3 -> "Tracking: Google Smart Passive"
            else -> "Tracking..."
        }
    }
}