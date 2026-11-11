# Recorded storage failure investigation

> Repository layout updated 2026-11-11. Old host/module paths, build commands and
> test totals below record their original revision. For current owners and commands,
> use the [Android build guide](android-builds.md), [architecture](architecture.md)
> and [executed separation audit](architecture-audit.md).

Completed 2026-11-02, following the library migration and icon repairs.
The owner authorized reproducing and investigating the historical failures.

## Results

The fresh initial run reproduced **353 cases, 30 failures, no skips**. After the
repairs below, the same storage suite reports **354 cases, all passing, no skips**.
All original case names remain; one interrupted-I/O regression case was added.
The immutable pre-migration snapshot `validation/infinistream-baseline.json` and
migration checkpoint case list were not changed to obtain this result.

| Suite | Initial failures | Cause and correction |
| --- | ---: | --- |
| FileOperationsTest | 7 | Test backend lacked destination stat/capability metadata; implement actual state-backed stat, metadata preservation/readback and collision checks. Original transfer/corruption assertions now execute. |
| RecoveryTransferTest | 15 | Same missing stat contract blocked all 15; state-backed directory/file metadata exposes the actual recovery paths. Two then revealed the cancellation exception mismatch below. |
| DiscImageReaderTest | 1 | Synthetic LVM PV put metadata in the data-area list and used an invalid metadata-header signature/origin/size. Correct lists, terminators, signature, bounds and sector units; retain real standalone and MBR-nested enumeration and content reads. |
| ArchiveStorageTest | 1 | An empty stream plus 20 GiB metadata asserted obsolete early network-size rejection. Exercise real stream-only staging to its 512 MiB scratch limit, checking bounded reads, stream closure, informative error and partial-file removal. Native positional access remains independent of that staging limit. |
| TorrentTransferLifecycleTest | 1 | Resumable stop action is now labelled Pause; retain the resumability-versus-status-word checks. |
| TorrentVerificationCheckpointTest | 5 | Expectations predated pre-write flushes and the checksummed write journal. Assert actual durable event order and trust only untouched verified pieces; distinguish rejected-before-submission writes from uncertain acknowledgements. Inject the flush error after pre-write publication to test the intended post-write failure. |

## Production correction

Store's public `FileOperations.batchTransfer` historically reports cancellation as
`InterruptedException`. Shared stream copying reports `InterruptedIOException`.
After the fixtures reached the transfer body, cancellation during data/report
writing cleaned up correctly but leaked the stream exception through the public
batch API. The boundary now translates that exception only when the operation's
cancellation flag or thread interruption is set, retaining its cause and the
thread's interrupt state. Existing destination/report cleanup runs first.
Unrelated interrupted I/O, including socket timeouts, remains an I/O failure.

The recovery tests assert that cancelled copies remove partial data/report files
and retain the original. The additional regression injects interrupted provider
I/O without cancellation and verifies failure, cause retention, cleanup and
unchanged source contents. No production preflight, verification or recovery
safety checks were relaxed.

Torrent tests retain coverage for corrupted/truncated sidecars, failed hashes,
failed flushes, clean-state invalidation and lost acknowledgements. A write journal
can preserve verified pieces outside a pending write; it cannot certify that
write's affected pieces. The historical test names remain for baseline comparison.

## Commands and evidence

```sh
gradle :storage-engine:testDebugUnitTest --offline --console=plain --max-workers=2
gradle :storage-engine:testDebugUnitTest --tests 'com.infinifolder.ops.FileOperationsTest' --tests 'com.infinifolder.ops.RecoveryTransferTest' --offline --console=plain --max-workers=2
gradle :storage-engine:testDebugUnitTest --tests 'com.infinifolder.torrent.TorrentVerificationCheckpointTest' --tests 'com.infinifolder.torrent.TorrentTransferLifecycleTest' --offline --console=plain --max-workers=2
gradle :app:assembleDebug --offline --console=plain
python3 scripts/verify-infinistream.py
```

- Initial complete storage run: expected failure, 353 cases / 30 failures.
- Focused transfer run after backend corrections: two cancellation failures exposed.
- Focused transfer/disc run after cancellation/LVM repair: passed.
- Focused torrent run: passed.
- Final complete storage run: **BUILD SUCCESSFUL**, 354 cases / zero failures,
  errors or skips (58 seconds).
- Persistent-pin app build: **BUILD SUCCESSFUL** (20 seconds).
- Full application/library gate: **BUILD SUCCESSFUL**, **942 cases / all passing,
  zero errors or skips**; regression comparison reports **30 resolved, zero
  regressions, zero missing baseline cases** (final rerun: 27 seconds).

The first full gate built and passed all 941 reported cases, but correctly rejected
one missing historical icon-test name, removed during an earlier cover-precedence
repair. Restore that named case for the still-required unusable-cover-to-dot-icon
fallback in both enumeration orders, retaining the existing valid-cover-wins test.
The final gate includes both cases; neither the comparator nor baseline was relaxed.

Logs and original/final XML report snapshots are in
`build/storage-failure-investigation/`: `storage-before.log`, `before-xml/`,
`before-failures.json`, `transfers-after-contract.log`,
`transfers-and-disc-after.log`, `torrents-after.log`, `storage-after.log`,
`after-xml/`, `after-summary.json`, `persistent-pins-final-build.log` and
`full-gate.log` and `full-gate-summary.json`. The gate's underlying Gradle log is
`build/infinistream-migration/verification-gate.log`.

## Scope and remaining acceptance

These are JVM tests through real transfer, checkpoint codec and container reader
implementations with controlled storage backends and synthetic bytes. They do not
establish live SFTP/network behavior, every LVM layout or on-device UI acceptance.
ADB has no connected device; pin restoration after process restart and the pending
icon/long-press checks remain unverified on a handset. No new device result is
claimed. Existing unrelated workspace edits were preserved. The gate ran against this
working tree, including those pre-existing edits, rather than an isolated clean
checkout of the new commits.

ANSI music is user-confirmed in the CP437 viewer. A softer waveform remains on
the to-do list; synthesis was not changed during this investigation.
