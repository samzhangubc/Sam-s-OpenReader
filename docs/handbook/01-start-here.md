# OpenReader handbook

This handbook explains the current Android repository, how its reader workflows are intended to work, and what evidence exists for each feature. It is a companion to the [Software Design Document](../SDD.md), which remains the normative source for requirements and acceptance criteria. The [verification report](../VERIFICATION.md) is the source of current test and device evidence.

## What OpenReader is

OpenReader is an offline-first Android application for DRM-free EPUB, TXT, and PDF books. It is designed for Samsung Galaxy phones, with the North American Galaxy A56 as the primary handset. The application has no account, server, advertising, or synchronization dependency. The codebase is Kotlin, Jetpack Compose, Android platform services, Room, DataStore, and Readium.

The app supports three main workflows: import a book and choose whether to keep an app-owned library copy; read with saved positions and configurable appearance; and optionally listen through Samsung's installed text-to-speech service. TXT books additionally have decoding, chapter detection, and whole-book cleanup tools.

## How to read this handbook

The pages are designed to be published independently. Start with [build and compatibility](02-build-install-and-compatibility.md), then use the workflow guides for [import](04-import-and-open-once.md), [the library](06-library-lifecycle-and-export.md), [TXT processing](07-txt-decoding-and-indexing.md), [reading](12-reader-gestures-and-page-turns.md), or [speech](14-samsung-tts-user-guide.md). The full [handbook index](21-known-limits-and-index.md) also provides a topic map and glossary.

| Topic | Handbook pages |
| --- | --- |
| Setup and internals | [Build](02-build-install-and-compatibility.md), [architecture](03-repository-architecture.md) |
| Files and books | [import](04-import-and-open-once.md), [formats](05-format-rendering.md), [library](06-library-lifecycle-and-export.md) |
| TXT | [decode/index](07-txt-decoding-and-indexing.md), [bulk edits](08-bulk-editing-and-recovery.md), [chapter rules](09-generic-chapter-detection.md), [front matter](10-contents-and-front-matter.md) |
| Reader | [locations](11-locations-bookmarks-and-progress.md), [gestures](12-reader-gestures-and-page-turns.md), [appearance](13-reader-appearance-controls.md), [fonts](16-fonts-and-rendering.md), [color styles](17-color-palettes-and-custom-styles.md) |
| Speech and language | [Samsung TTS](14-samsung-tts-user-guide.md), [service](15-speech-service-and-resource-use.md), [language/Unicode/accessibility](18-language-unicode-and-accessibility.md) |
| Reliability | [persistence/backups](19-persistence-migrations-and-backups.md), [privacy/licensing](20-privacy-security-and-licenses.md), [verification](21-known-limits-and-index.md) |

## Evidence labels

“Source-reviewed” means the implementation path was inspected; it does not mean every possible input or device state has passed a test. “Automated test” names a test run recorded in the verification report. “A56-tested” refers to the exact phone/build stated there. “SDD requirement” describes intended behavior where implementation or acceptance is incomplete. These distinctions matter: several ambitious requirements, particularly complete Unicode glyph coverage, chapter-map editing, large-book prefetch, migration/restore safety, and long-duration background speech, need additional acceptance evidence.

For support boundaries and how issues should be reported, see the final [known-limits page](21-known-limits-and-index.md). Project source uses the [MIT License](../../LICENSE); third-party assets retain their own notices.
