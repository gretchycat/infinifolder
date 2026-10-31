#!/usr/bin/env python3
"""Create synthetic document-viewer fixtures without overwriting existing files.

Run from any directory. Only Python's standard library is required.
"""
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile

from fixture_paths import ROOT, update_manifest

OUT = ROOT / "documents"
MIB = 1024 * 1024


def delimited(rows, delimiter=",", line_ending="\r\n", final_newline=True):
    output = io.StringIO(newline="")
    csv.writer(output, delimiter=delimiter, lineterminator=line_ending).writerows(rows)
    text = output.getvalue()
    if not final_newline and text.endswith(line_ending):
        text = text[:-len(line_ending)]
    return text.encode("utf-8")


def main():
    files = {}
    expected = {}

    def add(name, data, purpose, **details):
        if isinstance(data, str):
            data = data.encode("utf-8")
        files[name] = data
        expected[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                          "purpose": purpose, **details}

    def sheet(name, rows, purpose, delimiter=",", line_ending="\r\n", final_newline=True):
        add(name, delimited(rows, delimiter, line_ending, final_newline), purpose,
            delimiter=delimiter, encoding="UTF-8", row_count_including_header=len(rows),
            maximum_columns=max((len(row) for row in rows), default=0), expected_rows=rows,
            editable=True)

    sheet("01-edit-roundtrip.csv", [
        ["ID", "Name", "City", "Notes", "Amount", "Status"],
        ["1", "Alice", "Paris", "Edit me", "12.50", "ready"],
        ["2", "Bob", "New York", "Comma, stays inside this cell", "0", "ready"],
        ["3", "Chloé", "Québec", 'He said "hello"', "-7.25", "ready"],
        ["4", "美咲 🎵", "東京", "Café — Ελληνικά — العربية", "00123", "ready"],
        ["5", "Multiline", "London", "Line one\nLine two", "3.14", "ready"],
        ["6", "Empty fields", "", "", "", ""],
        ["7", "Literal text", "Test", "=1+1", "1e3", "not evaluated"],
    ], "Main edit/save/reopen check; six columns, eight rows including the header. Change Edit me to Edited on Android.")
    sheet("02-tabs.tsv", [
        ["ID", "Name", "Notes"],
        ["1", "Alice", "Edit me"],
        ["2", "Embedded tab", "left\tright"],
        ["3", "Embedded newline", "first\nsecond"],
        ["4", "Unicode", "naïve — 日本語 🎵"],
    ], "TSV detection, quoted embedded tabs/newlines and UTF-8 roundtrip.", delimiter="\t", line_ending="\n")
    sheet("03-semicolon.csv", [
        ["ID", "Name", "Notes", "Amount"],
        ["1", "Alice", "Edit me", "12,50"],
        ["2", "Bob", "Semicolon; stays inside this cell", "0,25"],
        ["3", "Chloé", 'Quoted "word"', "3,14"],
    ], "Semicolon auto-detection; decimal commas stay inside their cells.", delimiter=";")
    sheet("04-pipe.csv", [
        ["ID", "Name", "Notes"],
        ["1", "Alice", "Edit me"],
        ["2", "Pipe character", "left|right"],
        ["3", "Multiline", "first\nsecond"],
    ], "Pipe auto-detection and quoting; use .csv so the existing spreadsheet dispatch opens it.", delimiter="|")
    sheet("05-unicode.csv", [
        ["Script", "Text", "Notes"],
        ["Latin", "Café, naïve, Straße", "Accents"],
        ["Greek", "Ελληνικά", "Greek"],
        ["Japanese", "日本語 東京", "Japanese"],
        ["Arabic", "العربية", "Right-to-left text"],
        ["Emoji", "🎵 🎧 🌍", "Supplementary characters"],
        ["Combining", "e\u0301", "Combining accent, not a replacement character"],
    ], "Unicode display and save/reopen; text must retain its characters.")
    sheet("06-no-final-newline.csv", [["ID", "Notes"], ["1", "Final row survives"]],
          "Last row has no newline; saving uses the existing LF-terminated serialization.", line_ending="\n", final_newline=False)
    add("07-parser-compatibility.csv",
        'ID,Notes,Extra\r\n 1 ," padded value ",keep\r\n\r\n , , \r\n2,"first\r\nsecond",\r\n3,short\r\n',
        "Existing behavior: trim fields, skip empty newline-terminated rows, accept ragged rows and preserve quoted CRLF.",
        delimiter=",", encoding="UTF-8", row_count_including_header=4, maximum_columns=3, editable=True,
        expected_rows=[["ID", "Notes", "Extra"], ["1", "padded value", "keep"], ["2", "first\r\nsecond", ""], ["3", "short"]])
    add("08-unclosed-quote.csv", 'ID,Notes\n1,"Unclosed quote\ncontinues here',
        "Deliberately malformed input. Existing permissive parser accepts the quoted remainder as one multiline cell; it should not crash.",
        delimiter=",", encoding="UTF-8", row_count_including_header=2, maximum_columns=2, editable=True,
        expected_rows=[["ID", "Notes"], ["1", "Unclosed quote\ncontinues here"]])
    sheet("09-empty.csv", [], "Empty spreadsheet: no records; no crash.", line_ending="\n")
    sheet("10-header-only.csv", [["ID", "Name", "Notes"]], "Header only: one row, three columns.", line_ending="\n")
    sheet("11-cr-line-endings.csv", [["ID", "Notes"], ["1", "CR-only line endings"], ["2", "Edit me"]],
          "Classic CR-only line endings; saving still uses the existing LF convention.", line_ending="\r")

    add("sample.txt", 'InfiniStream document smoke test\n\nEdit me\nUnicode: Café — 日本語 — 🎵\nQuotes: "hello"; commas, tabs\there.\nEnd of file.\n',
        "Plain text editing/save/reopen; Unicode and the end marker remain visible.", editable=True, encoding="UTF-8")
    add("sample.md", '# InfiniStream document check\n\nEdit me\n\n**Bold**, *italic*, and `inline code`.\n\n- First item\n- Unicode: Café — 日本語 — 🎵\n\n> A quoted paragraph.\n\n```text\ncomma, tab\tand "quotes"\n```\n',
        "Markdown rendering and source editing; check heading, emphasis, list and fenced code.", editable=True, encoding="UTF-8")
    add("sample.html", '<!doctype html>\n<html><head><meta charset="utf-8"><title>InfiniStream document check</title>'
        '<style>body{font:18px sans-serif;padding:16px}td,th{border:1px solid #888;padding:8px}table{border-collapse:collapse}</style></head>'
        '<body><h1>InfiniStream document check</h1><p>Edit me</p><p>Café — 日本語 — 🎵</p>'
        '<table><tr><th>Name</th><th>Notes</th></tr><tr><td>Alice</td><td>Comma, and &quot;quotes&quot;</td></tr></table>'
        '<p><strong>Bold</strong> and <em>italic</em>.</p></body></html>\n',
        "Self-contained HTML rendering/source editing; no network resources required.", editable=True, encoding="UTF-8")
    add("sample.json", json.dumps({"title": "InfiniStream document check", "edit": "Edit me", "unicode": "Café — 日本語 — 🎵",
        "values": [1, 2, 3], "quoted": 'Comma, and "quotes"', "multiline": "first\nsecond"}, ensure_ascii=False, indent=2) + "\n",
        "JSON through the text viewer; edit/save/reopen without changing Unicode or escaped strings.", editable=True, encoding="UTF-8")

    # Moderate cell widths and rows work with the existing 500-row render cap.
    # The source exceeds the 8 MiB spreadsheet preview budget, without a huge single cell.
    large = io.BytesIO()
    large.write(b"ID,Name,Notes,Status\r\n")
    rows = 1
    while large.tell() <= 9 * MIB:
        large.write(f'{rows:06d},Large preview row,BEGIN-{rows:06d}-'.encode("ascii") + b"x" * 240 + b",read-only preview\r\n")
        rows += 1
    add("90-large-read-only.csv", large.getvalue(),
        "Over 8 MiB: read-only chunk browsing must stay responsive. Scrolling automatically loads previous/next record windows, and saving must not overwrite the source.",
        delimiter=",", encoding="UTF-8", row_count_including_header=rows, maximum_columns=4,
        preview_limit_bytes=8 * MIB, editable=False)
    large_text = ("Large text preview: " + "x" * 100 + "\n").encode("ascii")
    large_text = large_text * ((4 * MIB + MIB // 2) // len(large_text) + 1) + b"END OF ORIGINAL FILE - beyond preview limit\n"
    add("91-large-text-read-only.txt", large_text,
        "Over 4 MiB: read-only chunk browsing must stay responsive. Scroll both ways or Go to a byte offset near the end to reach the end marker; saving must not overwrite the source.",
        encoding="UTF-8", preview_limit_bytes=4 * MIB, editable=False)

    archive = io.BytesIO()
    members = [name for name in files if not name.startswith(("90-", "91-"))]
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name in members:
            info = zipfile.ZipInfo("documents/" + name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, files[name])
    add("document-viewer-checks.zip", archive.getvalue(),
        "Browse documents/ inside the ZIP and repeat CSV/TSV/text opening and guarded-save tests on a copy. Contains the small samples; large previews stay outside the archive.",
        members=["documents/" + name for name in members])

    readme = '''# Document viewer samples

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
| 04-pipe.csv | 4 rows, 3 columns; left\u007cright is one Notes cell |
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
The CSV counter shows visible source row numbers and the exact data-row total
(excluding the header) after background counting; it must not report the 250-row
cache as the document size. Filters keep original source row numbers.
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
'''
    # Escape the pipe in the Markdown table, independently of the actual fixture bytes.
    readme = readme.replace("left|right is", "left\\|right is")
    files["README.md"] = readme.encode("utf-8")
    files["expected.json"] = (json.dumps({"schema": 1, "generator": "generate-document-fixtures.py",
        "notice": "Expected original synthetic bytes; existing edited files are preserved on regeneration.",
        "files": expected}, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    OUT.mkdir(parents=True, exist_ok=True)
    created = []
    for name, data in files.items():
        target = OUT / name
        try:
            with target.open("xb") as output:
                output.write(data)
        except FileExistsError:
            state = "unchanged" if target.read_bytes() == data else "edited/different"
            print(f"Preserved {target.relative_to(ROOT)} ({state})")
        else:
            created.append(target)
            print(f"Created {target.relative_to(ROOT)} ({len(data):,} bytes)")
    if created:
        update_manifest(created)
    print(f"Ready: {OUT}; {len(created)} created, {len(files) - len(created)} preserved")


if __name__ == "__main__":
    main()
