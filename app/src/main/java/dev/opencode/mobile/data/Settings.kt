package dev.opencode.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "opencode")

data class Connection(val baseUrl: String, val token: String)

/** Bağlantı ayarları (sunucu adresi + token) ve tema tercihi kalıcı olarak saklanır. */
class Settings(private val context: Context) {

    private val keyUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("token")
    private val keyDarkTheme = booleanPreferencesKey("dark_theme")

    val connection: Flow<Connection?> = context.dataStore.data.map { prefs ->
        val url = prefs[keyUrl]
        val token = prefs[keyToken]
        if (url.isNullOrBlank() || token.isNullOrBlank()) null else Connection(url, token)
    }

    /** true = koyu tema, false = açık tema. */
    val darkTheme: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[keyDarkTheme] ?: true }

    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[keyUrl] = baseUrl.trimEnd('/')
            prefs[keyToken] = token
        }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.dataStore.edit { prefs -> prefs[keyDarkTheme] = dark }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}