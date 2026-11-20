# Format support and rendering

## EPUB

DRM-free EPUB is parsed with Readium Kotlin Toolkit and rendered through its EPUB navigator. Reflowable publications respond to reader fonts, size, spacing, margins, and colors according to publisher-style settings. The EPUB table of contents is navigable as document locations. Publisher-defined styling can be honored or relaxed where the reader preference allows. Fixed-layout EPUB is treated as fixed content: app typography controls cannot reflow its pages.

## TXT

TXT bytes are retained as the original. The selected decoder generates canonical Unicode text with normalized line endings and disk indexes. Chapter boundaries are generated from a saved detection profile and a reviewable heading map; the text is then presented to the EPUB renderer as derived resources. A text book may have an original revision and later edited revisions. See [decoding/indexing](07-txt-decoding-and-indexing.md), [bulk editing](08-bulk-editing-and-recovery.md), and [chapter parsing](09-generic-chapter-detection.md).

## PDF

PDF pages are rendered using Android PdfRenderer. Page layout is fixed; zoom is supported, but text font/color controls cannot rewrite the source page. Speech can use extracted text only when the platform provides extraction and the file has a usable text layer. Image-only scans do not magically become searchable or speakable; OCR is outside the current scope. Password-protected PDFs are unsupported in version 1.

## Navigation and positions

EPUB locations are Readium locators. TXT locations also preserve canonical text offsets to support durable resume through long paragraphs and revision mapping. PDF locations identify a page and, when extractable, a reading passage. The reader can expose format-specific contents or page navigation, bookmarks, search where implemented, and saved reading position.

Exact page counts can depend on viewport, font, font size, publisher styles, and renderer pagination. The reader footer's page number is the page within the current chapter/section; the shelf's progress is the whole-book saved position. These are deliberately different indicators. The current verification report says exact chapter-page totals and every renderer's interaction behavior need more measurement.

| Format | Main engine | Reflow | TTS source | Important caveat |
| --- | --- | --- | --- | --- |
| EPUB | Readium navigator | Usually | Structured publication text | DRM unsupported; fixed layout stays fixed |
| TXT | Indexed canonical text as derived EPUB | Yes | Indexed paragraphs | Chapter map quality depends on review/profile |
| PDF | Android PdfRenderer | No | Conditional text extraction | Scanned image pages need OCR not included here |

The [SDD format table](../SDD.md#format-support-boundaries) defines capability fallbacks.
