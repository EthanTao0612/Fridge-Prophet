package com.fridgeprophet.app.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fridgeprophet.app.R
import com.fridgeprophet.app.ui.navigation.MainTab
import com.fridgeprophet.app.ui.screens.fridge.FridgeScreen
import com.fridgeprophet.app.ui.screens.home.HomeScreen
import com.fridgeprophet.app.ui.screens.plaza.PlazaScreen
import com.fridgeprophet.app.ui.screens.profile.ProfileScreen
import com.fridgeprophet.app.ui.screens.recipes.RecipesScreen
import com.fridgeprophet.app.ui.screens.shopping.ShoppingScreen

private val TAB_ICONS = mapOf(
    MainTab.HOME to R.drawable.ic_tab_home,
    MainTab.PLAZA to R.drawable.ic_tab_plaza,
    MainTab.FRIDGE to R.drawable.ic_tab_fridge,
    MainTab.RECIPES to R.drawable.ic_tab_recipe,
    MainTab.SHOPPING to R.drawable.ic_tab_shopping,
    MainTab.PROFILE to R.drawable.ic_tab_profile,
)

@Composable
fun MainScaffold(
    onOpenScan: () -> Unit,
    onOpenRecipe: (Int) -> Unit,
    onOpenTips: () -> Unit,
    onOpenTip: (String) -> Unit,
    onOpenFamily: () -> Unit,
    onOpenPost: (Int) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenCompose: () -> Unit,
    onLogout: () -> Unit,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = MainTab.entries

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                painter = painterResource(TAB_ICONS.getValue(tab)),
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tabs[selectedIndex]) {
                MainTab.HOME -> HomeScreen(
                    onOpenScan = onOpenScan,
                    onOpenRecipe = onOpenRecipe,
                    onGoShopping = { selectedIndex = tabs.indexOf(MainTab.SHOPPING) },
                    onOpenTips = onOpenTips,
                    onOpenTip = onOpenTip,
                )

                MainTab.PLAZA -> PlazaScreen(
                    onOpenPost = onOpenPost,
                    onOpenAuthor = onOpenAuthor,
                    onOpenRecipe = onOpenRecipe,
                    onCompose = onOpenCompose,
                )

                MainTab.FRIDGE -> FridgeScreen()

                MainTab.RECIPES -> RecipesScreen(onOpenRecipe = onOpenRecipe)

                MainTab.SHOPPING -> ShoppingScreen()

                MainTab.PROFILE -> ProfileScreen(
                    onLogout = onLogout,
                    onOpenTips = onOpenTips,
                    onOpenFamily = onOpenFamily,
                )
            }
        }
    }
}
