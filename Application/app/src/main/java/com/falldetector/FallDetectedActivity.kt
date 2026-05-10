package com.falldetector

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class FallDetectedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_falldetected)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // logic for sending the alert
            }
        })

        val btnImFine = findViewById<Button>(R.id.button3)

        btnImFine.setOnClickListener {

            val cancelIntent = Intent(this, FallDetectionService::class.java).apply {
                action = "ACTION_CANCEL_ALERT"
            }
            startService(cancelIntent)

            finish()
        }
    }
}