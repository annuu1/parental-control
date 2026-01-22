package com.example.telegramsender

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TokenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // If token valid, skip this screen
        if (TokenManager.isTokenValid(this)) {
            val i = Intent(this, MainActivity::class.java)
            // Preserve intent extras if any?
            startActivity(i)
            finish()
            return
        }

        setContentView(R.layout.activity_token)

        val input = findViewById<EditText>(R.id.tokenInput)
        val btn = findViewById<Button>(R.id.validateButton)
        val status = findViewById<TextView>(R.id.statusText)

        btn.setOnClickListener {
            val token = input.text.toString().trim()
            if (token.isEmpty()) {
                status.text = "Please enter a token"
                return@setOnClickListener
            }

            // Temp save to check validity
            TokenManager.saveToken(this, token)
            
            if (TokenManager.isTokenValid(this)) {
                Toast.makeText(this, "Access Granted", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                status.text = "Invalid or Expired Token"
                // Clear invalid token
                TokenManager.saveToken(this, "") 
            }
        }
    }
}
