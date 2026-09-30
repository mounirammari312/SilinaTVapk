package com.agon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agon.app.ui.screens.SettingsScreen
import com.agon.app.ui.theme.AgonAppTheme
import com.agon.app.ui.util.applyImmersiveFullscreen

/**
 * MainActivity - Legacy Navigation Host
 *
 * NOTE: This activity is NOT the LAUNCHER. SplashActivity is the actual entry
 * point and routes users to LoginActivity → ProfilesActivity → DashboardActivity.
 * MainActivity remains for legacy/compatibility purposes only.
 *
 * HomeScreen.kt has been removed from the project. All channel-listing UI now
 * lives inside DashboardActivity (CompactDashboardScreen) which already contains
 * the Master-Detail 5-Column grid + Sidebar + Instant Focus Update + Mini-Player
 * + EPG panel. There is no need for a separate "home" route here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        applyImmersiveFullscreen()
        super.onCreate(savedInstanceState)
        setContent {
            AgonAppTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { BottomNav(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "settings",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("settings") { SettingsScreen() }
        }
    }
}

@Composable
fun BottomNav(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentRoute == "settings",
            onClick = {
                navController.navigate("settings") {
                    popUpTo("settings") { inclusive = true }
                }
            },
        )
    }
}
