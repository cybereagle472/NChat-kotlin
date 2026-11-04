package com.nchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nchat.app.data.ServiceLocator
import com.nchat.app.ui.NChatApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Composition root: the backend address comes from BuildConfig (see nchat.properties),
        // so there is nothing to configure here at runtime.
        ServiceLocator.init(applicationContext)
        setContent {
            NChatApp()
        }
    }
}
