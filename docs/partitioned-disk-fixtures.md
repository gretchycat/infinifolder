# Complete disk image fixtures

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Status reviewed 2026-10-24. See [current confirmations and remaining work](project-status.md); dated build/dump statements below describe their original increment.

Added 2026-10-23. The user confirms the delivered full-disk functionality working on the phone.

Fixtures live in `filesystem-test-images/disks/`:

| Image | Partition table and filesystems |
| --- | --- |
| `mbr-windows.img` | MBR: FAT16 and populated NTFS |
| `gpt-mixed.img` | GPT: Linux ext4, populated BSD UFS2 and NTFS |
| `apm-apple.img` | Apple Partition Map: populated HFS+ and HFSX |
| Each `.img.gz` counterpart | Same complete disk through compressed-image staging |

These are data fixtures, not bootable operating-system installations. Source filesystem images are copied without mutation; no OS mount is used. Partitions are aligned to 1 MiB. GPT includes a protective MBR, primary/backup headers, partition entry arrays and their CRCs. APM includes a driver descriptor and partition-map entry. FAT/NTFS boot metadata is retained from the original standalone volumes; these fixtures do not establish firmware boot behavior.

`disks/expected-layout.json` records partition starts, sizes, types, labels and source payload hashes. Existing disks are kept on regeneration; edit copies rather than canonical fixtures when changing contents.

Generate from the repository root:

```sh
python3 filesystem-test-images/generate-partitioned.py
```

Four automated integration tests in `PartitionedImagesTest` passed: MBR, mixed GPT, APM and gzip-wrapped MBR. They check partition-level directories, extract known README bytes through fresh handles after listing handles close, and compare source-image hashes before/after. Log: `build/partitioned-images-tests.log` (4 tests, 0 failures/errors).

```sh
gradle :storage-engine:testDebugUnitTest --tests com.infinifolder.archive.PartitionedImagesTest
```

Remaining work: extended MBR/EBR chains, single-partition disks, damaged/truncated/overlapping/out-of-range tables, invalid GPT CRCs and backup-header recovery, unsupported partitions shown as raw slices, nested virtual-disk wrappers and controlled writes restricted to the selected writable FAT partition. The current passing tests establish browsing/extraction of the supplied valid layouts; they do not establish full partition-table validation or safe writes for every malformed layout.
