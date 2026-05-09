package com.falldetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.AssetFileDescriptor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var tfliteInterpreter: Interpreter? = null
    private val WINDOW_SIZE = 453
    private val sensorDataBuffer = mutableListOf<FloatArray>()

    private val THRESHOLD = 2.0f
    private var isHighFrequency = false
    private var downgradeFrequencyRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallTimerRunnable: Runnable? = null
    private val RESPONSE_TIME_MS = 30000L
    private val DOWNGRADE_DELAY_MS = 5000L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        try {
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            tfliteInterpreter = Interpreter(loadModelFile("model.tflite"), options)
            Log.d("FallDetector", "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e("FallDetector", "Error loading model: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_CANCEL_ALERT") {
            cancelFallAlert()
            return START_STICKY
        }

        startForegroundServiceWithNotification()
        setSensorFrequency(SensorManager.SENSOR_DELAY_NORMAL)
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val delta = abs(magnitude - 9.81f)

            if (delta > THRESHOLD) {
                if (!isHighFrequency) {
                    Log.d("FallDetector", "Switching to higher frequency (f2)")
                    setSensorFrequency(SensorManager.SENSOR_DELAY_GAME)
                }
                downgradeFrequencyRunnable?.let { mainHandler.removeCallbacks(it) }
                downgradeFrequencyRunnable = null

            } else if (isHighFrequency) {
                if (downgradeFrequencyRunnable == null) {
                    downgradeFrequencyRunnable = Runnable {
                        Log.d("FallDetector", "Going back to lower frequency (f1)")
                        setSensorFrequency(SensorManager.SENSOR_DELAY_NORMAL)
                        sensorDataBuffer.clear()
                    }
                    mainHandler.postDelayed(downgradeFrequencyRunnable!!, DOWNGRADE_DELAY_MS)
                }
            }

            if (isHighFrequency) {
                sensorDataBuffer.add(floatArrayOf(x, y, z))

                if (sensorDataBuffer.size >= WINDOW_SIZE) {
                    runInference(sensorDataBuffer.toList())

                    repeat(100) { if(sensorDataBuffer.isNotEmpty()) sensorDataBuffer.removeAt(0) }
                }
            }
        }
    }

    private fun runInference(window: List<FloatArray>) {
        if (tfliteInterpreter == null) return

        val input = Array(1) { Array(WINDOW_SIZE) { FloatArray(1) } }

        for (i in 0 until WINDOW_SIZE) {
            val x = window[i][0]
            val y = window[i][1]
            val z = window[i][2]

            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            input[0][i][0] = magnitude
        }

        val output = Array(1) { FloatArray(1) }
        tfliteInterpreter?.run(input, output)

        if (output[0][0] > 0.8f) {
            onFallDetected()
        }
    }

    private fun onFallDetected() {
        if (fallTimerRunnable != null) return

        Log.w("FallDetector", "Fall detected")
        sendInterventionNotification()

        fallTimerRunnable = Runnable {
            triggerCriticalAlerts()
        }
        mainHandler.postDelayed(fallTimerRunnable!!, RESPONSE_TIME_MS)
    }

    private fun cancelFallAlert() {
        Log.d("FallDetector", "Canceling alert timer...")
        fallTimerRunnable?.let {
            mainHandler.removeCallbacks(it)
            fallTimerRunnable = null
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(2)
    }

    private fun triggerCriticalAlerts() {
        Log.e("FallDetector", "🚨 Timer expired.")
        fallTimerRunnable = null
        sendPostRequest()
        broadcastBLESignal()
    }

    private fun setSensorFrequency(delay: Int) {
        sensorManager.unregisterListener(this)
        accelerometer?.let {
            sensorManager.registerListener(this, it, delay)
            isHighFrequency = (delay == SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "FallDetectorChannel"
        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            "Fall Detection Background Service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Fall Detection Attivo")
            .setContentText("Monitoraggio sensori in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun sendInterventionNotification() {
        val cancelIntent = Intent(this, FallDetectionService::class.java).apply {
            action = "ACTION_CANCEL_ALERT"
        }
        val pendingCancelIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "FallDetectorAlertChannel"
        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            "Emergenze Caduta",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠️ Caduta Rilevata!")
            .setContentText("Tutto ok? I soccorsi verranno allertati tra 30 secondi.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STO BENE", pendingCancelIntent)
            .build()

        manager.notify(2, notification)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        tfliteInterpreter?.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun sendPostRequest() {}
    private fun broadcastBLESignal() {}
}