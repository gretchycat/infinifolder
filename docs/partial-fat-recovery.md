# Partial FAT recovery and byte-range reports

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Implemented after the original safety-fixture handset acceptance. This addition has automated coverage. The 02:40 UTC handset dump confirms a successful drag-initiated FAT16 partial export; direct inspection verifies the saved NUL/surviving ranges, report digest and unchanged source hash. Other new UI and fixture cases remain below.

## Use

Long press a deleted file and choose **Recover partial copy…**. Choose a destination outside the source image and confirm the partial recovery warning.

Dragging, pasting or copying a directly selected X-marked candidate now also offers **Recover partial copies** when that candidate is eligible. Choose **Skip unreadable files** to retain ordinary skip behavior. This follow-up addresses the captured drag attempt that used ordinary copying and skipped the candidate.

The output consists of:

- A `.partial` copy, with the original extension retained, such as `name.partial.bin`. Offset/fingerprint prefixes from the recovery view remain in the name.
- A companion `name.partial.bin.recovery.json` file describing every byte range, its confidence, whether it was copied or filled, and the reason.

Readable uncertain bytes are retained, as explicitly selected by the user. Unavailable and allocated/reused ranges become `0x00` bytes (NUL). File length and offsets are preserved. Ordinary **Undelete…**, preview and copy actions retain their existing behavior; they never silently fill holes. Invalid records with no defensible starting address still cannot be partially exported.

The report is indented JSON that can be opened as text. Offsets are bytes, not character positions; text encodings can use multiple bytes per character. Ranges use an inclusive `offset` and exclusive `end_exclusive` and cover the output exactly once.

## Confidence and evidence

| Grade | Meaning in this implementation |
| --- | --- |
| `good` | Reserved for independent original-content evidence; FAT metadata and magic do not establish this, so it is not assigned |
| `possibly_good` | Readable, stable bytes in the first cluster recorded by the deleted entry; original contents remain unverified |
| `possibly_bad` | Readable later bytes rely on assumed contiguous allocation, or allocation/type evidence casts doubt on the original contents |
| `overwritten` | Reserved for independently proven replacement of original bytes; current allocation alone does not prove this, so it is not assigned |
| `unavailable` | Unreadable ranges, FAT bad-cluster markers or positions outside the validated volume |

`disposition` is separate from confidence. `copied_unverified` means bytes were read and kept; `zero_filled` means the program generated NUL bytes. A zero byte already present in readable source data is a copied byte, not a generated hole.

Currently allocated/reused clusters are never copied into the output. Their ranges have `possibly_bad` confidence and `zero_filled` disposition, with an allocation reason. This prevents copying another live file's contents into a recovered file. Allocation does not prove which original bytes were overwritten, so the report does not falsely mark those bytes as proven `overwritten`.

For example, the safety fixture's `FRAGLIVE.BIN` exports:

| File offsets | Disposition | Evidence |
| --- | --- | --- |
| 0–511 | Copied, unverified | First recorded cluster is free and readable |
| 512–1023 | NUL-filled | Next assumed cluster is currently allocated |

The old chain's actual second cluster is not inferred or attached to the file. Free-gap fragmentation and overwrite of clusters that are free again remain undetectable from this metadata. Their readable bytes are kept and labelled uncertain.

## Magic/type hints

The report uses the shared `FileMagicDetector` and `MimeRegistry`, inspecting up to 2,048 consecutive copied header bytes before the first generated hole. Generated zeros are not used as evidence for a signature. Detected MIME, description, inspected prefix length, filename MIME and packaging-container MIME are recorded.

A meaningful mismatch lowers first-cluster confidence to `possibly_bad` and records the heuristic reason. A match never upgrades bytes to `good` and never verifies the rest of the payload. Ambiguous extensions such as `.bin` do not establish a reliable expected type. The registry's packaging relationships are respected: a ZIP signature is compatible with an APK/JAR or other registered ZIP-based package, rather than automatically being called a mismatch.

This is header/type detection, not structural validation, CRC validation, carving, format repair or reconstruction of a fragmented file.

## Safety and failure behavior

- The existing geometry, live-allocation, active/mirrored FAT, stale-record and partition checks still run. Partial mode does not relax a corrupt volume's scan policy.
- Boot/FAT/live-directory snapshots are checked before and after extraction. Only copied ranges are reread for stability; allocated or unavailable ranges are not read for output verification.
- A failed cluster read falls back to filesystem-sector-sized retries, retaining any returned readable prefix and other readable sectors. Unreadable retry units become holes. Some untested bytes within a failed sector may also be filled; `io_retry_unit_bytes` and the range reason make that granularity explicit.
- Cancellation is checked during scanning, reads, retries, staging, copying and report verification. A cancellation is not converted into missing data.
- The report records the exported SHA-256, declared size, filesystem, source image name, partition offset, counts by grade, copied/filled counts, range reasons and type hints. The transfer checks that the report size/digest matches the copied payload, then verifies both destination files.
- If report creation, writing or verification fails, cleanup removes the newly created data and report files. Cleanup failures are reported and the source remains intact. Existing destination names are never overwritten.
- Generic providers cannot promise an atomic two-file publication or process-death rollback. The `.partial` name distinguishes the data artifact, and both outputs should be kept together. Container destinations use the existing isolated write batch.
- Reports are limited to 20,000 merged ranges. Source rereads, retries, fresh decompression and report storage incur additional I/O and space.

## Fixtures and checks

Existing `fat12/16/32-adversarial.img` fixtures exercise partial copying of allocated clusters and uncertainty around fragmentation/overwrite. Two new images in `filesystem-test-images/filesystems/windows/recovery/safety/` add:

- `fat16-magic-partial.img`: `MATCH.PNG` has a PNG header and `MISMATCH.PNG` has a PDF header. Both have an allocated second cluster. Partial outputs must keep the first cluster and zero the second. The former gets a matching hint, the latter a mismatch; neither gets verified-good bytes.
- `fat16-partial-tail.img`: `TAIL.BIN` has one valid cluster of `T` bytes and a second assumed cluster outside the volume. Partial output must contain 512 `T` bytes followed by 512 NUL bytes, with the tail reported unavailable.

Regenerate missing fixtures with `python3 filesystem-test-images/generate-fat-recovery-safety.py`; existing images are preserved.

Automated checks include FAT12/16/32 masks, sector faults and readable prefixes, allocated-data exclusion, empty/invalid records, outside-volume tails, compressed/partitioned exports, header matches/mismatches, APK/JAR packaging, source immutability, report coverage, cancellation, destination exceptions and report creation/write/verification failure cleanup.

Full storage-engine run: **260 tests passed**, including 18 partial-recovery tests and 31 shared recovery-transfer tests. APK build succeeded and `xdg-open app/build/outputs/apk/debug/app-debug.apk` completed. Combined log: `build/fat-partial-recovery-build.log`; fixture generation: `build/fat-partial-recovery-fixtures.log`.

### Handset acceptance

The user accepts partial recovery as working after inspection of the 02:40 UTC dump and saved outputs. The remaining checks below track additional coverage.

- [x] Drag-initiated FAT16 allocated-cluster recovery: 1,024-byte copy, 512 NUL bytes, 512 surviving bytes, matching report digest and unchanged source hash. See [device evidence](device-dump-follow-up.md#partial-recovery-confirmed-2026-10-24-0240-utc-dump).

- [ ] Confirm **Recover partial copy…** appears for eligible readable and X-marked deleted candidates.
- [ ] Export the two new fixtures and inspect the `.partial` copies and JSON reports together.
- [ ] Check null-fill counts, half-open byte offsets, original output length, MIME hints and the absence of unsupported `good`/`overwritten` claims.
- [ ] Check destination/provider failures and cancellation; confirm ordinary Undelete behavior and source hashes remain unchanged.

Future work includes optional independent reference hashes, format-aware structural/chunk/member validation, partial recovery for other filesystems, and a selected-set partial-recovery menu. Structural consistency must remain distinct from proof of original deleted contents.
