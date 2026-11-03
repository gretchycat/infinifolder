# Remote archive transfer caching

## Handset evidence — 2026-11-03, 02:52 UTC

The owner's slow-transfer dump is
`build/infinifolder-debug-20261103-025223-746.{log,json,png}` on a Pixel 8 Pro,
with the APK updated at 02:40:30 UTC. It records a move of `Ringtones` from an
SFTP-hosted `portrait.zip` to an ordinary SFTP folder.

| Retained successful file stages | Observed time |
| --- | --- |
| Opening 49 source entries | 381,284 ms total; 7,562 ms median; 19,873 ms maximum |
| Verifying 49 destination files | 7,213 ms total; 138 ms median |
| Completed file payload | 9,072,343 bytes |

Even the empty file spends 7,808 ms opening its source. Its range-reader trace
already reports 106 remote reads / 6,925,378 returned bytes before open completes.
Each later entry opens a different SFTP range reader; no
`image.read-session.open` or `image.read-session.reuse` event occurs in the dump.

The real path is `FileOperations.batchTransfer` → `ArchiveStorageProvider.openRead`
→ `ArchiveReader.openEntryStream` → Commons Compress `ZipFile` over the backend's
range-cached device. The [ZIP builder's documented default](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipFile.Builder.html)
loads the central directory and all local headers when opened. Reopening it for
each entry repeatedly fetches scattered headers when each device starts with an
empty cache. Format probing also reads the source prefix. This explains substantial
per-entry overhead independently of payload throughput or destination verification.

## Repair

Store already has `ReadDeviceScope`, used by ordinary copies. Its `!move` guard
disabled reuse for the entire move. Ordinary moves now reuse it during the copy
phase as well. The source provider still checks size and modification time on
each device lease; changed sources retire the old cache and issue the existing
warning. Sources without a usable revision are not retained. The scope holds at
most four read-only devices; SFTP's existing range cache holds at most 16 MiB per
device. Everything closes at transfer completion or failure. This introduces no
persistent full-archive download or global cache.

Before checking source contents/attributes and deleting originals, the move now
explicitly disables `ReadDeviceScope` around that entire loop. Cancellation undo
already disables it. Recovery, link creation and transfers into archive
destinations keep their existing exclusions. Native mutation, verification,
permission handling and cancellation policies are unchanged. ZIP local-header
and Unicode-extra-field interpretation remain enabled.

Repeated opens can therefore reuse archive header/prefix blocks while they remain
resident. The first scan still needs remote reads; cache eviction, unavailable
revisions, network delays and final source validation can still be expensive.
No handset speed improvement is claimed yet. A retry should show
`image.read-session.open` followed by `image.read-session.reuse`, and reduced
`Opening source` times after the initial access.

## Other findings and verification limits

The permission-mode repair is exercised: the dump contains no `Failed requirement`
or `TransferMetadata.kt` exception frame. Remaining warnings are different:
63 source ACL/extended-attribute export warnings and 62 unsupported destination
attribute warnings. Existing safety policy keeps affected originals.

The move is **FAILED**, not completed or cancelled: an SFTP destination listing
fails while preparing `Trill.m4r`, after 9,072,343 payload bytes. The server exception
has an empty message, so its cause is not established by this dump. No original
removal or cancellation undo appears in the retained log. This run does not accept
warning-free move completion or restoration after deletion. The screenshot shows
two MMF icons in the browser; it does not verify transfer destinations or hashes.

`gradle :app:assembleDebug --offline --console=plain` passes in 19 seconds
(410 tasks: 6 executed, 404 up-to-date). No new tests were added or run for this
increment. Build output and an aggregate log summary are under
`build/remote-archive-transfer-cache/`. The earlier 958-case gate remains historical
evidence. Live speed, cache invalidation and final-check/undo acceptance on this
revision remain unverified.

## Handset cache evidence — 03:26 / 03:30 UTC

`build/infinifolder-debug-20261103-032600-324` reports APK update time
03:00:44 UTC and a move of `icon` from the same remote `portrait.zip`. The
03:25:59 capture overlaps this run; it is not a second transfer. The 03:30:02
capture retains its final stages and failure.

- The first source open takes 8,215 ms. Across 67 completed source opens, the
  total is 62,001 ms and median is **759 ms**, versus 7,562 ms in the earlier
  Ringtones run. These are different file sets, not a controlled throughput test.
- The scope opens the 62,580,459-byte source, reuses it 67 times, and closes with
  zero outstanding leases. This establishes actual cache reuse on the handset.
- The move writes/verifies 67,468 payload bytes before another SFTP destination
  listing failure, while creating `file-bin-zst.svg`. It is FAILED; no original
  removal is recorded. Unsupported attribute warnings remain.
- The earlier 03:06:32 dump records two Ringtones copies reaching COMPLETE,
  each at 32,400,767 bytes with an attribute warning. Those are separate copy
  workflows, not clean move completion or proof of cache use during moves.

The recurring SFTP read failure is addressed separately in the
[metadata-channel follow-up](sftp-channel-recovery.md#metadata-channel-follow-up--2026-11-03-0330-utc).
Live cache use and reduced observed entry-open times are now supported; final
move validation/deletion, cancellation undo on this revision and recovery from
the listing failure still need acceptance.
