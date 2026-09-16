package com.glassous.hmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.glassous.hmail.ui.HmailApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    val model get() = MailModel.of(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                model.also { it.restoreLocalMailbox() }
            }
            setContent { HmailApp(ready, this@MainActivity) }
            if (!ready.initialized) ready.boot()
        }
    }
}
