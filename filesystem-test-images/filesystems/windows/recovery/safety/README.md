# FAT recovery safety cases

These images intentionally contain damaged or misleading deleted-file records. Generate missing files with:

```sh
python3 filesystem-test-images/generate-fat-recovery-safety.py
```

Existing fixtures are preserved. `expected.json` records source hashes and each case's expected result. Original and assumed-contiguous payload hashes are separate fields.

## Adversarial FAT12/16/32 images

Each `fat*-adversarial.img` should show nine candidates:

| Original name (first character may be lost) | Expected result |
| --- | --- |
| `GOOD.TXT`, `EMPTY.TXT` | Readable, unverified; exported hashes match originals |
| `ALLOC.BIN`, `FATCOPY.BIN`, `FRAGLIVE.BIN`, `OUTSIDE.BIN` | Unreadable X; recovery refused |
| `FRAGFREE.BIN`, `PARTIAL.BIN`, `OVERWR.BIN` | Readable but unverified; exported hashes deliberately differ from originals |

`GONE/CHILD.TXT` is in a deleted directory and is excluded. `REUSED.TXT` is a live replacement record, and a live name beginning with the `0x05` escape is also excluded. Live owner files prevent recovery of clusters they own.

The free-gap fragmented case proves why detecting `0xE5` and free FAT entries cannot verify original contents. Do not treat its intentionally wrong exported bytes as a restored original.

## Other images

- `fat16-dirty`, `live-cycle`, `mirror-disagreement`, and invalid geometry/cluster-size/signature: scan refused.
- `fat32-active-second-fat` and `stale-inactive-fat`: five baseline candidates, using FAT1 as active. `invalid-active-fat`: refused.
- MBR/GPT/APM FAT16 disks and gzip/bzip2/xz wrappers: five baseline candidates; payload hashes are in the parent `expected.json`.
- MBR outside/overlapping volumes and damaged GPT primary/entry-array/backup CRCs: refused.

Handset acceptance: the user checked all files in this directory and reports graceful handling throughout.

Full behavior and additional checks: [recovery documentation](../../../../../docs/fat-deleted-file-recovery.md).

## New partial-recovery fixtures (handset checks pending)

`fat16-magic-partial.img` has two deleted entries with PNG/PDF headers and allocated second clusters. Choose **Recover partial copy…**: keep the first cluster, zero the second, and check MIME match/mismatch hints in the companion JSON report. A matching PNG header does not establish a valid PNG payload.

`fat16-partial-tail.img` adds `TAIL.BIN` with one valid cluster and an assumed tail outside the volume. Its partial copy must contain 512 `T` bytes followed by 512 NUL bytes, with exact range offsets in the report. Ordinary Undelete remains blocked.

The user's prior confirmation applies to the earlier fixture set. These two new images exercise the new explicit partial-copy mode. See [partial recovery](../../../../../docs/partial-fat-recovery.md).
