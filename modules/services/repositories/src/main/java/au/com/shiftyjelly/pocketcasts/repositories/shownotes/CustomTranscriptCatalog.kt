package au.com.shiftyjelly.pocketcasts.repositories.shownotes

import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import okhttp3.HttpUrl

internal object CustomTranscriptCatalog {
    fun find(podcastUuid: String, episodeUuid: String, baseUrl: HttpUrl?): Transcript? {
        if (baseUrl == null) return null

        val url = baseUrl.newBuilder()
            .addPathSegment("generated_transcripts")
            .addPathSegment(podcastUuid)
            .addPathSegment("$episodeUuid.vtt")
            .build()

        return Transcript(
            episodeUuid = episodeUuid,
            url = url.toString(),
            type = "text/vtt",
            isGenerated = false,
        )
    }

    fun isCustomUrl(url: String, baseUrl: HttpUrl?): Boolean {
        val customBaseUrl = baseUrl?.toString()?.trimEnd('/') ?: return false
        return url.startsWith("$customBaseUrl/generated_transcripts/")
    }
}
