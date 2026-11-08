# SFTP native transfer strategies

`StorageProvider` exposes contextual `StorageTransferCapabilities` and execution
hooks for a native move and a server-side regular-file copy. The routing layer only
delegates when source and destination have the same provider ID. The provider must
still establish a shared authenticated session and operation-specific support;
matching URI schemes are not sufficient.

## Strategy selection

| Operation | Condition | Strategy |
|---|---|---|
| Move | Same SFTP connection ID and source/destination filesystem IDs match, or filesystem identity is unavailable | `NATIVE_MOVE` using SFTP v3 `SSH_FXP_RENAME` |
| Move | Filesystem IDs differ, or rename reports an explicit cross-device failure | `COPY_AND_DELETE`; use `copy-data` for eligible files, then verify destination and recheck source before deletion |
| Copy | Same connection ID, regular file, and advertised `copy-data` version `1` | `SERVER_SIDE_COPY` |
| Copy or move | No usable server operation, including different saved SFTP connections | Existing bounded streaming path; moves use `COPY_AND_DELETE` |

SFTP uses `statvfs@openssh.com` version `2` when advertised to compare filesystem
IDs. If unavailable, it attempts a native rename. Only an explicit cross-device
error enables the alternative copy path; permission, invalid-path, and general
server errors do not. `SSH_FXP_RENAME` is sent without the POSIX overwrite extension,
so an unrelated destination cannot be replaced by a race after the collision check.
`posix-rename@openssh.com` is detected and exposed in capabilities when the
filesystem is known, but execution uses the v3 no-overwrite request because the
POSIX extension replaces existing destinations and transfers have no user-approved
overwrite mode.

`copy-data` handles one remote file at a time. Recursive directories continue through
the common listing/planning path; eligible regular files are copied on the same
server/session. The backend opens remote handles and sends the extension request with
64-bit length/offset fields. A unique exclusive temporary sibling is checked for
length and published with no-overwrite rename. Cancellation or an uncertain channel
failure never triggers a streaming retry. The unique temporary is removed on a fresh
channel when possible. Unsupported extension status is the only post-request result
that may fall back, after temporary cleanup succeeds.

The protocol does not return a portable content digest. Successful `copy-data`
therefore verifies server success, exact size, and source size/mtime/permissions
before publication and again checks source metadata before a move deletes it. It
does not claim SHA-256 content verification. The undo journal retains an unhashed
server-side destination if it cannot prove that its contents stayed unchanged.
Metadata preservation uses SFTP permissions and timestamps when accepted by the
server; unsupported ownership or ACL preservation is surfaced through the existing
transfer warning path.

## Tests

`StorageTransferPlannerTest` covers strategy priority, filesystem boundaries,
overlapping server capabilities, and session identity. An opt-in integration test
exercises the raw no-overwrite rename, collision preservation, UTF-8 names, and the
`copy-data` request/unsupported response against a real SFTP server:

```sh
INFINIFOLDER_SFTP_HOST=127.0.0.1 \
INFINIFOLDER_SFTP_PORT=2222 \
INFINIFOLDER_SFTP_USER=test-user \
INFINIFOLDER_SFTP_KEY=/path/to/test-key \
gradle :libinfinistore:store-android:testDebugUnitTest \
  --tests com.infinifolder.sftp.SftpRawProtocolIntegrationTest
```

Servers that do not advertise `copy-data` remain on the existing streaming path.
Cross-filesystem moves depend on `statvfs` filesystem IDs or an explicit
cross-device error string; servers that expose neither cannot be safely
distinguished from permission/path failures and do not trigger speculative copying.
