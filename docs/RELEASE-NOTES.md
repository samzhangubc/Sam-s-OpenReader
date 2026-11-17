# OpenReader 0.2.0-preview.1

This preview is an early SDD-based implementation for the Samsung Galaxy A56. It installs on Android 10 (API 29) and later. The primary design target is Android 16 / One UI 8.

The signed APK was installed and tested on the North American SM-A566W running Android 16. All three Android instrumentation tests passed. The verified checks include the app's library reader entry point, chapter navigation for a mixed English and Chinese TXT, and private open-once imports of TXT and PDF. The phone's current system TTS is Samsung Text-to-Speech, package com.samsung.SMT, version 3.5.02.16.

This build includes SAF import and export, app-owned book copies, EPUB rendering through Readium, PDF rendering, a streaming TXT reader, customizable heading rules, ordered whole-book replacement, four page-turn modes, bookmarks, search, the floating reader menu, eight bundled fonts per script, 36 palette variants, personal styles and a Samsung TTS playback service.

PDF text extraction for speech is available on Android 15 and later when the PDF contains extractable text. Scanned pages need OCR, which this build does not provide.

The app has not completed the full SDD release acceptance plan. Two-hour background playback, measured CPU/memory/battery budgets, the full three-language UI audit, rare Unicode/emoji coverage, voice locale inventory, exact chapter page totals, recovery under storage exhaustion, and the full hierarchy/multi-line parser fixtures remain unverified. The Samsung TTS service was observed as the system default. Screen-off timing and actual synthesis still need a physical-device run.

The APK is self-signed for direct installation. Keep the matching signing key and do not uninstall this preview if installing later builds signed with that key. This is a preview build, not a declaration that every SDD requirement is complete.
