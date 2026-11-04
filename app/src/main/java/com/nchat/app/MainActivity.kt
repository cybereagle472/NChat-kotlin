package com.nchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nchat.app.core.util.CrashLog
import com.nchat.app.data.ServiceLocator
import com.nchat.app.ui.CrashReportScreen
import com.nchat.app.ui.NChatApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Composition root: the backend address comes from BuildConfig (see nchat.properties),
        // so there is nothing to configure here at runtime.
        ServiceLocator.init(applicationContext)
        setContent {
            val context = LocalContext.current
            // If the previous launch died, show why before anything else. Reading and
            // deleting one small text file cannot fail in a way that matters.
            var report by remember { mutableStateOf(CrashLog.read(context)) }
            val pending = report
            if (pending != null) {
                CrashReportScreen(
                    report = pending,
                    onDismiss = {
                        CrashLog.clear(context)
                        report = null
                    }
                )
            } else {
                NChatApp()
            }
        }
    }
}
