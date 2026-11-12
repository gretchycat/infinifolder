# Static SVG and audio associations — 2026-11-11

## Architectural changes

- **Canvas:** Removed the custom SVG palette/cycle parser, per-frame vector renderer, cached raster palette renderer and their tests. SVG/SVGZ now use ordinary static AndroidSVG rendering. The in-memory artwork API preserves intrinsic document dimensions, aspect ratio and its existing overloads. Viewer thumbnails remain static. The cache content revision was bumped to invalidate old generated previews. Custom SVG metadata is ignored; standard fallback paints determine the still image.
- **Shared UI:** Removed SVG-specific frame pacing hooks. Existing IFF and other image animations still use lifecycle-aware playback, with rendering/publication included in a 33 ms deadline. Still-only sampling retains exact initial IFF/BMP pixels.
- **Infinimedia:** Removed its SVG-specific pacing hook and obsolete SVG benchmark/override options. Its own runtime probe now checks static SVG/SVGZ, unchanged IFF playback and packaged audio intent filters. The corrected IFF default theme, VIDEO/GEN visual frame, attribution, supported codecs, compatibility fixtures and optional SF3 source are preserved.
- **Infinifolder:** Its late companion-default migration previously recognized only removed document viewers. It now also recognizes its removed AudioPlayerActivity/VideoPlayerActivity and migrates those saved choices to the installed Media entry point. The startup migration continues to support both media and documents.
- **Saved defaults:** Companion initialization stores extension rules. These were more specific than a later user-selected “All files of this type” rule, so the old rule kept winning. Saving a type-wide choice now replaces extension defaults for the same MIME type, plus any override for the current file. Other MIME types and other files' explicit preferences remain intact. New tests cover these cases and removed-viewer ownership.
- **Dependencies:** Updated Canvas/UI source pins in Folder, Media and Scribe, including Folder's library gitlinks. No application dependency or library edge was added. Infinikey and the storage interface are unchanged. See the [complete graph and repository ownership](architecture.md) and [repository inventory](architecture-audit.md).

The original SVG source and Folder's SVG-to-IFF authoring script remain available. IFF CRNG/CCRT/DRNG color cycling remains supported. Existing obscure formats and their implementations were preserved.

## Audio registration and defaults

The built Media manifest already inherits `audio/*`, `video/*` and specific audio/playlist MIME types from the linked capability catalog. Its exported activity has ACTION_VIEW and CATEGORY_DEFAULT. Registration itself was not missing. The runtime probe reads the APK's binary manifest and uses Android's IntentFilter matcher to verify 14 combinations: content/file URIs with opaque IDs and MP3, FLAC, Ogg, WAV, vendor audio, video and playlist MIME types.

The phone's package manager also lists installed Infinimedia for a content-URI MP3 request. The resolver, rather than a selected default, was returned for that request. No Android default was changed by these checks.

After installing the updated Folder and Media APKs:

1. In Folder, choose **Open with…** on an audio file.
2. Enable **Remember in InfiniFolder**, choose **All files of this type**, then select Infinimedia.
3. For a system-wide association, open the file through Android's normal resolver and choose Infinimedia / **Always** when offered. A sender using Intent.createChooser forces a one-time choice. The app-info supported-links screen does not provide a general music-player role. See [Android intent resolution](https://developer.android.com/guide/components/intents-filters).

Folder's saved choices remain local to Folder; neither app silently changes system defaults.

## Executed verification

| Component | Build | Unit tests |
| --- | --- | ---: |
| libinfinicanvas | Standalone debug AAR | 90 |
| libinfiniui | Standalone ui-android + ui-image debug AARs | 125 + 5 |
| Infinimedia | Independent build.sh / debug APK | 80 |
| Infiniscribe | Independent build.sh / debug APK | 17 |
| Infinifolder | Independent build.sh / debug APK | 146, including 91 native storage cases |
| Infinikey | Unchanged; not rerun in this follow-up | Earlier result remains historical |

All executed builds/tests above passed. Each application also passed 11 build-environment regression cases. These were incremental reruns in the user's existing independent checkouts, with SDK environment variables unset, no local.properties, source-root guards and lock checks. No new fresh clone is claimed for this follow-up. Each of the four application repositories still has its own build.sh; those entry points and fresh-checkout setup are described in [Android builds](android-builds.md).

All **13 real Android runtime groups** passed against the independently built Media APK: static SVG/SVGZ and ignored cycling metadata; intrinsic dimensions/aspect ratio; preserved user SVG as a still; IFF/BMP still parity; IFF cycling and full opacity; signature detection; layered composition and bitmap lifetime; full visual frame at two scales; packaged default-capable audio intents; actual saved-rule JSON/ComponentName roundtrips in isolated in-memory preferences; both controller deadlines; and cache pause/resume/clear. The deadline simulations included 20 ms render work and measured 33 ms median intervals for both controllers. Media's three packaging tests also passed.

APK inspection confirms the removed SVG classes are absent from all three applications, Media/Scribe contain no advanced Folder storage implementation, and Folder/Scribe contain no complete Media player. Media packages only skins/Infini.wsz; other skin archives and SF3 are outside production packages. Compared with the preceding SVG experiment, Media is 18,763 bytes smaller and Scribe 18,389 bytes smaller; Folder is 159 bytes larger after its migration fix. These are small changes, not a major size optimization.

[Executed evidence, exact pins, APK hashes and test suites](validation/static-svg-audio-defaults.json). The [previous SVG performance experiment](svg-cycle-performance.md) remains archived and is marked superseded.

## Pushed implementation commits

| Repository | Commit |
| --- | --- |
| infinimedia | [f3d7bea](https://github.com/gretchycat/infinimedia/commit/f3d7bea6ef61b7227e416edb7c717042320f8a51) |
| infiniscribe | [6ac4aea](https://github.com/gretchycat/infiniscribe/commit/6ac4aea32580aaaa4d45675749e96717d37af398) |
| infinifolder | [96fdbce](https://github.com/gretchycat/infinifolder/commit/96fdbce6ab75d73f8c9d3c257cadcf3b670393f7) |
| libinfinicanvas | [1ae99a8](https://github.com/gretchycat/libinfinicanvas/commit/1ae99a8c5bc5a53cdda4d64fca6be55e143c126a) |
| libinfiniui | [c68feac](https://github.com/gretchycat/libinfiniui/commit/c68feacbcbabf2d10d2a89f7e72d52af3c3c5887) |

All five existing repositories were retained. No history was rewritten and no repositories were created. Documentation follow-up commits add this report and verification checkpoints.

## Verification limits

The headless Android probe does not establish visible chooser/Always interaction, Activity layout/touch, audible playback, Android storage grants or video-surface behavior. Those still need device UI acceptance. The package-manager query inspected the previously installed app; the probe used the newly built APK. Ubuntu testing, other-platform ports, TODOs and roadmaps remain deferred. User untracked assets and backups were preserved.
