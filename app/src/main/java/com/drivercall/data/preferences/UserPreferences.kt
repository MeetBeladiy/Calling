package com.drivercall.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drivercall_settings"
)

/**
 * Simple non-sensitive user preferences (theme choice, etc.).
 * Auth tokens live in TokenStorage (encrypted), never here.
 */
@Singleton
class UserPreferences constructor(
    @ApplicationContext private val context: Context
) {
    val darkTheme: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[KEY_DARK_THEME] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_DARK_THEME] = enabled }
    }

    private companion object {
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
    }
}
