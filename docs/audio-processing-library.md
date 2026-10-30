# Audio parsing and processing library

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-30. The independent [libinfinisound repository](https://github.com/gretchycat/libinfinisound) is pinned at `libraries/libinfinisound` and imported as a Gradle composite build. Both library-owned portable codecs and Android's codec adapter are selected inside the library.

## Current modules and ownership

| Module | Responsibility |
| --- | --- |
| `audio-api` | JVM contracts/models, byte-source and PCM stream interfaces, playlist/CUE/CDDB parsing; no Android dependency |
| `audio-core` | JVM MP3/AAC/FLAC/WAVE parsers/decoders, existing specialized/composite renderers, selection/fallback, PCM preparation and analysis; no Android dependency |
| `audio-processing` | Android extractor/codec backend, metadata and analysis adapters, default composition, tracker JNI packaging |
| `viewer-audio` | Source staging and leases, queue and gapless handoff, MediaPlayer PCM output, routing/focus, UI and Cast |

The player service now sends every original encoded source to `AudioProcessing.prepareForOutput` and gives only the prepared PCM WAVE to MediaPlayer. Normal loads and gapless preload use the same library policy. Source-duration discovery also uses the library; the service no longer constructs a MediaPlayer for metadata decoding. The buffered Cast conversion API uses the same library backend selection. Remote Cast renderers still own their own output handling.

The library has portable MP3 via JLayer, AAC via its ADTS/MP4 parsers and JAAD synthesis, and its own FLAC and WAVE stream decoders. The Android composition registers MediaExtractor/MediaCodec behind `PcmDecoder`; players never select a native codec directly. `PLATFORM_FIRST`, `PORTABLE_FIRST` and `PORTABLE_ONLY` policy belongs to the library. Existing specialized formats retain their renderer priority and limits.

Streams preserve source precision up to 32-bit FLAC, with bounded sample buffers. Prepared output files have exact decoded durations and streaming signal metrics. MP3 Xing/LAME metadata and supported AAC edit lists trim priming/padding without using silence detection. Failure after partial decoding discards the output and retries the next backend from the beginning. Cancellation releases handles and discards incomplete PCM; preloads do not alter the active diagnostic report.

## Checkout and builds

```sh
git submodule update --init --recursive
gradle :app:assembleDebug
```

InfiniFolder requests `io.github.gretchycat.libinfinisound:audio-api:0.2.0` for Store playlists and `io.github.gretchycat.libinfinisound:audio-processing:0.2.0` for audio. Gradle substitutes the pinned source; no Maven publication is required. The host shares its SDK setting with the included build when no SDK is configured globally.

From the library checkout, portable builds need a JDK/Gradle and no Android SDK:

```sh
gradle -PportableOnly=true :audio-core:build
```

The full library's Android build is `gradle :audio-core:test :audio-processing:assembleDebug`. Native tracker packaging remains the existing Termux build and fallback.

## Portability and output costs

The required roadmap targets are Android, Linux, Windows, macOS and iOS. The portable JVM core is the current desktop foundation; native iOS/macOS contracts, codec and output adapters remain roadmap work. See [the library roadmap](../libraries/libinfinisound/docs/portability-roadmap.md).

InfiniFolder currently prepares the complete PCM file before playback so its existing MediaPlayer gapless link can be retained. This adds disk usage and can add startup/preload time. It does not allocate the whole decoded track in memory for standard codecs. The RIFF writer rejects output over 4 GiB. Hosts can consume `PcmStream` directly when a streaming output adapter is available. Device playback, seeking, interruption, gapless timing and route checks remain necessary after this boundary change.

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
