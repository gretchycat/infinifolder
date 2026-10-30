# FAT deleted-file recovery

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

General undelete confirmed working by the user. After the safety follow-up, the user checked all files in the `safety/` directory and reports that every case was handled gracefully. This records handset acceptance of the delivered safety fixtures; additional provider, gesture and explicit hash checks are listed below.

## What the feature does

Long press a FAT image → **Show deleted files**. Deleted candidates have a trash-can badge over their MIME icon. Long press a candidate → **Undelete…** and choose an output folder. Recovery saves a copy and leaves source directory markers, allocation and contents unchanged. It does not restore files in place.

Before transferring recovery data, a **Recover unverified data?** dialog explains its limitations. This also covers clipboard, selected-set and drag/drop transfers through the common transfer action, including containers opened inside recovered files. A completed copy still reports that original deleted contents are unverified. Destination hash verification proves that extracted and saved bytes match, not that those bytes match the original deleted file.

## Partial export with a byte-range report

An additional **Recover partial copy…** action now keeps readable uncertain bytes, fills unavailable/allocated ranges with NUL bytes, and saves a companion JSON range report. Shared magic/MIME detection provides header hints and package-aware mismatch checks without claiming original contents are verified. The earlier handset confirmation covers the original safety feature; the new partial-export UI and fixtures await handset confirmation. See [partial recovery behavior and checks](partial-fat-recovery.md).

## Recovery decisions

| Surviving evidence | Behavior |
| --- | --- |
| Empty file with no allocation | Export an empty file; name remains uncertain |
| Single free cluster | Allow an explicitly unverified copy |
| Multiple consecutive free clusters | Allow an explicitly unverified copy assuming contiguous allocation |
| Allocated/reused cluster, invalid address or reserved cluster bits | Show unreadable X and refuse ordinary extraction. Eligible records can use explicit partial export with zero-filled unavailable/allocated ranges |
| Dirty/error-marked volume, inconsistent live chains, cross-links, invalid geometry | Refuse the scan with a reason |
| Freed fragmented chain with free intervening clusters | Cannot determine original fragmentation; an assumed contiguous copy can contain wrong bytes |
| Partial/full overwrite while allocation stays free | Cannot identify the original contents from FAT metadata; output remains unverified |
| Deleted directory tree or overwritten directory record | Not reconstructed or carved |

The last two readable damage cases are deliberately included in the fixtures. Their expected exported hashes differ from the original hashes. They must never be described as verified restoration.

## Source protection

- FAT12/16/32 scanning validates boot geometry, signatures, allocation-table headers, bounds and live allocation. FAT32 selects the declared active FAT when mirroring is disabled. With mirroring enabled, live chain links must agree across copies and a candidate must be free in every copy. An explicitly inactive FAT is not treated as authoritative allocation.
- Candidate identities include a fingerprint of their surviving record, long-name fragments and boot sector. A changed record invalidates an old selection rather than silently returning a different candidate.
- Extraction scans again, checks boot/FAT/live-directory snapshots before and after reading, and rereads the payload to detect observed changes. Bytes are staged privately and the temporary output is removed on failure or cancellation before a read stream is exposed.
- Compressed recovery reads bypass the normal browsing cache. Equal file size and modification time cannot cause that cache to supply an old recovery snapshot.
- Recovery from MBR/GPT/APM partitions checks slice bounds and overlap. GPT recovery additionally requires valid, agreeing primary/backup headers and entry arrays, CRCs and usable-area bounds. Unsupported or damaged GPT layouts are refused without repair. Partition location participates in candidate identity and layout is rechecked around extraction. These stricter checks apply to recovery; the general partition reader has separate outstanding work.
- Moves and recovery destinations inside the source image or its enclosing containers are refused before creating output. The provider exposes the recovery origin so opening another container inside a deleted file cannot bypass this rule. Recovery views reject writes, creation and deletion.
- Cancellation reaches scanning, extraction and compressed staging between reads. Temporary space and destination space/size limits use the shared operation pipeline. Unreadable children are skipped with warnings; loading listings are not treated as complete.

External writers are not locked out. Keep the source unchanged while recovering. The app cannot establish whether separate provider identities share physical media; use a separate drive for valuable recovery. Source rereads and fresh decompression add I/O and temporary-space cost.

## Scope and limits

Results are flat within each FAT volume; partitioned images retain partition folders. Only deleted regular-file records (`0xE5`) in reachable live directories are scanned. A live `0x05` first-character escape is not a deleted record.

Deleted long-name records lose sequence information. Adjacent matching fragments remain tentative; malformed UTF-16, inconsistent checksums/type/padding fall back to a short name with `_` for the lost first character. Short-name OEM encoding is not inferred. Offset/fingerprint prefixes disambiguate candidates and remain in output names. Names are sanitized and bounded by UTF-8 byte length without splitting surrogate pairs. Original timestamps, permissions and directory trees are not reconstructed.

Limits include 16 million volume clusters, 64 MiB of FAT tables, 8 MiB per live directory, 4 million live-chain steps, 500,000 directory records, 50,000 candidates and 8 million candidate allocation checks. Exceeding a limit refuses the scan. GPT recovery currently supports 512-byte disk sectors, up to 128 entries and 128/256/512-byte entries. Other layouts, damaged-table fallback, fragmented-chain reconstruction, exFAT and raw physical-media access remain future work.

## Fixtures and automated checks

Base fixtures are in `filesystem-test-images/filesystems/windows/recovery/`; adversarial cases and partition/compression variants are in its `safety/` subdirectory. Their generators preserve existing files. Both `expected.json` files record source and payload hashes, allocation details or deliberately damaged bytes.

Coverage includes:

- FAT12/16/32 intact, empty, short/long-name and live-subdirectory candidates; extracted-byte hashes and whole-source immutability.
- Allocated/reused clusters, second-FAT conflicts, fragmented files with allocated or free gaps, partial/full overwrite, deleted directory exclusion, reused records and the live `0x05` escape.
- Dirty/malformed geometry, cycles, mirror disagreement, active/inactive FAT selection, reserved cluster bits, malformed long names, Unicode name length and truncated sources.
- MBR/GPT/APM volumes; raw and gzip/bzip2/xz images and partitioned disks; invalid partition bounds/overlap and GPT primary/array/backup checksums.
- Changed records, reallocation after listing, changes during extraction, same-size/same-mtime compressed replacement, payload read errors, cancellation and temporary-output cleanup.
- Shared transfer protection for source/enclosing/nested containers, multiple files, unreadable children, incomplete listings, low space, destination failures, verification failures and cleanup failures.

Tests: `FatRecoveryTest` and `RecoveryTransferTest`, plus the full storage-engine regression suite. Two older operation expectations were updated to the already implemented skip-with-warning behavior. The regression run also exposed a ZIP lookup inconsistency: seekable reads now share exact-first lookup with extraction and reject ambiguous case/duplicate matches.

Safety follow-up run before partial export: **232 tests, 0 failures, 0 errors** (42 scanner/image recovery tests and 21 transfer recovery tests). APK build succeeded; `xdg-open app/build/outputs/apk/debug/app-debug.apk` completed successfully for installation.

Commands and results are recorded in `build/fat-recovery-safety-tests.log` and `build/fat-recovery-hardening-build.log`. Fixture generation is recorded in `build/fat-recovery-safety-fixtures.log`.

Latest run with partial export: **260 storage-engine tests passed**; see [partial recovery](partial-fat-recovery.md) for its additional coverage.

## Handset acceptance and additional checks

- [x] Original undelete workflow confirmed by the user.
- [x] All delivered `safety/` fixtures checked on the handset; the user reports graceful handling throughout.
- [ ] Confirm the new warning dialog, completion warnings and trash/X badges, including selected sets, clipboard and drag/drop.
- [x] General handset acceptance of adversarial and malformed fixtures. Automated checks establish nine candidates per adversarial image: four blocked and five unverified readable candidates, with reused live records and deleted-directory children excluded.
- [ ] Recover from compressed images and partition folders; compare hashes against the fixture manifests.
- [ ] Attempt output into the source image/enclosing archive; check cancellation, low space and destination failures on actual providers. Confirm source hashes remain unchanged.

Format references: [Microsoft FAT specification](https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf), [UEFI GPT layout](https://uefi.org/specs/UEFI/2.10/05_GUID_Partition_Table_Format.html).
