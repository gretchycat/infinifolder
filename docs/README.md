# Infinifolder documentation

These pages document Infinifolder's file manager, storage backend/provider,
format handling, and integration boundaries.

## Start here

- [Application description and implementation status](APPLICATION.md).

- [Infinifolder architecture and storage ownership](architecture.md)
- [Standalone build and test guide](android-builds.md)
- [Companion applications and Android handoff](viewer-modules.md)
- [libinfiniselect storage boundary](libinfiniselect.md)
- [Storage validation and device acceptance](storage-validation.md)
- [Directory Properties failures and validation](directory-properties.md)
- [Runtime capability discovery](runtime-capabilities.md)
- [Implemented file formats](file-formats.md)
- [Visual media fixture corpus and Android render harness](../filesystem-test-images/images/README.md)

## Companion app documentation

Each application owns its detailed behavior and build instructions:

- [Infinimedia documentation](https://github.com/infiniforge/infinimedia/tree/main/docs)
- [Infiniscribe documentation](https://github.com/infiniforge/infiniscribe/tree/main/docs)
- [Infinikey documentation](https://github.com/infiniforge/infinikey-ime/tree/main/docs). It shares branding only and does not use `libinfiniselect` or Infinifolder's storage backend.

Infinifolder's `docs/history/` and dated validation reports preserve historical
implementation records. They do not replace current app-owned documentation or
standalone build instructions.

## Shared ecosystem documentation

- [InfiniForge ecosystem map](ECOSYSTEM.md) — generated overview; synchronized from the organization manifest.
