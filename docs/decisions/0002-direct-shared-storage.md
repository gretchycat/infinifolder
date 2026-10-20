# Decision 0002: optional direct access to primary shared storage

Date: 2026-10-19

Android 11+ prevents ACTION_OPEN_DOCUMENT_TREE from granting the primary storage root or Downloads. A fabricated list of standard directory names is neither complete nor accurate. The requested conventional root browser therefore needs direct filesystem access where Android permits it.

Use an optional MANAGE_EXTERNAL_STORAGE grant on API 30+, with an explanation at the point the user selects Internal shared storage. Android settings owns the grant. API 26–29 uses runtime storage permissions. Declining access retains existing SAF locations and labels that view as incomplete. The local provider discovers the root through Android, lists real files/directories off the UI thread, and uses the same resource/operation interfaces as SAF. Granted SAF copies of primary folders are not added as extra directory nodes.

Use a non-exported AndroidX FileProvider for external opening with a temporary read grant to one chosen file. Its external-path mapping is limited to the primary shared-storage root; it never exposes private app storage. Validate local components and canonical containment, reject symbolic links, avoid overwrites, and verify local copies before deleting originals. SAF document IDs remain opaque. Transfers between SAF and local providers report unsupported until a provider-neutral transfer engine is implemented.

Other apps’ private data remains restricted by Android. The file-manager use case is eligible for All files access, but future Play distribution will require the corresponding permission review. This choice does not add launcher integration or network access.

References: [Android All files access](https://developer.android.com/training/data-storage/manage-all-files), [SAF directory restrictions](https://developer.android.com/training/data-storage/shared/documents-files).
