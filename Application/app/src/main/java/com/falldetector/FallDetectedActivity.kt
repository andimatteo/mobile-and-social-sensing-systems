package com.falldetector

import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import android.os.CountDownTimer
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FallDetectedActivity : ComponentActivity() {

    private var countDownTimer: CountDownTimer? = null
    private val TOTAL_TIME = 30000L

    private val backendUrl = "http://10.0.2.2:5000/alert"

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_falldetected)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // logic for sending the alert
            }
        })

        val btnImFine = findViewById<Button>(R.id.button3)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        startCountdown(progressBar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            }
        })

        btnImFine.setOnClickListener {

            val cancelIntent = Intent(this, FallDetectionService::class.java).apply {
                action = "ACTION_CANCEL_ALERT"
            }
            startService(cancelIntent)

            sendPostRequest()

            finish()
        }
    }

    private fun sendPostRequest() {
        scope.launch {
            try {
                val url = URL(backendUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonStr = JSONObject().apply {
                    put("event", "FALL_CONFIRMED")
                    put("device", Build.MODEL)
                }.toString()

                OutputStreamWriter(connection.outputStream).use { it.write(jsonStr) }

                val code = connection.responseCode
                Log.d("FallDetector", "Request sent. Response Code: $code")

                connection.disconnect()
            } catch (e: Exception) {
                Log.e("FallDetector", "Error sanding the request", e)
            }
        }
    }

    private fun startCountdown(progressBar: ProgressBar) {
        countDownTimer = object : CountDownTimer(TOTAL_TIME, 100) {
            override fun onTick(millisUntilFinished: Long) {
                progressBar.progress = millisUntilFinished.toInt()
            }

            override fun onFinish() {
                progressBar.progress = 0
                sendPostRequest()
                finish()
            }
        }.start()
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimer()
    }
}