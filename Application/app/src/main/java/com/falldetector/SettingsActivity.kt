package com.falldetector

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {

    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPrefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        setupUI()
    }

    private fun setupUI() {
        val radioGroup = findViewById<RadioGroup>(R.id.locationModeGroup)
        val editUpdatePeriod = findViewById<EditText>(R.id.editUpdatePeriod)
        val editFreshness = findViewById<EditText>(R.id.editFreshness)
        val editAccuracy = findViewById<EditText>(R.id.editAccuracy)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        
        // Load Mode
        val currentMode = sharedPrefs.getInt("LOCATION_MODE", 0)
        when (currentMode) {
            1 -> findViewById<RadioButton>(R.id.radioNetwork).isChecked = true
            2 -> findViewById<RadioButton>(R.id.radioGoogleBalanced).isChecked = true
            3 -> findViewById<RadioButton>(R.id.radioGooglePassiveSmart).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioGps).isChecked = true
        }

        // Load custom attributes
        val updatePeriod = sharedPrefs.getLong("UPDATE_PERIOD_MS", 60000L)
        val freshnessThreshold = sharedPrefs.getLong("FRESHNESS_THRESHOLD_MS", 600000L)
        val accuracyThreshold = sharedPrefs.getFloat("ACCURACY_THRESHOLD_M", 1000f)

        editUpdatePeriod.setText(updatePeriod.toString())
        editFreshness.setText(freshnessThreshold.toString())
        editAccuracy.setText(accuracyThreshold.toString())

        btnSave.setOnClickListener {
            val selectedMode = when (radioGroup.checkedRadioButtonId) {
                R.id.radioNetwork -> 1
                R.id.radioGoogleBalanced -> 2
                R.id.radioGooglePassiveSmart -> 3
                else -> 0
            }

            val newUpdatePeriod = editUpdatePeriod.text.toString().toLongOrNull() ?: 60000L
            val newFreshness = editFreshness.text.toString().toLongOrNull() ?: 600000L
            val newAccuracy = editAccuracy.text.toString().toFloatOrNull() ?: 1000f

            sharedPrefs.edit().apply {
                putInt("LOCATION_MODE", selectedMode)
                putLong("UPDATE_PERIOD_MS", newUpdatePeriod)
                putLong("FRESHNESS_THRESHOLD_MS", newFreshness)
                putFloat("ACCURACY_THRESHOLD_M", newAccuracy)
            }.apply()
            
            Toast.makeText(this, "Settings saved. Restarting tracking...", Toast.LENGTH_SHORT).show()
            restartLocationService()
        }
    }

    private fun restartLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
        startForegroundService(serviceIntent)
    }
}