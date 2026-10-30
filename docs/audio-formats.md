# Audio and sound formats

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

Updated 2026-10-30. This document tracks audio and sound format support across MIME/magic classification (`MediaTypeDetector`, `MimeRegistry`, `FileMagicDetector`) and the modular audio playback engine (`:viewer-audio` / `AudioPlayerActivity`, `AudioPlayerService`, `PcmAudioEngine`, and `UniversalAudioDecoder`). Parsing, codec adapters, metadata probing and analysis now live in the external `libinfinisound` library (`audio-api` / `audio-core` / Android `audio-processing`); see [audio library boundaries](audio-processing-library.md). Playback/output remains in `:viewer-audio`.

> Text-based musical notation and terminal sound sequences—such as **ANSI music** (`ESC[...M` / `ESC[...N` / QBasic `PLAY` macro strings embedded in `.ans`/`.asc` streams) and ABC music notation—belong to the text renderers and are documented in [Text, markup, document, and text-based media formats](text-and-document-formats.md).

---

## Implemented Architecture & Decoders

The InfiniFolder audio engine provides seamless playback through a two-tiered architecture:
1. **Hardware / MediaPlayer Pipeline**: Supported formats (`.mp3`, `.flac`, `.wav`, `.ogg`, `.opus`, `.m4a`, `.aac`) are streamed or staged directly into Android's `MediaPlayer`.
2. **Modular Synthesizer & PCM Engine Fallback**: For tracker, synthesizer, chiptune, workstation, and niche audiophile formats, `UniversalAudioDecoder` parses the streams, performs arbitrary-rate resampling or synthesis, and either feeds `MediaPlayer` via memory WAV streaming or drives `PcmAudioEngine` via an Android `AudioTrack` 16-bit 44.1 kHz stereo pipeline.

All formats integrate with `AudioPlayerService` background playback, lock screen media notifications, audio focus management, the segmented 16-band animated spectrum visualizer (`VisualizerView`), and playlist navigation.

---

### 1. Sample-Based Audio Formats

| Format | Extensions | MIME Type | Decoder Engine | Description & Capabilities |
| --- | --- | --- | --- | --- |
| **Sun / NeXT Audio** | `.au`, `.snd` | `audio/basic` | `SunAuDecoder` | Big-endian 24-byte header (`.snd`). Supports 8-bit µ-law, 8-bit A-law, 8-bit linear signed, 16-bit linear big-endian, 24-bit linear big-endian, 32-bit linear big-endian, and 32-bit IEEE float. |
| **Audio Interchange File Format** | `.aiff`, `.aif`, `.aifc` | `audio/x-aiff` | `AiffDecoder` | IFF `FORM` with `AIFF` or `AIFC`. 80-bit IEEE extended float sample-rate decoding, `COMM` and `SSND` chunks, big-endian and little-endian (`sowt`) 16-bit PCM, 24-bit, 32-bit float (`fl32`), µ-law, and A-law. |
| **Creative Voice** | `.voc` | `audio/x-voc` | `VocDecoder` | Classic Sound Blaster format (`Creative Voice File\x1A`). Supports block type 1 (legacy 8-bit with time constant divisor), block type 2 (continuation), block type 8 (extended stereo), and block type 9 (16-bit linear / arbitrary sample rates). |
| **Amiga IFF 8SVX & 16SV** | `.8svx`, `.svx`, `.16sv` | `audio/x-8svx` | `IffAudioDecoder` | Amiga sampled sound voices. Decodes `VHDR`, `CHAN`, `BODY` chunks. Supports uncompressed 8-bit signed PCM and 4-bit Amiga Fibonacci Delta decompression. |
| **MacroSystem MAUD** | `.maud` | `audio/x-maud` | `IffAudioDecoder` | Amiga MacroSystem IFF audio (`MHDR`, `MDAT`). Supports 8-bit and 16-bit big-endian PCM. |
| **Sony Wave64** | `.w64` | `audio/x-wave64` | `Wave64AndRf64Decoder` | 64-bit GUID RIFF container (`riff` GUID `{66696672-912E-11CF-A5D6-28DB04C10000}`). 64-bit chunk sizes for `fmt ` and `data`. |
| **EBU RF64** | `.rf64` | `audio/x-rf64` | `Wave64AndRf64Decoder` | European Broadcasting Union 64-bit RIFF format (`RF64` + `ds64` 64-bit chunk table). |
| **Apple Core Audio Format** | `.caf` | `audio/x-caf` | `CafDecoder` | Big-endian `caff` container. Parses `desc` and `data` chunks for linear PCM (8, 16, 24, 32-bit integer and 32-bit IEEE float, big and little endian), µ-law, and A-law. |
| **Audio Visual Research** | `.avr` | `audio/x-avr` | `SpecializedSampleDecoder` | 128-byte `2BIT` header. Supports signed/unsigned 8-bit and 16-bit mono and stereo samples. |
| **IRCAM Sound File** | `.ircam` | `audio/x-ircam` | `SpecializedSampleDecoder` | 1024-byte header (VAX little-endian and Sun big-endian). 16-bit linear PCM and 32-bit float. |
| **NIST SPHERE** | `.sph` | `audio/x-nist-sphere` | `SpecializedSampleDecoder` | 1024-byte ASCII keyword header (`NIST_1A`). Reads sample rate, channels, bit depth, and byte order (`01` LE / `10` BE). |
| **Portable Voice Format** | `.pvf` | `audio/x-pvf` | `SpecializedSampleDecoder` | ASCII header (`PVF1\n` / `PVF2\n`). Parses channels, sample rate, bit depth, and 16-bit big-endian PCM. |
| **Psion Audio** | `.wve` | `audio/x-wve` | `SpecializedSampleDecoder` | 32-byte `ALawSoundFile**` header with 8 kHz G.711 A-law speech decompression. |
| **HTK Speech Feature** | `.htk` | `audio/x-htk` | `SpecializedSampleDecoder` | 12-byte big-endian header. Converts 100ns sample periods to sample rate and decodes 16-bit linear PCM. |
| **CRI ADX ADPCM** | `.adx` | `audio/x-adx` | `TelephonyAudioDecoder` | CRI Middleware game audio (`0x80 0x00`). 18-byte frames per channel with 2nd-order AR prediction filter. |
| **Adaptive Multi-Rate (AMR-NB/WB)** | `.amr`, `.awb` | `audio/amr`, `audio/amr-wb` | `TelephonyAudioDecoder` / `MediaPlayer` | `#!AMR\n` and `#!AMR-WB\n` headers. Dual-mode support through native hardware codecs and software fallback. |

---

### 2. Synthesizer Prerequisites & Sample Modulation

The synthesizer pipeline (`com.infinifolder.audio.synth`) provides full polyphony and modulation:

- **`SampleResampler`**:
  - High-precision fractional playback rate interpolation ($step = \frac{f_{\text{target}}}{f_{\text{base}}}$).
  - Supports **Linear interpolation** and **Hermite cubic spline interpolation** ($4$-point $3$-rd order curve).
  - Loop handling: `LoopMode.NONE`, `LoopMode.FORWARD`, and `LoopMode.PING_PONG` (bidirectional loop bounce).
- **`EnvelopeGenerator`**:
  - 4-stage ADSR (Attack, Decay, Sustain, Release) state machine.
  - Per-instrument attack, decay, sustain level, and release timing.
- **`Lfo`**:
  - Low Frequency Oscillator for vibrato (pitch modulation), tremolo (amplitude modulation), and auto-pan.
  - Waveforms: Sine, Triangle, Sawtooth, Square.
- **`SoundFontReader`**:
  - **SoundFont 2 (`.sf2`)**: RIFF `sfbk` parser reading `LIST INFO`, `LIST sdta` (16-bit PCM sample pool), and `LIST pdta` (`shdr`, `phdr`, `pbag`, `pgen`, `inst`, `ibag`, `igen`).
  - **SFZ (`.sfz`)**: Plain-text definition parser (`<region> sample=... pitch_keycenter=... loop_mode=...`).
  - **Gravis UltraSound (`.pat`)**: `GF1PATCH110` parser reading instrument headers and waveforms.
- **`WavetableSynthesizer`**:
  - 32-to-64 polyphonic dynamic voice allocator with voice stealing.
  - Channel volume, expression, panning, pitch bend, and soft-knee limiting.
  - Includes a built-in multi-harmonic additive instrument soundbank for high-quality standalone synthesis.

---

### 3. Sequencer & Tracker Formats

| Format | Extensions | MIME Type | Engine | Description & Capabilities |
| --- | --- | --- | --- | --- |
| **Standard MIDI & RMID** | `.mid`, `.midi`, `.kar`, `.rmi` | `audio/midi` | `MidiSequencer` | Complete SMF Format 0, 1, 2, and RIFF RMID unpacker. Multi-track chronological event merge, microsecond-accurate tempo changes (`FF 51 03`), Note On/Off, Pitch Bend, Program Change, and synthesis via `WavetableSynthesizer`. |
| **ProTracker Module** | `.mod` | `audio/x-mod` | `OpenMpt` / `TrackerEngine` | Industry-standard `libopenmpt` tracker engine (with pure Kotlin fallback). Full support for 4 to 32 channels (`M.K.`, `4CHN`, `6CHN`, `8CHN`, `OCTA`, `16CN`, `32CN`). Paula period to frequency calculation ($f = \frac{3546895}{2p}$), Amiga stereo panning, 31 sample definitions, 64-row pattern playback, tick scheduling, speed/tempo control (`Fxx`), and volume slides. |
| **Scream Tracker 3** | `.s3m` | `audio/x-s3m` | `OpenMpt` / `TrackerEngine` | `libopenmpt` engine (`SCRM` header). Supports C4 speed calculations, signed/unsigned 8/16-bit samples, multi-channel rendering, portamentos, vibrato, and ST3 panning. |
| **FastTracker II** | `.xm` | `audio/x-xm` | `OpenMpt` / `TrackerEngine` | `libopenmpt` engine (`Extended Module: ` header). Multi-sample instruments, volume/panning envelopes, delta-encoded 8/16-bit sample decoding, linear and Amiga frequency tables. |
| **Impulse Tracker** | `.it` | `audio/x-it` | `OpenMpt` / `TrackerEngine` | `libopenmpt` engine (`IMPM` header). Supports IT instrument tables, New Note Actions (NNA), filters, and 64-channel playback. |

---

### 4. Chiptune & Hardware Register Dumps

| Format | Extensions | MIME Type | Engine | Description & Capabilities |
| --- | --- | --- | --- | --- |
| **DOSBox Raw OPL** | `.dro` | `audio/x-dro` | `ChiptunePlayer` | `DBRAWOPL` header. DOSBox hardware register dumps for Yamaha OPL2/OPL3 FM sound cards (AdLib, Sound Blaster). |
| **Id Software Music** | `.imf` | `audio/x-imf` | `ChiptunePlayer` | OPL2 register/value/delay sequences from *Wolfenstein 3D*, *Commander Keen*, and *Duke Nukem II*. |
| **Video Game Music** | `.vgm`, `.vgz` | `audio/x-vgm` | `ChiptunePlayer` | `Vgm ` header (with automatic GZIP decompression for `.vgz`). Emulates PSG / FM sound chips. |
| **YM Sound Chip** | `.ym` | `audio/x-ym` | `ChiptunePlayer` | Atari ST and Amstrad CPC YM2149 / AY-3-8910 chiptune register logs (`YM5!LeOnArD!`, `YM6!`, `YM2!`, `YM3!`). |
| **Nintendo Sound File** | `.nsf` | `audio/x-nsf` | `ChiptunePlayer` | NES sound chip music dump (`NESM\x1A`). Generates pulse, triangle, and noise waveforms. |
| **SNES Sound File** | `.spc` | `audio/x-spc` | `ChiptunePlayer` | `SNES-SPC700 Soun` header. SNES 8-channel DSP audio. |
| **Commodore 64 SID** | `.sid` | `audio/x-sid` | `ChiptunePlayer` | `PSID` / `RSID` header. MOS 6581/8580 SID chip synthesis. |

---

### 5. Audiophile Streams & Direct Stream Digital (DSD)

| Format | Extensions | MIME Type | Engine | Description & Capabilities |
| --- | --- | --- | --- | --- |
| **Direct Stream Digital (DSF)** | `.dsf` | `audio/x-dsf` | `AudiophileStreamDecoder` | Sony DSF container (`DSD `). DSD64 1-bit Pulse Density Modulation (PDM) @ 2.8224 MHz decimated into 16-bit 44.1 kHz PCM via a 64-sample moving average decimation filter. |
| **Direct Stream Digital (DFF)** | `.dff` | `audio/x-dff` | `AudiophileStreamDecoder` | Philips DSDIFF container (`FRM8....DSD `). 1-bit PDM stream decimation to 16-bit PCM. |
| **True Audio** | `.tta` | `audio/x-tta` | `AudiophileStreamDecoder` | `TTA1` header. Lossless 16-bit multichannel PCM audio. |
| **WavPack Lossless** | `.wv` | `audio/x-wavpack` | `AudiophileStreamDecoder` | `wvpk` header. Decodes WavPack lossless audio frames. |
| **RealMedia Audio** | `.ra` | `audio/x-pn-realaudio` | `AudiophileStreamDecoder` | `.RMF` RealMedia container. |
| **Dolby Digital & Surround** | `.ac3`, `.eac3`, `.dts`, `.mlp`, `.thd` | `audio/ac3`, `audio/eac3`, `audio/vnd.dts`, `audio/x-mlp`, `audio/x-truehd` | `AudiophileStreamDecoder` | Synchronized frame extraction for Dolby Digital AC-3 (`0x0B 0x77`), Enhanced AC-3, and Dolby TrueHD / MLP streams (`0x10 0x50 0xFF 0xD8`). |

---

### 6. Playlists & Cue Sheets

| Format | Extensions | MIME Type | Engine | Description & Capabilities |
| --- | --- | --- | --- | --- |
| **M3U / Extended M3U / M3U8** | `.m3u`, `.m3u8` | `audio/x-mpegurl` | `PlaylistParser` | `#EXTM3U` and `#EXTINF` metadata parser. Extracts track titles, durations, and URIs or relative filesystem paths. |
| **PLS Playlist** | `.pls` | `audio/x-scpls` | `PlaylistParser` | INI-style `[playlist]` format. Parses `FileN`, `TitleN`, and `LengthN`. |
| **XML Shareable Playlist** | `.xspf` | `application/xspf+xml` | `PlaylistParser` | XML `<playlist>` parser extracting `<track>` `<location>` and `<title>`. |
| **Cue Sheet** | `.cue` | `application/x-cue` | `PlaylistParser` | CD digital audio cue sheets. Parses `FILE`, `TRACK AUDIO`, `TITLE`, and `INDEX 01` cue markers. |
