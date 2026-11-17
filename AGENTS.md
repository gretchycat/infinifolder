# Infinifolder agent guidance

Infinifolder is Infiniforge's central file manager and advanced storage layer. It owns its Android application, file browsing, storage providers, and its own installable/buildable project. It is useful as a companion to other Infiniforge apps, but it is not a build or runtime prerequisite for them.

## Independence and integration

- Build this repository from its own checkout with its pinned library dependencies and documented Android toolchain. Do not depend on sibling application source, build outputs, or workspace-level paths.
- Keep this app's Gradle build independent. Include reusable libraries through their declared public APIs; never include Infinimedia, Infiniscribe, Infinikey, or a future app as a project dependency.
- Keep media playback and broad document viewing in their companion apps. Infinifolder may provide its documented in-app inspectors and hand off supported content using Android intents, content URIs, and grants.
- Expose storage to other apps through documented Android interfaces, including SAF/DocumentsProvider and supported library contracts. Preserve grant checks, opaque source identities, and URI permissions.
- Companion apps must continue to work when Infinifolder is absent. Keep their source and data private to their own application.

## Project focus

- Treat this app as the suite's file layer and file manager: browsing, storage access, file operations, and safe handoff of user-selected resources.
- Keep storage backend and format support boundaries explicit. Do not claim a format or write path is supported unless the implementation and its documented limits agree.
- Preserve existing data-safety guarantees, cancellation behavior, source permissions, and documented read-only/write limitations.
- Maintain this repository's standalone build instructions and dependency pins when changing modules or linked libraries.

## Ecosystem workflow for agents

1. Read `docs/ECOSYSTEM.md` first to understand the component map and ownership boundaries.
2. Before changing a public interface, read this repository's architecture and API documentation listed below and inspect its compatibility notes.
3. Check the owning shared library repository before implementing behavior that may already exist elsewhere. Do not conclude a capability is unavailable just because its implementation is outside the current repository.
4. Keep application responsibilities, shared library APIs, and other applications' private data/source separate.
5. Before a cross-repository change, check the exact dependency pin and the relevant interoperability contract. Dependency versions and protocol versions are separate; do not infer protocol incompatibility from a library-version mismatch.
6. Update the local documentation index and relevant API/behavior documentation when public behavior changes. `docs/ECOSYSTEM.md` is generated from the central manifest and must not be edited locally.

## Documentation index

- `README.md` — project purpose, prerequisites, and standalone build instructions.
- `AGENTS.md` — repository-specific development rules and this ecosystem workflow.
- `docs/README.md` — local documentation index.
- `docs/ECOSYSTEM.md` — generated ecosystem-wide inventory, boundaries, dependencies, versions, and protocols.
- `docs/architecture.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/libinfiniselect.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/runtime-capabilities.md` — public API and architecture documentation; read before changing interfaces or behavior.