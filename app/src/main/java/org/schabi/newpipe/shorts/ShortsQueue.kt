package org.schabi.newpipe.shorts

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Hands the list of Shorts from the screen the user tapped on to [ShortsActivity]. Kept in
 * memory instead of the intent, so long lists never hit the binder size limit.
 */
internal object ShortsQueue {
    private var items: List<StreamInfoItem> = emptyList()
    private var startIndex = 0

    fun set(items: List<StreamInfoItem>, startIndex: Int) {
        this.items = ArrayList(items)
        this.startIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    /** Returns the queue once; the activity keeps its own copy afterwards. */
    fun take(): Pair<List<StreamInfoItem>, Int> {
        val result = items to startIndex
        items = emptyList()
        startIndex = 0
        return result
    }
}
