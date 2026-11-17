# OpenReader 0.1.0-alpha.1

First installable native Android alpha for Android 15 and newer, including the Samsung A56 on Android 16.

The launcher uses the supplied green book artwork. Import opens the phone document browser. TXT, basic EPUB text and PDF viewing use private app storage. TXT includes candidate chapter navigation, literal whole-book cleanup, undo and encoding selection. The reader includes type size and light/dark/watercolor choices. Samsung TTS has screen-independent foreground playback, pause/resume, paragraph controls, speed, a 15-minute timer and stop.

## Installation

Download the APK attached to this prerelease, open it on the phone and allow installation from your browser or file manager when Android asks. Select Samsung TTS as the phone's preferred speech engine and install the relevant voice data before listening.

This alpha is debug-signed for sideload evaluation. Future builds may require uninstalling it. Export originals before uninstalling. Stored edits currently have no portable backup.

## Current limitations

- The SDD remains the intended product specification. This alpha implements an initial subset.
- EPUB is extracted to text in spine order. Publisher layout, illustrations, fixed-layout EPUB and authored navigation are pending.
- Fonts, the full 36-style palette catalog, separate Chinese/English typography, complete translations and the four page-turn animations are pending.
- TXT chapter rules currently support a prefix, number and marker with candidate preview. The visual block editor and manual chapter overrides are pending.
- Background speech has no synchronization back to the visual position. Paragraphs are split at line boundaries and long chunks. PDF TTS and robust voice selection are pending.
- TXT/EPUB text is loaded into memory, with a 50 MiB import limit and additional EPUB extraction limits. Large-book streaming and SDD performance budgets remain unverified.
- Previous-page layout uses an approximate preceding text window. Exact previous-page pagination, chapter/page footer totals, selection, search, bookmarks, deletion and full library backup remain pending.
- Appearance colors currently last for the activity session. Typeface customization is pending.
- No physical Samsung device validation has been performed. The build workflow includes an Android 15 emulator smoke test for opening a library TXT with Chinese and emoji.

OpenReader is free of charge under the MIT License, with no SLA, guaranteed response time or promise of indefinite maintenance.
