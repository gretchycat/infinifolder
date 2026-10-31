# Audio parsing and processing library

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-31. The independent [libinfinisound repository](https://github.com/gretchycat/libinfinisound) is pinned at `libraries/libinfinisound` and imported as a Gradle composite build. Both library-owned portable codecs and Android's codec adapter are selected inside the library.

## Current modules and ownership

| Module | Responsibility |
| --- | --- |
| `audio-api` | JVM contracts/models, byte-source and PCM stream interfaces, playlist/CUE/CDDB parsing; no Android dependency |
| `audio-core` | JVM MP3/AAC/FLAC/WAVE parsers/decoders, existing specialized/composite renderers, selection/fallback, PCM preparation and analysis; no Android dependency |
| `audio-processing` | Android extractor/codec backend, metadata and analysis adapters, default composition, tracker JNI packaging |
| `viewer-audio` | Source staging and leases, queue and gapless handoff, MediaPlayer PCM output, routing/focus, UI and Cast |

The player service sends original encoded sources to `AudioProcessing.openPlaybackSource` and gives only the resulting common PCM WAVE ByteSource to the existing host MediaDataSource output adapter. Normal loads and gapless preload use the same library policy. Visualizer analysis borrows the same PCM resource, sharing the decoder/cache. Source-duration discovery also uses the library; the service no longer constructs a MediaPlayer for metadata decoding or decodes a complete track just to enrich metadata before playback. The buffered Cast conversion API uses the same library backend selection. Remote Cast renderers still own their own output handling.

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
