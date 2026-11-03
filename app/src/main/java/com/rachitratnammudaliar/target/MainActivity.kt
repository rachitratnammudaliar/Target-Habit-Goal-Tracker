package com.rachitratnammudaliar.target

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rachitratnammudaliar.target.ui.TargetRoot
import com.rachitratnammudaliar.target.ui.TargetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Health Connect opens the app to explain why it reads health data: show Settings, which says.
        val rationale = intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE"
        // Debug builds accept extras to show sample data and open a screen, for screenshots.
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("target.demo", false)
        val start = when {
            rationale -> "settings"
            BuildConfig.DEBUG -> intent.getStringExtra("target.screen") ?: "today"
            else -> "today"
        }
        setContent {
            TargetTheme {
                TargetRoot(startScreen = start, demo = demo)
            }
        }
    }
}
