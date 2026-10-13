package com.paperview.mobile.tools

import com.paperview.mobile.store.Prefs
import kotlinx.coroutines.flow.first

/**
 * Recently used tools, most recent first — the "History" affordance from the
 * Tools screen. Stored as a comma-separated list of tool ids so it survives
 * adding or renaming tools without a migration.
 */
object ToolHistory {
    private const val LIMIT = 8

    suspend fun list(prefs: Prefs): List<ToolId> = decode(prefs.toolHistoryRaw.first())

    suspend fun record(prefs: Prefs, id: ToolId) {
        val next = (listOf(id) + list(prefs).filterNot { it == id }).take(LIMIT)
        prefs.setToolHistoryRaw(next.joinToString(",") { it.name })
    }

    suspend fun clear(prefs: Prefs) = prefs.setToolHistoryRaw("")

    private fun decode(raw: String): List<ToolId> =
        raw.split(',')
            .mapNotNull { name -> ToolId.entries.firstOrNull { it.name == name.trim() } }
}
