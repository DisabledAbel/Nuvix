/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import app.opentv.core.RecentChannels
import org.junit.Assert.*
import org.junit.Test

class RecentChannelsTest {
    @Test fun excludesMalformedNonpositiveAndDuplicateHistory() {
        assertEquals(listOf(8L, 4L, 12L), RecentChannels.parseIds("8,garbage,0,-3,4,8,12,"))
    }
    @Test fun historyIsBoundedAndKeepsMostRecentInputOrder() {
        val ids = RecentChannels.parseIds((1..90).joinToString(","))
        assertEquals(40, ids.size)
        assertEquals(1L, ids.first())
        assertEquals(40L, ids.last())
    }
    @Test fun emptyHistoryDoesNotProducePlaceholderChannel() {
        assertTrue(RecentChannels.parseIds("").isEmpty())
    }
}
