# Document viewer samples

Start with **01-edit-roundtrip.csv**. It has **8 rows including the header and 6 columns**.
Change **Edit me** to **Edited on Android**, save, close and reopen. The comma inside
Bob's Notes cell, Chloé's escaped quotes, Unicode, empty cells, and the two lines
in the Multiline row must remain in their own cells. `00123`, `1e3`, and `=1+1`
are literal text, not calculated spreadsheet values. The grid displays at most
two lines per cell; tap an editable cell to inspect its complete contents.

## Checks

| Sample | What to check |
| --- | --- |
| 01-edit-roundtrip.csv | Commas, escaped quotes, multiline Notes, Unicode, empty cells; edit/save/reopen |
| 02-tabs.tsv | 5 rows, 3 columns; embedded tab and newline stay inside Notes |
| 03-semicolon.csv | 4 rows, 4 columns; decimal commas stay intact |
| 04-pipe.csv | 4 rows, 3 columns; left\|right is one Notes cell |
| 05-unicode.csv | 7 rows, 3 columns; accents, Greek, Japanese, Arabic, emoji and combining accent |
| 06-no-final-newline.csv | Final row survives without a newline |
| 07-parser-compatibility.csv | 4 rows including header; surrounding spaces trimmed, empty rows skipped, short row accepted |
| 08-unclosed-quote.csv | Deliberately malformed: 2 rows, 2 columns; remainder is one multiline cell under the existing permissive parser |
| 09-empty.csv | No records; no crash |
| 10-header-only.csv | One header row with 3 columns |
| 11-cr-line-endings.csv | 3 rows, 2 columns; CR-only input opens normally |
| sample.txt / sample.md / sample.html / sample.json | Text/Markdown/HTML/JSON smoke checks; edit/save/reopen where supported |
| 90-large-read-only.csv | Over 9 MiB; automatic bidirectional scrolling, read-only cells, no overwrite |
| 91-large-text-read-only.txt | Over 4 MiB; automatic bidirectional scrolling; Go to near the end to see the end marker |
| document-viewer-checks.zip | Open small samples under documents/ inside the archive; repeat on a disposable archive copy |

For repeatable edits, copy a small sample first. Upload copies to your normal
remote provider to test that path. The ZIP contains the same original small
samples and can supply a fresh copy if you have edited a loose file.

Saving CSV/TSV keeps the existing behavior: fields are trimmed on load, blank
newline-terminated rows are skipped, and serialization uses UTF-8 and LF line
endings. These samples test the current parser, not strict CSV conformance.
Scroll down and back up through 90/91, including a fast fling and a direction
change while loading. Rows/text should remain in order without jumping to the
top or duplicating content. Both viewers automatically load near the scroll
edges and discard only off-screen windows. The grid recycles visible rows,
retains up to five 50-record windows plus a fixed header, and shows 16 columns
at a time with controls for wider tables. Text retains up to three bounded
byte windows and keeps Go to byte offset. Sources that cannot seek use a bounded
sequential cache, so evicted ranges cannot be revisited on those sources.
Large files remain read-only. Explicit editing retains the existing complete-file
limits (8 MiB for spreadsheets, 4 MiB for text) and original revision checks.

`expected.json` records original sizes, SHA-256 hashes and exact small-table cells.
A hash mismatch after a deliberate edit is expected. All sample contents are
synthetic. Regenerate missing files from the repository root with:

```sh
python3 filesystem-test-images/generate-document-fixtures.py
```

The generator preserves every existing file, including your edited samples.
Existing image and audio smoke samples are under ../images/ and ../audio/.
