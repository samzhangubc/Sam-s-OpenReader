# SDD v0.6 verification

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
