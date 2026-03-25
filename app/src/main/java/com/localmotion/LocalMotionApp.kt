package com.localmotion

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localmotion.ui.screens.HomeScreen
import com.localmotion.ui.screens.PlayerScreen

@Composable
fun LocalMotionApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenArtifact = { artifactId ->
                    navController.navigate("player/${Uri.encode(artifactId)}")
                },
            )
        }
        composable(
            route = "player/{artifactId}",
            arguments = listOf(navArgument("artifactId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val artifactId = backStackEntry.arguments?.getString("artifactId").orEmpty()
            PlayerScreen(
                artifactId = artifactId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
