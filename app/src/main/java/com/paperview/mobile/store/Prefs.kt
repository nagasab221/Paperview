package com.paperview.mobile.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "paperview")

/**
 * Persisted settings and history. Mirrors the desktop's eframe storage keys
 * one-for-one so the two builds stay conceptually identical.
 */
class Prefs(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val ZOOM = stringPreferencesKey("zoom")
        val SIDEBAR = stringPreferencesKey("sidebar")
        val RECENTS = stringPreferencesKey("recents")
        val LAST_FILE = stringPreferencesKey("last_file")
        val B2_KEY_ID = stringPreferencesKey("b2_key_id")
        val B2_APP_KEY = stringPreferencesKey("b2_app_key")
        val B2_BUCKET = stringPreferencesKey("b2_bucket")
        val ALL_FILES = booleanPreferencesKey("all_files_browser")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val PAGE_FIT = intPreferencesKey("page_fit")
        val TOOL_HISTORY = stringPreferencesKey("tool_history")
        val SKIN = stringPreferencesKey("skin")
        val SHELF_CONSOLIDATED = booleanPreferencesKey("shelf_consolidated")
        val SCAN_FILTER = stringPreferencesKey("scan_filter")
    }

    private fun <T> flowOf(key: Preferences.Key<T>, fallback: T): Flow<T> =
        context.dataStore.data.map { it[key] ?: fallback }

    val theme: Flow<String> = flowOf(Keys.THEME, "system")
    val viewMode: Flow<String> = flowOf(Keys.VIEW_MODE, "continuous")
    val zoom: Flow<String> = flowOf(Keys.ZOOM, "fit_page")
    val sidebar: Flow<String> = flowOf(Keys.SIDEBAR, "hidden")
    val recentsRaw: Flow<String> = flowOf(Keys.RECENTS, "")
    val lastFile: Flow<String> = flowOf(Keys.LAST_FILE, "")
    val b2KeyId: Flow<String> = flowOf(Keys.B2_KEY_ID, "")
    val b2AppKey: Flow<String> = flowOf(Keys.B2_APP_KEY, "")
    val b2Bucket: Flow<String> = flowOf(Keys.B2_BUCKET, "")
    val allFilesBrowser: Flow<Boolean> = flowOf(Keys.ALL_FILES, false)
    val keepAwake: Flow<Boolean> = flowOf(Keys.KEEP_AWAKE, false)
    val toolHistoryRaw: Flow<String> = flowOf(Keys.TOOL_HISTORY, "")
    val skin: Flow<String> = flowOf(Keys.SKIN, "paper")

    /** The cleanup applied to a new scan; remembered between captures. */
    val scanFilter: Flow<String> = flowOf(Keys.SCAN_FILTER, "document")

    /** Whether shelf copies have been renamed by content and the list deduped. */
    val shelfConsolidated: Flow<Boolean> = flowOf(Keys.SHELF_CONSOLIDATED, false)

    suspend fun setTheme(value: String) = put(Keys.THEME, value)
    suspend fun setViewMode(value: String) = put(Keys.VIEW_MODE, value)
    suspend fun setZoom(value: String) = put(Keys.ZOOM, value)
    suspend fun setSidebar(value: String) = put(Keys.SIDEBAR, value)
    suspend fun setRecentsRaw(value: String) = put(Keys.RECENTS, value)
    suspend fun setLastFile(value: String) = put(Keys.LAST_FILE, value)
    suspend fun setAllFilesBrowser(value: Boolean) = put(Keys.ALL_FILES, value)
    suspend fun setKeepAwake(value: Boolean) = put(Keys.KEEP_AWAKE, value)
    suspend fun setToolHistoryRaw(value: String) = put(Keys.TOOL_HISTORY, value)
    suspend fun setSkin(value: String) = put(Keys.SKIN, value)
    suspend fun setShelfConsolidated(value: Boolean) = put(Keys.SHELF_CONSOLIDATED, value)
    suspend fun setScanFilter(value: String) = put(Keys.SCAN_FILTER, value)

    suspend fun setB2(keyId: String, appKey: String, bucket: String) {
        context.dataStore.edit {
            it[Keys.B2_KEY_ID] = keyId
            it[Keys.B2_APP_KEY] = appKey
            it[Keys.B2_BUCKET] = bucket
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
