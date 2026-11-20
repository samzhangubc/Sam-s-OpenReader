# Library lifecycle and export

## Library identity and progress

Every library item has an app-generated ID and private book directory. The title shown on the shelf can be renamed without renaming or deleting the external original. Search and sort are library operations. Beneath each title, the library displays the saved whole-book reading position. The reader footer is reserved for the current chapter and page. Reading progress is stored separately from the current visual page so it can be reopened independently of the activity lifecycle.

## Add, remove, and recover

Adding a book makes an app-owned copy. Removing the library item removes only that local copy and its associated app-managed state after confirmation; it never deletes a source document outside app storage. Removal and failed import paths need recoverable handling so metadata is not left pointing at a missing file. Temporary Open once items follow a separate cleanup policy and should not be mistaken for durable library entries.

## Export choices

The app offers export of the untouched original, edited TXT, individual book packages, and a library backup. Exports go through a user-selected destination. Original export is byte-preserving. Edited TXT export contains the current canonical text, not only an edit recipe. A package/backup can include supported metadata, styles, reading positions, TXT revision/profile content, and files with integrity hashes. Restore should validate a complete archive before activating changes and should handle duplicates/collisions without damaging existing books.

## Progress and revisions

TXT edits can change the source offsets and chapter map. Revisions.kt maps old locations into the new text and carries forward bookmarks, reading progress, manual chapter decisions, and speech position where context allows. If a stable mapping is impossible, the UI must surface an unresolved mapping rather than jump silently to unrelated prose. A chapter-only correction should not rewrite text.

## Current evidence

Source review found library search/sort/rename/remove/export, backup preview/restore paths, and progress persistence. Verification reports A56 coverage for local-library resume and chapter navigation. It does not claim storage-failure recovery, removal undo, restore collisions, corrupt multi-book archive atomicity, or populated-database upgrade acceptance. Review [persistence and backups](19-persistence-migrations-and-backups.md) before relying on a backup as the only copy of a book.
