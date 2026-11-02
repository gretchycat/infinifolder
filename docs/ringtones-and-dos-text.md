# Ringtones and the CP437 text viewer

## Audio

- MMF/SMAF: source-built SMAF score/FM/embedded-sample backend in Sound, selected
  by the `MMMD` signature. The ROM-free preset voices approximate original handset
  timbres; this is not bit-exact Yamaha emulation. Unsupported MA-7 streaming,
  some bulk wave banks and higher waveshapes remain outside the engine's support.
- 3GP/3GPP/3G2 audio tracks: Android decoding and the existing FFmpeg fallback.
  Files containing video retain video routing. Audio MIME inputs and explicit
  audio playback can use their audio tracks. The existing `.3ga` alias remains.
- Guitar Pro 3 (`.gp3`): the GP3 reader/score sequencer in the separate optional
  `audio-guitarpro` module feeds Sound's existing MIDI synthesizer and SoundFont.
  Recognition uses the version header; GP4/GP5/GPX are not newly advertised.
- ANSI Music: BASIC PLAY-style BBS music strings extracted by Scribe are played
  with Sound's mono PC-speaker-style square-wave synthesizer. Supports tempo,
  octave, default/explicit note length, A–G accidentals, numeric notes, rests,
  dotted durations and normal/legato/staccato articulation. Classic `CSI M`,
  BananaCom `CSI N` and `CSI |` strings end at Ctrl-N. Music plays on request in
  the viewer; this static document view does not time an animated BBS session.

Source references and full licenses are retained beside the imported engine and
reader. SMAF engine: [upstream](https://github.com/akustikrausch/yamaha-smaf-player),
revision `627f7ad8583ce34c5fec0269ef9778167af080d6` (Apache-2.0).
GP3 subset: [TuxGuitar](https://github.com/helge17/tuxguitar), revision
`74d67f3681322fddf7462a36450f56c80295450b` (LGPL 2.1). Both are source builds;
no proprietary ROM/SDK or opaque binary was added. The
[CTerm reference](https://syncterm.net/cterm.html#_ansi_music) defines the ANSI
Music variants used here. New components' licenses are also packaged in assets.

## DOS text

Every file opened by the text viewer receives a bounded 16 KiB encoding probe.
A positional source keeps its cursor and random-access capability; a sequential
source replays its prefix. Detection never downloads a whole file to identify it.
Unicode BOMs and valid multibyte UTF-8 take priority. Repeated valid ANSI control
sequences, CP437 box/line runs, OEM letter patterns and SAUCE metadata provide
content evidence independent of the filename. DOS text extensions and an 8.3
name combined with DOS line endings/EOF are secondary clues. Single-byte encodings
cannot always be distinguished; the text-source encoding picker includes manual
CP437 and Automatic options.

`FILE_ID.DIZ`, `.asc` and `.nfo` have explicit text MIME mappings, including local
shared `content://` types. ANSI retains its visual MIME for icon generation but
opens through the text viewer. Bitmap icon previews remain separate from the
selectable document surface; DOS text extensions can also generate icon previews.

Detected DOS text opens in the dedicated CP437 viewer:

- Fixed **80 columns**, a real selectable CP437 typeface, VGA/extended ANSI colors,
  cursor positioning, erase controls, saved cursors, tabs, CR/LF and DOS EOF.
- Native Android TextView selection, ScrollView/HorizontalScrollView panning,
  ScaleGestureDetector pinch zoom, zoom in/out buttons and a fit-width action.
  Zoom changes the cell size and preserves 80 columns; it never reflows the art.
- Long documents are native text, not a huge full-document bitmap. Limits are
  4 MiB source bytes, 65,536 rows and 262,144 nondefault style runs. These are
  explicit failures, not successful truncated documents. Text source remains
  available for documents beyond the terminal-view limits.
- Music can be started/stopped in the viewer. Output uses Android MediaPlayer
  and audio focus, stops when the viewer leaves the foreground and cleans up
  its generated PCM cache file.

The typeface is generated from Canvas's existing CP437 glyphs (public-domain font8x8 plus project-licensed supplements) by
`libraries/libinfinicanvas/scripts/generate-cp437-typeface.py` (fonttools). Its
8×16 cell geometry matches the icon glyphs. The glyph source/provenance and
reproducible font generation script accompany the generated asset.

## Inspected owner files and build status

Internal storage `Ringtones` contains ten MMF files, four 3GP files and one actual
Guitar Pro 3 file. MMFs use Mobile Standard score tracks; `newname.3gp` contains
AAC, and the other three clips contain AMR plus H.263. `fly_by_night.gp3` has the
Guitar Pro 3 version header. Source files were read only and are not copied into
version control. The system ffprobe executable could not load its dependency;
3GP codec identities were inspected directly from their container sample entries.

Debug APK builds pass for the ringtone changes and the dedicated CP437 viewer.
No new tests or interactive/audible playback checks were run for these additions.
The prior migration regression record is historical and is not presented as
coverage for the new features. App/text build log:
`build/ringtones-cp437-release-check.log`. Standalone image/audio build logs:
`build/cp437-image-host-build.log` and `build/ringtones-audio-host-build.log`.

Local library checkpoints: Sound `c1ecfd2`, Scribe `7f8428a`, Canvas `459b458`,
Store `4d8990c`. The host pins these commits; remote publication is pending.

## Viewer and icon follow-up

The CP437 root applies status/navigation bar and display-cutout insets. Text icon
previews are selected from resource metadata rather than the URI extension and
read at most 16 KiB, showing the first 25 rows. Ordinary Unicode text uses native
monospace rendering; DOS/ANSI text uses CP437 glyphs. Existing custom icons retain
priority. Generated text-art cache signatures are versioned to refresh old results.
App and text-host build log: `build/cp437-insets-icons-build.log`. These fixes have
not been visually checked on a device.

The generated CP437 font now preserves each glyph's left bearing rather than
forcing it to zero. In particular, U+2590 (`0xDE`, right-half block) occupies
x=256..512 of the 512-unit cell with a 256-unit left bearing; U+258C occupies
x=0..256. This also preserves the spacing of other glyphs with blank left
columns. Rebuilt font version: 2.0. App/text-host build log:
`build/cp437-glyph-bearings-build.log`; no on-device rendering check was run.
