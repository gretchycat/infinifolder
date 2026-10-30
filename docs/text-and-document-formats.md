# Text, markup, document, and text-based media formats

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Current implementation ownership: libinfiniscribe/scribe-core owns the existing MarkdownRenderer and bounded charset/text decoding. Text, Markdown, HTML and CSV viewers consume it through MediaSourceAdapter while retaining original complete bytes for revision checks. Android WebView, editing UI and guarded save/recovery remain host adapters. Input and actual text-output metadata distinguish encoding, completeness and warnings.

Updated 2026-10-27. This document tracks plain text, source code, markup, paginated document, eBook, spreadsheet, and **text-based media** (such as ANSI art and ANSI music) across the six text/document viewer modules, along with difficulty ratings and prerequisites for implementing viewers/players and creators/editors for currently unsupported formats:

1. **Plain Text & Code Viewer (`:viewer-text` / `TextViewerActivity`)**
2. **Markdown Viewer (`:viewer-markdown` / `MarkdownViewerActivity` & `MarkdownRenderer`)**
3. **HTML Viewer (`:viewer-html` / `HtmlViewerActivity`)**
4. **PDF Viewer (`:viewer-pdf` / `PdfViewerActivity`)**
5. **EPUB Reader (`:viewer-epub` / `EpubViewerActivity`)**
6. **Spreadsheet Viewer (`:viewer-csv` / `CsvViewerActivity`)**

---

## Implemented

### 1. Plain text & source code (`:viewer-text`)

`TextViewerActivity` provides a monospace viewer and in-place editor with UTF-8 / UTF-16 BOM detection, line numbers, word-wrap toggle, find-in-page with match highlighting, undo/redo stacks, and source-revision-checked save/Save As:

- **Plain text, logs & configuration**: `.txt`, `.log`, `.cfg`, `.conf`, `.ini`, `.properties`, `.toml`, `.env`, `.gitignore`, `.gitattributes`, `.editorconfig`, `.bashrc`, `.zshrc`, `.profile`
- **Shell & automation scripts**: `.sh`, `.bash`, `.zsh`, `.fish`, `.bat`, `.cmd`, `.ps1`, `.tcl`, `dockerfile`, `makefile`, `.mk`, `.cmake`, `.gradle`
- **Programming languages**: `.py`, `.kt`, `.kts`, `.java`, `.c`, `.cpp`, `.cc`, `.cxx`, `.h`, `.hpp`, `.cs`, `.go`, `.rs`, `.rb`, `.php`, `.js`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `.jsx`, `.lua`, `.r`, `.swift`, `.dart`, `.asm`, `.s`, `.pl`, `.pm`, `.sql`
- **Structured data & patches**: `.json`, `.xml`, `.yaml`, `.yml`, `.diff`, `.patch`
- **Magic-byte fallback**: Extensionless files with UTF-16 BOM, `#!` shebangs, JSON/XML prefixes, or printable UTF-8 content (<5% non-whitespace control bytes and no NUL bytes) are classified as `text/plain` by `FileMagicDetector` (noting that `0x1B` ESC is explicitly permitted in `detectTextFormat` so ANSI escape sequences pass text detection).

### 2. Markdown (`:viewer-markdown`)

`MarkdownViewerActivity` and `MarkdownRenderer` provide rendered HTML preview and source editing with Save / Save As:

- **Extensions & MIME**: `.md`, `.markdown`, `.mdown`, `.mkd`, `.mkdn` (`text/markdown`, `text/x-markdown`)
- **Supported syntax**: Headings (`#`–`######`), bold/italic/strikethrough, inline code and fenced code blocks, blockquotes, ordered/unordered lists, task lists (`- [ ]` / `- [x]`), tables, horizontal rules, links, and images.

### 3. HTML & XHTML (`:viewer-html`)

`HtmlViewerActivity` provides a sandboxed `WebView` renderer (with external network requests blocked and relative VFS asset loading) plus a Rendered / Source toggle and in-place editing:

- **Extensions & MIME**: `.html`, `.htm`, `.xhtml` (`text/html`, `application/xhtml+xml`)

### 4. PDF documents (`:viewer-pdf`)

`PdfViewerActivity` uses Android's native `PdfRenderer` with pinch-to-zoom (`ZoomImageView`), fit-width / fit-page toggle, previous/next page buttons, and direct page jump:

- **Extensions & MIME**: `.pdf` (`application/pdf`, `%PDF-` magic header)

### 5. EPUB eBooks (`:viewer-epub`)

`EpubViewerActivity` extracts EPUB containers into a leased disk-cache directory, parses `META-INF/container.xml`, OPF package manifests/spines, and NCX/nav tables of contents, and renders chapters with Dark, Sepia, and Light themes and adjustable font scaling:

- **Extensions & MIME**: `.epub` (`application/epub+zip`)

### 6. Delimited spreadsheets (`:viewer-csv`)

`CsvViewerActivity` parses comma- and tab-delimited tables with RFC 4180 quoting, automatic delimiter detection, row numbering, column search/filtering, cell inspection/editing, and in-place saving:

- **Extensions & MIME**: `.csv`, `.tsv` (`text/csv`, `text/tab-separated-values`)

---

## Unimplemented

### Difficulty Rating Scale

- **Tier 1 — Trivial**: Charset table registration, delimiter expansion, or simple regex/tag transformation (<150 LOC).
- **Tier 2 — Low**: Self-contained state machine, binary cell grid, or XML-to-HTML converter (150–500 LOC).
- **Tier 3 — Medium**: Requires a virtual terminal screen buffer + bitmap font renderer, a real-time tone synthesizer synchronized with text output, or multi-file ZIP/XML document styling (500–2,000 LOC).
- **Tier 4 — High**: Requires a character-cell art/music editor UI, complex binary compound-document (OLE2 / PDB) parsing, or full page layout engine (2,000+ LOC).
- **Tier 5 — Very High**: Full TeX/LaTeX typesetting engine or DjVu IW44/JB2 wavelet/bi-level decoder.

---

### 1. Text-Based Visual & Audio Media (Target: `:viewer-text` and `:viewer-html` Renderers)

Text-based art and terminal music are stored inside plain-text or terminal byte streams and belong in the text renderers (`TextViewerActivity` and/or `HtmlViewerActivity`).

#### Core Prerequisites for ANSI Art & ANSI Music

1. **CP437 & Retro Bitmap Font Atlas (`8×16` IBM VGA, `8×8` CGA, Amiga Topaz)**:
   - Standard Android system fonts do not align box-drawing (`0xB0`–`0xDF` shading blocks `░▒▓█│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼`) seamlessly without sub-pixel gaps. Accurate rendering in `:viewer-text` / `:viewer-html` requires an embedded pixel-exact bitmap font atlas (or integer-aligned `<canvas>` / `Bitmap` cell blitter) covering all 256 Code Page 437 glyphs.
2. **Virtual Terminal Screen Buffer & CSI/SGR State Machine**:
   - ANSI art frequently uses cursor movement (`ESC[A`–`H`, `ESC[s`, `ESC[u`) to draw out-of-order rather than top-to-bottom. A simple regex-to-`Spannable` pass is insufficient; the renderer needs a 2D cell grid (`width × dynamicRows` of `[glyph, fgColor, bgColor, blink/iCE]`) driven by an ECMA-48 / `ANSI.SYS` state machine.
3. **SAUCE Metadata Parser & Writer**:
   - Reads/writes the trailing 128-byte `SAUCE00` record (after `0x1A` EOF marker) and optional `COMNT` block to determine exact column width (typically 80, or custom for `.ans`/`.bin`), iCE color mode (non-blink high-intensity backgrounds), aspect ratio, and font choice.
4. **Terminal Tone Synthesizer (`AudioTrack`) & Render-Clock Synchronizer**:
   - Required for **ANSI music**. Must parse `PLAY`-style note macros out of `ESC[M...` / `ESC[N...` / `ESC[?...` sequences, generate square/triangle/FM waveforms into an Android `AudioTrack`, and synchronize character-by-character terminal output with musical note playback when baud-rate emulation is active.

| Format | Extensions / Sequences | Viewer / Player Difficulty | Creation / Editor Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **ASCII / NFO / DIZ Art** | `.asc`, `.nfo`, `.diz` | **Tier 2 (Low)** | **Tier 2 (Low)** (text/CP437 editor) | **Prerequisites**: CP437 byte-to-Unicode mapping table + CP437 8×16 bitmap font (or zero-line-spacing monospace canvas) + SAUCE metadata parser. Creation uses `TextViewerActivity` with CP437 encoding output and a block-character palette bar. |
| **ANSI Art & VT Animations** | `.ans`, `.vt` | **Tier 3 (Medium)** | **Tier 4 (High)** (character-cell draw editor) | **Prerequisites**: (1) **CP437 / Topaz bitmap font atlas**; (2) **Virtual terminal screen buffer** handling `ESC[...m` (16-color, iCE colors, 256-color `38;5;n`, 24-bit `38;2;r;g;b` / `.tnd` `ESC[1;r;g;bt`) and cursor positioning; (3) **SAUCE parser**; (4) Optional **baud-rate timer** (300–115200 bps) for `.vt`/`.ans` animations. Creating `.ans` art requires a character-cell canvas editor with foreground/background color picker, F1–F12 CP437 shading-block brushes (`░▒▓█▄▀▌▐`), and an optimizer that serializes the 2D grid into minimal `ESC[...m` sequences + SAUCE footer. |
| **ANSI Music (in Text Renderer)** | `ESC[M...`, `ESC[N...`, `ESC[?...` (`0x0E` terminator) in `.ans`, `.asc`, `.mus` | **Tier 3 (Medium)** | **Tier 3 (Medium)** (`PLAY` macro composer/tester) | **Prerequisites**: (1) **ANSI terminal parser hook** that intercepts CSI music sequences (`ESC[M`, `ESC[N`, `ESC[?`, `ESC[=freq;durT`, and `0x0E` Ctrl-N musical note prefix) without printing raw macro text to the screen; (2) **QBasic `PLAY` macro interpreter** (`O0`–`O6` octave, `<`/`>`, `A`–`G` with `#`/`+`/`-` sharps/flats, `L1`–`L64` note length, `.` dotted notes, `T32`–`T255` BPM tempo, `P`/`R` rests, `MN`/`ML`/`MS` articulation, `MB`/`MF` background/foreground execution); (3) **`AudioTrack` waveform synthesizer** (band-limited square/pulse/triangle/OPL tone generator with envelope click-suppression); (4) **Playback transport bar** in the text viewer (Play/Pause/Seek/Mute ANSI Music while viewing the document). Creation requires a `PLAY`-string editor with live audition and `ESC[M...^N` block insertion. |
| **Binary VGA / XBin / iCE Draw / Tundra / PCBoard** | `.bin`, `.xb`, `.idf`, `.tnd`, `.pcb`, `.avt` | **Tier 2–3 (Low–Medium)** | **Tier 3–4 (Medium–High)** | **Prerequisites**: Same CP437 bitmap font cell blitter + format-specific grid readers: `.bin` reads raw `[char, attr]` pairs (160 bytes per 80-col row); `.xb` (`XBIN\x1a` header) supports custom embedded 16-color palettes, custom 8×N VGA fonts, and RLE compression; `.idf` has a 12-byte header + RLE + custom palette/font; `.tnd` uses 24-bit RGB cells; `.pcb` parses `@X<bg><fg>` hex color codes. |
| **PETSCII / ATASCII / Teletext** | `.pet`, `.seq`, `.ata`, `.ep1`, `.tti` | **Tier 2–3 (Low–Medium)** | **Tier 3–4 (Medium–High)** | **Prerequisites**: Commodore 64 PETSCII (shifted/unshifted) or Atari 8-bit ATASCII or Teletext Mode 7 (`40×25` 2×3 sixel mosaic) glyph tables + control-code color/reverse-video state machine. |
| **Terminal Session Recordings** | `.ttyrec`, `.cast` (asciinema) | **Tier 3 (Medium)** | **Tier 2 (Low)** (session recorder) | **Prerequisites**: Virtual terminal screen buffer + timestamped packet reader (`[sec, usec, len]` header in `.ttyrec`; newline-delimited JSON `[time, "o", data]` in `.cast` v2) + play/pause/speed timeline scrubber. |

---

### 2. Plain Text & Code Viewer (`:viewer-text`) Enhancements

| Feature / Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Legacy & CJK Character Encodings** | Any text file (`CP437`, `CP850`, `Windows-1252`, `Shift_JIS`, `GB18030`, `Big5`, `EUC-KR`) | **Tier 1 (Trivial)** | **Tier 1 (Trivial)** | **Prerequisites**: Encoding selector dropdown in `TextViewerActivity` + heuristic charset detector using `java.nio.charset.Charset.forName(...)` (all standard legacy and CJK charsets are already built into Android's ICU-backed JVM runtime) when reading and saving bytes. |
| **Subtitles, Synchronized Lyrics & Cue Sheets** | `.srt`, `.vtt`, `.ass`, `.ssa`, `.lrc`, `.cue` | **Tier 1 (Plain Text)** / **Tier 2 (Formatted Preview)** | **Tier 1 (Plain Text)** / **Tier 2 (Timestamp Helper)** | **Prerequisites**: Register extensions in `MediaTypeDetector.TEXT_EXTENSIONS` & `MimeRegistry`. Formatted preview parses timecodes (`00:00:00,000 --> 00:00:02,000` or `[mm:ss.xx]`) into a timestamp-aligned table. |
| **Unix Manual Pages & troff/groff** | `.1`–`.9`, `.man`, `.mdoc`, `.roff` | **Tier 2–3 (Low–Medium)** | **Tier 2 (Low)** | **Prerequisites**: Macro interpreter for `.TH`, `.SH`, `.SS`, `.TP`, `.PP`, `.B`, `.I`, `.BR` (man) and `.Dd`, `.Dt`, `.Nm`, `.Nd`, `.Fl`, `.Ar` (mdoc), rendering to styled HTML in `:viewer-html` or spans in `:viewer-text`. |
| **Hex / Binary Inspector Mode** | Any binary file | **Tier 2 (Low)** | **Tier 3 (Medium)** (in-place byte patching) | **Prerequisites**: Windowed `RandomAccessFile` / stream pager rendering 16 bytes per row (`offset | hex bytes | ASCII / CP437 glyphs`) without loading the entire file into a `String`. |

---

### 3. Rich Text, Markup & Help Formats (`:viewer-html` / `:viewer-markdown`)

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **Rich Text Format (RTF)** | `.rtf` | **Tier 3 (Medium)** | **Tier 2 (Low)** | **Prerequisites**: RTF group (`{...}`) and control-word (`\b`, `\i`, `\ul`, `\fsN`, `\cfN`, `\colortbl`, `\fonttbl`, `\par`, `\'hh` hex escapes, `\uN` Unicode) parser converting to sandboxed HTML for `HtmlViewerActivity`. Creation serializes styled text or HTML spans into RTF control words. |
| **MIME HTML Web Archives** | `.mht`, `.mhtml` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: `WebView.saveWebArchive` (creation) and multipart MIME boundary parser (`Content-Type: multipart/related`, `Content-Location`, `Content-Transfer-Encoding: quoted-printable / base64`) feeding `WebViewClient.shouldInterceptRequest` in `HtmlViewerActivity`. |
| **reStructuredText, AsciiDoc & Org-mode** | `.rst`, `.adoc`, `.org` | **Tier 3 (Medium)** | **Tier 1 (Trivial)** (plain text editor) | **Prerequisites**: Block/inline parser in `:viewer-markdown` converting directives, tables, and headings into HTML for the existing `WebView` previewer. |
| **LaTeX / TeX Documents** | `.tex`, `.ltx`, `.bib` | **Tier 3 (Math/Subset HTML)** / **Tier 5 (Full TeX Engine)** | **Tier 1 (Trivial)** (source editor) | **Prerequisites**: Source editing works directly in `:viewer-text`. Rendered preview requires a LaTeX-to-HTML+MathML/SVG translator (or bundled offline KaTeX/MathJax in `:viewer-html`). |
| **AmigaGuide** | `.guide` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: `@database`, `@node`, `@endnode`, and `@{"label" link "node"}` hypertext parser rendering interactive node pages in `:viewer-html` or `:viewer-text`. |
| **Compiled HTML Help (CHM)** | `.chm` | **Tier 4 (High)** | **Tier 4 (High)** | **Prerequisites**: MS ITSS (`ITSF` / `ITSP`) header parser + LZX (`LZXC`) reset-table decompression stream (can share LZX code with the native CAB archive reader) + internal `#SYSTEM` / `.hhc` / HTML page routing into `HtmlViewerActivity`. |

---

### 4. Paginated Documents, eBooks & Office Formats (`:viewer-pdf` / `:viewer-epub` / `:viewer-csv`)

| Format | Extensions | Viewer Difficulty | Creation Difficulty | Prerequisites |
| --- | --- | --- | --- | --- |
| **PDF Creation / Export (from Text, HTML, Markdown, Images)** | `.pdf` | *Already implemented* | **Tier 2 (Low)** | **Prerequisites**: Android's built-in `android.graphics.pdf.PdfDocument` API or `WebView.createPrintDocumentAdapter()` allows exporting plain text, ANSI art canvases, Markdown/HTML pages, and images directly to `.pdf` with zero third-party dependencies. |
| **OpenDocument & OOXML Word / Spreadsheets** | `.odt`, `.docx`, `.ods`, `.xlsx` | **Tier 3 (Medium)** | **Tier 3 (Medium)** | **Prerequisites**: ZIP package reader (already in `storage-engine`) + `XmlPullParser` transforming `content.xml` (`.odt`/`.ods`) or `word/document.xml` / `xl/worksheets/sheet1.xml` + `sharedStrings.xml` (`.docx`/`.xlsx`) into HTML (`:viewer-html`) or grid rows (`:viewer-csv`). |
| **FictionBook eBooks** | `.fb2`, `.fb2.zip` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: Single-file XML (`<FictionBook>`, `<body>`, `<section>`, `<binary>` base64 cover/images) to HTML chapter converter in `:viewer-epub` / `:viewer-html`. |
| **Mobipocket / Kindle (Unencrypted)** | `.mobi`, `.prc`, `.azw`, `.azw3` | **Tier 3–4 (Medium–High)** | **Tier 3 (Medium)** | **Prerequisites**: PalmDB (`.pdb`) record-offset header parser + PalmDOC LZ77 or Huff/CDIC decompressor + MOBI HTML / KF8 chapter extractor feeding `:viewer-epub`. |
| **DjVu Documents** | `.djvu`, `.djv` | **Tier 5 (Very High)** | **Tier 5 (Very High)** | **Prerequisites**: `AT&TFORM....DJVU`/`DJVM` chunk parser + ZP-coder arithmetic entropy decoder + JB2 bi-level mask decoder + IW44 wavelet background/foreground decoder (`DjVuLibre` JNI). |
| **dBASE / FoxPro & SQLite Tables** | `.dbf`, `.sqlite`, `.db` | **Tier 2 (Low)** | **Tier 2 (Low)** | **Prerequisites**: 32-byte `.dbf` header + fixed-width field descriptor reader (or `android.database.sqlite.SQLiteDatabase.openDatabase` for `.sqlite`/`.db`) populating the tabular grid in `:viewer-csv`. |
