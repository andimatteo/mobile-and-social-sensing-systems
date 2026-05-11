package com.falldetector

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : ComponentActivity() {

    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var statusText: TextView

    private val authPrefs by lazy { getSharedPreferences("Auth", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (authPrefs.getBoolean("LOGGED_IN", false)) {
            navigateToMain()
            return
        }

        setContentView(R.layout.activity_login)

        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        statusText = findViewById(R.id.textStatus)

        btnLogin.setOnClickListener { submit("/login") }
        btnRegister.setOnClickListener { submit("/register") }
    }

    private fun submit(endpoint: String) {
        val username = editUsername.text.toString().trim()
        val password = editPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Insert username and password", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        Thread {
            val (ok, message) = postCredentials(endpoint, username, password)
            runOnUiThread {
                setLoading(false)
                if (ok) {
                    authPrefs.edit().apply {
                        putBoolean("LOGGED_IN", true)
                        putString("USERNAME", username)
                    }.apply()
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    statusText.text = message
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun postCredentials(endpoint: String, username: String, password: String): Pair<Boolean, String> {
        val baseUrl = getString(R.string.backend_base_url)
        val url = URL(baseUrl + endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val payload = JSONObject()
        payload.put("username", username)
        payload.put("password", password)

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(payload.toString())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        val message = if (responseBody.isNotBlank()) responseBody else connection.responseMessage

        return Pair(code in 200..299, message)
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        btnRegister.isEnabled = !loading
        statusText.text = if (loading) "Sending request..." else ""
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
