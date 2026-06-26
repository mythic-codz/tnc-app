package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.QuizScreen
import com.example.ui.screens.TestListScreen
import com.example.ui.viewmodel.TncViewModel

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    viewModel: TncViewModel = viewModel()
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        // --- HomeScreen Destination ---
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToTestList = { navController.navigate("test_list") }
            )
        }

        // --- TestListScreen Destination ---
        composable("test_list") {
            TestListScreen(
                viewModel = viewModel,
                onNavigateToQuiz = { examId -> navController.navigate("quiz/$examId") },
                onBack = { navController.popBackStack() }
            )
        }

        // --- QuizScreen Destination ---
        composable(
            route = "quiz/{examId}",
            arguments = listOf(
                navArgument("examId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getString("examId") ?: ""
            QuizScreen(
                viewModel = viewModel,
                examId = examId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
