# Import and Open once

## Entry points

The user can choose Import book from the library, choose OpenReader from Android's Open with flow, or share a supported document to the app. Android's Storage Access Framework picker is the primary file-browser route. The manifest registers content URI viewing and sharing for plain text, EPUB ZIP, and PDF MIME types. URI grants are treated as temporary access; the app copies bytes into private storage instead of depending on a provider URI forever.

## Ownership choice

When an external document is staged, the user chooses Open once or Add to library. Open once gives temporary access and is eligible for cleanup when the app no longer needs it. Add to library creates an app-owned snapshot. Changes to the source file afterward must not alter that library item. The library copy is the source for later reading, editing, export, and backup.

Duplicate detection uses a content hash, not a provider's display name alone. If an identical item already exists, the app can direct the user to the existing copy rather than create a confusing duplicate. Malformed or unsupported content should fail before a permanent shelf entry is committed. A cancelled picker or failed copy leaves the shelf unchanged.

## Normal flow

1. Open Import book or invoke Open with / Share from another app.
2. Select a local document in the Android system picker, or let the receiving intent hand the URI to OpenReader.
3. OpenReader stages and validates a private snapshot, then identifies the format and available metadata.
4. Choose temporary reading or Add to library.
5. For TXT, review the suggested encoding and chapter candidates before relying on the generated contents. See [TXT decoding](07-txt-decoding-and-indexing.md) and [chapter detection](09-generic-chapter-detection.md).
6. Enter the reader. A library book can later be reopened even if the original external path has moved.

## Failure and privacy behavior

Import can fail because of missing URI permission, provider I/O, malformed archive structure, unsupported DRM/encryption, insufficient storage, or ambiguous text decoding. Errors should be actionable and must not leave half-registered books. EPUB DRM and password-protected PDF are unsupported in the first release. The app requests no broad storage permission: the picker grants access to the selected URI.

The current source contains URI staging and validation; the A56 fixture covered TXT and PDF snapshot imports. Android Sharesheet dispatch, arbitrary provider quirks, storage exhaustion, cancellation races, and cleanup recovery still need broader testing. The current evidence is summarized in [verification status](../VERIFICATION.md).
