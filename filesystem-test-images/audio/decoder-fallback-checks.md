# libVLC fallback and FluidR3 checks

These are synthetic, deliberately named checks; they contain no owner recordings.

- `c_major_piano.wma` and `.ra` should play their actual encoded audio using the
  libVLC fallback where the other registered backends cannot decode them.
- `90-fluidr3-piano.mid`, `91-fluidr3-flute.mid` and `92-fluidr3-drums.mid` play
  through the portable synthesizer by default. A user-imported SF2/SF3 bank
  enables FluidLite GM programs and channel-10 percussion.
- `95-unreadable.wma` and `96-unreadable.ra` are intentionally invalid. They must
  produce no replacement audio and must remain in the queue with `--` duration.
- Open `97-skip-unreadable.m3u`: invalid entries should skip silently, and the
  WMA, RA and MIDI entries should play in order.
- Open `98-all-unreadable.m3u`: it should stop without looping, keep both entries,
  and show unknown lengths. No error toast is expected for skipped file failures.
- Repeat from `99-fluidr3-and-skip.zip` for archive-backed reads and playlist paths.

Regular MP3/FLAC seeking and gapless playback should retain their existing behavior.
