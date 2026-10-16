/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class ProgrammeDetailsMetadataTest {
    @get:Rule val compose = createComposeRule()
    private val channel = Channel(id = 1, sourceId = 1, streamId = "1", name = "Original channel", customName = "My Channel",
        categoryId = null, logoUrl = null, epgChannelId = "channel", number = 1,
        streamUrl = "https://provider.invalid/private-stream")

    @Test fun sparseMalformedMetadataShowsReadableFallbackAndArtworkPlaceholder() {
        val programme = Programme(feedId = 1, epgChannelId = "channel", startUtcMillis = 10, endUtcMillis = 5,
            title = "", season = -1, episode = -1, iconUrl = "file:///private/artwork")
        compose.setContent { MaterialTheme { Column {
            ProgrammeDetailsMetadata(programme, channel, GuideProgrammeStatus.INVALID, showArtwork = true)
        } } }
        compose.onNodeWithText("Program details").assertExists()
        compose.onNodeWithText("My Channel").assertExists()
        compose.onNodeWithText("Program timing unavailable").assertExists()
        compose.onNodeWithText("No program description available.").assertExists()
        compose.onNodeWithContentDescription("Program artwork").assertExists()
        compose.onNodeWithText("Season -1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("file:///private/artwork", substring = true).assertDoesNotExist()
        compose.onNodeWithText("private-stream", substring = true).assertDoesNotExist()
    }

    @Test fun suppliedDescriptionGenreEpisodeAndDurationRenderWithoutRequiringArtwork() {
        val programme = Programme(feedId = 1, epgChannelId = "channel", startUtcMillis = 1_800_000_000_000,
            endUtcMillis = 1_800_003_600_000, title = "Match night", description = "Provider description",
            category = "Sports", season = 2, episode = 4)
        compose.setContent { MaterialTheme { Column {
            ProgrammeDetailsMetadata(programme, channel, GuideProgrammeStatus.UPCOMING, showArtwork = false)
        } } }
        compose.onNodeWithText("Match night").assertExists()
        compose.onNodeWithText("Provider description").assertExists()
        compose.onNodeWithText("Sports").assertExists()
        compose.onNodeWithText("Season 2 · Episode 4").assertExists()
        compose.onNodeWithText("60 min · Upcoming").assertExists()
        compose.onNodeWithText("My Channel", substring = true).assertExists()
        compose.onNodeWithContentDescription("Program artwork").assertDoesNotExist()
    }
}
