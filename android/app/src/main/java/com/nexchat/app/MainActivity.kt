package com.nexchat.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.nexchat.app.service.NotificationHelper
import com.nexchat.app.ui.NexChatRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val deepLinkConversation = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        deepLinkConversation.value = intent?.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
        observeScreenshotProtection()
        setContent {
            NexChatRoot(
                initialConversationId = deepLinkConversation.value,
                onConversationConsumed = { deepLinkConversation.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkConversation.value = intent.getStringExtra(NotificationHelper.EXTRA_CONVERSATION_ID)
    }

    private fun observeScreenshotProtection() {
        lifecycleScope.launch {
            NexChatApp.from(this@MainActivity).container.settings.state
                .map { it.screenshotProtection }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }
    }
}
