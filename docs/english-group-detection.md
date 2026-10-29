# Smart English Group Detection (unreleased)

This optional feature is proposed after the stable v1.0.0 baseline. It is not a claim
that the published v1.0.0 APK includes it. No release or hardware acceptance is implied.

## Using a TV remote

Open Channel Manager and choose **Find English Groups**, then **Scan provider groups**.
Opening the screen does not scan. Scanning uses imported local metadata from all configured
providers, independently of the Manager's current provider selector, and never applies a filter.

Review the original provider name, original group name, channel count (including hidden
channels), confidence and reason. Use the D-pad to focus a group and OK to toggle it.
Focus alone never selects. **Select all high-confidence suggestions** adds only likely groups;
**Clear selection** clears the draft. Groups with unknown evidence can also be selected manually.

- **Likely English · high confidence:** an explicit English, EN or ENG label in a deliberate
  language-label position. This is confidence in metadata, not verified broadcast language.
- **Needs review · uncertain:** mixed-language labels, subtitle/competition/learning references,
  or ambiguous abbreviations. Review these yourself before selecting.
- **No reliable English evidence · unknown:** missing, unusable or unrecognized language evidence.
  UK, USA, Canada, BBC, FOX and English-looking names do not establish English audio.

**Apply view filter** explicitly confirms your selection and returns to the Manager.
Only selected provider/category identities contribute to the category rail and Manager
channel/search results. Existing provider selection still applies; choosing a provider with
no selected groups can show an empty view. **Show All Groups** in the Manager rail or review
screen clears the English filter; the separate provider selector continues to work as before.
**Cancel without changes** or Back discards draft changes while retaining the previous applied
filter. Applying, clearing and cancelling return focus to Find English Groups. Scan stays
focused while working. Long results and action lists scroll with the D-pad.

The filter lasts only for this Channel Manager session, including visits to its custom-group
editor. Leaving the Manager clears it. Provider/category names, counts and existing Manager
revision changes invalidate stale results, drafts and the active filter; scan again after a
refresh. Classification never runs automatically after invalidation.

## Safety and limits

Provider categories remain the source of truth. M3U/M3U8 uses existing `group-title` imports;
Xtream and authorized Stalker/MAC portals use their existing category names and IDs. The same
name or category ID at two providers remains separate in review and filtering. The existing
folded category rail keeps its labels and relative ordering; only selected underlying groups
contribute channels while a filter is active.

No importer currently supplies a genuine structured broadcast language field. None is invented.
Uncategorized channels without a provider category cannot receive a group suggestion. Unknown
labels are normal. Conservative local rules are not an exhaustive language dictionary and do
not establish the audio language of any channel in a group. Labels can be inaccurate or refer
to content other than audio; users make the final choice.

No channels or custom groups are deleted, renamed, moved or reorganized. Detection does not
write hidden flags, favorites, ordering, EPG, DVR, Sports Hub, Multi-View or cloud records. No
schema migration, sync protocol, dependency, network language lookup or stream probe is added.
Only the existing channel-management controls can explicitly change favorites or visibility.

## Implementation and validation

`EnglishGroupDetector` bounds names to 512 UTF-16 units, applies NFKC normalization and
case folding with `Locale.ROOT`, and uses complete tokens. Oversized input is rejected rather
than truncated, so a distant subtitle/mixed-language suffix cannot disappear. Format controls
and malformed surrogate input remain unknown. Normalization itself is bounded by the input limit.

`englishProviderGroups` maps existing categories and a single grouped count projection to
metadata-only records. `EnglishGroupSession` classifies on `Dispatchers.Default`, coalesces
repeated Scan presses and cancels work with generation checks after changes, Cancel or exit.
There is no persistent cache; Compose memoizes section grouping for the current result list.
Manager rows reuse the original repository query and are filtered by provider/category before
channel-variant folding. No classification occurs during recomposition or per-channel lookup.

Run the complete Kotlin/Robolectric regression suite, lint and debug compilation:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Focused checks (also included in the complete suite):

```sh
./gradlew :app:testDebugUnitTest --tests 'app.opentv.channels.*' --tests 'app.opentv.ui.channels.EnglishGroupsScreenTest' --tests 'app.opentv.ui.AdvancedGuideViewModelTest'
```

Tests cover synthetic M3U/Xtream/Stalker metadata, colliding provider/category identities,
metadata-only projection of 20,000 channels, 20,000 synthetic category names, absence of
additional database/network requests during detection, catalog/state immutability, session
lifecycle and stale work, real Manager flow filtering, and Compose remote focus/OK/Back.

Before release, test on physical Android TV, Google TV and Fire TV, including a low-memory
model: D-pad paths between actions and long result sections, focus after refresh/scan/apply,
Back/Cancel with an existing filter, large font scaling, TalkBack selected states, source
switching, catalog refresh during scan, and custom-editor round trips. Record device/OS,
category/channel counts, timings and memory observations. Synthetic automated checks do not
certify hardware latency, accessibility or focus behavior.

The existing playback instrumentation suite requires a disposable running emulator and
FFmpeg. Run `bash scripts/run-iptv-android.sh`, which supplies local synthetic provider/media
fixtures to `:app:connectedDebugAndroidTest`, or use the existing
[IPTV Playback Tests workflow](../.github/workflows/iptv-playback.yml). No connected device
means this instrumentation/device validation cannot run locally. UI text uses default Android
string resources with the repository's English-fallback convention pending community translations.
