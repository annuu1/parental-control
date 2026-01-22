package com.example.telegramsender

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        
        val input = findViewById<EditText>(R.id.codeParams)
        val funnyText = findViewById<TextView>(R.id.funnyText)
        
        input.requestFocus()

        input.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                
                checkCode(input.text.toString(), funnyText)
                return@setOnEditorActionListener true
            }
            false
        }
        
    }
    
    private fun checkCode(enteredCode: String, funnyText: TextView) {
        val currentContext = Calendar.getInstance()
        val currentMinute = SimpleDateFormat("mm", Locale.getDefault()).format(currentContext.time)
        
        // Remove leading zeros if user typed single digit
        val cleanInput = enteredCode.trimStart('0')
        val cleanMinute = currentMinute.trimStart('0')
        
        if (cleanInput == cleanMinute) {
            // Success
            
            // Check Token Logic
            val targetActivity = if (TokenManager.isTokenValid(this)) {
                MainActivity::class.java
            } else {
                TokenActivity::class.java
            }
            
            val intent = Intent(this, targetActivity)
            // Clear back stack so user can't go back to Lock
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } else {
            // Failure: Show funny animation/text
            showFunnyFeedback(enteredCode, funnyText)
        }
    }
    
    private fun showFunnyFeedback(code: String, textView: TextView) {
        val feedbacks = listOf(
            "NOPE $code",
            "TRY HARDER",
            "LOL $code?",
            "NICE TRY",
            "ACCESS DENIED",
            ":(",
            "$code IS WRONG",
            "ERROR 404"
        )
        val randomText = feedbacks[Random.nextInt(feedbacks.size)]
        
        textView.text = randomText
        textView.animate()
            .rotation(Random.nextFloat() * 30 - 15)
            .scaleX(1.5f)
            .scaleY(1.5f)
            .setDuration(300)
            .withEndAction {
                textView.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            .start()
            
        // Clear input for next try
        findViewById<EditText>(R.id.codeParams).text.clear()
    }
    
    // Prevent back button
    override fun onBackPressed() {
       // Do nothing or minimize
       super.onBackPressed()
    }
}
