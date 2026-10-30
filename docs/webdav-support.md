# WebDAV provider

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

Updated 2026-10-24. WebDAV support is implemented in `storage-engine` and available through the protocol-specific **New → Link** setup. The user confirmed WebDAV working. A saved connection appears on Virtual root as a container link and in the navigation tree. Credentials use a separate encrypted vault; passwords in a URL are rejected.

## Implemented

- HTTP and HTTPS collection URLs, Basic authentication, saved username/password, initial collection path, connection test and connection editing.
- DAV `PROPFIND` depth 0/1 for metadata, stat and folder listing; `GET`/`PUT`; `MKCOL`; `DELETE`; and `MOVE` for rename. New file creation uses `If-None-Match: *`; PUT uploads stream in chunks.
- Read-only random access is enabled only when a byte-range probe receives HTTP 206 with a valid `Content-Range`. Each range read checks the server continues honoring ranges and the shared range cache coalesces reads. Writable random access is unsupported.
- Standard shared transfer logic handles copy, cross-device move, collision checks, verification, progress and cancellation using stream reads/writes. Native symbolic links and server-side COPY are not exposed.
- Each saved connection has a distinct device identity. HTTP is permitted for compatibility, with an in-dialog warning that Basic credentials are unencrypted; HTTPS is recommended.

## Server-dependent behavior and known limits

Servers differ in DAV class/version, authentication policy, collection URL conventions, conditional PUT, chunked upload, method permissions, quotas, redirects and range support. The provider reports HTTP response codes and response text when available; a successful test only confirms an authenticated `PROPFIND`, not every mutation method. Some servers require a collection URL ending in a slash or a specific DAV path.

Only Basic authentication is currently implemented; Digest, bearer/OAuth, client certificates, proxy configuration, DAV locks/versioning, quota discovery, native server-side COPY, and WebDAV-specific permission/ACL fidelity are not implemented. Redirected endpoints have not had broad real-server review. Cleartext HTTP should be avoided whenever possible.

## Acceptance checklist

- [ ] Broader acceptance against at least two real implementations (for example, Nextcloud and Apache/nginx DAV), including HTTPS and a controlled HTTP server.
- [ ] Verify create, nested listing, read, streamed large write, collision refusal, rename, recursive delete, copy, and cross-device move; confirm overwrite never occurs during create.
- [ ] Check a range-capable server and a server that ignores Range; ensure fallback/unsupported reporting is clear and container/image browsing works only with real byte ranges.
- [ ] Exercise URL base paths, encoded filenames, Unicode, spaces, redirects, auth failures, expired credentials, read-only accounts, quota/space errors, cancellation and server disconnects.
- [ ] Confirm edit-connection and remove-connection behavior from both Virtual root links and saved connection actions.

WebDAV connection and browsing are user-confirmed. The broader server-specific mutation, range and failure checks above remain open. Current project priorities are in [project status](project-status.md).
