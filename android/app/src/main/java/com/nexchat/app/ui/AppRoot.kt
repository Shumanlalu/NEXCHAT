package com.nexchat.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexchat.app.AppContainer
import com.nexchat.app.NexChatApp
import com.nexchat.app.data.AuthState
import com.nexchat.app.service.MessagingService
import com.nexchat.app.ui.components.ChromeNLogo
import com.nexchat.app.ui.components.GlassCard
import com.nexchat.app.ui.components.LoadingOrb
import com.nexchat.app.ui.components.MainTabs
import com.nexchat.app.ui.components.NexBottomBar
import com.nexchat.app.ui.components.PrimaryButton
import com.nexchat.app.ui.components.SilverBackground
import com.nexchat.app.ui.screens.AddContactScreen
import com.nexchat.app.ui.screens.ArchivedScreen
import com.nexchat.app.ui.screens.AuthScreen
import com.nexchat.app.ui.screens.BlockedScreen
import com.nexchat.app.ui.screens.ChatScreen
import com.nexchat.app.ui.screens.ChatsScreen
import com.nexchat.app.ui.screens.ContactDetailScreen
import com.nexchat.app.ui.screens.ContactsScreen
import com.nexchat.app.ui.screens.DevicesScreen
import com.nexchat.app.ui.screens.DiscoverScreen
import com.nexchat.app.ui.screens.GroupInfoScreen
import com.nexchat.app.ui.screens.NewGroupScreen
import com.nexchat.app.ui.screens.OnboardingScreen
import com.nexchat.app.ui.screens.ProfileScreen
import com.nexchat.app.ui.screens.SettingsScreen
import com.nexchat.app.ui.theme.LocalNexPalette
import com.nexchat.app.ui.theme.NexChatTheme
import com.nexchat.app.ui.viewmodel.AuthMode

@Composable
fun NexChatRoot(
    initialConversationId: String?,
    onConversationConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { NexChatApp.from(context).container }
    val preferences by container.settings.state.collectAsStateWithLifecycle()
    val authState by container.auth.state.collectAsStateWithLifecycle()
    val startupError by container.startupError.collectAsStateWithLifecycle()

    NexChatTheme(mode = preferences.themeMode) {
        when {
            startupError != null -> StartupErrorScreen(
                message = startupError.orEmpty(),
                onRetry = container::retryBootstrap,
            )
            authState is AuthState.Loading -> StartupLoadingScreen()
            authState is AuthState.LoggedIn -> {
                SessionForegroundService()
                MainShell(
                    container = container,
                    initialConversationId = initialConversationId,
                    onConversationConsumed = onConversationConsumed,
                )
            }
            else -> AuthFlow(
                onboarded = preferences.onboardingCompleted,
            )
        }
    }
}

@Composable
private fun StartupLoadingScreen() {
    val palette = LocalNexPalette.current
    SilverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChromeNLogo(size = 128.dp)
            Spacer(Modifier.height(26.dp))
            LoadingOrb(size = 28.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Opening your encrypted space…",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartupErrorScreen(message: String, onRetry: () -> Unit) {
    val palette = LocalNexPalette.current
    SilverBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassCard(
                strong = true,
                corner = 28.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = palette.danger,
                        modifier = Modifier.size(42.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "The encrypted store could not be opened",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "If this repeats after reinstalling, the device keystore may have been reset. Your account itself is not affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        text = "Try again",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthFlow(onboarded: Boolean) {
    var mode by rememberSaveable { mutableStateOf<AuthMode?>(null) }
    if (!onboarded && mode == null) {
        OnboardingScreen(
            onCreateAccount = { mode = AuthMode.REGISTER },
            onSignIn = { mode = AuthMode.LOGIN },
        )
    } else {
        AuthScreen(
            initialMode = mode ?: AuthMode.LOGIN,
            onBack = if (!onboarded) {
                { mode = null }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SessionForegroundService() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val intent = Intent(context, MessagingService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        onDispose {
            runCatching { context.stopService(intent) }
        }
    }
}

@Composable
private fun MainShell(
    container: AppContainer,
    initialConversationId: String?,
    onConversationConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = MainTabs.any { it.route == route }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (!askedForNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(initialConversationId) {
        if (!initialConversationId.isNullOrBlank()) {
            nav.navigate("chat/${Uri.encode(initialConversationId)}")
            onConversationConsumed()
        }
    }

    SilverBackground {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "chats",
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                composable("chats") {
                    ChatsScreen(
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                        onOpenArchived = { nav.navigate("archived") },
                        onOpenContacts = { nav.openTab("contacts") },
                    )
                }
                composable("contacts") {
                    ContactsScreen(
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                        onAddContact = { nav.navigate("add-contact") },
                        onNewGroup = { nav.navigate("new-group") },
                        onOpenBlocked = { nav.navigate("blocked") },
                    )
                }
                composable("discover") {
                    DiscoverScreen(
                        onOpenProfile = { nav.navigate("profile") },
                        onOpenSettings = { nav.openTab("settings") },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onOpenProfile = { nav.navigate("profile") },
                        onOpenDevices = { nav.navigate("devices") },
                    )
                }
                composable(
                    route = "chat/{conversationId}",
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                ) { entry ->
                    ChatScreen(
                        conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpenContact = { nav.navigate("contact/${Uri.encode(it)}") },
                        onOpenGroup = { nav.navigate("group/${Uri.encode(it)}") },
                    )
                }
                composable("archived") {
                    ArchivedScreen(
                        onBack = { nav.popBackStack() },
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                    )
                }
                composable("blocked") {
                    BlockedScreen(
                        onBack = { nav.popBackStack() },
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                    )
                }
                composable("add-contact") {
                    AddContactScreen(
                        onBack = { nav.popBackStack() },
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                    )
                }
                composable("new-group") {
                    NewGroupScreen(
                        onBack = { nav.popBackStack() },
                        onCreated = { conversationId ->
                            nav.navigate("chat/${Uri.encode(conversationId)}") {
                                popUpTo("new-group") { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    route = "contact/{nexId}",
                    arguments = listOf(navArgument("nexId") { type = NavType.StringType }),
                ) { entry ->
                    ContactDetailScreen(
                        nexId = entry.arguments?.getString("nexId").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpenConversation = { nav.navigate("chat/${Uri.encode(it)}") },
                    )
                }
                composable(
                    route = "group/{groupId}",
                    arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                ) { entry ->
                    GroupInfoScreen(
                        groupId = entry.arguments?.getString("groupId").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onLeft = {
                            nav.navigate("chats") {
                                popUpTo("chats") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable("profile") {
                    ProfileScreen(onBack = { nav.popBackStack() })
                }
                composable("devices") {
                    DevicesScreen(onBack = { nav.popBackStack() })
                }
            }

            if (showBottomBar) {
                NexBottomBar(
                    currentRoute = route,
                    onSelect = { nav.openTab(it) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
