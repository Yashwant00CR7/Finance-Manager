package com.yk.finance

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yk.finance.ui.FinanceApp
import com.yk.finance.ui.theme.FinanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FinanceApplication

        // The header is DarkBar in both light and dark themes and now paints behind
        // the status bar, so the system icons have to stay light whatever the phone's
        // theme is. The default auto() flips them to dark on a light-themed phone,
        // which puts dark icons on a dark header and makes the clock unreadable.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContent {
            FinanceTheme {
                FinanceApp(app)
            }
        }
    }
}
