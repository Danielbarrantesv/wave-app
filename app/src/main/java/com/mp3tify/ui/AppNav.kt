package com.mp3tify.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mp3tify.ui.screens.drive.DriveModeScreen
import com.mp3tify.ui.screens.equalizer.EqualizerScreen
import com.mp3tify.ui.screens.library.LibraryScreen
import com.mp3tify.ui.screens.lyrics.LyricsScreen
import com.mp3tify.ui.screens.nowplaying.NowPlayingScreen
import com.mp3tify.ui.screens.playlist.PlaylistScreen
import com.mp3tify.ui.screens.queue.QueueScreen
import com.mp3tify.ui.screens.search.SearchScreen
import com.mp3tify.ui.screens.settings.SettingsScreen

object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val LYRICS = "lyrics"
    const val EQUALIZER = "equalizer"
    const val DRIVE = "drive"
    const val PLAYLIST = "playlist/{playlistId}"

    fun playlist(id: Long): String = "playlist/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) },
                onOpenPlaylist = { id -> nav.navigate(Routes.playlist(id)) },
                onOpenDriveMode = { nav.navigate(Routes.DRIVE) }
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenEqualizer = { nav.navigate(Routes.EQUALIZER) },
                onOpenDriveMode = { nav.navigate(Routes.DRIVE) }
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                onBack = { nav.popBackStack() },
                onOpenQueue = { nav.navigate(Routes.QUEUE) },
                onOpenLyrics = { nav.navigate(Routes.LYRICS) },
                onOpenEqualizer = { nav.navigate(Routes.EQUALIZER) }
            )
        }
        composable(Routes.QUEUE) {
            QueueScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.LYRICS) {
            LyricsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EQUALIZER) {
            EqualizerScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DRIVE) {
            DriveModeScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.PLAYLIST,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
            PlaylistScreen(
                playlistId = playlistId,
                onBack = { nav.popBackStack() },
                onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) }
            )
        }
    }
}
