# Documentation

Start with the current separation records:

- [Android build commands and current test owners](android-builds.md)
- [Architecture, component ownership and dependency graphs](architecture.md)
- [Architecture audit, repository inventory and executed verification](architecture-audit.md)
- [Storage API behavior and device acceptance matrix](storage-validation.md)
- [Production source/resource inventory](validation/separation-inventory.json)
- [Executed app builds and APK inspection](validation/separation-app-builds.json)
- [Build script verification](validation/separation-build-scripts.json)
- [Android descriptor probe results](validation/separation-storage-runtime.json)

Build commands belong to each repository's README. The four applications are
independent repositories; this checkout builds Infinifolder and its declared
libraries only. The `scripts/verify-standalone.py` harness checks fresh GitHub
checkouts in separate temporary parents and rejects project/source roots outside
each checkout.

Other feature documents describe format behavior, UI decisions and historical
validation. [The stream migration record](infinistream-migration.md) preserves the
older checkpoints and their exact limitations. Older paths and build counts in
those historical records do not replace the current ownership/build instructions.

Optional Android `app_process` integration probes remain under `scripts/`. Media
and document probes accept explicitly supplied built artifacts through
`INFINIMEDIA_APK`, `INFINIMEDIA_CLASSES_JAR`, `INFINIMEDIA_VIDEO_CLASSES_JAR`,
`INFINISCRIBE_APK`, and the corresponding `INFINISCRIBE_*_CLASSES_JAR` variables.
These integration probes are separate from standalone builds and require an
Android runtime. They do not implicitly read another application's source tree.
