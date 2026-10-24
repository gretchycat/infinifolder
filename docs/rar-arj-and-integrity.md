# RAR, ARJ, integrity and unreadable-source handling

Implemented 2026-10-23. The user confirms the delivered archive, copying, Zstandard/CPIO and full-disk functionality working on the phone. This is general acceptance; exhaustive corruption, format-version, provider and metadata coverage is not implied.

## Shared archive adapter

`NativeArchiveReader` uses the Android 7-Zip-JBinding library for read-only RAR/RAR5/CBR and ARJ browsing and extraction. Local files, SAF, mounted containers and network sources supply sibling volume handles through `ArchiveVolumeSet`; the decoder never constructs provider-specific paths. Stream-only volumes are staged with space checks and removed when closed. Observed SAF parent identities are remembered because some document providers do not implement parent-path queries.

- Recognizes RAR `partN.rar`, `.000.rar` numbered names, legacy `.rar`/`.r00` names, and ARJ `.arj`/`.a01` names. Opening an available later member selects the earliest available matching member in that directory.
- RAR uses native volume callbacks. ARJ volumes are decoded separately, then joined by path, continuation flags and unpacked offsets. Missing, duplicate or discontinuous segments make that member unreadable.
- Cache revisions include sibling names, sizes and modification times. If sibling times are unavailable, the listing is not reused under a stable cache key.
- Initial catalog loading checks each identifiable file by decoding without saving its contents. CRC/data failures, unsupported methods, missing segments, encryption without a supplied password, and unsupported hardlinks receive a `readError`. Directory listings can therefore retain readable members alongside damaged ones.
- `readError` reaches provider metadata as `canRead=false`; the existing permission badge now uses an X. The detail view and listing notices distinguish archive read failures from ordinary permission denial. Unsafe/unrepresentable names become unreadable placeholders rather than preventing access to unrelated entries.
- Extraction checks the requested member into a private temporary file before exposing it to a viewer or copy operation. Failed or incomplete output is removed. Solid archives may require replaying earlier members, and damage to shared compressed state can affect later files.
- Missing volumes and unreadable members are logged as `archive.volume.missing`, `archive.entry.unreadable` and `archive.integrity`.

Limits: headers that cannot be parsed cannot identify every missing file; no invented directory contents are presented. Checksums can only establish what the format actually records, and archives without content checksums provide weaker integrity evidence. No password-entry UI, repair/recovery-record processing, RAR/ARJ writing or cross-directory volume search is implemented. Volumes must be available as siblings within one provider. Split ZIP/7z sets are a separate remaining integration task. Initial integrity scanning adds work before the first listing.

Dependency: `com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03`, from its [upstream project](https://github.com/omicronapps/7-Zip-JBinding-4Android). It includes four Android ABIs and 16 KiB ELF alignment. The engine is based on 7-Zip 16.02; methods introduced by later RAR versions can remain unsupported. Upgrading the native engine is a follow-up maintenance task. LGPL/unRAR notices and source references are packaged in assets; JNI names are kept for release shrinking. CAB has since been enabled through this adapter; see [CAB scope and checks](cab-support.md). Other engine formats are not enabled merely because the dependency can decode them.

## Copy and move behavior

`FileOperations.batchTransfer` skips known unreadable sources, source directories whose contents cannot be listed, unresolved copy-link targets, and source-open/read/close failures when partial destination cleanup succeeds. It continues with other readable files. Destination failures, cancellation, failed cleanup, failed copy verification and unsafe dependency/cycle conditions remain failures.

Every skip is logged as `transfer.source-skipped`. The UI displays a warning summary after the transfer, with up to 20 details and remaining details in the dump log. These are partial copies, not silent complete successes.

For moves, any top-level source folder with skipped contents is retained in full. Independently completed top-level entries can still move. The app-category move/uninstall path does not remove originals after a transfer that reported warnings. This favors retaining duplicate readable source files over deleting unreadable contents.

## Zstandard and CPIO

Zstandard decoding now includes the Android AAR `com.github.luben:zstd-jni:1.5.7-11`. Version 1.5.7-24 required compile SDK 37; this project uses SDK 36, so the compatible version is pinned. See [upstream Android dependency instructions](https://github.com/luben/zstd-jni#android-support). Licenses and source references are packaged in assets.

- Reads `.tar.zst`/`.tzst`, Zstandard-wrapped filesystem images through the shared image decoder, and standalone `.zst` payloads as a single decompressed entry.
- Zstandard writing/recompression remains unavailable.
- CPIO now explicitly decodes filenames as UTF-8, fixing `Input length = 1` on the fixture's accented filename. CPIO symbolic-link targets, modes and numeric UID/GID are exposed through the shared metadata model. Legacy filename encodings and full CPIO hardlink reconstruction remain follow-up work.

## Fixtures and acceptance

Fixtures are organized under `filesystem-test-images/archives/legacy/`, with separate intact, multipart, missing-volume and damaged-volume directories. ARJ fixtures are generated locally with the open-source `arj` tool. RAR fixtures come from [ssokolow/rar-test-files](https://github.com/ssokolow/rar-test-files) (CC0) and [junrar](https://github.com/junrar/junrar) (license included); their original volume names are preserved. The damaged ARJ fixture changes `bad.bin` payload bytes; the damaged RAR5 set changes a payload byte in part 2. The missing-volume fixtures omit a continuation member.

General phone acceptance is confirmed. Remaining specific coverage:

- [ ] Exercise all supported compression methods, solid dependency failures, legacy names and encrypted headers/password behavior.
- [ ] Check every provider route, missing first/last volumes, case-ambiguous sibling names and parts added/replaced during copying.
- [ ] Confirm destination failures and failed cleanup cannot turn into source-skip warnings or source deletion.
- [ ] Extend multipart integration to ZIP/7z and add detailed metadata-preservation coverage.
- [x] Enable CAB through the same adapter, with linked-volume selection and fixtures. Handset acceptance remains pending.
- [ ] Upgrade the native engine.

No automated native archive implementation tests were run in this batch. Full-disk integration checks are documented separately in [partitioned disk fixtures](partitioned-disk-fixtures.md).
