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
        val B2_ENABLED = booleanPreferencesKey("b2_enabled")
        val CLOUD_ACCOUNTS = stringPreferencesKey("cloud_accounts")
        val CLOUD_CURRENT = stringPreferencesKey("cloud_current")
        val CLOUD_DIR = stringPreferencesKey("cloud_dir")
        val BOOKS = stringPreferencesKey("books")
        val READING = stringPreferencesKey("reading")
        val ALL_FILES = booleanPreferencesKey("all_files_browser")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val PAGE_FIT = intPreferencesKey("page_fit")
        val TOOL_HISTORY = stringPreferencesKey("tool_history")
        val SKIN = stringPreferencesKey("skin")
        val SHELF_CONSOLIDATED = booleanPreferencesKey("shelf_consolidated")
        val SCAN_FILTER = stringPreferencesKey("scan_filter")
        val PEEK_SPOTS = stringPreferencesKey("peek_spots")
        val PEEK_TIPS = intPreferencesKey("peek_tips")
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

    /** Cloud accounts as JSON (the desktop's `cloud_accounts` format). */
    val cloudAccountsRaw: Flow<String> = flowOf(Keys.CLOUD_ACCOUNTS, "")
    val cloudCurrent: Flow<String> = flowOf(Keys.CLOUD_CURRENT, "")
    val cloudDir: Flow<String> = flowOf(Keys.CLOUD_DIR, "")

    /** The Books shelf as JSON; see [com.paperview.mobile.book.BookStore]. */
    val booksRaw: Flow<String> = flowOf(Keys.BOOKS, "")

    /** How books are typeset (the desktop's "reading" key format). */
    val readingRaw: Flow<String> = flowOf(Keys.READING, "")

    /**
     * Whether the Library shows its Cloud tab. On by default so a bucket that
     * was already set up keeps appearing; it still needs saved credentials.
     */
    val b2Enabled: Flow<Boolean> = flowOf(Keys.B2_ENABLED, true)
    val allFilesBrowser: Flow<Boolean> = flowOf(Keys.ALL_FILES, false)
    val keepAwake: Flow<Boolean> = flowOf(Keys.KEEP_AWAKE, false)
    val toolHistoryRaw: Flow<String> = flowOf(Keys.TOOL_HISTORY, "")
    val skin: Flow<String> = flowOf(Keys.SKIN, "paper")

    /** The cleanup applied to a new scan; remembered between captures. */
    val scanFilter: Flow<String> = flowOf(Keys.SCAN_FILTER, "document")

    /** Saved peek spots, one per document; see [PeekSpots]. */
    val peekSpotsRaw: Flow<String> = flowOf(Keys.PEEK_SPOTS, "")

    /** How many times the "hold to peek" tip has been shown. */
    val peekTips: Flow<Int> = flowOf(Keys.PEEK_TIPS, 0)

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
    suspend fun setB2Enabled(value: Boolean) = put(Keys.B2_ENABLED, value)
    suspend fun setCloudAccountsRaw(value: String) = put(Keys.CLOUD_ACCOUNTS, value)
    suspend fun setCloudCurrent(value: String) = put(Keys.CLOUD_CURRENT, value)
    suspend fun setCloudDir(value: String) = put(Keys.CLOUD_DIR, value)
    suspend fun setBooksRaw(value: String) = put(Keys.BOOKS, value)
    suspend fun setReadingRaw(value: String) = put(Keys.READING, value)
    suspend fun setPeekSpotsRaw(value: String) = put(Keys.PEEK_SPOTS, value)
    suspend fun setPeekTips(value: Int) = put(Keys.PEEK_TIPS, value)

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
