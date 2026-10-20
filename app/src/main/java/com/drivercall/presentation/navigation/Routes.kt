package com.drivercall.presentation.navigation

/** All navigation destinations used by the production calling flow. */
sealed class Routes(val route: String) {
    data object Splash : Routes("splash")
    data object Login : Routes("login")
    data object Register : Routes("register")
    data object Home : Routes("home")
    data object Friends : Routes("friends")
    data object FriendProfile : Routes("friend_profile/{friendId}") {
        const val ARG_FRIEND_ID = "friendId"
        fun create(friendId: String) = "friend_profile/$friendId"
    }
    data object CallHistory : Routes("call_history")
    data object ActiveCall : Routes("active_call")
    data object CustomerCall : Routes("customer_call")
    data object DriverMode : Routes("driver_mode")
    data object NetworkDiagnostics : Routes("network_diagnostics")
    data object Settings : Routes("settings")
    data object Privacy : Routes("privacy")
    data object Help : Routes("help")
}
