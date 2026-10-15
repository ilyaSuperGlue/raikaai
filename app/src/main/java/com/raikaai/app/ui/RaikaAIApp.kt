package com.raikaai.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.raikaai.app.ui.theme.success
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.raikaai.app.ui.about.AboutScreen
import com.raikaai.app.ui.characters.CharactersScreen
import com.raikaai.app.ui.chat.ChatScreen
import com.raikaai.app.ui.chat.ChatViewModel
import com.raikaai.app.ui.history.HistoryScreen
import com.raikaai.app.ui.logs.LogsScreen
import com.raikaai.app.ui.models.ModelsScreen
import com.raikaai.app.ui.sampler.SamplerScreen
import com.raikaai.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

object NavRoute {
    const val CHAT = "chat"
    const val CHARACTERS = "characters"
    const val SKILLS = "skills"
    const val SAMPLER = "sampler"
    const val MODELS = "models"
    const val SERVER = "server"
    const val LOGS = "logs"
    const val HISTORY = "history"
    const val MCP = "mcp"
    const val ENVIRONMENT = "environment"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

data class DrawerMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Root Application Container with Swipeable Modal Navigation Drawer (Sidebar).
 * Replicating ChatterUI's modular menu organization, mode toggle, and design structure.
 */
@Composable
fun RaikaAIApp(
    viewModel: ChatViewModel,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoute.CHAT

    val conversations = viewModel.conversations.value

    fun navigateTo(route: String) {
        scope.launch { drawerState.close() }
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true, // Edge swipe opens drawer
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(290.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp)
                            .statusBarsPadding(),
                ) {
                    // Header: brand + close only
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "RaikaAI",
                            style = MaterialTheme.typography.titleLarge,
                        )

                        IconButton(
                            onClick = { scope.launch { drawerState.close() } },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Drawer", modifier = Modifier.size(18.dp))
                        }
                    }

                    // New chat
                    OutlinedButton(
                        onClick = {
                            viewModel.newChat()
                            navigateTo(NavRoute.CHAT)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New chat")
                    }

                    Spacer(Modifier.height(8.dp))

                    var showMore by remember { mutableStateOf(false) }
                    val mainItems =
                        listOf(
                            DrawerMenuItem(NavRoute.CHARACTERS, "Characters", Icons.Outlined.Psychology),
                            DrawerMenuItem(NavRoute.MODELS, "Models", Icons.Outlined.Memory),
                            DrawerMenuItem(NavRoute.SAMPLER, "Generation Parameters", Icons.Outlined.Tune),
                            DrawerMenuItem(NavRoute.HISTORY, "History", Icons.Outlined.History),
                            DrawerMenuItem(NavRoute.SETTINGS, "Settings", Icons.Outlined.Settings),
                        )
                    val moreItems =
                        listOf(
                            DrawerMenuItem(NavRoute.SERVER, "Server", Icons.Outlined.Dns),
                            DrawerMenuItem(NavRoute.SKILLS, "Skills", Icons.Outlined.AutoAwesome),
                            DrawerMenuItem(NavRoute.MCP, "MCP Servers", Icons.Outlined.Build),
                            DrawerMenuItem(NavRoute.ENVIRONMENT, "Linux Env", Icons.Default.Terminal),
                            DrawerMenuItem(NavRoute.LOGS, "Telemetry Logs", Icons.Outlined.Terminal),
                            DrawerMenuItem(NavRoute.ABOUT, "About", Icons.Outlined.Info),
                        )

                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(conversations.take(10), key = { it.id }) { conv ->
                            DrawerRow(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                label = conv.title.ifBlank { "New conversation" },
                                selected = conv.id == viewModel.currentConversationId,
                                onClick = {
                                    viewModel.loadConversation(conv.id)
                                    navigateTo(NavRoute.CHAT)
                                },
                            )
                        }
                        items(mainItems, key = { it.route }) { item ->
                            DrawerRow(
                                icon = item.icon,
                                label = item.label,
                                selected = currentRoute == item.route,
                                onClick = { navigateTo(item.route) },
                            )
                        }
                        item(key = "more_toggle") {
                            DrawerRow(
                                icon = Icons.Outlined.MoreHoriz,
                                label = if (showMore) "Less" else "More",
                                selected = false,
                                onClick = { showMore = !showMore },
                            )
                        }
                        if (showMore) {
                            items(moreItems, key = { it.route }) { item ->
                                DrawerRow(
                                    icon = item.icon,
                                    label = item.label,
                                    selected = currentRoute == item.route,
                                    onClick = { navigateTo(item.route) },
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoute.CHAT,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(NavRoute.CHAT) {
                    ChatScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateToCharacters = {
                            navController.navigate(NavRoute.CHARACTERS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToModels = {
                            navController.navigate(NavRoute.MODELS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.CHARACTERS) {
                    CharactersScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onStartChatWithCharacter = {
                            navController.navigate(NavRoute.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.SKILLS) {
                    com.raikaai.app.ui.skills.SkillsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.MCP) {
                    com.raikaai.app.ui.mcp.McpScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.ENVIRONMENT) {
                    com.raikaai.app.ui.environment.EnvironmentScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateToChat = {
                            navController.navigate(NavRoute.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.SAMPLER) {
                    SamplerScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.MODELS) {
                    ModelsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.SERVER) {
                    com.raikaai.app.ui.server.ServerScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.LOGS) {
                    LogsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.HISTORY) {
                    HistoryScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                        onOpenConversation = {
                            navController.navigate(NavRoute.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.ABOUT) {
                    AboutScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
            }
        }
    }
}

/**
 * Minimal 48dp drawer row: outline icon only, selected state uses neutral
 * surfaceContainerHigh without primary tint.
 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
