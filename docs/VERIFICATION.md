# SDD v0.6 verification

The previous Java alpha has been replaced. The new implementation targets API 36 with an API 29 installation floor. The user-supplied launcher icon is preserved. The release candidate requires physical-device acceptance before it can be described as certified for the North American A56.

| Area | Implementation and remaining acceptance work |
| --- | --- |
| Library | SAF picker, Android View/Share, private copies, duplicate detection, open-once promotion, search/sort/rename/remove, original and edited export, book and library archives. Verify interruption, storage exhaustion, restore collisions and removal recovery. |
| TXT | Strict decoding, normalization, bounded streaming reads, disk indexes, derived paged EPUB, configurable full-line heading rules with candidates and manual include/exclude, staged literal replacement, undo and original restore. Advanced edit batches and all hierarchy, anchor and multi-line parsing cases in sections 5–6 require further work. |
| Reading | Readium EPUB navigator, PDF renderer, zoom, physical left/right page turns, Slide/Cover/Uncover/None, center menu, contents, bookmarks, search and persistent locators. EPUB page counts come from the current rendered spine resource. Exact chapter totals across resource boundaries and nested in-resource sections require further work. Percentage is optional and defaults off. |
| Appearance | Eight bundled families per script, checksum-pinned fonts and licenses, 36 palettes, named personal styles, role editor, global and per-book settings. Verify region selection, fallback, emoji coverage and style round trips on the handset. |
| Speech | A foreground media service with a Samsung engine check, installed offline voice selection, bounded utterances, audio focus, media controls, paragraph transport, rate and stop timers. PDF extraction requires API 35+. Verify semantic seeking, speech continuity, timer accuracy and engine behavior on Samsung hardware. |
| Compatibility and budgets | CI exercises an Android emulator. Physical A56 firmware, Samsung voice inventory, complete three-language coverage, two-hour stability, screen-off PSS, CPU and battery figures remain unmeasured. See SDD sections 11–12 for methodology and thresholds. |

PDF extraction is conditional on an accessible text layer. The runtime requires no network permission. The project’s MIT license applies to project code. Readium is BSD-3-Clause, RE2/J uses a BSD license, AndroidX uses Apache-2.0, and the bundled fonts carry the notices embedded under Settings → Font licenses.
