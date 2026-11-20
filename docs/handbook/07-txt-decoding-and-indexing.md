# TXT decoding and indexing

## Preserve the source

OpenReader keeps the imported original bytes unchanged. Decoding creates a working representation; it does not overwrite the original. The intended selection order is: inspect a byte-order mark, try strict UTF-8, then show an encoding suggestion and preview if strict decoding fails. The initial manual choices are UTF-8, UTF-16 little-endian, UTF-16 big-endian, GB18030, Big5, and Windows-1252, subject to runtime decoder availability. A failed decode must not silently substitute replacement characters and report success.

Canonical text is Unicode with LF line endings. Paragraph boundaries and punctuation are retained by default. Joining lines, trimming whitespace, and removing blank lines are explicit transformations that should be previewed. Changing the decoder after an edit should branch from the original and preview the effect; it must not destroy the active edited revision.

## Index design

TextFiles.kt processes text in bounded pieces and writes disk-backed indexes. UTF-16 offsets are used for integration with Android and Readium, while user-facing edit boundaries and chunks should respect extended grapheme clusters. Chapter resources are generated separately so a saved position can be resolved near the target passage without scanning every preceding chapter into memory. Stable text anchors are capped at 256 UTF-16 units and stop at grapheme boundaries, according to the current source review.

The first page for a saved location should be drawn before page totals or nearby chapters are counted/preloaded. The SDD requires a sliding target chapter plus two chapters on each side and a decoded-text memory budget. The current verification report explicitly says that an app-managed five-chapter window and the 4 MiB decoded-text limit are not yet implemented/measured. Indexed resources are a useful foundation, not proof of this performance acceptance.

## Import review checklist

- Confirm that Chinese encodings display punctuation and supplementary characters correctly.
- Inspect a preview around ambiguous or malformed byte sequences.
- Compare text from BOM-marked, UTF-8, GB18030, Big5, and UTF-16 fixtures.
- Verify emoji sequences, variation selectors, combining marks, and line endings survive decode and export.
- If decoding is wrong, switch the decoder from the preserved original before doing cleanup edits.

## Troubleshooting

If CJK appears as garbled text, reopen TXT decoding and choose the correct encoding rather than changing fonts; if boxes appear, decoding may be fine but glyph coverage is missing. A missing glyph placeholder must retain its original scalar values. See [fonts and rendering](16-fonts-and-rendering.md) and [Unicode coverage](18-language-unicode-and-accessibility.md). The full decoder contract appears in [SDD 5.1](../SDD.md#51-decoding-and-canonical-text).
