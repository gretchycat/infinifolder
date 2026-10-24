# CAB fixtures

`mszip.cab` was made with gcab from the common test tree. Stored cabinets with independent compression folders were constructed according to Microsoft's MS-CAB layout, including CFDATA checksums; the damaged copy changes only `bad.bin` payload bytes.

Multipart and methods fixtures are from https://github.com/kyz/libmspack at `55d501976171397ccd5d5a7a1ca7da065b1d9a06`, under `libmspack/test/test_files/cabd/`. The multipart files are published with the names referenced in their headers (`cabd_multi_basic_ptN.cab`); member bytes are unchanged. `missing-volume/` omits part 3. `methods.cab` is the upstream `mszip_lzx_qtm.cab`. The upstream LGPL notice is included.

Expected hashes for generated file contents are in `expected.json`. These cabinets do not exercise CAB creation in the Android app.
