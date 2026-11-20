# Repository architecture

## Application layers

The app is a single Android application module under app/. The package namespace and application ID are org.sam.openreader. MainActivity.kt hosts the Compose application and routes between library, import, reader, settings, and book-action panels. Library.kt owns Room metadata, file staging, local book directories, prepared documents, and transactional book operations. DataStore preferences hold app-wide settings such as speech configuration; per-book appearance and reading state are stored with the book record or its locators.

The reading layer uses Readium's EPUB streamer and navigator for EPUB and generated TXT publications. DocumentView.kt adapts the EPUB navigator and Android PdfRenderer, including location and interaction callbacks. PageTurn.kt coordinates visual page transitions. ReaderPanels.kt contains reader chrome and navigation/editor panels. Appearance.kt defines reader typography, palettes, style data, and the appearance panel.

TXT processing is split by responsibility: TextFiles.kt decodes canonical text, detects headings, creates derived EPUB resources and navigation, and builds the text index. HeadingBlocks.kt implements editable ordered matching blocks and number validation. TextEdits.kt streams replacements. Revisions.kt maps offsets, creates staged revision drafts, and remaps locators and manual decisions. SpeechSource.kt reads passages from each supported format; SpeechService.kt owns the foreground playback lifecycle; ListenPanel.kt exposes controls.

Backup.kt writes and restores library packages. app/src/test contains JVM tests for text and edit logic. app/src/androidTest contains the device fixture provider and ReadingJourneyTest. docs/SDD.md specifies required behavior; docs/VERIFICATION.md records evidence and acceptance gaps.

## Data and document flow

An external URI is copied into app-private staging before validation. Once accepted, the book has a private original and a normalized book directory. A TXT book also has revision-scoped text, profile, indexes, and derived reading output. A Room record points at the active revision and the current reading/speech positions. EPUB/PDF are opened from the private copy when kept in the library; Open once uses temporary ownership and cleanup policy.

Reader controls call the same location-bearing operations used by bookmarks, speech, and resume. TXT locators map back to canonical offsets through disk indexes rather than requiring the entire book in memory. The SDD's desired sliding window around the current chapter is not fully implemented or measured; do not equate indexed chapter resources with a proven bounded five-chapter cache.

## Dependencies and boundaries

The key external libraries are Readium Kotlin Toolkit, RE2/J, AndroidX Compose/Room/DataStore, and Android platform services. Exact versions are pinned in app/build.gradle. Readium handles EPUB structure/navigation; RE2/J bounds the advanced heading-pattern feature to a safer regular-expression engine; Android supplies document-picker, PDF, TTS, media, notification, locale, and storage APIs.

The app is intentionally local-only. It has no backend layer. Font assets are downloaded during build according to a checked-in manifest and packaged with their notices. More implementation detail and authoritative acceptance requirements are in the [SDD architecture section](../SDD.md#10-architecture-and-implementation-choices).
