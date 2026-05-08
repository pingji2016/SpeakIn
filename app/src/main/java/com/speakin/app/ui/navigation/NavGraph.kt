package com.speakin.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.speakin.app.ui.notedetail.NoteDetailScreen
import com.speakin.app.ui.notelist.NoteListScreen

@Composable
fun SpeakInNavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Routes.NOTE_LIST
    ) {
        composable(Routes.NOTE_LIST) {
            NoteListScreen(
                onNavigateToDetail = { noteId ->
                    navController.navigate(Routes.noteDetail(noteId))
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
    }
}
