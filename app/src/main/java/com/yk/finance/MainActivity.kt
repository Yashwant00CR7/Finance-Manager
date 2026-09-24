package com.yk.finance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yk.finance.ui.FinanceApp
import com.yk.finance.ui.theme.FinanceTheme
import com.yk.finance.widget.WidgetRefresh
import com.yk.finance.widget.WidgetRoute

class MainActivity : ComponentActivity() {

    /**
     * The destination a widget tap asked for.
     *
     * Held as state rather than read once, because the activity is singleTop: tapping
     * the widget while the app is already open delivers onNewIntent, not onCreate, and
     * a value read only at startup would be silently dropped in exactly that case.
     *
     * The counter suffix makes each tap a distinct value. Without it, tapping the same
     * widget button twice produces an identical string, the LaunchedEffect keyed on it
     * does not re-run, and the second tap does nothing - which reads as the app being
     * broken rather than as a deduplicated key.
     */
    private var widgetRoute by mutableStateOf<String?>(null)
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FinanceApplication
        consume(intent)

        // The header is DarkBar in both light and dark themes and now paints behind
        // the status bar, so the system icons have to stay light whatever the phone's
        // theme is. The default auto() flips them to dark on a light-themed phone,
        // which puts dark icons on a dark header and makes the clock unreadable.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContent {
            FinanceTheme {
                FinanceApp(app, widgetRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        val route = intent?.getStringExtra(WidgetRoute.EXTRA) ?: return
        taps++
        widgetRoute = "$route#$taps"
        // Consumed, so a configuration change does not replay the navigation and yank
        // you back to Budgets after you have walked somewhere else.
        intent.removeExtra(WidgetRoute.EXTRA)
    }

    /**
     * The widget reads the ledger directly, so it only needs telling that something
     * changed. Leaving is the moment everything the session did has landed.
     */
    override fun onStop() {
        super.onStop()
        WidgetRefresh.request(this)
    }
}
