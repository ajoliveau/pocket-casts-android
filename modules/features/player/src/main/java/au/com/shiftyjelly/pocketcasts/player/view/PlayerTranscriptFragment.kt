package au.com.shiftyjelly.pocketcasts.player.view

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.LocalPodcastColors
import au.com.shiftyjelly.pocketcasts.compose.PodcastColors
import au.com.shiftyjelly.pocketcasts.compose.components.AnimatedPlayPauseButton
import au.com.shiftyjelly.pocketcasts.compose.components.rememberViewInteropNestedScrollConnection
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.player.viewmodel.PlayerViewModel
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingFlow
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingLauncher
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingUpgradeSource
import au.com.shiftyjelly.pocketcasts.transcripts.TranscriptViewModel
import au.com.shiftyjelly.pocketcasts.transcripts.ui.ToolbarColors
import au.com.shiftyjelly.pocketcasts.transcripts.ui.TranscriptPage
import au.com.shiftyjelly.pocketcasts.transcripts.ui.TranscriptShareButton
import au.com.shiftyjelly.pocketcasts.utils.extensions.requireParcelable
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import com.automattic.eventhorizon.TranscriptGeneratedPaywallSubscribeTappedEvent
import com.automattic.eventhorizon.TranscriptTextHighlightedEvent
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@AndroidEntryPoint
class PlayerTranscriptFragment : BaseFragment() {
    companion object {
        private const val ARGS_KEY = "args"

        fun newInstance(
            episodeUuid: String,
            podcastUuid: String?,
        ) = PlayerTranscriptFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARGS_KEY, Args(episodeUuid, podcastUuid))
            }
        }
    }

    private val args get() = requireArguments().requireParcelable<Args>(ARGS_KEY)

    private val playerViewModel: PlayerViewModel by activityViewModels()
    private val viewModel by viewModels<TranscriptViewModel>(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<TranscriptViewModel.Factory> { factory ->
                val viewModel = factory.create(TranscriptViewModel.Source.Player)
                viewModel.loadTranscript(args.episodeUuid)
                viewModel
            }
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        val uiState by viewModel.uiState.collectAsState()
        val podcast by playerViewModel.podcastFlow.collectAsState()

        AppTheme(theme.activeTheme) {
            CompositionLocalProvider(
                LocalPodcastColors provides (podcast?.let(::PodcastColors) ?: PodcastColors.ForUserEpisode),
            ) {
                TranscriptPage(
                    uiState = uiState,
                    viewModel = viewModel,
                    fingerprintTimingManager = viewModel.fingerprintTimingManager,
                    playbackManager = viewModel.playbackManager,
                    toolbarPadding = PaddingValues(horizontal = 16.dp),
                    paywallPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    transcriptPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    showCloseButton = false,
                    onClickClose = {},
                    onClickReload = viewModel::reloadTranscript,
                    onUpdateSearchTerm = viewModel::searchInTranscript,
                    onClearSearchTerm = viewModel::clearSearch,
                    onSelectPreviousSearch = viewModel::selectPreviousSearchMatch,
                    onSelectNextSearch = viewModel::selectNextSearchMatch,
                    onShowSearchBar = viewModel::openSearch,
                    onHideSearchBar = viewModel::hideSearch,
                    onClickSubscribe = {
                        viewModel.track { source, podcastUuid, episodeUuid ->
                            TranscriptGeneratedPaywallSubscribeTappedEvent(
                                podcastUuid = podcastUuid,
                                episodeUuid = episodeUuid,
                                source = source,
                            )
                        }
                        OnboardingLauncher.openOnboardingFlow(
                            requireActivity(),
                            OnboardingFlow.Upsell(OnboardingUpgradeSource.GENERATED_TRANSCRIPTS),
                        )
                    },
                    onHighlightText = {
                        viewModel.track { source, podcastUuid, episodeUuid ->
                            TranscriptTextHighlightedEvent(
                                podcastUuid = podcastUuid,
                                episodeUuid = episodeUuid,
                                source = source,
                            )
                        }
                    },
                    toolbarTrailingContent = { toolbarColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uiState.isTextTranscriptLoaded) {
                                TranscriptShareButton(
                                    toolbarColors = toolbarColors,
                                    onClick = viewModel::shareTranscript,
                                )
                            }
                            PlayPauseButton(toolbarColors)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberViewInteropNestedScrollConnection()),
                )
            }
        }
    }

    @Composable
    private fun PlayPauseButton(
        toolbarColors: ToolbarColors,
        modifier: Modifier = Modifier,
    ) {
        val scope = rememberCoroutineScope()
        val playbackState by remember {
            viewModel.playbackManager.playbackStateFlow.map { it.episodeUuid to it.isPlaying }
        }.collectAsState(initial = null)
        val isPlayingThisEpisode = playbackState?.first == args.episodeUuid && playbackState?.second == true

        AnimatedPlayPauseButton(
            isPlaying = isPlayingThisEpisode,
            onClick = {
                if (isPlayingThisEpisode) {
                    viewModel.playbackManager.pause(sourceView = SourceView.PLAYER)
                } else {
                    scope.launch {
                        viewModel.playbackManager.playNowSuspend(args.episodeUuid, sourceView = SourceView.PLAYER)
                    }
                }
            },
            iconWidth = 24.dp,
            iconHeight = 24.dp,
            circleSize = 48.dp,
            iconTint = toolbarColors.button,
            circleColor = toolbarColors.buttonBackground,
            modifier = modifier,
        )
    }

    @Parcelize
    private class Args(
        val episodeUuid: String,
        val podcastUuid: String?,
    ) : Parcelable
}
