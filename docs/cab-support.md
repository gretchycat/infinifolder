# CAB support

Implemented 2026-10-23. The user confirms CAB support working. Detailed variant and damage checks below remain open unless individually confirmed.

## Implementation

CAB browsing and extraction use `NativeArchiveReader`, sharing its provider handles, integrity scan, unreadable-entry metadata, X badge and verified temporary-file extraction with RAR/ARJ. `.cab`, CAB MIME types and the `MSCF` signature identify cabinets. MIME icon candidates use the existing archive icon pipeline. CAB views are read-only.

The native engine supports stored, MSZIP, LZX and Quantum cabinets within its existing version limits. CAB exposes filenames/timestamps and Windows attributes; it does not provide Unix ownership, permissions or symbolic links, so these are not fabricated. Folder paths can be synthesized from member names; empty source directories need not be represented by CAB.

### Multipart selection

CAB previous/next header links can name siblings with unrelated basenames or extensions. The provider offers non-directory sibling identities to the volume callback and preserves the selected cabinet as the starting member. Only names explicitly requested by the decoder are opened; unrelated cabinets are not selected by alphabetical order or merged into the set. The native handler checks cabinet set IDs and sequence numbers and can follow previous as well as next links. As with RAR, requests containing separators or traversal names are rejected.

Sibling metadata participates in cache revisions. For CAB this includes the directory's non-directory siblings, so unrelated file changes can invalidate the cached catalog. This is conservative and may warrant a narrower linked-volume revision cache later. Providers without sibling/parent access can read standalone cabinets but cannot supply unavailable parts. A linked sibling that fails to open is reported as unavailable rather than aborting the initial cabinet solely for that source-open failure; native header availability still determines which members can be listed. Native archive error messages are included in listing notices.

### Damage and limitations

Initial listing decodes identifiable members without saving payloads, marking native checksum/data failures and unavailable members unreadable. Separate compression folders may remain recoverable independently. Files sharing a damaged compression folder can fail together. Checksums only detect what the cabinet records; omitted CFDATA checksums weaken corruption detection. Unparseable headers can prevent complete enumeration. This is not cabinet repair or digital-signature/authenticity verification.

Copying a directory containing unreadable CAB members uses the existing skip-and-warning behavior. A move with skipped contents retains its source folder. CAB creation, updates, recovery and password handling are not added.

The Android native engine remains 7-Zip-JBinding 16.02-2.03; its upgrade remains a maintenance task. No new native dependency was needed for CAB.

References: Microsoft's [CAB overview](https://learn.microsoft.com/en-us/windows/win32/setupapi/about-cabinet-files), [MS-CAB format specification](https://download.microsoft.com/download/4/D/A/4DA14F27-B4EF-4170-A6E6-5B1EF85B1BAA/%5BMS-CAB%5D.pdf), and the shared binding's [source](https://github.com/omicronapps/7-Zip-JBinding-4Android).

## Fixtures

Located in `filesystem-test-images/archives/cab/`:

| Fixture | Purpose |
| --- | --- |
| `independent-folders.cab` | Four stored members in separate compression folders: good text, binary, nested text and Unicode name |
| `damaged-member.cab` | Same cabinet with a changed `bad.bin` data byte and stale checksum; other folders are intact |
| `mszip.cab` | Common populated tree, UTF-8 names, nested paths and empty files; no symbolic links |
| `methods.cab` | Upstream MSZIP/LZX/Quantum fixture |
| `multipart/cabd_multi_basic_pt1.cab` … `pt5.cab` | Complete linked cabinet set with original embedded link names |
| `missing-volume/` | Same set without part 3 |

`expected.json` records generated payload hashes/readability expectations. `PROVENANCE.md` identifies the imported libmspack revision and recipe; its LGPL notice is included. The custom stored cabinets follow MS-CAB and include CFDATA checksums. MSZIP fixtures use host `gcab`. Existing fixtures are preserved on regeneration.

```sh
python3 filesystem-test-images/generate-cab.py --libmspack build/fixture-tools/libmspack-cab
```

The previously authorized `pkg` install supplied gcab 1.6 and cabextract 1.11. Host 7-Zip listing recognizes the stored cabinet, all five linked cabinets, and MSZIP/LZX/Quantum methods. Logs: `build/fixture-tools/cab-generation.log`, `cab-stored-listing.log`, `cab-multipart-listing.log`, `cab-methods-listing.log`. These are fixture inspections, not Android runtime acceptance. No automated implementation tests were added or run for CAB.

## Handset checks

- [ ] Browse/open/copy stored and MSZIP fixtures, including Unicode names, nested paths and empty files.
- [ ] Confirm `bad.bin` has an X in the damaged fixture while both intact text files and the Unicode member remain readable. Copy all contents and confirm the skip warning.
- [ ] Open the complete set from the first and a middle cabinet; confirm `test1.txt`, `test2.txt` and `test3.txt` are exposed once with correct bytes.
- [ ] Check the missing-volume warning and per-file availability with part 3 absent; restore it and refresh.
- [ ] Place unrelated cabinets alongside the selected one and confirm selection and linked-volume resolution stay correct.
- [ ] Check MSZIP/LZX/Quantum content, provider routes and case-ambiguous/malformed header links.

Build log: `build/cab-support-build.log`; build succeeded and APK was opened for installation.
