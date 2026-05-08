package com.xs.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xs.reader.ui.bookmark.BookmarksScreen
import com.xs.reader.ui.reader.ReaderScreen
import com.xs.reader.ui.settings.SettingsScreen
import com.xs.reader.ui.settings.TtsSettingsScreen
import com.xs.reader.ui.shelf.ShelfScreen

sealed class TopRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Shelf : TopRoute("shelf", "书架", Icons.AutoMirrored.Outlined.LibraryBooks)
    data object Bookmarks : TopRoute("bookmarks", "书签", Icons.Outlined.Bookmark)
    data object Settings : TopRoute("settings", "设置", Icons.Outlined.Settings)
}

private val topRoutes = listOf(TopRoute.Shelf, TopRoute.Bookmarks, TopRoute.Settings)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = topRoutes.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topRoutes.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateBottom(nav, dest.route) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = TopRoute.Shelf.route) {
                composable(TopRoute.Shelf.route) {
                    ShelfScreen(
                        onOpenBook = { bookId -> nav.navigate("reader/$bookId") }
                    )
                }
                composable(TopRoute.Bookmarks.route) {
                    BookmarksScreen(
                        onOpenBookmark = { bookId, chapter, offset ->
                            nav.navigate("reader/$bookId?chapter=$chapter&offset=$offset")
                        }
                    )
                }
                composable(TopRoute.Settings.route) {
                    SettingsScreen(
                        onOpenTtsSettings = { nav.navigate("settings/tts") }
                    )
                }
                composable("settings/tts") {
                    TtsSettingsScreen(onBack = { nav.popBackStack() })
                }
                composable(
                    route = "reader/{bookId}?chapter={chapter}&offset={offset}",
                    arguments = listOf(
                        androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType },
                        androidx.navigation.navArgument("chapter") {
                            type = androidx.navigation.NavType.IntType; defaultValue = -1
                        },
                        androidx.navigation.navArgument("offset") {
                            type = androidx.navigation.NavType.IntType; defaultValue = -1
                        }
                    )
                ) { entry ->
                    val args = entry.arguments!!
                    ReaderScreen(
                        bookId = args.getLong("bookId"),
                        jumpChapter = args.getInt("chapter").takeIf { it >= 0 },
                        jumpOffset = args.getInt("offset").takeIf { it >= 0 },
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}

private fun navigateBottom(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
