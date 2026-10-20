package com.drivercall.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drivercall.presentation.friends.FriendProfileScreen
import com.drivercall.presentation.friends.FriendsScreen
import com.drivercall.presentation.history.CallHistoryScreen
import com.drivercall.presentation.home.HomeScreen
import com.drivercall.presentation.info.HelpScreen
import com.drivercall.presentation.info.PrivacyScreen
import com.drivercall.presentation.auth.LoginScreen
import com.drivercall.presentation.auth.RegisterScreen
import com.drivercall.presentation.call.ActiveCallScreen
import com.drivercall.presentation.call.CallViewModel
import com.drivercall.presentation.call.CustomerCallScreen
import com.drivercall.presentation.call.DriverModeScreen
import com.drivercall.presentation.call.NetworkDiagnosticsScreen
import com.drivercall.presentation.settings.SettingsScreen
import com.drivercall.presentation.splash.SplashScreen
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DriverCallNavGraph(
    navController: NavHostController = rememberNavController()
) {
    val callViewModel: CallViewModel = hiltViewModel()
    val callState by callViewModel.state.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val callRoutes = setOf(
        Routes.ActiveCall.route,
        Routes.CustomerCall.route,
        Routes.DriverMode.route,
        Routes.NetworkDiagnostics.route
    )

    LaunchedEffect(callState.callId, callState.hasLiveSession, currentRoute) {
        if (callState.hasLiveSession && currentRoute !in callRoutes &&
            currentRoute !in setOf(Routes.Splash.route, Routes.Login.route, Routes.Register.route)
        ) {
            navController.navigate(Routes.ActiveCall.route) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Splash.route
    ) {
        composable(Routes.Splash.route) {
            SplashScreen(
                onSignedIn = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                },
                onSignedOut = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                },
                onRegisterClick = { navController.navigate(Routes.Register.route) }
            )
        }

        composable(Routes.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Register.route) { inclusive = true }
                    }
                },
                onBackToLogin = { navController.popBackStack() }
            )
        }

        composable(Routes.Home.route) {
            HomeScreen(
                onFriendsClick = { navController.navigate(Routes.Friends.route) },
                onCustomerCallClick = { navController.navigate(Routes.CustomerCall.route) },
                onDriverModeClick = { navController.navigate(Routes.DriverMode.route) },
                onDiagnosticsClick = { navController.navigate(Routes.NetworkDiagnostics.route) },
                onHistoryClick = { navController.navigate(Routes.CallHistory.route) },
                onSettingsClick = { navController.navigate(Routes.Settings.route) }
            )
        }

        composable(Routes.Friends.route) {
            FriendsScreen(
                onFriendClick = { friendId ->
                    navController.navigate(Routes.FriendProfile.create(friendId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.FriendProfile.route,
            arguments = listOf(
                navArgument(Routes.FriendProfile.ARG_FRIEND_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val friendId = backStackEntry.arguments
                ?.getString(Routes.FriendProfile.ARG_FRIEND_ID).orEmpty()
            FriendProfileScreen(
                friendId = friendId,
                onCall = { id, name ->
                    callViewModel.start(id, name)
                    navController.navigate(Routes.ActiveCall.route) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ActiveCall.route) {
            ActiveCallScreen(
                onBack = { navController.popBackStack() },
                onCustomerCall = { navController.navigate(Routes.CustomerCall.route) },
                onDriverMode = { navController.navigate(Routes.DriverMode.route) },
                onDiagnostics = { navController.navigate(Routes.NetworkDiagnostics.route) }
            )
        }

        composable(Routes.CustomerCall.route) {
            CustomerCallScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DriverMode.route) {
            DriverModeScreen(
                onBack = { navController.popBackStack() },
                onCustomerCall = { navController.navigate(Routes.CustomerCall.route) }
            )
        }

        composable(Routes.NetworkDiagnostics.route) {
            NetworkDiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CallHistory.route) {
            CallHistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onPrivacyClick = { navController.navigate(Routes.Privacy.route) },
                onHelpClick = { navController.navigate(Routes.Help.route) },
                onLoggedOut = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Privacy.route) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.Help.route) {
            HelpScreen(onBack = { navController.popBackStack() })
        }
    }
}
