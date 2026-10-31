# Image inspector and Canvas

The image viewer obtains EXIF through Canvas's `ImageMetadata.read(ByteSource)`
registered pull processor. The host opens the common source through Store's
existing `MediaSourceAdapter.openSource` route and closes it after parsing. The
library borrows that handle, consumes its current sequential cursor and retains
no stream, resolver, bitmap or Activity in the returned immutable description.
`read(SourceResource)` is also available for callers that want Canvas to open
and close one independent handle.

Canvas retains the existing Android `ExifInterface` backend. `ImageExifFields`
contains the raw make/model, date/time, exposure, f-number, ISO, focal length and
optional float GPS coordinates. Output metadata describes this structured result;
encoded byte lengths, positional capabilities, pixel dimensions and verification
are not copied onto it. Original provenance/checksums remain source provenance,
and warnings are retained. Processor inputs, outputs and pull mode are inspectable.
Cancellation is checked before parsing, around input reads and after the backend
returns, including backend paths that catch an input IOException. Android EXIF
expects full requested reads: the adapter fills the backend's supplied buffer
across legal short reads, stopping at EOF without staging or another buffer.

The host retains labels, ordering, units, locale formatting and the basic
name/MIME/current-bitmap details. Unsupported or unreadable EXIF still leaves
those basic details available. A changed image, newer inspector request or viewer
teardown invalidates pending dialog publication; source work stays off the UI
thread. Existing image decoding, orientation, zoom, navigation, editing and PNG
copy export are unchanged by this checkpoint.

This is extraction of the existing Android adapter. A portable EXIF parser,
metadata writing, new metadata fields and a portable image editor remain future
work. EXIF values describe the original encoded resource, while displayed pixel
dimensions describe the current sampled/edited bitmap, as before.

## Fixture checks

Open `filesystem-test-images/images/sample-exif-le.jpg` and
`sample-exif-be.jpg`, then tap the info button. Both use the existing app-icon
pixels with synthetic tags:

- Camera: InfiniStream Synthetic Metadata Fixture
- Date: 2026:10:31 12:34:56
- ISO: 200
- GPS: 41.50000, -87.25000 with the current locale's decimal separator
- Exposure, aperture and focal length using the existing backend's value text.

Repeat from `image-metadata-checks.zip`. Compare a normal `icon.jpg`/`icon.png`
with no camera metadata. `invalid-metadata.jpg` is a deliberately truncated
20-byte JPEG header with no encoded picture data, so it cannot render an image.
It tests handling of a truncated image file. It should remain visible in the file
listing; opening it must not crash the viewer. Navigate/back while an inspector
is loading, and check zoom/rotate/crop/resize/PNG-copy export.
Use copies when checking edits. Live SAF/network loading and UI interaction
still require handset acceptance.

`generate-image-metadata-fixtures.py` uses Python's standard library and preserves
all existing files. `image-metadata-expected.json` records original fixture hashes;
the ZIP contains the same JPEG bytes. JVM tests check processor contracts and
presentation parity. `scripts/verify-image-metadata-runtime.py` compares actual
APK Canvas output against the prior Android EXIF calls, and checks byte orders,
short reads, sequential/archive sources, ownership, failures and cancellation.
It does not bootstrap Android widgets or establish UI acceptance.

## Handset feedback (2026-10-31)

The owner confirms the little-endian and big-endian tagged JPEG checks work,
including the ZIP variants, after checkpoint 17. The owner also confirms that
`invalid-metadata.jpg` remains listed, renders no image and shows the toast
“Cannot decode this image format.” This is the expected rejection of the
truncated fixture. Other malformed inputs, live SAF/network, navigation/back
during inspector work and image editing/export remain separate acceptance work.
