# Audio decoding and signal checks (JVM fixtures)

Generated on 2026-10-31T21:07:10.055330717Z across **78** audio/playlist fixtures in `filesystem-test-images/audio/`.

This JVM fixture report uses Android stubs. It does not establish device playback or codec availability. Unsupported codecs must fail; generated substitute tones are forbidden. Earlier reports counted placeholder synthesis as successful decoding and overstated support.

### Diagnostic Health Criteria
- **HEALTHY**: Decoded valid PCM stream with audible signal (Peak > 0.05, RMS > -40 dB, zero clipping, duration > 100ms).
- **SILENT**: Stream decoded without error, but sample values are zero or inaudible (RMS < -60 dB).
- **CLIPPING**: Sample values exceed dynamic range on >1% of frames.
- **DECODE_FAILED**: Parser or codec error occurred during audio unpacking.

### Test Results Matrix

| Format / Fixture | Size | MIME Type | Decoder / Engine | Duration | Format (Hz/Ch) | Peak Amp | RMS Level | Audio Health | Errors / Warnings |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `90-fluidr3-piano.mid` | 38 B | `audio/midi` | `MidiSequencer` | 1.50s | 44100 Hz / 2 ch | 0.39 | -26.1 dB | **PASS** | None |
| `91-fluidr3-flute.mid` | 38 B | `audio/midi` | `MidiSequencer` | 1.50s | 44100 Hz / 2 ch | 0.39 | -26.1 dB | **PASS** | None |
| `92-fluidr3-drums.mid` | 38 B | `audio/midi` | `MidiSequencer` | 1.50s | 44100 Hz / 2 ch | 0.51 | -20.9 dB | **PASS** | None |
| `95-unreadable.wma` | 128 B | `audio/x-ms-wma` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `96-unreadable.ra` | 60 B | `text/plain` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `97-skip-unreadable.m3u` | 120 B | `text/plain` | `PlaylistParser` | 0.00s | N/A | N/A | N/A | **PARSED** | None |
| `98-all-unreadable.m3u` | 43 B | `text/plain` | `PlaylistParser` | 0.00s | N/A | N/A | N/A | **PARSED** | None |
| `c_major_piano.3ga` | 24856 B | `video/mp4` | `Portable.AAC` | 1.50s | 44100 Hz / 2 ch | 0.94 | -18.4 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.8svx` | 24100 B | `audio/x-8svx` | `IffAudioDecoder` | 1.50s | 16000 Hz / 1 ch | 0.88 | -18.6 dB | **PASS** | None |
| `c_major_piano.aac` | 30218 B | `audio/aac` | `Portable.AAC` | 1.53s | 44100 Hz / 2 ch | 0.96 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.ac3` | 36780 B | `audio/ac3` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.adx` | 74502 B | `audio/x-adx` | `TelephonyAudioDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.aif` | 264688 B | `audio/x-aiff` | `AiffDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.aifc` | 264686 B | `audio/x-aiff` | `AiffDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.aiff` | 264688 B | `audio/x-aiff` | `AiffDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.alac` | 66397 B | `audio/mp4` | `AlacAndMpeg2Decoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.alac.m4a` | 66397 B | `audio/mp4` | `AlacAndMpeg2Decoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.amr` | 2438 B | `audio/amr` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.au` | 264644 B | `audio/basic` | `SunAuDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.avr` | 66278 B | `audio/x-avr` | `SpecializedSampleDecoder` | 1.50s | 22050 Hz / 1 ch | 0.87 | -18.6 dB | **PASS** | None |
| `c_major_piano.awb` | 4584 B | `audio/amr-wb` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.bin` | 263424 B | `application/x-raw-disk-image` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.caf` | 268696 B | `audio/x-caf` | `CafDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.cdda` | 263424 B | `audio/x-cdda` | `CddaDecoder` | 1.49s | 44100 Hz / 2 ch | 0.89 | -18.4 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.dff` | 1056897 B | `audio/x-dff` | `AudiophileStreamDecoder` | 1.50s | 44100 Hz / 2 ch | 0.62 | -21.5 dB | **PASS** | None |
| `c_major_piano.dro` | 151 B | `audio/x-dro` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.dsf` | 1056860 B | `audio/x-dsf` | `AudiophileStreamDecoder` | 1.50s | 44100 Hz / 2 ch | 0.62 | -21.5 dB | **PASS** | None |
| `c_major_piano.dts` | 72800 B | `audio/vnd.dts` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.eac3` | 36780 B | `audio/eac3` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.flac` | 58377 B | `audio/flac` | `Portable.FLAC` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.g722` | 12000 B | `audio/x-g722` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.gsm` | 2475 B | `audio/x-gsm` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.htk` | 48012 B | `audio/x-htk` | `SpecializedSampleDecoder` | 1.50s | 16000 Hz / 1 ch | 0.88 | -18.6 dB | **PASS** | None |
| `c_major_piano.imf` | 170 B | `audio/x-imf` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.ircam` | 265624 B | `audio/x-ircam` | `SpecializedSampleDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.it` | 1188 B | `audio/x-it` | `TrackerEngine` | 7.68s | 44100 Hz / 2 ch | 1.00 | -16.3 dB | **PASS** | None |
| `c_major_piano.kar` | 86 B | `audio/midi` | `MidiSequencer` | 2.00s | 44100 Hz / 2 ch | 1.00 | -19.9 dB | **PASS** | None |
| `c_major_piano.m4a` | 30827 B | `audio/mp4` | `Portable.AAC` | 1.50s | 44100 Hz / 2 ch | 0.96 | -18.4 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.m4b` | 30827 B | `audio/mp4` | `Portable.AAC` | 1.50s | 44100 Hz / 2 ch | 0.96 | -18.4 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.m4r` | 30827 B | `audio/mp4` | `Portable.AAC` | 1.50s | 44100 Hz / 2 ch | 0.96 | -18.4 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.maud` | 264688 B | `audio/x-maud` | `IffAudioDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.mid` | 86 B | `audio/midi` | `MidiSequencer` | 2.00s | 44100 Hz / 2 ch | 1.00 | -19.9 dB | **PASS** | None |
| `c_major_piano.midi` | 86 B | `audio/midi` | `MidiSequencer` | 2.00s | 44100 Hz / 2 ch | 1.00 | -19.9 dB | **PASS** | None |
| `c_major_piano.mka` | 32881 B | `video/x-matroska` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.mlp` | 85118 B | `audio/x-mlp` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.mod` | 2620 B | `audio/x-mod` | `TrackerEngine` | 7.68s | 44100 Hz / 2 ch | 0.83 | -15.8 dB | **PASS** | None |
| `c_major_piano.mp2` | 36362 B | `audio/mpeg` | `AlacAndMpeg2Decoder` | 1.51s | 44100 Hz / 2 ch | 0.90 | -18.5 dB | **PASS** | None |
| `c_major_piano.mp3` | 37660 B | `audio/mpeg` | `Portable.MPEG` | 1.50s | 44100 Hz / 2 ch | 0.86 | -18.7 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.nsf` | 180 B | `audio/x-nsf` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.oga` | 21562 B | `audio/ogg` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.ogg` | 21562 B | `audio/ogg` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.opus` | 32094 B | `audio/ogg` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.pat` | 939 B | `audio/x-gus-patch` | `SoundFontReader.PAT` | 2.50s | 44100 Hz / 2 ch | 0.40 | -19.9 dB | **PASS** | None |
| `c_major_piano.pvf` | 48016 B | `audio/x-pvf` | `SpecializedSampleDecoder` | 1.50s | 16000 Hz / 1 ch | 0.88 | -18.6 dB | **PASS** | None |
| `c_major_piano.ra` | 2699 B | `audio/x-pn-realaudio` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.rf64` | 264714 B | `audio/x-rf64` | `Wave64AndRf64Decoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.rmi` | 106 B | `audio/midi` | `MidiSequencer` | 2.00s | 44100 Hz / 2 ch | 1.00 | -19.9 dB | **PASS** | None |
| `c_major_piano.s3m` | 992 B | `audio/x-s3m` | `TrackerEngine` | 7.68s | 44100 Hz / 2 ch | 0.98 | -19.4 dB | **PASS** | None |
| `c_major_piano.sf2` | 1064 B | `audio/x-soundfont` | `SoundFontReader.SF2` | 2.50s | 44100 Hz / 2 ch | 0.91 | -14.0 dB | **PASS** | None |
| `c_major_piano.sfz` | 105 B | `text/plain` | `SoundFontReader.SFZ` | 2.50s | 44100 Hz / 2 ch | 0.79 | -25.8 dB | **PASS** | None |
| `c_major_piano.sid` | 196 B | `audio/x-sid` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.snd` | 264644 B | `audio/basic` | `SunAuDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.spc` | 66048 B | `audio/x-spc` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.sph` | 265624 B | `audio/x-nist-sphere` | `SpecializedSampleDecoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.svx` | 24100 B | `audio/x-8svx` | `IffAudioDecoder` | 1.50s | 16000 Hz / 1 ch | 0.88 | -18.6 dB | **PASS** | None |
| `c_major_piano.thd` | 85122 B | `audio/x-truehd` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.tta` | 52719 B | `audio/x-tta` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.vgm` | 176 B | `audio/x-vgm` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.vgz` | 79 B | `application/gzip` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.voc` | 132343 B | `audio/x-voc` | `VocDecoder` | 1.50s | 44100 Hz / 1 ch | 0.88 | -18.6 dB | **PASS** | None |
| `c_major_piano.w64` | 264704 B | `audio/x-wave64` | `Wave64AndRf64Decoder` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | None |
| `c_major_piano.wav` | 264644 B | `audio/wav` | `Portable.WAVE` | 1.50s | 44100 Hz / 2 ch | 0.89 | -18.5 dB | **PASS** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track) |
| `c_major_piano.weba` | 32823 B | `video/webm` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.wma` | 35744 B | `audio/x-ms-wma` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.wv` | 66333 B | `audio/x-wavpack` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |
| `c_major_piano.wve` | 12032 B | `audio/x-wve` | `SpecializedSampleDecoder` | 1.50s | 8000 Hz / 1 ch | 0.89 | -18.6 dB | **PASS** | None |
| `c_major_piano.xm` | 1532 B | `audio/x-xm` | `TrackerEngine` | 7.68s | 44100 Hz / 2 ch | 0.61 | -20.8 dB | **PASS** | None |
| `c_major_piano.ym` | 1287 B | `audio/x-ym` | `UniversalAudioDecoder` | 0.00s | N/A | N/A | N/A | **FAIL** | Decoder Android.MediaCodec failed: Android found no audio track (Android found no audio track); Decoder libVLC.PCM failed: Codec backend libVLC.PCM is unavailable (Codec backend libVLC.PCM is unavailable); Decoder returned null PCM data |

### Summary Statistics
- **Total Audio Fixtures Tested**: 76
- **Playlists Parsed (no playback claimed)**: 2
- **Successfully Decoded & Verified Healthy**: 47 / 76 (61.8%)
- **Not decoded in this JVM**: 29 / 76
