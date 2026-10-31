# Storage fixtures

## Ext metadata copy fixture

**2026-10-26 follow-up:** `ext4-metadata-tests.img` now has directory checksum
errors after handset operations. Preserve it for investigation and avoid further
writes. `ext4-metadata-retry.img` is a new clean fixture with the same initial
contents and expected values. A separate repaired copy is retained at
`build/ext-metadata-investigation/recovery-copy.img`; its filesystem check passes,
its five file hashes match the manifest, and its ACL and xattrs are present.
The original image was not overwritten. `ext4-write-tests.img` also has a directory
checksum error after a subsequent copy; preserve it and avoid further writes.

`filesystems/linux/ext4-metadata-retry.img` contains `copy-from/` and an empty
`copy-back-here/` directory. Copy the whole `copy-from/` directory into a second
ext image, such as `ext4-metadata-copy-target.img/copy-to/`, then copy it back into
`ext4-metadata-retry.img/copy-back-here/`. Offload a debug dump afterward; the
resulting image can be inspected with `debugfs` and `e2fsck`.

The fixture contains 0644, 0600, 0755 and 0640 files, a nested directory, UID/GID
1000, and known timestamps. `acl-and-xattrs-0640.txt` has a named-user POSIX ACL
plus `user.test_label` and a 400-byte `user.large` attribute. The complete expected
file hashes and metadata are in `filesystems/linux/ext4-metadata-tests.json`.
Image verification on creation: `e2fsck -fn` passed; attribute values were read
back and compared to the fixture bytes. Recreate with
`python3 generate-ext-metadata-fixture.py` only after placing any existing fixture
and manifest elsewhere; the generator refuses to overwrite either.

Properties currently displays basic permission bits and owner/group when supplied
by the provider, but not individual ACL entries or extended attributes. Some
providers do not expose Unix modes; the current screen can show an estimated
default in those cases. Copying to a destination unable to preserve these fields
may generate a metadata warning. Test ext-to-ext first to exercise preservation.

The original `fat*.img`, `ext*.img`, `hfs.img`, `ntfs.img`, and archive files are user-owned test data. The generator never overwrites them or any existing generated fixtures. Work on copies when testing destructive operations.

## Layout

| Directory | Fixtures |
| --- | --- |
| `filesystems/windows/` | FAT12/16/32, exFAT, NTFS and wrappers |
| `filesystems/linux/` | ext2/ext3/ext4 |
| `filesystems/bsd/` | UFS1/UFS2 and compression wrappers |
| `filesystems/apple/` | Classic HFS, HFS+, HFSX and wrappers |
| `filesystems/optical/` | ISO images |
| `archives/zip/`, `archives/7z/`, `archives/tar/`, `archives/cpio/`, `archives/ar/`, `archives/cab/` | Archive formats and packaged containers |
| `archives/legacy/` | RAR/ARJ single, multipart, missing-volume and damaged cases, with provenance/licenses |
| `compression/zstandard/` | Standalone compressed-file fixture |
| `disks/` | Complete MBR, GPT and Apple Partition Map disks and gzip wrappers |
| `images/` | 80 image format fixtures rendered from the InfiniFolder app icon (`generate-media-fixtures.py`) |
| `audio/` | 75 audio/sound format fixtures synthesized as a C-major piano chord (`C4`–`E4`–`G4`–`C5`, `generate-media-fixtures.py`) |
| `documents/` | CSV/TSV delimiter/quoting/Unicode/edit fixtures, text/Markdown/HTML/JSON, large read-only previews and an archive-access ZIP (`generate-document-fixtures.py`) |

The OS groups are organizational labels; these formats can be used by other operating systems. Existing fixture names and bytes were retained during the move. `layout-migration.json` maps old paths to current paths. All generators use the shared layout resolver; `manifest.json` now records relative paths.

## Added fixtures

Ext mutation fixtures: `filesystems/linux/ext2-write-tests.img`,
`ext3-write-tests.img`, and `ext4-write-tests.img` contain `move-from` and
`move-to`. Move `single-file.txt` and `directory-with-files` into `move-to`, then
reopen and copy them out. They include nested data, Unicode names, an empty
directory, a symbolic link and an outside hardlink for metadata/deletion checks.
`outside-hardlink.txt` should retain its contents when the other link is moved or
deleted. Use copies for destructive runs. `generate-ext-writes.py` creates these
only if absent and checks new images with `e2fsck`; it never changes the original
ext fixtures. Details and expected scope: [ext support](../docs/ext-image-writes.md).

| Files | Contents and purpose |
| --- | --- |
| `archive.zip`, `archive.7z` | Populated archives, directories, empty files, long/Unicode names, and symbolic links |
| `archive.tar` | PAX TAR with permissions, symbolic links, and an explicit hardlink |
| `archive.tar.gz`, `.tar.bz2`, `.tar.xz`, `.tar.lzma`, `.tar.zst` | The same TAR test tree through different compression codecs |
| `archive.tar.Z` | The existing TAR through genuine Unix-compress (LZW), distinct from gzip |
| `archive.cpio` | New ASCII CPIO (`newc`), with directories and symbolic links |
| `archive.ar` | AR with three ordinary files; no directory/link expectations |
| `archive.deb` | Debian container with `debian-binary`, `control.tar.gz`, and `data.tar.gz`; browsing fixture, not an application installer |
| `archive.love` | A minimal LÖVE program packaged as ZIP |
| `rockridge.iso`, `.iso.gz` | ISO 9660 with Rock Ridge filenames/metadata and a gzip wrapper |
| `ufs1.img`, `ufs2.img` | 16 MiB UFS1/UFS2 volumes with the test tree; user confirms browsing works |
| `ufs1.img.Z`, `ufs2.img.gz` | Compressed UFS fixtures; user confirms compressed UFS works |
| `exfat.img` | 32 MiB populated exFAT volume; symlinks omitted |
| `ntfs-populated.img` | 32 MiB NTFS volume with 24 root files and an empty file; NTFS3 is a Linux driver, not a separate disk format |
| `ntfs-populated.img.gz` | Gzip-wrapped populated NTFS fixture for the shared decompression route |
| `hfs-populated.img`, `hfs-populated.img.gz` | 16 MiB classic HFS volume with folders and files; no symlinks, names encoded as MacRoman and limited to 31 bytes |
| `hfsplus-populated.img`, `.img.gz` | 32 MiB HFS+ volume: 119 user entries, Unicode filenames, Unix modes, UID/GID 1000, file/directory symbolic links |
| `hfsx-populated.img`, `.img.gz` | 32 MiB case-sensitive HFSX volume: the same tree plus a distinct `case.txt` alongside `Case.txt` (120 entries) |
| `fat16.img.gz`, `.img.bz2`, `.img.xz` | Compressed copies of the original user `fat16.img`, for staged compressed-image mutation checks |

The common tree contains 100 small files, nested folders, an empty directory, an executable script, an 8 KiB binary, and known text payloads. Format limitations mean not every image has identical metadata. TAR alone explicitly includes a hardlink. ZIP and 7z filenames do not imply support for every application package or compression method using those containers.

## Current app coverage

Archive code supports ZIP, 7z, TAR and listed compression wrappers, CPIO, AR, Debian containers, and limited read-only RAR/RAR5/ARJ/CAB. The user confirms the delivered RAR/ARJ, unreadable-copy handling, Zstandard and CPIO changes working; see [scope and remaining checks](../docs/rar-arj-and-integrity.md). ISO, FAT, exFAT, ext, BSD UFS1/UFS2, a limited NTFS 3.x subset, and a limited HFS/HFS+/HFSX subset have image readers. The user confirms NTFS working; see [NTFS scope and checks](../docs/ntfs-support.md). The user confirms HFS, HFS+ and HFSX working; specific coverage checks remain pending; see [HFS scope and checks](../docs/hfs-support.md). Native storage mounted by Android is a separate route. ISO/exFAT/ext/UFS/NTFS/HFS image views remain read-only. Writable FAT image filesystems now include gzip/bzip2/xz/LZMA wrappers through staged recompression. TAR and compressed TAR writes have also been added; both new write paths need handset acceptance. `.tar.Z`/`.taz` and `.Z`-wrapped images now support reading; `.Z` writing remains unavailable. See [UFS scope and acceptance](../docs/ufs-and-legacy-compression.md).

Zstandard reading is enabled for compressed TAR, images and standalone payloads. Complete partitioned disks and RAR/ARJ multipart, missing-volume and damaged cases are now included. Virtual-disk wrappers, embedded HFS+ wrappers, encryption and split ZIP/7z sets still need additional coverage. CAB reading is implemented and the user confirms it working; detailed variant checks remain open.

## Creation and integrity

Run from the repository root:

```sh
python3 filesystem-test-images/generate.py \
  --makefs build/fixture-tools/makefs/src/makefs \
  --hfs-library build/fixture-tools/libhfs-fixtures.so
```

Dependencies: Python 3, 7-Zip, GNU cpio/binutils, zstd, ntfs-3g tools, makefs, libhfs, and host libarchive (for `.Z` fixture generation). On this Termux host, helper source/tools live in ignored `build/fixture-tools/`. Generation logs are in `build/fixture-tools/generate.log`.

Tool sources:

- [makefs](https://github.com/kusumi/makefs), built with HAMMER2 disabled. Local Android compatibility definitions cover BSD bitmap/byte-swap helpers, allocation, permissions, password iteration, and Android logging; the local `fparseln` fallback is renamed to avoid a libc collision.
- [libhfs](https://github.com/Distrotech/hfsutils), configured in `libhfs/` and built with `-O2 -fPIC -DHAVE_CONFIG_H -Wno-implicit-function-declaration`, then linked into `libhfs-fixtures.so`. The generator calls its volume API directly without mounting through the OS or changing hfsutils' global current-volume state.

`manifest.json` records file sizes and SHA-256 hashes at generation time. Re-running the generator keeps existing fixtures and updates the manifest, so the manifest is an inventory, not an immutable reference after manual mutation.

Creation checks on 2026-10-23: 7-Zip integrity tests passed for 7z, ZIP, and ISO; makefs's exFAT checks passed; NTFS and HFS README payloads matched after reopening; `file` identified UFS1, UFS2, NTFS, and classic HFS. These checks establish fixture structure, not Android app compatibility. Handset browsing/extraction and failure handling still need manual validation.

## HFS+/HFSX fixtures

Created 2026-10-23 with independent host tools, without using the Android decoder or mounting a filesystem. Use the current APK to open the raw images and their gzip wrappers. All four are read-only in the app.

- `names/café.txt` uses a decomposed accent; `names/日本語.txt` and `names/emoji-😀.txt` exercise non-Latin text and UTF-16 surrogate pairs.
- `readme-link` points to `README.txt`; `nested-link` points to `nested`.
- Modes: ordinary files `0644`, `run.sh` `0755`, `restricted` `0700`, `restricted/private.txt` `0600`, links `0777`. All expected UID/GID values are 1000. Destination engines may have different metadata capabilities.
- Only HFSX includes both `Case.txt` and `case.txt`, with different contents. Check them inside the image first; a case-insensitive destination cannot keep both under the same names.
- [hfsplus-expected.json](hfsplus-expected.json) records expected paths, kinds, content sizes/hashes, link targets and modes/ownership for each raw image. The wrappers contain the same respective volumes. Timestamps are generated by the host tools and are not part of the expected metadata inventory.

Generation:

```sh
python3 filesystem-test-images/generate-hfsplus.py \
  --newfs-hfs build/fixture-tools/diskdev_cmds-540.1.linux3/newfs_hfs.tproj/newfs_hfs \
  --hfsplus build/fixture-tools/libdmg-hfsplus/out/hfs/hfsplus
```

Both generators preserve existing fixtures and retain each other's common-manifest entries. Expected contents describe the canonical generated tree; they are not updated to match manual image edits. Generation log: `build/fixture-tools/hfsplus-fixtures.log`.

### Host-tool reproduction

These tools and patches are for fixture generation only; they are not Android dependencies.

1. Download [diskdev_cmds 540.1.linux3 from Debian](https://deb.debian.org/debian/pool/main/h/hfsprogs/hfsprogs_540.1.linux3.orig.tar.gz), SHA-256 `b01b203a97f9a3bf36a027c13ddfc59292730552e62722d690d33bd5c24f5497`, and extract under `build/fixture-tools/`.
2. Apply [newfs-hfs-termux.patch](newfs-hfs-termux.patch) with `patch -p1` from that source directory. It guards an unused Linux sysctl header and replaces unavailable `valloc` with page-aligned `posix_memalign`.
3. Build only the formatter from the repository root:

```sh
make -C build/fixture-tools/diskdev_cmds-540.1.linux3/newfs_hfs.tproj \
  -f Makefile.lnx CC=clang \
  'CFLAGS=-O2 -I../include -DDEBUG_BUILD=0 -D_FILE_OFFSET_BITS=64 -DLINUX=1 -DBSD=1 -DVERSION="540.1.linux3" -Wno-implicit-function-declaration'
```

4. Clone [planetbeing/libdmg-hfsplus](https://github.com/planetbeing/libdmg-hfsplus) at commit `7ac55ec64c96f7800d9818ce64c79670e7f02b67` under `build/fixture-tools/libdmg-hfsplus`. Apply [hfsplus-utf8-host.patch](hfsplus-utf8-host.patch) with `patch -p1` there. It adds lossless UTF-8/UTF-16 conversion with host libiconv, exposes the existing TAR importer, and applies imported link metadata to the link rather than its referent. The generator supplies decomposed names; the helper does not perform HFS Unicode normalization itself. Renaming/moving through the helper is not part of the fixture workflow.
5. Build the population tool (CMake, Clang, zlib, OpenSSL and libiconv headers/libraries are required):

```sh
cmake -S build/fixture-tools/libdmg-hfsplus \
  -B build/fixture-tools/libdmg-hfsplus/out -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build build/fixture-tools/libdmg-hfsplus/out --target hfsplus -j 4
```

Host inspection: 7-Zip lists both formats, the Unicode names, distinct HFSX case variants, executable/private modes and symbolic-link modes. The population tool's catalog listing shows UID/GID 1000. Logs are `build/fixture-tools/hfsplus-listing.log`, `hfsx-listing.log` and `hfsx-host-root.log`. These are fixture inspections; no Android implementation tests were run. The user subsequently confirms HFS+ and HFSX working after the header-offset fix; detailed coverage checks remain pending.

## Legacy archives and complete disks

Create/import legacy fixtures with installed `arj`, the CC0 RAR fixture repository and the junrar fixture repository:

```sh
python3 filesystem-test-images/generate-legacy-archives.py \
  --rar-fixtures build/fixture-tools/rar-test-files \
  --junrar build/fixture-tools/junrar-fixtures
python3 filesystem-test-images/generate-partitioned.py
```

The user authorized `pkg` installation; `pkg install -y arj unrar` found both already installed. Upstream RAR fixture names are preserved because native volume callbacks request those names. Keep each multipart set's members together in its own directory.

Start with `archives/legacy/arj/damaged.arj`: `bad.bin` should show an X; `good.txt`, `after.txt` and the nested message should remain accessible. Copying the archive's directory contents should skip the damaged member and report a warning. In `rar5-missing-volume/` and `rar5-damaged-volume/`, `note.txt` should remain readable while the affected `spanned.bin` is unavailable. These are corruption checks, not repair fixtures.

`compression/zstandard/README.txt.zst` exposes one decompressed README; `archives/tar/archive.tar.zst` exposes a TAR tree; `filesystems/bsd/ufs2.img.zst` exposes a filesystem tree. CPIO UTF-8 names and symbolic links are supported by the updated reader.

See [archive scope](../docs/rar-arj-and-integrity.md) and [full-disk layouts and tests](../docs/partitioned-disk-fixtures.md). The user confirms the delivered functionality generally working; detailed edge-case checks remain on those lists.

## Cabinets

CAB fixtures are under `archives/cab/`: independent-folder stored cabinets (intact and a damaged `bad.bin`), the populated `mszip.cab`, an imported MSZIP/LZX/Quantum methods cabinet, and complete/missing five-member sets. Keep the `cabd_multi_basic_ptN.cab` names: cabinet headers reference those exact names. Generated content hashes are in `archives/cab/expected.json`; imported fixture provenance and licenses are alongside it.

```sh
python3 filesystem-test-images/generate-cab.py --libmspack build/fixture-tools/libmspack-cab
```

CAB build succeeded and the user confirms support working; detailed handset checks remain open. See [CAB behavior and checks](../docs/cab-support.md).

## Deleted FAT files

`filesystems/windows/recovery/` contains `fat12-deleted.img`, `fat16-deleted.img`, `fat32-deleted.img`, and a gzip copy of FAT16. Each has five controlled deleted files: short/long names, an empty file, a contiguous multi-cluster binary and a file inside a live directory. `README.TXT` and `LIVE` remain visible normally. `expected.json` records original sizes, payload hashes, cluster runs before deletion and image hashes. No further allocation occurs after deletion.

Regenerate missing fixtures with `python3 filesystem-test-images/generate-fat-recovery.py`. Existing images are preserved. Long press an image → **Show deleted files**, then long press a trash-badged candidate → **Undelete…**. See [scope and remaining checks](../docs/fat-deleted-file-recovery.md).

Recovery safety fixtures are now in `filesystems/windows/recovery/safety/`: allocated and stale allocation, detectable/undetectable fragmentation, overwrite, reused records, excluded deleted directories, active FAT selection, malformed volumes, partitioned disks and compression wrappers. Use `generate-fat-recovery-safety.py` to add missing fixtures. [Expected outcomes](filesystems/windows/recovery/safety/README.md) distinguish blocked candidates from unverified copies that deliberately contain wrong bytes.

## Image and audio format fixtures

`images/` (80 files) and `audio/` (75 files) contain samples covering both currently supported and unimplemented formats documented in [Image formats](../docs/image-formats.md) and [Audio and sound formats](../docs/audio-formats.md):

- **Images (`images/icon.<ext>`)**: Rendered from the InfiniFolder launcher icon (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`) via ImageMagick (`magick`) and format-specific binary writers (including standard raster, `HEIC`/`AVIF`/`JXL`, `SVG`/`SVGZ`, `TGA`/`ICB`/`VDA`/`VST`, `TIFF`, `JP2`/`J2K`, `QOI`, `Netpbm`, `XBM`/`XPM`/`XWD`, `PCX`/`DCX`, `PSD`/`PSB`/`ORA`/`KRA`/`XCF`/`ASE`, Amiga `IFF`/`ILBM`/`LBM`, Atari ST `NEO`/`PI1`, ZX Spectrum `SCR`, C64 Koala `KLA`/`KOA`, Apple `ICNS`/`PICT`/`PNTG`/`MAC`, `HDR`/`EXR`/`DDS`/`KTX`/`FITS`/`DCM`/`DNG`, `PS`/`EPS`/`EPT`/`WMF`/`WMZ`, `SIXEL`, and CP437 24-bit `ANS` with `SAUCE00` metadata).
- **Audio (`audio/c_major_piano.<ext>`)**: Synthesized as a 1.5-second stereo C-major piano chord (`C4`–`E4`–`G4`–`C5` with harmonics, hammer transient, and exponential decay) encoded via `ffmpeg`, `sox`, and binary module/chiptune builders (including `WAV`, `MP3`, `MP2`, `FLAC`, `OGG`/`OGA`, `OPUS`, `M4A`/`M4B`/`M4R`/`AAC`/`ALAC`/`3GA`, `MKA`/`WEBA`, `AMR`/`AWB`, `WMA`, `WV`, `TTA`, `AC3`/`EAC3`/`DTS`/`MLP`/`THD`, `ADX`/`G722`/`RA`/`GSM`, `AIFF`/`AIF`/`AIFC`, `AU`/`SND`, `VOC`, Amiga `8SVX`/`SVX`/`MAUD`, `CAF`, `W64`/`RF64`, `SPH`/`IRCAM`/`AVR`/`WVE`/`PVF`/`HTK`, `MID`/`MIDI`/`RMI`/`KAR`, SoundFont `SF2`/`SFZ` and GUS `PAT`, tracker modules `MOD`/`S3M`/`XM`/`IT`, chiptunes `VGM`/`VGZ`/`DRO`/`IMF`/`YM`/`SID`/`NSF`/`SPC`, 1-bit DSD64 `DSF`/`DFF`, ANSI Music `ANS`, and playlists/cue sheets `M3U`/`M3U8`/`PLS`/`XSPF`/`CUE`).

Regenerate and update `media-fixtures-manifest.json` with:

```sh
python3 filesystem-test-images/generate-media-fixtures.py
```

Explicit partial recovery now has `fat16-magic-partial.img` and `fat16-partial-tail.img` fixtures in the recovery safety folder. They exercise retained uncertain bytes, NUL-filled missing/reused ranges and companion byte-range reports with shared magic/MIME hints. The new partial-export handset checks remain pending; see [behavior and evidence](../docs/partial-fat-recovery.md).

## Document viewer and spreadsheet checks

Start in [documents/](documents/README.md) with `01-edit-roundtrip.csv`, then
`02-tabs.tsv`, `03-semicolon.csv` and `04-pipe.csv`. Edit **Edit me** to
**Edited on Android**, save and reopen. Exact original small-table cells,
byte sizes and hashes are in [documents/expected.json](documents/expected.json).
The folder also contains Unicode, newline, empty/header-only and existing
permissive-parser cases; plain text, Markdown, HTML and JSON; a CSV over 9 MiB
and text over 4 MiB for random-access chunk navigation and read-only checks; and a ZIP containing
the small samples for opening/editing through an archive. Copy that ZIP or a
small fixture for repeatable edit tests, and upload copies to your normal remote
provider when checking network access. Existing image/audio samples remain in
their own folders.

```sh
python3 filesystem-test-images/generate-document-fixtures.py
```

All document fixture contents are synthetic. The generator creates missing files
and preserves existing samples, including edits made during handset testing.
