package com.paperview.mobile.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One entry in the Recent list, carrying the per-file view state so reopening
 * a document lands exactly where you left it — page, zoom and rotation, same
 * as the desktop build's recents v2 format.
 */
data class RecentEntry(
    val uri: String,
    val name: String,
    val page: Int,
    val zoom: String,
    val rotation: Int,
    val timestamp: Long,
)

/**
 * Recent documents, stored as tab-separated lines in a single preference —
 * a direct port of the desktop's "recents" storage key.
 *
 * Line format: `ts \t page \t zoom \t rotation \t name \t uri`. Older 2-field
 * lines (`ts \t uri`) still parse, so an upgrade never loses history.
 */
object Recents {
    private const val LIMIT = 20

    /**
     * Serialises every read-modify-write of the list.
     *
     * Four callers rewrite it from three different coroutine scopes: opening a
     * document, forgetting one, the one-time consolidation, and the viewer
     * saving your place — which fires every 600ms while you read and again when
     * the app stops. Each one reads the whole list, changes it and writes it
     * back; two overlapping meant the second silently undid the first, so a
     * position could be saved and lost without anything going wrong visibly.
     * DataStore makes each *write* atomic, which is not the same thing.
     */
    private val lock = Mutex()

    suspend fun list(prefs: Prefs): List<RecentEntry> = decode(prefs.recentsRaw.first())

    suspend fun record(prefs: Prefs, entry: RecentEntry) = lock.withLock {
        val current = list(prefs).filterNot { it.uri == entry.uri }
        val next = (listOf(entry) + current).take(LIMIT)
        prefs.setRecentsRaw(encode(next))
    }

    suspend fun remove(prefs: Prefs, uri: String) = lock.withLock {
        prefs.setRecentsRaw(encode(list(prefs).filterNot { it.uri == uri }))
    }

    suspend fun clear(prefs: Prefs) = prefs.setRecentsRaw("")

    /**
     * Repoints entries at documents that have moved, then collapses any that
     * now agree.
     *
     * Shelf copies used to be named after the URI they arrived from, so the
     * same document shared twice was filed twice — two rows, two reading
     * positions, neither of them where you actually were. Renaming those copies
     * by content makes the duplicates resolve to one path, and this is what
     * makes the list follow: the newest entry for a document wins, because that
     * is the one holding the page last read.
     */
    suspend fun follow(prefs: Prefs, moved: Map<String, String>) = lock.withLock {
        val current = list(prefs)
        val next = collapse(current, moved)
        if (next != current) prefs.setRecentsRaw(encode(next))
    }

    /**
     * The pure half of [follow], so the collapsing rule can be tested.
     *
     * The survivor of a group is the newest entry that was actually read into —
     * not simply the newest. The duplicates exist *because* reopening a document
     * filed it afresh at page one, so the newest row is usually the one the bug
     * just reset, and taking it would finish the job of losing your place. A
     * page of zero carries no information; any real position beats it. The
     * survivor still sorts by the group's newest timestamp, because that is when
     * the document was last in front of you.
     */
    internal fun collapse(
        entries: List<RecentEntry>,
        moved: Map<String, String>,
    ): List<RecentEntry> = entries
        .map { entry -> moved[entry.uri]?.let { entry.copy(uri = it) } ?: entry }
        .groupBy { it.uri }
        .map { (_, group) ->
            val newest = group.maxBy { it.timestamp }
            val read = group.filter { it.page > 0 }.maxByOrNull { it.timestamp }
            (read ?: newest).copy(name = newest.name, timestamp = newest.timestamp)
        }
        .sortedByDescending { it.timestamp }
        .take(LIMIT)

    /** The stored state for one file, or null if it isn't in the list. */
    suspend fun find(prefs: Prefs, uri: String): RecentEntry? =
        list(prefs).firstOrNull { it.uri == uri }

    internal fun encode(entries: List<RecentEntry>): String =
        entries.joinToString("\n") { e ->
            listOf(
                e.timestamp.toString(),
                e.page.toString(),
                e.zoom,
                e.rotation.toString(),
                e.name.clean(),
                e.uri.clean(),
            ).joinToString("\t")
        }

    internal fun decode(raw: String): List<RecentEntry> =
        raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split('\t')
                when {
                    f.size >= 6 -> RecentEntry(
                        uri = f[5],
                        name = f[4],
                        page = f[1].toIntOrNull() ?: 0,
                        zoom = f[2],
                        rotation = f[3].toIntOrNull() ?: 0,
                        timestamp = f[0].toLongOrNull() ?: 0L,
                    )
                    // Legacy: "ts \t uri" from before per-file state existed.
                    f.size == 2 -> RecentEntry(
                        uri = f[1],
                        name = f[1].substringAfterLast('/'),
                        page = 0,
                        zoom = "fit_page",
                        rotation = 0,
                        timestamp = f[0].toLongOrNull() ?: 0L,
                    )
                    else -> null
                }
            }
            .toList()

    /** Tabs and newlines would break the line format. */
    private fun String.clean(): String = replace('\t', ' ').replace('\n', ' ')
}
