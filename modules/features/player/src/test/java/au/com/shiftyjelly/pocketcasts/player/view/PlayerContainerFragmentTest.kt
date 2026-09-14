package au.com.shiftyjelly.pocketcasts.player.view

import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Bookmarks
import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Chapters
import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Notes
import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Player
import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Summary
import au.com.shiftyjelly.pocketcasts.player.view.PlayerSectionType.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerContainerFragmentTest {
    @Test
    fun `transcript availability follows the player episode when shelf state arrives first`() {
        val shelfState = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-1",
            playerPodcastUuid = "podcast-1",
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = true,
        )

        assertEquals(true, shelfState?.isAvailable)
        assertEquals("podcast-1", shelfState?.podcastUuid)
    }

    @Test
    fun `transcript availability stays hidden until shelf state matches the player episode`() {
        val shelfState = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-2",
            playerPodcastUuid = "podcast-2",
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = true,
        )

        assertEquals(false, shelfState?.isAvailable)
    }

    @Test
    fun `transcript availability reconciles when the shelf arrives before the player`() {
        val shelfFirst = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = null,
            playerPodcastUuid = null,
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = true,
        )
        val playerCatchesUp = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-1",
            playerPodcastUuid = "podcast-1",
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = true,
        )

        assertEquals(null, shelfFirst)
        assertEquals(true, playerCatchesUp?.isAvailable)
    }

    @Test
    fun `transcript availability reconciles when the player arrives before the shelf`() {
        val playerFirst = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-1",
            playerPodcastUuid = "podcast-1",
            shelfEpisodeUuid = null,
            shelfTranscriptAvailable = false,
        )
        val shelfCatchesUp = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-1",
            playerPodcastUuid = "podcast-1",
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = true,
        )

        assertEquals(false, playerFirst?.isAvailable)
        assertEquals(true, shelfCatchesUp?.isAvailable)
    }

    @Test
    fun `transcript availability is removed when shelf state clears`() {
        val shelfState = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = "episode-1",
            playerPodcastUuid = "podcast-1",
            shelfEpisodeUuid = "episode-1",
            shelfTranscriptAvailable = false,
        )

        assertEquals(false, shelfState?.isAvailable)
    }

    @Test
    fun `selection follows the same section when sections are inserted or removed`() {
        val sectionsWithTranscript = listOf(Player, Notes, Transcript, Bookmarks)
        val sectionsWithoutTranscript = listOf(Player, Notes, Bookmarks)

        assertEquals(3, remapSectionPosition(Bookmarks, sectionsWithTranscript, currentPosition = 2))
        assertEquals(2, remapSectionPosition(Bookmarks, sectionsWithoutTranscript, currentPosition = 3))
        assertEquals(2, remapSectionPosition(Transcript, sectionsWithoutTranscript, currentPosition = 2))
    }

    @Test
    fun `transcript stable ID is deterministic and episode-specific`() {
        val first = PlayerSection(PlayerSectionType.Transcript, episodeUuid = "episode-1")
        val second = PlayerSection(PlayerSectionType.Transcript, episodeUuid = "episode-2")

        assertEquals(playerSectionStableId(first), playerSectionStableId(first))
        assertNotEquals(playerSectionStableId(first), playerSectionStableId(second))
    }

    @Test
    fun `transcript follows notes and precedes bookmarks`() {
        val sections = buildPlayerSections(
            hasNotes = true,
            transcriptEpisodeUuid = "episode-1",
            transcriptPodcastUuid = "podcast-1",
        )

        assertEquals(
            listOf(Player, Notes, Transcript, Bookmarks),
            sections.map { it.type },
        )
    }

    @Test
    fun `transcript is omitted when unavailable`() {
        val sections = buildPlayerSections(hasNotes = true)

        assertEquals(
            listOf(Player, Notes, Bookmarks),
            sections.map { it.type },
        )
    }

    @Test
    fun `transcript section is replaced for a new episode`() {
        val first = buildPlayerSections(
            hasNotes = true,
            transcriptEpisodeUuid = "episode-1",
            transcriptPodcastUuid = "podcast-1",
        )
        val second = buildPlayerSections(
            hasNotes = true,
            transcriptEpisodeUuid = "episode-2",
            transcriptPodcastUuid = "podcast-2",
        )

        assertEquals("episode-1", first[2].episodeUuid)
        assertEquals("podcast-1", first[2].podcastUuid)
        assertEquals("episode-2", second[2].episodeUuid)
        assertEquals("podcast-2", second[2].podcastUuid)
        assertEquals(2, remapSectionPosition(Transcript, second.map { it.type }, currentPosition = 2))
    }

    @Test
    fun `optional sections retain their order around transcript`() {
        val sections = buildPlayerSections(
            hasNotes = true,
            hasSummary = true,
            transcriptEpisodeUuid = "episode-1",
            hasChapters = true,
        )

        assertEquals(
            listOf(Player, Notes, Summary, Transcript, Chapters, Bookmarks),
            sections.map { it.type },
        )
    }
}
