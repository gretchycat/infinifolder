# Decision 0003: Application public represents app-exported SAF sources

Historical architectural decision. See [current architecture](../architecture.md) and [project status](../project-status.md) for subsequent implementation and limitations.

Date: 2026-10-19

The Termux Monet test case clarified that Application public should expose the storage each app makes available through Android's document picker. Mapping app names to Android/media directories was an incorrect interpretation of this requirement. Termux's document provider exposes its home directory under its own authority and opaque IDs.

Discover DocumentsProvider components through their intent metadata, and query their actual roots when permitted. If a provider protects root metadata, still list the app and let Android's picker return the user-selected root. Validate the provider authority and any known document target, persist the returned grant, and browse that URI without path conversion. Group existing app-provider grants under Application public and avoid duplicate root entries. Old media grants remain retained, and media folders remain available through Internal shared storage.

The Android picker may require the user to choose the requested app from its sidebar when no initial document URI is available. InfiniFolder does not fabricate a root ID, request a launcher-app inventory, or require MANAGE_DOCUMENTS. Listing failures and pending provider results must not be rendered as confirmed empty directories.

Reference: [Termux document-provider implementation](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java).
