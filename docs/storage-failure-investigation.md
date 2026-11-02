# Recorded storage failure investigation

Started 2026-11-02, after completing the library migration and current icon repairs.
The owner authorized reproducing and investigating the historical failures.

The immutable pre-migration snapshot is
`validation/infinistream-baseline.json`. Its 30 failures are grouped as follows:

| Suite | Recorded failures |
| --- | ---: |
| ArchiveStorageTest | 1 |
| DiscImageReaderTest | 1 |
| FileOperationsTest | 7 |
| RecoveryTransferTest | 15 |
| TorrentTransferLifecycleTest | 1 |
| TorrentVerificationCheckpointTest | 5 |

Fresh results and diagnoses will be added here. At this initial checkpoint, no
new test result is claimed. Existing safety expectations, the failure baseline
and unrelated workspace changes are preserved. Any fixture/test correction must
describe the contract mismatch and retain meaningful failure-path assertions.

Logs and report snapshots for this work live under
`build/storage-failure-investigation/`.
