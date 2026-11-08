# Audio parsing and processing library

> [Current architecture](architecture.md) and [runtime discovery](runtime-capabilities.md) describe implemented ownership. [Migration checkpoints](infinistream-migration.md) retain dated evidence; format and data-safety limits still apply.

Updated 2026-10-31. The independent [libinfinisound repository](https://github.com/gretchycat/libinfinisound) is pinned at `libraries/libinfinisound` and imported as a Gradle composite build. Both library-owned portable codecs and Android's codec adapter are selected inside the library.

## Current modules and ownership

| Module | Responsibility |
| --- | --- |
| `audio-api` | JVM contracts/models, byte-source and PCM stream interfaces, playlist/CUE/CDDB parsing; no Android dependency |
| `audio-core` | JVM MP3/AAC/FLAC/WAVE parsers/decoders, existing specialized/composite renderers, selection/fallback, PCM preparation and analysis; no Android dependency |
| `audio-processing` | Android extractor/codec backend, metadata and analysis adapters, default composition, tracker JNI packaging |
| `viewer-audio` | Source staging and leases, queue and gapless handoff, MediaPlayer PCM output, routing/focus, UI and Cast |

The player service sends original encoded sources to `AudioProcessing.openPlaybackSource` and gives only the resulting common PCM WAVE ByteSource to the existing host MediaDataSource output adapter. Normal loads and gapless preload use the same library policy. Visualizer analysis borrows the same PCM resource, sharing the decoder/cache. Source-duration discovery also uses the library; the service no longer constructs a MediaPlayer for metadata decoding or decodes a complete track just to enrich metadata before playback. Cast conversion requests explicit PCM packing through the same library source/cache path, and HTTP requests borrow positional views. Remote Cast renderers still own their own output handling.

The library has portable MP3 via JLayer, AAC via its ADTS/MP4 parsers and JAAD synthesis, and its own FLAC and WAVE stream decoders. The Android composition registers MediaExtractor/MediaCodec behind `PcmDecoder`; players never select a native codec directly. `PLATFORM_FIRST`, `PORTABLE_FIRST` and `PORTABLE_ONLY` policy belongs to the library. Existing specialized formats retain their renderer priority and limits.

Streams preserve source precision up to 32-bit FLAC, with bounded sample buffers. Known exact frame counts permit lazy PCM WAVE reads; unknown counts retain complete preparation. Finished output has exact decoded duration and signal metrics; partial reads do not claim a complete health result. MP3 Xing/LAME metadata and supported AAC edit lists trim priming/padding without using silence detection. Late decode failure retries full preparation and requires the same negotiated PCM format. Cancellation releases handles without retry; preloads do not alter the active diagnostic report.

## Checkout and builds

```sh
git submodule update --init --recursive
gradle :app:assembleDebug
```

InfiniFolder requests `io.github.gretchycat.libinfinisound:audio-api:0.2.0` for Vault playlist containers and `io.github.gretchycat.libinfinisound:audio-processing:0.2.0` for audio. Gradle substitutes the pinned source; no Maven publication is required. The host shares its SDK setting with the included build when no SDK is configured globally.

From the library checkout, portable builds need a JDK/Gradle and no Android SDK:

```sh
gradle -PportableOnly=true :audio-core:build
```

The full library's Android build is `gradle :audio-core:test :audio-processing:assembleDebug`. Native tracker packaging remains the existing Termux build and fallback.

## Portability and output costs

The required roadmap targets are Android, Linux, Windows, macOS and iOS. The portable JVM core is the current desktop foundation; native iOS/macOS contracts, codec and output adapters remain roadmap work. See [the library roadmap](../libraries/libinfinisound/docs/portability-roadmap.md).

For known exact frame counts, InfiniFolder opens one bounded decoded block and lets MediaPlayer pull additional PCM as needed. Seek-capable backends fill independent 4096-frame blocks in the explicit disk cache; backward reads reuse valid blocks. Android's standard backend and portable MPEG/FLAC/WAVE support frame positioning; MP3 uses a lazy MPEG packet index and preroll to preserve exact sample alignment. Non-seekable decoders retain prefix decoding. Unknown-length streams retain full preparation; explicit staging for unsupported/sequential sources, CUE slicing, legacy file-context renderers and archive extraction adapters remains, along with existing composite synthesis limits. The decoded cache can grow to a full track and rejects RIFF output over 4 GiB. Hosts can also consume `PcmStream` directly. Device playback, seeking, interruption, gapless timing, visualization and route checks remain necessary.

MediaPlayer prefetch can still request several megabytes during seeking. The library's playback policy prefers its existing portable MPEG decoder when content and exact timing validate that route, retaining Android fallback. Other formats retain platform-first decoding. The recorded two-minute MP3 full-decode benchmark was 777 ms portable versus 10,030 ms Android. Explicit decoder policies and the existing openWaveSource API are preserved. The player still receives only PCM WAVE, and its linked gapless mechanism is retained.

In the recorded local Android check, a two-minute MP3 prepared without starting output in 333 ms, with 135,016 of 21,168,044 PCM bytes cached. This is a fixture/device observation. `audio.load.prepared` diagnostics separately record source staging, metadata, PCM opening and player preparation so remaining latency can be attributed. Repeat the PCM transport/device checks using `python3 scripts/verify-audio-pull-runtime.py` after building the full APK and library JARs; see that script's device/tool requirements.

See [library architecture](../libraries/libinfinisound/docs/architecture.md) and [codec implementations](../libraries/libinfinisound/docs/third-party.md).

## Initial extraction evidence (before portable codec adapters)

Validation on 2026-10-30 in the existing Termux environment:

- Built `:audio-api:assembleDebug` and `:audio-processing:assembleDebug` as separate AARs.
- Built `:app:assembleDebug` and `:viewer-host:assembleDebug -Pviewer=audio` offline.
- Passed 53 selected JVM checks: 7 processing/analysis contracts, 42 viewer/decoder/playlist/queue/Cast/CDDB checks, and 4 storage playlist-directory checks. The report-generating playback diagnostic fixture was not run; its historical report is unchanged.
- Confirmed the processing AAR and both APKs contain the same seven expected ARM64 tracker JNI/dependency libraries. The API AAR contains no native libraries.
- Confirmed neither audio library references viewer or storage-runtime classes; `git diff --check` passed.

These checks do not constitute device playback verification. Existing compiler deprecation and Termux AAPT2 diagnostics were emitted; both builds completed successfully.


## Portable/native increment validation

The new source boundary passed 66 core/viewer/storage checks and built both debug APKs. A DEX harness also ran the built library code under the device's Android runtime: 12 portable-only/platform-first decode cases, including native MediaCodec and precision-preserving portable FLAC fallback. MP3/MP4 timing matched and FLAC samples were identical between selected paths. See [detailed validation](../libraries/libinfinisound/docs/validation.md). Audible output and gapless handoff were not exercised by that decoding harness.

## InfiniStream migration

Sound now shares `stream-core:0.1.0`. StreamAudioInput wraps known-length positional sources without implicit spooling; PcmStreamHandle and AudioStreamProcessor expose actual typed output metadata and declared ports. FileAudioInput retains legacy file hints while reading through the shared source. Store/Vault sources can be passed to the same adapter; Android output and queue lifetimes are unchanged. Standalone builds require the sibling Stream checkout pinned by the workspace (or -PinfiniLibrariesDir).

## MP3 footer recovery (2026-10-31)

JLayer's packet look-ahead can discard the final audio packet when a trailing ID3v1 tag is passed to the codec. On affected files this left the declared PCM timeline short and invoked a full-file fallback during a seek near the end. Sound now presents JLayer with an owned byte view ending before that tag, including after indexed seeks. The probe reads three footer bytes; original metadata, encoder trim and gapless frame counts are retained. Synthesized tagged/untagged PCM comparisons and affected-file runtime checks cover this path.

The host records `audio.seek.requested` / `audio.seek.completed` with the latest target, elapsed time, decoder, actual cache strategy, cached PCM bytes and decoder-positioning count. Android can coalesce requests; the completion log identifies the latest requested target. These snapshots request no PCM and do not wait for decoding. The runtime harness runs playback muted with concurrent analysis; `--file /path/to/track.mp3` checks an owner-supplied MP3 of at least 32 seconds without adding it to source control.

## Common encoded inputs and FLAC seeking (2026-10-31)

Normal playback and gapless preloading now retain an `AudioPlaybackInput` lease. Known-length positional provider handles use the common source API directly; metadata and decoder restarts borrow one serialized `SharedPositionalResource` handle. Unsupported positional access does not first start an abandoned sequential download. Local files, CUE ranges, companion-file renderers and archive adapters which extract whole entries retain their existing file route. An initial direct decoder-open failure can explicitly retry staging; cancellation does not retry. Optional metadata failures remain warnings and do not crash the load worker. `audio.load.prepared` records `encodedAccess` as well as the PCM decoder/cache strategy.

Portable FLAC now seeks by exact coded sample/frame numbers with validated frame CRCs and following-frame links. A bounded byte search and anchor map avoid decoding skipped PCM. Android raw extraction on this device can return 16-bit samples even for a 24-bit source: the adapter now checks the real output instead of overwriting its encoding hint. Precision loss selects portable FLAC, while wider codec containers use the canonical source width for format-stable fallback. Exact sample checks include mono/stereo, 16/24/32-bit, fixed/variable blocks and a valid-looking frame embedded in sample payload.

The common input path does not remove provider latency or make cold MPEG indexing constant-time. Archives without an economical positional adapter still stage. Native iOS/desktop output, related-file composition and arbitrary multi-track planning remain later work.

## Decoder failure contract

Only actual decoding or input-driven note/sample rendering can produce PCM. The
old WMA, RealAudio/advanced-audio, chiptune and telephony chord substitutes are
removed; supported Android codecs remain registered inside Sound. Unsupported
input reports failure. SFZ missing/invalid referenced samples and empty GUS
patches fail explicitly. Chiptune emulation and additional portable codecs remain
planned. `.rm`/`.rmvb` association belongs to the Video host and libVLC.


## Additional native codecs and bundled MIDI (checkpoint 24)

Sound's Android module now registers a libVLC PCM fallback, using the same
3.7.7 upstream dependency as Video without depending on Video's API. A native
callback adapter borrows independent common input handles and produces PCM into
a bounded queue. The player continues to consume the common PCM/WAVE output;
libVLC opens no output device for this path. WMA/RA, WavPack/TTA and AC-3/E-AC-3/DTS
fixtures decode actual input. Candidate extensions are not conformance guarantees.
The backend outputs signed 16-bit PCM and retains full PCM preparation when an
exact output frame total is unknown. It does not invent a WAVE length from a
container duration. The established portable MPEG/FLAC policy remains.

`AudioProcessing.initialize(context)` enables lazy installation of the bundled
FluidR3Mono_GM.sf3 and selects libVLC's FluidLite for MIDI. The 14.5 MB
MIT-licensed SF3 asset is streamed and SHA-256 checked before atomic publication
to a private cache; FluidLite reads its Ogg-compressed samples directly. The
previous 148 MB SF2 expansion is no longer bundled. Core/portable synthesis remains
available. Explicit `PORTABLE_FIRST`/`PORTABLE_ONLY` policies retain their meaning.
Upstream LGPL/MIT/Apache notices are packaged with the library assets. Android
CMake/NDK source is provided for the callback bridge; the current Termux build
and runtime checks exercise arm64, not desktop/iOS execution.

Unreadable-queue handling belongs to the host. It preserves entries with unknown
`--` duration, skips silently, blocks metadata races and bounds retries so an
entirely unreadable list stops. Successful explicit retry restores actual metadata.
See the [handset fixtures](../filesystem-test-images/audio/decoder-fallback-checks.md).

## Loading corrections (checkpoint 25)

Sound now parses MIDI event timing in the portable core and exposes exact finite
rendered frame counts to the existing lazy PCM source. Native FluidLite holds one
resident bank and independent synthesizers, using shared-bank synchronization.
The optional GME adapter renders real game music in a declared finite window
(metadata playback length or 150 seconds, plus eight-second fade). Original
FluidLite/GME notices are packaged. Neither engine owns an output device.

Generic native PCM can use the FFmpeg C API exported by the same libVLC binary,
with explicit EOF drain and no player clock. Public-header ABI checks guard its
use; the existing libVLC callback path remains a compatibility fallback. Unknown
exact totals still require a complete PCM spool. Duration inspection no longer invokes unbounded full PCM rendering; tiny untimed
native files have a strict 64 KiB / ten-second PCM / 250 ms counting budget. Host playlist
scanning uses that common-source metadata interface, yields while a track loads,
and preserves known durations/source slices. Loader interruption belongs to the
host; backend cancellation and bank lifetime belong to Sound. MIDI seeking still
uses decoded-prefix access, separately from existing indexed MPEG/FLAC seeking.

Checkpoint 26 corrects this policy for finite game music: file length and fade
are honored without adding a universal eight seconds. Untimed loop caps remain
playback policy and are not returned as source duration metadata. GME metadata
uses information-only sessions, VGZ has bounded inflation, and command warnings
fail decoding. Native duration inspection also covers portable legacy containers
and stream timing; an optional host format probe cannot discard successful tags.
The consumer's malformed game-music fixtures are corrected separately. Successful
builds do not establish their audible correctness; handset acceptance is pending.

Checkpoint 27 supplies portable timing inspection for the owner's remaining
CDDA/DFF/MLP/THD files and the explicit 2.5-second SF2 preview. Optional NSF timing
chunks are parsed in the core and applied by the native adapter's common PCM
stream. The updated chord fixture carries a 1.5-second endpoint. Archive identity,
relative playlists, known-duration retention and gapless promotion are corrected
in the host; those queue policies remain outside Sound.

## Cast source migration (checkpoint 28)

Receiver-native formats retain their encoded-byte policy and range slicing;
positional provider/container sources avoid unnecessary staging. Other formats
use `PcmOutputOptions(bitsPerSample = 16, duplicateMono = true)` with Sound's
WAVE source, retaining source rate and existing multichannel behavior. The host
does not hold entire encoded, PCM or serialized-WAV buffers. Existing legacy
codec buffers and unsupported-source staging remain explicit compatibility paths.

The server retains four prepared sources, pins active HTTP consumers through
eviction, and reopens older session URLs on demand. Requests have independent
cursors, bounded transfer/header buffers, four workers and a bounded pending
queue. Stop closes sockets, cancels preparation and releases source/cache owners.
HTTP HEAD/range semantics are retained; short/failed reads cannot be presented as
complete data. Existing transcoded CUE-slice limitations remain; this checkpoint
does not introduce compressed-file time slicing or new receiver codec support.
Compilation is separate from real receiver playback/seek/reconnect acceptance.
