# Implementation status and open issues

Updated 2026-10-24. The [project status](project-status.md) is the authoritative confirmation/acceptance matrix; the [feature list](feature-todo.md) is the working agenda.

## Completed foundations

Provider-neutral operations and mounts; independent viewer/editor modules; shared MIME/icons/packages and link handling; safer replacement/recovery; writable ZIP/TAR and supported compressed/FAT containers with batching; recovery fixtures; network transfers; drag/drop and hover targets; nested menus, selection/sorting and pins; Virtual root container links; disk usage; operation feedback; shared disk cache and external-edit sessions.

The user confirms FTP, SMB opening, FAT16 batch performance, general format browsing, FAT/partial recovery, tree drops/autoscroll, pane switching, two-finger selection, exit-selection actions, root links/root fixes, notifications, automatic dialog dismissal, debug toolbar placement and the latest loading-light behavior. These are not currently reproduced blockers.

## Active acceptance priorities

1. External-edit conflict and interruption cases; cache quota/LRU/expiry/copy-or-raise behavior.
2. Image-viewer Back and latest remote breadcrumb ancestry correction.
3. Transfer window sizing/progress/verification controls, pins/templates and selected-set/backend regression coverage.
4. Compressed destination low space, FAT12/FAT32 batches, retained-file hashes and individual format variants.

## Architectural gaps

Mounted entry capability/cache consistency; session/device cleanup; shared action extraction; random access in every applicable viewer; full case/link/metadata fidelity; durable operation queues and general pause/resume/retry. Transactions cannot provide distributed locking against another writer on backends without conditional writes. Editable working copies are retained after failures; they are not disposable cache or full version history.

## Performance and historical evidence

FAT16 copy/delete improvements are accepted. Performance is still backend/workload-dependent, especially large image catalogs, slow SAF/USB calls and non-ext enumeration. Do not infer that every FAT family or network route is now equally fast. See [batch measurements and discrepancies](container-batching.md), [incremental image browsing](image-directory-browsing.md) and [UI responsiveness](ui-responsiveness.md).

Earlier failures and revision-specific build/test evidence remain in the [original audit](code-consistency-audit.md), [dump follow-ups](device-dump-follow-up.md) and individual format documents. Their dated pending statements are historical unless retained in the current task list.
