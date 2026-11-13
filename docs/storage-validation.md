# Storage boundary validation

The client boundary separates a resource's authoritative URI from its current
Android content URI. Infinifolder owns backend routing and credentials;
libinfiniselect returns both values, resolves authoritative locations lazily,
and handles relative locations without requiring client-side backend parsing.
The library has no InfiniSuite implementation dependency.

## Automated coverage

The application unit tests exercise proxy read completion, EOF, stalled sources
and invalid ranges. Existing provider tests cover guarded saves, revisions,
capability validation, stream closure and backend/container operations. Standalone
builds validate that consuming apps cannot link Infinifolder implementation types.

libinfiniselect also supplies StorageAccessDeviceTest. It exercises its own real
ContentResolver provider, reopening and seeking an owned descriptor, Android tree
intent flags, and rejection of persistence for temporary references. Assemble with:

```sh
./gradlew :select-android:assembleDebugAndroidTest
```

Run on an attached Android device with:

```sh
./gradlew :select-android:connectedDebugAndroidTest
```

An instrumentation APK building successfully does not mean its tests ran.

## Required device acceptance

Repeat on supported Android versions with both applications installed, then with
Infinifolder absent/disabled. Use a third-party SAF client as well as InfiniScribe
and InfiniMedia. The execution status of these checks belongs in the audit report;
this checklist is not evidence of execution.

| Scenario | Expected behavior |
| --- | --- |
| Single/multiple read selection | Content and authoritative URIs are returned separately; every selected item remains independently openable |
| Playlist persistence | Authoritative URIs are serialized; relay content URIs never become playlist locations |
| Relative playlist entries | Resolve against the playlist's authoritative location, including remote and nested archive paths |
| Reopen after relay expiry | Request a fresh content URI from the saved authoritative URI |
| Directory selection | Standard tree URI and only the granted read/write/prefix/persist flags |
| Editable selection/create | Write through granted descriptor/stream, reopen to verify exact bytes |
| Infinifolder absent or floating overlay unavailable | SAF remains usable without loading storage engines into the client |
| Local and seekable remote/image entry | Native proxy descriptor supports nonsequential reads without complete materialization |
| Sequential/compressed entry | Reliable pipe remains a stream; seek probe reports unsupported |
| Native write unsupported | No silent downgrade to a read-only or staged random-access handle |
| Open/seek/read/write failure | Provider error is observable, including failures after stream open |
| Consumer closes early | Worker closes its backend stream; proxy release closes its device and callback thread |
| Concurrent open/close | Bounded handles; a failed open releases its allocation |
| Process recreation | SAF document IDs persist; credentials remain in Infinifolder; backend may require reconnection |
| Revocation/expired relay | Current content access fails; resolve a fresh link from the retained authoritative URI |
| Malicious requesting-package extra | URI grants use the framework caller identity |
| Save request with unauthorized source | Caller cannot use Infinifolder as a read-permission deputy |
| Tree traversal across roots | isChildDocument rejects an unrelated document ID |
| Loading directory | Cursor notification causes SAF to requery pending listings |
| Archive/image create and modification | Backend capabilities and existing guarded publication/recovery remain authoritative |

Directory references used for grouping/navigation hints confer no sibling-file
permission. To browse them, request an explicit directory/tree grant. Floating
read relay references and persistent SAF references have different lifetimes.
Neither the library nor proxy bridge copies a whole file to emulate seeking.
Document renderers and codecs may explicitly stage data when their APIs need it;
those policies belong to the consuming application/library.

Proxy descriptor callback semantics follow Android's
[ProxyFileDescriptorCallback contract](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback).

## Executed local Android descriptor probes

The audit ran `scripts/verify-storage-runtime.py` through Android app_process64
without installing an APK. Six checks cover native seek, positional reads,
nonseekable reliable pipes, producer error delivery, consumer-close delivery,
and repeated descriptor closure without a leak. The audit records the exact APK
hash. The script accepts `--select-jar` for the compile artifact from an isolated
checkout; its runtime classes still come from the supplied APK.

These probes do not exercise Folder's actual proxy callback, backend connection,
picker UI, cross-UID permission grants or a third-party caller. Unit tests cover
proxy read completion and access-mode classification, including write-only
descriptor flags with the read-access bit disabled. Full device acceptance remains the table above.
