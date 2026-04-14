package com.localmotion

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localmotion.ui.screens.ArtifactScreen
import com.localmotion.ui.screens.HomeScreen

@Composable
fun LocalMotionApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenArtifact = { artifactId ->
                    navController.navigate("artifact/${Uri.encode(artifactId)}")
                },
            )
        }
        composable(
            route = "artifact/{artifactId}",
            arguments = listOf(navArgument("artifactId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val artifactId = backStackEntry.arguments?.getString("artifactId").orEmpty()
            ArtifactScreen(
                artifactId = artifactId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
