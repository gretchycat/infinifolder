# Container integrity and publication validation

Updated 2026-10-27. This is a headless validation increment, not a complete
filesystem validator or a claim that every provider publishes atomically.

## Automated checks

`ContainerPublicationIntegrityTest` makes disposable ext4 image copies, creates a
directory and file through the shipped native writer and private batch device,
and checks the staged image with `e2fsck`. It then exercises the production
`ContentReplacement.replaceFromFile` streaming fallback using a controlled
provider:

- Successful publication is read back, compared byte-for-byte, reopened, and
  checked with `e2fsck`; the new file is read from the published image.
- A source revision change before publication prevents any write.
- Interrupted writes and silently corrupted output fail and restore the original;
  the retained recovery copy and restored image are checked.
- If restoration fails too, the original remains in a verified, discoverable
  recovery record marked interrupted.

All image fixtures remain untouched. The test exercises image mutation and the
replacement/recovery boundary, but uses a controlled in-memory provider and does
not exercise Android's picker, the full `ContainerWriteBatch` entry point, an
actual network server, or a process death during publication.

The existing native matrix additionally covers ext2/3/4 mutation, content and
metadata checks, I/O failures, rejection of corrupt inodes and unsupported
features, plus ext3/4 journal recovery after actual test-process termination.
FAT private batch, ext round-trip and storage recovery regression tests are also
run as part of the focused set.

New ext directories receive a targeted checksum check before the mutation can
succeed: a child lookup traverses the new directory's index and leaf. A separate
test compiles a deliberately defective writer with a stale directory buffer. The
unguarded version creates an image that `e2fsck` reports as corrupt; the guarded
version rejects the mutation. This only checks the newly created directory, not
all metadata or all blocks in the image. The damaged private snapshot is discarded.

## Remaining integrity work

- Implement a portable prepublication integrity check for supported image formats.
  The native writer validates metadata it touches, but it does not scan the entire
  filesystem. `e2fsck` in these Termux checks is not an Android runtime dependency.
- Exercise the complete Android container batch, compressed rebuild and publication
  path with cancellation and process termination at each durable state boundary.
- Test concurrent source changes around publication. Revision checks reduce risk,
  but a generic read-then-write or read-then-rename is not an atomic compare-and-swap
  against independent writers. Streaming rollback can also race outside writers.
- Add real SAF and network failure cases, including disconnect, full storage,
  failed close and recovery after app or device restart. Verify behavior separately
  for providers that have atomic replacement and providers that stream.
- Expand corruption coverage to directory index and leaf blocks, allocation
  bitmaps, journal and xattrs, and verify that a rejected or failed private batch
  cannot publish. Extend equivalent checks to FAT, ZIP, TAR and compressed images.

Run the focused tests:

```sh
gradle :storage-engine:testDebugUnitTest --tests 'com.infinifolder.archive.ContainerPublicationIntegrityTest' --tests 'com.infinifolder.archive.ExtImageMutationTest' --tests 'com.infinifolder.archive.PrivateFatBatchTest' --tests 'com.infinifolder.storage.RevisionAndRecoveryTest' --tests 'com.infinifolder.storage.StorageSafetyRegressionTest'
python3 storage-engine/src/test/native/run-ext-integration.py
python3 storage-engine/src/test/native/run-ext-directory-guard.py
```

Logs: `build/container-integrity-focused-tests.log` and
`build/container-native-integrity.log` and `build/ext-directory-guard-fault-injection.log`.
The focused set contains 30 tests. On Termux, the native library is explicitly a
Gradle test input so changes to it cannot leave JVM results marked up to date.
