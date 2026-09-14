package au.com.shiftyjelly.pocketcasts.player.view

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.compose.PlayerColors
import au.com.shiftyjelly.pocketcasts.compose.PodcastColors
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Chapter
import au.com.shiftyjelly.pocketcasts.player.R
import au.com.shiftyjelly.pocketcasts.player.databinding.FragmentPlayerContainerBinding
import au.com.shiftyjelly.pocketcasts.player.view.bookmark.BookmarksFragment
import au.com.shiftyjelly.pocketcasts.player.view.chapters.ChaptersFragment
import au.com.shiftyjelly.pocketcasts.player.view.chapters.ChaptersViewModel
import au.com.shiftyjelly.pocketcasts.player.view.chapters.ChaptersViewModel.Mode.Player
import au.com.shiftyjelly.pocketcasts.player.viewmodel.BookmarksViewModel
import au.com.shiftyjelly.pocketcasts.player.viewmodel.PlayerViewModel
import au.com.shiftyjelly.pocketcasts.player.viewmodel.ShelfSharedViewModel
import au.com.shiftyjelly.pocketcasts.player.viewmodel.SummaryViewModel
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextSource
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.ui.helper.NavigationBarColor
import au.com.shiftyjelly.pocketcasts.ui.helper.StatusBarIconColor
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.views.helper.HasBackstack
import au.com.shiftyjelly.pocketcasts.views.helper.OffsettingBottomSheetCallback
import com.automattic.eventhorizon.ChaptersShownSource
import com.automattic.eventhorizon.EpisodeSummarySourceType
import com.automattic.eventhorizon.EpisodeSummaryTappedEvent
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.PlayerTabSelectedEvent
import com.automattic.eventhorizon.PlayerTabType
import com.automattic.eventhorizon.UpNextDismissedEvent
import com.automattic.eventhorizon.UpNextShownEvent
import com.automattic.eventhorizon.UpNextSourceType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class PlayerContainerFragment :
    BaseFragment(),
    HasBackstack {
    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var eventHorizon: EventHorizon

    private val bookmarksViewModel: BookmarksViewModel by viewModels()
    private val summaryViewModel: SummaryViewModel by viewModels()

    var upNextBottomSheetBehavior: BottomSheetBehavior<View>? = null

    private var upNextExpandedSource: UpNextSource? = null
    private var transcriptTabEpisodeUuid: String? = null
    private var isReconcilingSections = false

    private lateinit var adapter: ViewPagerAdapter
    private val viewModel: PlayerViewModel by activityViewModels()
    private val shelfSharedViewModel: ShelfSharedViewModel by activityViewModels()
    private val chaptersViewModel by viewModels<ChaptersViewModel>(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<ChaptersViewModel.Factory> { factory ->
                factory.create(Player)
            }
        },
    )
    private var binding: FragmentPlayerContainerBinding? = null

    private val closeUpNextCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState in listOf(BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_HIDDEN)) {
                upNextBottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentPlayerContainerBinding.inflate(inflater, container, false)
        return binding?.root
    }

    fun snackBarView(): View? {
        return upNextSnackBarView() ?: view
    }

    private fun upNextSnackBarView(): View? {
        return childFragmentManager.fragments
            .filterIsInstance<UpNextFragment>()
            .lastOrNull { it.isAdded && it.isResumed }
            ?.view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? FragmentHostListener)?.removePlayerBottomSheetCallback(closeUpNextCallback)
        binding = null
        upNextBottomSheetBehavior = null
        bookmarksViewModel.multiSelectHelper.context = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val upNextFragment = UpNextFragment.newInstance(embedded = true, source = UpNextSource.NOW_PLAYING)
        childFragmentManager.beginTransaction().replace(R.id.upNextFrameBottomSheet, upNextFragment).commitAllowingStateLoss()

        val binding = binding ?: return

        // UpNext bottom sheet needs to be gone. Otherwise, dragging player bottom sheet doesn't work as
        // the motion events are intercepted.
        //
        // However, we make it gone after it is laid out to speed up initial fling motion to show it.
        // Having it gone from the beginning adds a small delay before it can be initially shown.
        binding.upNextFrameBottomSheet.doOnLayout {
            it.isGone = true
            (activity as? FragmentHostListener)?.addPlayerBottomSheetCallback(closeUpNextCallback)
        }
        val upNextBehavior: BottomSheetBehavior<View> = BottomSheetBehavior.from(binding.upNextFrameBottomSheet)
        upNextBottomSheetBehavior = upNextBehavior
        upNextBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                updateUpNextVisibility(newState != BottomSheetBehavior.STATE_COLLAPSED)
                notifyBackstackChangedToHost()

                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    eventHorizon.track(
                        UpNextShownEvent(
                            source = upNextExpandedSource?.analyticsValue ?: UpNextSourceType.Unknown,
                        ),
                    )

                    activity?.let {
                        theme.updateWindowNavigationBarColor(window = it.window, navigationBarColor = NavigationBarColor.UpNext(isFullScreen = true))
                        theme.updateWindowStatusBarIcons(it.window, StatusBarIconColor.UpNext(isFullScreen = true))
                    }

                    upNextFragment.onExpanded()
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    eventHorizon.track(
                        UpNextDismissedEvent(
                            source = upNextExpandedSource?.analyticsValue ?: UpNextSourceType.Unknown,
                        ),
                    )

                    (activity as? FragmentHostListener)?.updateSystemColors()
                    upNextFragment.onCollapsed()
                }
            }
        })
        upNextBehavior.addBottomSheetCallback(OffsettingBottomSheetCallback(binding.upNextFrameBottomSheet))

        val viewPager = binding.viewPager

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var previousPosition: Int = INVALID_TAB_POSITION
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                viewPager.isUserInputEnabled = !bookmarksViewModel.multiSelectHelper.isMultiSelecting
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (isReconcilingSections) {
                    previousPosition = position
                    return
                }
                val tab = when {
                    adapter.isPlayerTab(position) -> {
                        if (previousPosition == INVALID_TAB_POSITION) {
                            return
                        }
                        PlayerTabType.NowPlaying
                    }

                    adapter.isNotesTab(position) -> {
                        PlayerTabType.ShowNotes
                    }

                    adapter.isTranscriptTab(position) -> {
                        null
                    }

                    adapter.isBookmarksTab(position) -> {
                        PlayerTabType.Bookmarks
                    }

                    adapter.isChaptersTab(position) -> {
                        if (previousPosition != INVALID_TAB_POSITION) {
                            chaptersViewModel.trackChaptersShown(ChaptersShownSource.FullscreenPlayer)
                        }
                        PlayerTabType.Chapters
                    }

                    adapter.isSummaryTab(position) -> {
                        val episode = viewModel.listDataLive.value?.podcastHeader?.episode as? PodcastEpisode
                        if (episode != null) {
                            eventHorizon.track(
                                EpisodeSummaryTappedEvent(
                                    source = EpisodeSummarySourceType.FullscreenPlayer,
                                    episodeUuid = episode.uuid,
                                    podcastUuid = episode.podcastUuid,
                                ),
                            )
                        }
                        null
                    }

                    else -> {
                        Timber.e("Invalid tab selected")
                        null
                    }
                }
                tab?.let { tab ->
                    eventHorizon.track(
                        PlayerTabSelectedEvent(
                            tab = tab,
                        ),
                    )
                }
                previousPosition = position
            }
        })

        adapter = ViewPagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        transcriptTabEpisodeUuid = null
        viewPager.adapter = adapter
        viewPager.getChildAt(0).isNestedScrollingEnabled = false // HACK to fix bottom sheet drag, https://issuetracker.google.com/issues/135517665
        TabLayoutMediator(binding.tabLayout, viewPager, true) { tab, position ->
            tab.setText(adapter.pageTitle(position))
        }.attach()

        viewModel.listDataLive.observe(viewLifecycleOwner) {
            updateSections {
                adapter.updateNotes(addNotes = !it.podcastHeader.isUserEpisode)
            }
            if (transcriptTabEpisodeUuid != it.podcastHeader.episodeUuid) {
                transcriptTabEpisodeUuid = it.podcastHeader.episodeUuid
                reconcileTranscriptTab(shelfSharedViewModel.uiState.value)
            }
            val isSummaryOrChaptersEnabled =
                FeatureFlag.isEnabled(Feature.AI_SUMMARIES) || FeatureFlag.isEnabled(Feature.GENERATED_CHAPTERS)
            if (isSummaryOrChaptersEnabled && !it.podcastHeader.isUserEpisode) {
                summaryViewModel.loadSummary(it.podcastHeader.episodeUuid)
            } else {
                summaryViewModel.clearSummary()
            }
            val upNextCount = it.upNextEpisodes.size
            val drawableId = when {
                upNextCount == 0 -> R.drawable.mini_player_upnext
                upNextCount < 10 -> R.drawable.mini_player_upnext_badge
                else -> R.drawable.mini_player_upnext_badge_large
            }
            val upNextDrawable: Drawable? = AppCompatResources.getDrawable(binding.upNextButton.context, drawableId)
            binding.upNextButton.setImageDrawable(upNextDrawable)
            binding.countText.text = if (upNextCount == 0) "" else upNextCount.coerceAtMost(Settings.UP_NEXT_BADGE_MAX_COUNT).toString()

            binding.upNextButton.setOnClickListener {
                openUpNext(UpNextSource.PLAYER)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                podcastColorsFlow().collect { podcastColors ->
                    val playerColors = PlayerColors(theme.activeTheme, podcastColors)
                    view.setBackgroundColor(playerColors.background01.toArgb())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                shelfSharedViewModel.uiState.collect { state ->
                    reconcileTranscriptTab(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chaptersViewModel.uiState.collect {
                    updateSections {
                        adapter.updateChapters(addChapters = it.chaptersCount > 0)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                summaryViewModel.state.collect { state ->
                    updateSections {
                        adapter.updateSummary(
                            addSummary = state is SummaryViewModel.SummaryState.Loaded ||
                                state is SummaryViewModel.SummaryState.Upsell,
                        )
                    }
                }
            }
        }

        binding.btnClosePlayer.setOnClickListener { (activity as? FragmentHostListener)?.closePlayer() }

        bookmarksViewModel.multiSelectHelper.isMultiSelectingLive.observe(viewLifecycleOwner) { isMultiSelecting ->
            binding.multiSelectToolbar.isVisible = isMultiSelecting
            binding.multiSelectToolbar.setNavigationIcon(IR.drawable.ic_arrow_back)
            notifyBackstackChangedToHost()
        }
        bookmarksViewModel.multiSelectHelper.context = context
        binding.multiSelectToolbar.setup(
            lifecycleOwner = viewLifecycleOwner,
            multiSelectHelper = bookmarksViewModel.multiSelectHelper,
            menuRes = null,
            activity = requireActivity(),
            includeStatusBarPadding = false,
        )
    }

    fun openUpNext(source: UpNextSource) {
        this.upNextExpandedSource = source
        updateUpNextVisibility(true)
        upNextBottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun updateTabsVisibility(show: Boolean) {
        binding?.tabHolder?.isVisible = show
        binding?.viewPager?.isUserInputEnabled = show
        notifyBackstackChangedToHost()
    }

    fun onPlayerOpen() {
        try {
            if (isAdded) {
                ((childFragmentManager.fragments.firstOrNull { it is BookmarksFragment }) as? BookmarksFragment)
                    ?.onPlayerOpen()
            }
        } catch (e: IllegalStateException) {
            Timber.e(e)
        }
    }

    fun onPlayerClose() {
        try {
            if (isAdded) {
                ((childFragmentManager.fragments.firstOrNull { it is BookmarksFragment }) as? BookmarksFragment)
                    ?.onPlayerClose()
            }
        } catch (e: IllegalStateException) {
            Timber.e(e)
        }
    }

    private fun reconcileTranscriptTab(shelfState: ShelfSharedViewModel.UiState) {
        val playerEpisode = viewModel.listDataLive.value?.podcastHeader?.episode
        val transcriptTab = transcriptTabForPlayerEpisode(
            playerEpisodeUuid = playerEpisode?.uuid,
            playerPodcastUuid = (playerEpisode as? PodcastEpisode)?.podcastUuid,
            shelfEpisodeUuid = shelfState.episode?.uuid,
            shelfTranscriptAvailable = shelfState.isTranscriptAvailable,
        )
        updateSections {
            adapter.updateTranscript(
                addTranscript = transcriptTab?.isAvailable == true,
                episodeUuid = transcriptTab?.episodeUuid ?: playerEpisode?.uuid,
                podcastUuid = transcriptTab?.podcastUuid ?: (playerEpisode as? PodcastEpisode)?.podcastUuid,
            )
        }
    }

    private fun updateSections(update: () -> Unit) {
        val viewPager = binding?.viewPager ?: return
        val selectedSection = adapter.sectionType(viewPager.currentItem)
        isReconcilingSections = true
        try {
            update()
            val newPosition = remapSectionPosition(selectedSection, adapter.sectionTypes(), viewPager.currentItem)
            if (newPosition != viewPager.currentItem) {
                viewPager.setCurrentItem(newPosition, false)
            }
        } finally {
            isReconcilingSections = false
        }
    }

    fun openPlayer() {
        val index = adapter.indexOfPlayer
        if (index == -1) return
        binding?.viewPager?.currentItem = index
    }

    fun openBookmarks() {
        val index = adapter.indexOfBookmarks
        if (index == -1) return
        binding?.viewPager?.currentItem = index
    }

    fun openChaptersAt(chapter: Chapter) {
        val index = adapter.indexOfChapters
        if (index == -1) {
            return
        }
        binding?.viewPager?.currentItem = index

        // tapping on the chapter title on the now playing screen should scroll to that chapter when the fragment is available
        chaptersViewModel.scrollToChapter(chapter)
    }

    fun updateUpNextVisibility(show: Boolean) {
        binding?.upNextFrameBottomSheet?.isVisible = show
        (activity as? FragmentHostListener)?.lockPlayerBottomSheet(show)
    }

    private fun notifyBackstackChangedToHost() {
        notifyBackstackChanged()
        (activity as? FragmentHostListener)?.onPlayerBackstackChanged()
    }

    override fun getBackstackCount(): Int {
        return if (upNextBottomSheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED ||
            bookmarksViewModel.multiSelectHelper.isMultiSelecting ||
            isTranscriptVisible
        ) {
            1
        } else {
            0
        }
    }

    override fun onBackPressed(): Boolean {
        return when {
            upNextBottomSheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED -> {
                upNextBottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                true
            }

            bookmarksViewModel.multiSelectHelper.isMultiSelecting -> {
                bookmarksViewModel.multiSelectHelper.closeMultiSelect()
                binding?.viewPager?.isUserInputEnabled = true
                true
            }

            isTranscriptVisible -> {
                updateTabsVisibility(true)
                shelfSharedViewModel.closeTranscript()
                true
            }

            else -> false
        }
    }

    private val isTranscriptVisible: Boolean
        get() = binding?.tabHolder?.isVisible == false

    private fun podcastColorsFlow(): Flow<PodcastColors> {
        return viewModel.podcastFlow.map { podcast ->
            podcast?.let(::PodcastColors) ?: PodcastColors.ForUserEpisode
        }
    }

    companion object {
        private const val INVALID_TAB_POSITION = -1
        private const val SOURCE_KEY = "source"
        private const val TAB_KEY = "tab"
    }
}

internal data class TranscriptTab(
    val episodeUuid: String,
    val podcastUuid: String?,
    val isAvailable: Boolean,
)

internal fun transcriptTabForPlayerEpisode(
    playerEpisodeUuid: String?,
    playerPodcastUuid: String?,
    shelfEpisodeUuid: String?,
    shelfTranscriptAvailable: Boolean,
): TranscriptTab? {
    val episodeUuid = playerEpisodeUuid?.takeIf { it.isNotEmpty() } ?: return null
    return TranscriptTab(
        episodeUuid = episodeUuid,
        podcastUuid = playerPodcastUuid,
        isAvailable = shelfTranscriptAvailable && episodeUuid == shelfEpisodeUuid,
    )
}

internal enum class PlayerSectionType {
    Player,
    Notes,
    Summary,
    Transcript,
    Bookmarks,
    Chapters,
}

internal data class PlayerSection(
    val type: PlayerSectionType,
    val episodeUuid: String? = null,
    val podcastUuid: String? = null,
)

internal fun buildPlayerSections(
    hasNotes: Boolean,
    hasSummary: Boolean = false,
    transcriptEpisodeUuid: String? = null,
    transcriptPodcastUuid: String? = null,
    hasChapters: Boolean = false,
): List<PlayerSection> {
    return buildList {
        add(PlayerSection(PlayerSectionType.Player))
        if (hasNotes) add(PlayerSection(PlayerSectionType.Notes))
        if (hasSummary) add(PlayerSection(PlayerSectionType.Summary))
        if (transcriptEpisodeUuid != null) {
            add(PlayerSection(PlayerSectionType.Transcript, transcriptEpisodeUuid, transcriptPodcastUuid))
        }
        if (hasChapters) add(PlayerSection(PlayerSectionType.Chapters))
        add(PlayerSection(PlayerSectionType.Bookmarks))
    }
}

private class ViewPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) : FragmentStateAdapter(fragmentManager, lifecycle) {
    private var sections = buildPlayerSections(hasNotes = false)

    val indexOfPlayer: Int
        get() = sections.indexOfFirst { it.type == PlayerSectionType.Player }

    val indexOfChapters: Int
        get() = sections.indexOfFirst { it.type == PlayerSectionType.Chapters }

    val indexOfBookmarks: Int
        get() = sections.indexOfFirst { it.type == PlayerSectionType.Bookmarks }

    fun updateNotes(addNotes: Boolean) {
        updateSections(hasNotes = addNotes)
    }

    fun updateSummary(addSummary: Boolean) {
        updateSections(hasSummary = addSummary)
    }

    fun updateChapters(addChapters: Boolean) {
        updateSections(hasChapters = addChapters)
    }

    fun updateTranscript(addTranscript: Boolean, episodeUuid: String?, podcastUuid: String?) {
        updateSections(
            hasTranscript = addTranscript && episodeUuid != null,
            transcriptEpisodeUuid = episodeUuid,
            transcriptPodcastUuid = podcastUuid,
        )
    }

    fun sectionType(position: Int): PlayerSectionType? {
        return sections.getOrNull(position)?.type
    }

    fun sectionTypes(): List<PlayerSectionType> {
        return sections.map { it.type }
    }

    // Stable IDs via getItemId/containsItem allow FragmentStateAdapter to efficiently diff fragments
    @SuppressLint("NotifyDataSetChanged")
    private fun updateSections(
        hasNotes: Boolean = sections.any { it.type == PlayerSectionType.Notes },
        hasSummary: Boolean = sections.any { it.type == PlayerSectionType.Summary },
        hasTranscript: Boolean = sections.any { it.type == PlayerSectionType.Transcript },
        transcriptEpisodeUuid: String? = sections.firstOrNull { it.type == PlayerSectionType.Transcript }?.episodeUuid,
        transcriptPodcastUuid: String? = sections.firstOrNull { it.type == PlayerSectionType.Transcript }?.podcastUuid,
        hasChapters: Boolean = sections.any { it.type == PlayerSectionType.Chapters },
    ) {
        val currentSections = sections
        val newSections = buildPlayerSections(
            hasNotes = hasNotes,
            hasSummary = hasSummary,
            transcriptEpisodeUuid = transcriptEpisodeUuid.takeIf { hasTranscript },
            transcriptPodcastUuid = transcriptPodcastUuid,
            hasChapters = hasChapters,
        )
        if (currentSections != newSections) {
            sections = newSections
            notifyDataSetChanged()
        }
    }

    override fun getItemId(position: Int): Long {
        return sections[position].stableId()
    }

    override fun containsItem(itemId: Long): Boolean {
        return sections.any { it.stableId() == itemId }
    }

    override fun getItemCount(): Int {
        return sections.size
    }

    override fun createFragment(position: Int): Fragment {
        val section = sections[position]
        return when (section.type) {
            PlayerSectionType.Player -> PlayerHeaderFragment()

            PlayerSectionType.Notes -> NotesFragment()

            PlayerSectionType.Summary -> SummaryFragment()

            PlayerSectionType.Transcript -> PlayerTranscriptFragment.newInstance(
                episodeUuid = requireNotNull(section.episodeUuid),
                podcastUuid = section.podcastUuid,
            )

            PlayerSectionType.Bookmarks -> BookmarksFragment.newInstance(SourceView.PLAYER)

            PlayerSectionType.Chapters -> ChaptersFragment.forPlayer()
        }
    }

    @StringRes
    fun pageTitle(position: Int): Int {
        return when (sections[position].type) {
            PlayerSectionType.Player -> LR.string.player_tab_playing
            PlayerSectionType.Notes -> LR.string.player_tab_notes
            PlayerSectionType.Summary -> LR.string.player_tab_summary
            PlayerSectionType.Transcript -> LR.string.transcript
            PlayerSectionType.Bookmarks -> LR.string.player_tab_bookmarks
            PlayerSectionType.Chapters -> LR.string.player_tab_chapters
        }
    }

    fun isPlayerTab(position: Int) = sections[position].type == PlayerSectionType.Player
    fun isNotesTab(position: Int) = sections[position].type == PlayerSectionType.Notes
    fun isSummaryTab(position: Int) = sections[position].type == PlayerSectionType.Summary
    fun isTranscriptTab(position: Int) = sections[position].type == PlayerSectionType.Transcript
    fun isBookmarksTab(position: Int) = sections[position].type == PlayerSectionType.Bookmarks
    fun isChaptersTab(position: Int) = sections[position].type == PlayerSectionType.Chapters

    private fun PlayerSection.stableId(): Long {
        return playerSectionStableId(this)
    }
}

internal fun remapSectionPosition(
    selectedSection: PlayerSectionType?,
    sections: List<PlayerSectionType>,
    currentPosition: Int,
): Int {
    return sections.indexOf(selectedSection).takeIf { it >= 0 }
        ?: currentPosition.coerceIn(0, sections.lastIndex)
}

internal fun playerSectionStableId(section: PlayerSection): Long {
    return when (section.type) {
        PlayerSectionType.Player -> 1L
        PlayerSectionType.Notes -> 2L
        PlayerSectionType.Summary -> 3L
        PlayerSectionType.Transcript -> 4L xor stableStringId(requireNotNull(section.episodeUuid))
        PlayerSectionType.Bookmarks -> 5L
        PlayerSectionType.Chapters -> 6L
    }
}

private fun stableStringId(value: String): Long {
    return value.fold(1125899906842597L) { hash, character ->
        hash * 31 + character.code
    }
}
