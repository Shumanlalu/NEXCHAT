package com.nexchat.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexchat.app.AppContainer
import com.nexchat.app.NexChatApp

@Composable
inline fun <reified T : ViewModel> nexViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> T,
): T {
    val context = LocalContext.current
    val container = (context.applicationContext as NexChatApp).container
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}

val ScreenPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
