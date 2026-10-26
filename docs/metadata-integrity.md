# Transfer metadata and filesystem integrity

Implemented 2026-10-26; device acceptance pending.

Transfer warnings summarize metadata loss once per operation; detailed reasons and
affected entries remain in the diagnostic log. Moves keep affected originals.

## Shared metadata contract

`StorageProvider.readTransferMetadata` and `applyTransferMetadata` expose known
mode, numeric owner/group IDs, precise access/modification timestamps, and extended
attributes. Unknown values remain unknown. Backends which cannot import metadata
return unsupported rather than silently claiming success. The transfer engine
reads destination metadata back and compares all requested fields.

Local Android paths and supported ext2/3/4 image filesystems implement import and
export. Android permissions and the destination filesystem still determine which
attributes can actually be changed. Other providers currently export only the
fields available from their existing stat interface; full provider parity remains
pending. Program, torrent and partial-recovery exports do not invent source POSIX
attributes.

The ext adapter converts its compact ACL encoding to portable Linux ACL version 2
at the shared boundary and converts back on import. It checks ACL entry structure,
IDs, masks and access ACL consistency with the permission mode. Invalid ACLs fail
the private image mutation; they cannot be published as successful writes.

## Copy and move rules

- Capture source metadata before copying. Apply directory attributes after copying
  children so restrictive modes do not prevent population.
- Warn on unavailable exports, unsupported imports and mismatched destination
  attributes. A move retains the affected original root when its contents or
  metadata are incomplete.
- Before deleting an original after copy, recheck its captured metadata as well as
  the existing content checks. Ignore access-time changes caused by our own reads.
- Native moves within an ext filesystem retain the original inode, permissions,
  ownership, hardlinks and extended attributes. Directories containing symbolic
  links can use this path; an unavailable native move cannot silently become a
  following-links copy/delete operation.
- Preflight all requested partition relocations before changing entries. A move
  across partitions falls back to checked copying and source removal, with source
  retention on metadata loss. LVM mutation remains unavailable.

The ext exporter refuses generic copying of non-directory hardlinked inodes and
files with behavioral flags it cannot portably import. This prevents a generic
move from destroying their topology or silently dropping immutable/append and
related behavior. Native inode moves retain those attributes.

## Integrity boundaries and remaining work

Ext writes continue to use private snapshots, supported journal replay and
transactions, checksum checks, clean unmount and checked image publication. See
[ext image writes](ext-image-writes.md). Unsupported configurations remain blocked.

Pending work:

- Full metadata export/import for network, SAF and archive providers; explicit
  identity mapping where numeric ownership has different meaning.
- Portable hardlink topology, inode behavioral flags, creation times, DOS flags
  and broader security-label/ACL interoperability.
- More damaged-metadata, extended-attribute and full-volume coverage, plus handset
  acceptance of restrictive directory modes and metadata failure/source retention.
- Quotas/project IDs, MMP, external journals, fast commits, pending orphan cleanup
  and other currently rejected ext configurations.
- Writable LVM and volume resizing only after validating mappings, redundant
  metadata, checksums, partition overlaps and backup partition tables.

These boundaries are deliberate. The current implementation does not claim
support for every filesystem integrity or volume-management feature.

## Validation

The continuing ext integration run passed all five tests, including metadata
round-trip/import, canonical POSIX ACLs, inline and external-block extended
attributes, precise timestamps, native move retention, inconsistent ACL rejection
and filesystem checks on disposable images. Log: `build/metadata-integrity-tests.log`.
No new torrent tests were run for the finalization fix. The combined debug APK
built successfully and opened with `xdg-open`; handset acceptance remains pending.
