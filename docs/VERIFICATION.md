# OpenReader verification status

## Historical preview result

The facts in this section describe release preview 0.2.0-preview.1 and do not verify the v0.8 implementation requirements.

The previous Java alpha has been replaced. The new implementation targets API 36 with an API 29 installation floor. The user-supplied launcher icon is preserved. Release preview 0.2.0-preview.1 installed successfully on the user's SM-A566W, Android 16, build BP4A.251205.006.A566WVLSBCZI2, security patch 2026-09-05. Instrumentation passed all three device tests on 2026-11-17. The observed Samsung TTS default is com.samsung.SMT, version 3.5.02.16.

| Area | Implementation and remaining acceptance work |
| --- | --- |
| Library | SAF picker, Android View/Share, private copies, duplicate detection, open-once promotion, search/sort/rename/remove, original and edited export, and archives. Device fixtures covered TXT and PDF open-once imports and reader entry. Storage-failure recovery, restore collision cases and removal undo need more coverage. |
| TXT | Strict decoding, normalization, bounded streaming, disk indexes, derived paged EPUB, editable block rules, manual include/exclude, staged ordered replacement, undo, and original restoration. The full SDD hierarchy, anchor remapping and multi-line fixtures need broader validation. |
| Reading | Readium EPUB navigator, Android PDF renderer, zoom, physical left/right paging, Slide/Cover/Uncover/None, floating controls, contents, bookmarks, search and locators. The device test opened a TXT section and a PDF. Exact chapter-page totals and animation-frame behavior need further measurement. Percentage is optional and defaults off. |
| Appearance | Eight bundled font families per script, pinned fonts and notices, 36 palettes, named styles, per-role editing, and global/book settings. Region-specific glyph and emoji coverage still need the SDD corpus. |
| Speech | A foreground media service, checked Samsung engine, offline-voice picker, bounded utterances, audio focus, media controls, paragraph transport, rate and timer controls. The A56 reports Samsung TTS as the system default. Screen-off playback, voice locales, end-of-chapter timer and Samsung power behavior still need measured device runs. PDF text extraction requires API 35+. |
| Compatibility and budgets | Physical-device instrumentation passed on Android 16. GitHub emulator coverage targets API 29 and 36. Full UI localization, two-hour stability, screen-off PSS, CPU and battery measurements remain open. See SDD sections 11–12 for the measurement method. |

PDF extraction is conditional on an accessible text layer. The runtime requires no network permission. The project's MIT license applies to project code. Readium is BSD-3-Clause, RE2/J uses a BSD license, AndroidX uses Apache-2.0, and the bundled fonts carry the notices embedded under Settings → Font licenses.

## SDD v0.8 implementation run

Reviewed and implemented against `docs/SDD.md` on 2026-11-18. The existing Android preview was retained where it met the SDD. This implementation preserves app ID `org.sam.openreader`, the supplied launcher icon, the bundled font assets and notices, MIT license, and personal-project support terms. The SDD was retained and its database-version note updated to distinguish the preview from current source.

### Build and device evidence

| Check | Result |
| --- | --- |
| Debug APK build | `:app:assembleDebug` passed on the final source revision. |
| JVM tests | `:app:testDebugUnitTest` passed; 14 tests cover text decoding/indexing, heading detection, edits and revision/locator behavior. |
| Lint | `:app:lintDebug` passed. |
| A56 connected tests | Passed 6/6 on 2026-11-18. Checks cover rapid turn requests, private URI-backed TXT and PDF import copies, local-library resume and chapter navigation, Samsung offline speech start/pause/quit, and revision/original/voice transport. UI-level Android Sharesheet and file-provider dispatch are source-reviewed but not covered by this fixture. |
| Device | Samsung SM-A566W (`a56xcs`), Android 17/API 37, One UI property 90000, build fingerprint `samsung/a56xcs/a56x:17/CP2A.260605.016/A566WVLUBDZJ1_OYVBDZJ1:user/release-keys`. This is newer than the SDD's Android 16/API 36 and One UI 8 baseline, so it is forward-compatibility evidence only. |
| Samsung TTS | `com.samsung.SMT` 3.5.02.58 is the system default. The device test verifies an installed offline Samsung voice starts an utterance, pauses and quits. Two-hour screen-off playback, PSS, CPU, battery drain and chapter-timer expiry are not measured. |
| Install/launch | Debug APK `0.2.0-preview.2` (version code 3) is installed and launched on the A56. It is a debug build, not release-signed or published. |

The connected test exercises TXT saved-position resume in the middle of a long paragraph and checks the resulting EPUB locator is within 512 UTF-16 units of the saved offset. It also navigates to a later Chinese chapter. The rapid-turn regression exercises a synthetic `PageTurn`, not all gestures in every renderer or animation. URI-backed import tests exercise snapshot copying and validation through a fixture provider; Android Sharesheet dispatch and arbitrary third-party providers are not covered by this fixture.

### Source behavior reviewed

- TXT import keeps the source snapshot in app storage, supports the configured decoders, builds canonical text and disk indexes, publishes indexed chapter resources and stores stable nearby anchors for direct passage resume. Generated text anchors are bounded to 256 UTF-16 units at grapheme boundaries. Library shelf progress is persisted separately from the reader footer, and migrations 1→2→3→4 are present.
- Chapter detection supports configurable line blocks (prefix, contains, number system, marker, title, end and exclusion), Chinese/English/Roman/decimal forms, selected front-matter and contents heuristics, and persisted manual include/exclude decisions. The broader SDD fixture matrix, candidate conflict/ranking workflow, multi-line and user-example rule synthesis, split/merge editor, and preservation of manual decisions through all bulk edit patterns still need acceptance coverage and some UI work.
- Appearance, script-specific font selection, chapter typography, color palettes and custom styles are present in source. Current tests do not verify font shaping/Unicode 18 coverage, all renderer combinations, or every persisted appearance setting after process death.
- Samsung speech uses an explicit Samsung engine and an independent foreground service, with paragraph navigation, rate, timer, audio focus and locator persistence paths. Device tests cover real offline speech start/pause/stop; background/screen-off operation and resource budgets remain unmeasured.
- Library copies, exports, backup preview/restore, settings, bookmarks, and schema migrations were source-reviewed. Migration from populated preview databases, archive restore collision/corruption, atomic recovery, process death, and preservation of all user data have not been exercised end to end.

### Remaining SDD acceptance gates

- The current TXT path generates bounded chapter/resource files and stable anchors, but it does not yet implement or measure an app-managed sliding prefetch window for the current chapter plus two neighbors each way or enforce the specified 4 MiB decoded-text budget. Cold open, first-frame latency, page-turn percentiles and large-book memory use need profiling.
- Run the chapter parser's full fixture matrix and complete UI workflows for custom rule conflicts, front matter, manual map edits and bulk edit remapping. Verify contents listings never absorb or split actual body chapters incorrectly.
- Test populated database upgrades and backup restore/collision cases while verifying original files, revisions, locators, bookmarks, book styles, global styles and manual chapter corrections.
- Complete renderer-level selection/handle-drag arbitration, all four page animation modes under rapid input, screen-off/background speech, timer expiry, TalkBack, and localization walkthroughs in English, Simplified Chinese and Traditional Chinese.
- Run the SDD's Android 16/API 36, API 29 compatibility, Unicode/glyph coverage, two-hour playback, screen-off PSS/CPU/battery and A56 latency measurements. Android 17/API 37 results do not replace the baseline matrix.

Packaging/signing and GitHub release publication are outside this source handoff. The device APK is for debug installation only.
