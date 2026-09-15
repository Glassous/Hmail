package com.glassous.hmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.glassous.hmail.ui.HmailApp

class MainActivity : ComponentActivity() {
    val model get() = MailModel.of(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HmailApp(model, this) }
        if (!model.initialized) model.boot()
    }
}
