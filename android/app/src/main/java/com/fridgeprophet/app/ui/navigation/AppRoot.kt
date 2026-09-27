package com.fridgeprophet.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fridgeprophet.app.ui.screens.auth.AuthScreen
import com.fridgeprophet.app.ui.screens.family.FamilyScreen
import com.fridgeprophet.app.ui.screens.main.MainScaffold
import com.fridgeprophet.app.ui.screens.onboarding.OnboardingScreen
import com.fridgeprophet.app.ui.screens.plaza.ComposePostScreen
import com.fridgeprophet.app.ui.screens.plaza.PostDetailScreen
import com.fridgeprophet.app.ui.screens.plaza.UserProfileScreen
import com.fridgeprophet.app.ui.screens.recipes.RecipeDetailScreen
import com.fridgeprophet.app.ui.screens.scan.ScanScreen
import com.fridgeprophet.app.ui.screens.splash.SplashScreen
import com.fridgeprophet.app.ui.screens.tips.TipDetailScreen
import com.fridgeprophet.app.ui.screens.tips.TipsScreen

@Composable
fun AppRoot() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onGoLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onGoMain = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onGoOnboarding = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LOGIN) {
            AuthScreen(
                onLoggedIn = { needsOnboarding ->
                    val target = if (needsOnboarding) Routes.ONBOARDING else Routes.MAIN
                    navController.navigate(target) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.MAIN) {
            MainScaffold(
                onOpenScan = { navController.navigate(Routes.SCAN) },
                onOpenRecipe = { id -> navController.navigate(Routes.recipeDetail(id)) },
                onOpenTips = { navController.navigate(Routes.TIPS) },
                onOpenTip = { id -> navController.navigate(Routes.tipDetail(id)) },
                onOpenFamily = { navController.navigate(Routes.FAMILY) },
                onOpenPost = { id -> navController.navigate(Routes.plazaPost(id)) },
                onOpenAuthor = { id -> navController.navigate(Routes.plazaUser(id)) },
                onOpenCompose = { navController.navigate(Routes.PLAZA_COMPOSE) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.PLAZA_COMPOSE) {
            ComposePostScreen(
                onBack = { navController.popBackStack() },
                // 发完就退回广场。用 popBackStack 而不是 navigate：
                // 发布页是一次性的，留在返回栈里会导致「返回」又回到发布页。
                onPublished = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PLAZA_POST,
            arguments = listOf(navArgument("postId") { type = NavType.IntType }),
        ) {
            PostDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenAuthor = { id -> navController.navigate(Routes.plazaUser(id)) },
                onOpenRecipe = { id -> navController.navigate(Routes.recipeDetail(id)) },
            )
        }

        composable(
            route = Routes.PLAZA_USER,
            arguments = listOf(navArgument("userId") { type = NavType.IntType }),
        ) {
            UserProfileScreen(
                onBack = { navController.popBackStack() },
                onOpenPost = { id -> navController.navigate(Routes.plazaPost(id)) },
            )
        }

        composable(Routes.FAMILY) {
            FamilyScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TIPS) {
            TipsScreen(
                onBack = { navController.popBackStack() },
                onOpenTip = { id -> navController.navigate(Routes.tipDetail(id)) },
            )
        }

        composable(
            route = Routes.TIP_DETAIL,
            arguments = listOf(navArgument("tipId") { type = NavType.StringType }),
        ) {
            TipDetailScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onFinished = {
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.RECIPE_DETAIL,
            arguments = listOf(navArgument("recipeId") { type = NavType.IntType }),
        ) { entry ->
            val recipeId = entry.arguments?.getInt("recipeId") ?: 0
            RecipeDetailScreen(
                recipeId = recipeId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
