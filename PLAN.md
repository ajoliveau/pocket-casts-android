# Player Transcript Tab Plan

## Goal

Add custom transcripts to the fullscreen player as a normal top-level tab, instead of requiring the player overflow menu.

When the current episode has a transcript, the intended order is:

```text
En lecture → Détails → Transcription → Favoris
```

`En lecture` is the existing Player section, `Détails` is the existing Notes section, and `Favoris` is the existing Bookmarks section.

Keep AI summaries and generated chapters disabled by default. Native chapters may still appear when an episode provides real chapters.

## Scope and data safety

- Change player navigation and transcript presentation only.
- Do not change Room entities, schemas, migrations, episode data, downloads, playback progress, accounts, or transcript storage.
- Do not uninstall the personal app or clear its data when testing an APK update.
- Keep the existing overflow transcript action as a fallback until the top tab is verified.
- Do not deploy or modify a remote server as part of the Android change.

Installing over the existing `.personal` package with the same debug signing key should preserve app data.

## Current architecture

`modules/features/player/.../PlayerContainerFragment.kt` owns the fullscreen player `ViewPager` and currently defines:

```text
Player   → En lecture
Notes    → Détails
Summary  → Résumé, optional
Chapters → Chapitres, optional
Bookmarks → Favoris
```

The transcript is currently opened by `ShelfSharedViewModel` as a full-screen overlay. `TranscriptFragment` is a `BaseDialogFragment`, so it cannot be inserted directly into the player `ViewPager` without either:

- creating an embedded `PlayerTranscriptFragment`, or
- extracting the shared transcript content into a reusable non-dialog fragment/composable.

Prefer the smallest refactor that reuses the existing `TranscriptPage`, transcript view model, search, sharing, playback controls, and fingerprint timing integration.

## Transcript availability

Show the new tab only when the current playing episode has a successfully available transcript from the configured custom endpoint.

The existing `ShelfSharedViewModel.uiState` already observes transcript availability for the current Up Next episode. Reuse that state, or inject `TranscriptManager` into `PlayerContainerFragment` if lifecycle ownership requires it.

Update the adapter when:

- the current playing episode changes;
- transcript availability changes;
- the player is reopened after the endpoint becomes available.

Avoid adding a tab merely because an episode has persisted metadata. Availability should continue to mean that the transcript can be loaded successfully.

## Player tab implementation

1. Add a `Transcript` section to `PlayerContainerFragment`'s `ViewPagerAdapter`.
2. Place it after `Notes` and before `Bookmarks`.
3. Add `updateTranscript(addTranscript: Boolean)` to the adapter's section diffing logic.
4. Instantiate an embedded transcript page for the new section.
5. Pass the current episode UUID and podcast UUID into that page.
6. Keep the existing tab visibility, back-stack, and Up Next behavior working.
7. Ensure changing episode while the player remains open replaces the transcript page and does not display the previous episode's transcript.
8. Keep the existing overflow action during the first implementation. Remove it only after the top tab is proven reliable and the user explicitly wants it removed.

For the requested configuration, with AI Summaries and Generated Chapters off, the resulting tabs should be exactly:

```text
En lecture, Détails, Transcription, Favoris
```

## Timing and fingerprinting

The app already contains `FingerprintTimingManager` and the `Synced transcripts with playback timing` feature flag.

Use the existing behavior first:

- Keep the flag enabled for the current working build.
- Preserve fingerprint-based alignment for official generated transcripts.
- Preserve direct cue timestamp seeking for custom VTT transcripts.
- Reuse the same `TranscriptPage` so phrase tapping, current-line highlighting, auto-scroll, search, and playback controls behave consistently.

Current known detail: `TranscriptPage` gates its tap handler behind `SYNCED_TRANSCRIPTS`. The custom direct-seek path in `TranscriptViewModel` already avoids fingerprint preparation for directly seekable VTTs. If the new embedded page works with the flag enabled, do not broaden the timing change in this tab task.

If direct custom VTT playback highlighting does not progress reliably, make that a focused follow-up:

- allow direct custom VTT interaction without requiring the Beta flag;
- resolve highlights directly against `playbackState.positionMs` and cue timestamps;
- keep fingerprint mapping for official/reference-timeline transcripts;
- avoid starting fingerprint work for direct custom VTTs.

Do not mix this follow-up into the tab navigation change unless testing shows it is required for the embedded page.

## Endpoint prerequisite

The APK was built with:

```text
http://192.168.2.73:9870
```

The following custom URL pattern is expected:

```text
/generated_transcripts/{podcastUuid}/{episodeUuid}.vtt
```

Current investigation found:

- The `51f13c47-e9e3-494d-bba5-cb6d1da8a40e` VTT exists in the web project's private transcript storage.
- `http://pocket-cast-web.ddev.site/generated_transcripts/b9367bd0-7ada-013b-f29f-0acc26574db2/51f13c47-e9e3-494d-bba5-cb6d1da8a40e.vtt` returns 200 from the local DDEV project.
- The APK-configured `192.168.2.73:9870` URL currently returns 404 for that episode.
- The `c0fd37d0-b8d6-4f81-8c09-4f206db44909` URL also currently returns 404 at the configured endpoint, although it previously returned a valid 808-cue VTT and its visible transcript may be cached.

Before UI acceptance testing, make sure the endpoint reachable from the Android device serves both requested episodes. Do not assume a DDEV hostname or localhost is reachable from the device.

## Testing

Add or update focused tests for:

- adapter section order with and without a transcript;
- transcript tab insertion after Details and before Bookmarks;
- removal when the current episode has no transcript;
- replacement when the Up Next episode changes;
- embedded transcript loading with the correct episode UUID;
- no stale transcript from the previous episode;
- existing transcript search and phrase seeking;
- existing overflow transcript behavior as a fallback.

Run commands conservatively so the development machine remains usable:

```bash
./gradlew --max-workers=2 --no-parallel :modules:features:player:testDebugUnitTest
./gradlew --max-workers=2 --no-parallel :modules:features:transcripts:testDebugUnitTest
./gradlew --max-workers=2 --no-parallel :modules:features:podcasts:testDebugUnitTest
./gradlew --max-workers=2 --no-parallel spotlessCheck
./gradlew --max-workers=2 --no-parallel :app:assemblePersonal \
  -PcustomTranscriptBaseUrl=http://192.168.2.73:9870
```

The broad repository test suite previously had unrelated Mockito/ClassReader failures. Report those separately rather than treating them as failures of this tab change.

## Acceptance criteria

- With a transcript available, the fullscreen player shows `En lecture`, `Détails`, `Transcription`, and `Favoris` in that order when optional AI tabs are disabled.
- Without a transcript, no Transcription tab appears.
- Selecting Transcription stays inside the player `ViewPager` and no longer hides the player tab bar behind an overlay.
- The transcript belongs to the currently playing episode.
- Existing custom VTT phrase seeking and playback progression work.
- Search, sharing, and play/pause controls continue to work.
- The overflow action remains safe during rollout.
- Installing the APK does not alter podcast, episode, download, account, playback, or transcript data.
- The custom endpoint is reachable from the device and returns the expected VTT for each test episode.
