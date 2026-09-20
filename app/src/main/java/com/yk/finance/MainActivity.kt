package com.yk.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yk.finance.ui.FinanceApp
import com.yk.finance.ui.theme.FinanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FinanceApplication
        setContent {
            FinanceTheme {
                FinanceApp(app)
            }
        }
    }
}
