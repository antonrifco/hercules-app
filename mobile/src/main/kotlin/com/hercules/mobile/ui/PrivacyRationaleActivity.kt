package com.hercules.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/** Opened by Health Connect when the user asks how Hercules uses their health data. Shows the privacy policy. */
class PrivacyRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HerculesTheme(true) {
                Box(Modifier.fillMaxSize().background(Ink)) {
                    LegalScreen(LegalDoc.PRIVACY, WindowInsets.systemBars.asPaddingValues())
                }
            }
        }
    }
}
