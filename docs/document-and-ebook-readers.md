# Document and ebook readers

Implemented 2026-11-03. This first reading pass follows the requested “closest good enough” approach: readable, reflowed content and worksheet values. The user confirmed document support working on the handset (2026-11-03).

## Formats and presentation

| Family | Inputs routed to the new reader | Presentation |
| --- | --- | --- |
| Kindle/Mobipocket | MOBI, AZW, AZW3, MOBI-based PRC | Reconstructed HTML chapters and embedded resources; unencrypted supported variants only. |
| FictionBook | FB2 XML | Reflowed sections, paragraphs, poetry, tables and supported embedded images. |
| Word | DOC/DOT (Word 97+), DOCX/DOCM/DOTX/DOTM | Text, headings, basic emphasis, tables and common embedded images. |
| Excel | XLS/XLT, XLSX/XLSM/XLTX/XLTM | Read-only worksheets in the existing CSV grid, with sheet selection, cell coordinates, copy and filtering of loaded rows. |
| PowerPoint | PPT/POT/PPS, PPTX/PPTM/POTX/POTM/PPSX/PPSM | One reading chapter per slide, containing text, tables and supported pictures in document order. |
| OpenDocument | ODT/OTT/FODT, ODP/OTP/FODP | Reflowed text, tables and package images. Slide positioning is not reproduced. |
| OpenDocument spreadsheet | ODS/OTS/FODS | Read-only worksheet grid with repeated rows/cells handled within preview limits. |
| RTF | RTF | Unicode text view; rich layout and embedded pictures are not displayed. |

Existing EPUB, PDF, CSV/TSV, HTML and Markdown readers retain their own routes. A `.prc` route means Mobipocket content; unrelated Palm databases are not claimed. Generic `.pdb` image associations are retained. DRM, KFX, encrypted Office files, old Word 6/95, embedded OLE execution, document editing and full desktop layout are outside this pass. Macro-containing containers are read as content; macros are never executed. Spreadsheet formulas use saved results when available and are not recalculated.

## Library ownership

Parsing belongs to optional Scribe modules:

- `scribe-core`: format routes, `ReadingDocument`/`ReadingChapter`, host-owned `DocumentOutput` byte sinks, bounded existing XML adapter and FB2 reading.
- `scribe-office`: Word/PowerPoint extraction, Excel worksheet reading and OpenDocument conversion.
- `scribe-poi`: private Android-compatible Apache POI dependency bundle.
- `scribe-mobi-android`: source-built libmobi JNI backend and cover extraction.
- `scribe-rtf`: RTF Parser Kit text conversion.

[Apache POI 5.5.1](https://poi.apache.org/components/index.html) supplies the Microsoft Office parsers. The packaging follows the StAX relocation approach used by [poi-on-android](https://github.com/centic9/poi-on-android), with a small sRGB color value adapter. It does not provide an AWT graphics engine. Office's Commons and logging dependencies are private to avoid collisions with Store/Sound. Original dependency licenses/notices are retained under `META-INF/libinfiniscribe-office`.

[libmobi 0.12](https://github.com/bfabiszewski/libmobi), pinned at `906274205c11944b628da1c553b255acb1af7c55`, supplies MOBI/KF8 parsing and resource reconstruction. Its LGPL-3.0-or-later source, attribution, license and native build scripts are in Scribe. [RTF Parser Kit 1.16.0](https://github.com/joniles/rtfparserkit) supplies Unicode RTF text conversion under Apache-2.0; its license/notice are packaged separately. These backends are optional Scribe dependencies, rather than parsers duplicated in activities.

The host retains storage access and disk-cache leases. `DocumentResourceStaging` borrows/stages sources through `MediaSourceAdapter`; Vault expands OpenDocument ZIP resources. Generated resources go into a separate confined output directory and use cache-accounted byte sinks. Readers preserve source provenance and return warnings. New reflow routes reuse the EPUB reader's chapter, text-size and theme controls. New workbook routes reuse the CSV viewer without enabling CSV save or cell editing on Office inputs. “Open with…” supplies the workbook MIME and grants access to a content URI.

The new reading routes block automatic network loads and confine file resources to their generated directory. Deliberate external web/mail links still use Android handlers. Existing EPUB behavior is retained.

## Bounds and fallback

- Accepted staged document input: at most 64 MiB. This check happens after existing source staging; staging itself retains the shared cache/capacity policy.
- Office ZIP entries: at most 32 MiB each and 10,000 entries. Expanded OpenDocument resources also obey Vault and shared cache limits.
- Worksheet previews: 10,000 populated rows, 256 columns, 4 MiB of cell characters and 32 KiB per cell. Actual row numbers and empty preceding columns are retained. The viewer marks a limited preview, and filtering is explicitly limited to loaded rows.
- Generated Office/FB2 resources: up to 16 MiB per image and 64 MiB total. Reflow content has an 8 MiB character limit. FB2 and OpenDocument XML use the existing byte/event/text/depth limits.
- MOBI: up to 16 MiB reconstructed text, 2,000 chapters, 10,000 emitted parts, 16 MiB per emitted resource and 64 MiB total output.
- RTF: up to 32 MiB encoded input and 8 MiB extracted UTF-8 text.

Failures report an unreadable/unsupported document and release leases rather than manufacturing a preview. Cancellation is checked during stream/XML/resource processing and worksheet traversal. Native MOBI and POI internals can complete a parse phase before observing cancellation; this is not a hard execution-time guarantee. Binary Office parsing and shared-string tables still require memory within the input limits.

## Covers and MIME integration

The shared artwork resolver now tries real embedded MOBI/KF8 and FB2 covers, alongside existing EPUB covers/PDF first pages. Missing, encrypted, unsupported or over-budget covers fall back to the normal MIME/theme icon. Provider sources used for cover staging are limited to 16 MiB; a larger provider book can still use the full reader within its separate limit. Successful covers use the existing memory/disk caches and `book-cover-v2` key. Existing explicit MIME image-mode choices are preserved; new book records default to content artwork.

Store associations and bounded magic detection include MOBI and RTF. Both the app and standalone host route the new formats. The supported-types inventory and generated default theme include the new extensions; distinct labels remain separate even when MIME artwork is shared.

## Build evidence and pending checks

Command:

```sh
gradle :app:assembleDebug :viewer-host:assembleDebug --offline --console=plain
```

Both debug APKs build successfully. Final log: `build/document-formats/final-build-4.log`. Earlier build logs record the resolved Office dependency/resource collision. No tests were added or run. Packaged native dependencies and license resources were inspected; a Termux-only zlib soname/runpath was corrected to Android’s public `libz.so`. Native MOBI was compiled for Termux ARM64; the alternative SDK/NDK CMake path and other ABIs have not been built here.

The user confirmed document support working on the handset (2026-11-03). This is acceptance of the exercised workflow; the report does not identify every format or edge case. Broader checks remain for MOBI/AZW3, FB2, DOC/DOCX, XLS/XLSX, PPT/PPTX, ODT/ODS/ODP and RTF variants, Unicode/pictures, chapter navigation, multiple sheets, sparse cell coordinates, cached formulas, limited previews and read-only guards. Invalid/encrypted files, cancellation, large files, remote/provider/archive input, external handoff and cover icons remain separate checks. No connected ADB device was available for agent-driven UI verification; compilation and packaged artifacts do not establish those results.
