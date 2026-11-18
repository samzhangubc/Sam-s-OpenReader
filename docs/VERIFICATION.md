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

PDF extraction is conditional on an accessible text layer. The runtime requires no network permission. The project’s MIT license applies to project code. Readium is BSD-3-Clause, RE2/J uses a BSD license, AndroidX uses Apache-2.0, and the bundled fonts carry the notices embedded under Settings → Font licenses.

## SDD v0.8 source review

Reviewed against the existing checkout on 2026-11-18. This pass made source-level corrections to `PageTurn.kt` so a new turn cancels the active animation and resolves its committed or cancelled destination before starting the new turn, and to library progress storage/display with a Room migration from database version 2 to version 3. The SDD v0.8 document was already an uncommitted local change and is included in the implementation handoff.

No Android build, unit tests, instrumentation tests or A56 measurements were run in this environment. The historical device test above predates these source changes. The SDD acceptance plan remains the implementation and release checklist.

| Area | Source review status |
| --- | --- |
| Page-turn animation interruption | Updated to resolve the prior animation before a fresh page turn. Build, rapid-input tests, TalkBack and renderer integration remain unverified. |
| Library progress | Added a persisted progress field, Room 2→3 migration, and shelf display. The visual and speech locator update paths and migration on-device remain unverified. |
| TXT chapter-indexed reopen, bounded prefetch and offset mapping | SDD requirement. Implementation remains unverified. |
| Chapter parsing, front matter and manual overrides | Existing parser source is present. SDD v0.8 fixture coverage and persistence behavior remain unverified. |
| Library progress and resume consistency | Existing saved locator is present. Whole-book progress display and transactional consistency remain unverified. |
| Appearance and chapter typography | Existing appearance settings are present. SDD v0.8 consistency across TXT, EPUB, library and restart remains unverified. |
| Samsung TTS engine selection, offline voices, lifecycle, timers and resource budgets | Existing playback service is present. SDD v0.8 behavior and A56 measurements remain unverified. |
| Backup compatibility, restore atomicity and database migration | Existing Room version 2 and archive schema 1 are recorded by the SDD. Upgrade and restore scenarios remain unverified. |
| Localization, font coverage, accessibility and performance | Historical checks are limited to the preview report above. SDD v0.8 coverage remains unverified. |

## Still required

JDK 17 and Android command-line tools are now installed locally. The official command-line tools archive checksum was verified. The A56 SM-A566W (Android 17, API 37) appears as an authorized ADB device, and its reported TTS default is `com.samsung.SMT`.

On 2026-11-18, `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` passed after source changes. The unit suite ran 8 tests with no failures. `:app:connectedDebugAndroidTest` installed and launched the side-by-side debug package, then all three instrumentation tests failed in AndroidX Espresso idle synchronization with `NoSuchMethodException: android.hardware.input.InputManager.getInstance`. The tests did not reach their assertions. The test framework needs a compatible Android 17 path before these results can verify app behavior.

The debug APK was installed beside the existing signed `org.sam.openreader` preview as `org.sam.openreader.debug`, launched, and rebuilt/reinstalled after source changes. The existing app and its data were not uninstalled. This confirms installation and activity launch only, not visual or workflow behavior.

The source changes in this pass cover page-turn interruption, persisted and displayed library progress with a Room 2→3 migration, and larger TXT chapter headings with spacing. These changes have a successful compile/lint/unit-test result; the relevant UI, migration and gesture behavior still needs device assertions. Indexed chapter-only TXT reopening, complete front-matter parsing, full selection arbitration, complete TTS lifecycle/resource budgets, and the remainder of SDD v0.8 still require implementation or acceptance verification.

Run the connected tests after updating or adapting the AndroidX test stack for API 37. Then exercise the SDD acceptance plan on the A56, including screen-off Samsung TTS, selection and interrupted page turns, chapter-indexed reopen, progress migration, and performance/power measurements. Record results against the exact commit and app version tested.
