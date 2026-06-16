package com.speakin.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.speakin.app.ui.notedetail.NoteDetailScreen
import com.speakin.app.ui.notelist.NoteListScreen
import com.speakin.app.ui.settings.SettingsScreen

@Composable
fun SpeakInNavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.NOTE_LIST,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
        }
    ) {
        composable(Routes.NOTE_LIST) {
            NoteListScreen(
                onNavigateToDetail = { noteId ->
                    navController.navigate(Routes.noteDetail(noteId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.NOTE_DETAIL,
            arguments = listOf(
                navArgument("noteId") { type = NavType.StringType }
            )
        ) {
            NoteDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
