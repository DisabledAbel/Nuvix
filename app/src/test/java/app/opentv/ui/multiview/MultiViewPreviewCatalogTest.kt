/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.multiview.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MultiViewPreviewCatalogTest {
    @Test fun previewRegistersCurrentLocalProviderMetadataWithoutIncludingStreamCredentials() = runTest {
        val source = Source(id = 7, name = "My Provider", kind = SourceKind.XTREAM,
            url = "https://example.invalid", username = "private-user", password = "private-password")
        val registered = mutableListOf<Source>()
        val result = loadMultiViewPreviewChannels(presentation(1L to 7L), { channel(1, 7) }, { source }, registered::add)
        assertThat(registered).containsExactly(source)
        assertThat(result).containsExactly(MultiViewPreviewChannel(1, 7, "Channel 1", "My Provider"))
        assertThat(result.toString()).doesNotContain("private-user")
        assertThat(result.toString()).doesNotContain("private-password")
        assertThat(result.toString()).doesNotContain("private-token")
    }

    @Test fun missingMovedHiddenAndDisabledChannelsAreUnavailableAndNeverRegisterWrongSource() = runTest {
        val sources = listOf(Source(id = 7, name = "Enabled", kind = SourceKind.M3U, url = "https://example.invalid"),
            Source(id = 8, name = "Disabled", kind = SourceKind.M3U, url = "https://example.invalid", enabled = false)).associateBy { it.id }
        val catalog = listOf(channel(2, 9), channel(3, 7).copy(hidden = true), channel(4, 8)).associateBy { it.id }
        val registered = mutableListOf<Source>()
        val result = loadMultiViewPreviewChannels(presentation(1L to 7L, 2L to 7L, 3L to 7L, 4L to 8L),
            { catalog[it] }, { sources[it] }, registered::add)
        assertThat(result).isEmpty()
        assertThat(registered).isEmpty()
    }

    @Test fun savedChannelsCannotBypassCurrentCategoryVisibility() = runTest {
        val source = Source(id = 7, name = "Provider", kind = SourceKind.M3U, url = "https://example.invalid")
        val registered = mutableListOf<Source>()
        val result = loadMultiViewPreviewChannels(presentation(1L to 7L), { channel(1, 7) }, { source },
            registerSource = registered::add, visible = { false })
        assertThat(result).isEmpty()
        assertThat(registered).isEmpty()
    }

    private fun presentation(vararg refs: Pair<Long, Long>) = MultiViewPresentation(refs.mapIndexed { index, ref ->
        MultiViewSavedPanel("p$index", MultiViewChannelReference(ref.first, ref.second))
    }, "p0", "p0", MultiViewLayout.GRID)

    private fun channel(id: Long, sourceId: Long) = Channel(id = id, sourceId = sourceId, streamId = "$id",
        name = "Channel $id", categoryId = null, logoUrl = null, epgChannelId = null, number = null,
        streamUrl = "https://example.invalid/live?token=private-token")
}
