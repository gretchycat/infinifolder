# Magnet networking diagnosis and changes

> [InfiniStream architecture](infinistream_architecture_codex.md) is authoritative; [migration checkpoints](infinistream-migration.md) records implemented ownership. Dated evidence and legacy paths below describe their original revisions. Existing safety and format limits remain requirements.

## Device dump 20261026-063511-745

The metadata listener opened successfully on port 38093. The earlier address-in-use
and address-family errors are absent. Metadata still timed out after three minutes.
Tracker replies led to refused/timed-out candidate connections, including ports 0
and 65535. A DNS failure affected torrent.gresille.org. The listener-closed and
interrupted errors at the timeout are shutdown effects, not initial bind failures.

Inspection of Bt 1.10's AnnounceResponseHandler confirms that it always selects
CompactPeerInfo.AddressType.IPV4. UDP responses received from IPv6 trackers therefore
use the wrong peer-entry width. The suspect candidate addresses are consistent with
that defect, though this dump alone cannot establish that it explains every failed
peer or the absence of metadata.

## Replacement UDP tracker adapter

DualStackTrackerModule replaces the UDP factory through ITrackerService, retaining
the existing HTTP/HTTPS factories. It applies to metadata and download runtimes.

- Resolve tracker addresses and try both families within the configured timeout.
- Bind an ephemeral UDP socket matching the remote address family.
- Validate transaction IDs, response actions and minimum response lengths.
- Decode six-byte IPv4 or eighteen-byte IPv6 peer records according to transport.
- Merge valid peers, discard port zero, multicast, and unspecified addresses.
- Use one announce key across address families, and carry actual transfer counters
  when available. Unknown metadata advertises nonzero remaining data, not a seed.
- Carry raw tracker path/query in BEP 41 URLData options, bounded to 4096 bytes.
- Close all active sockets on runtime shutdown. Log host, family, hash, peer counts,
  seeder/leecher counts, interval, and failure type without peer IP addresses.

The implementation follows [BEP 15](https://www.bittorrent.org/beps/bep_0015.html)
and its transport-dependent peer widths, with URLData from
[BEP 41](https://www.bittorrent.org/beps/bep_0041.html).

A timed-out query returns failure to Bt's existing tracker scheduler; this adapter
does not add an independent retry loop. Existing metadata timeout and per-hash
request sharing remain. Remote tracker availability, peer reachability, and metadata
support are still external requirements.

Validation: debug APK builds only; no tests run. Next device dump should contain
`torrent.tracker.udp.response` and distinguish IPv4/IPv6 peer discovery from a swarm
that has no reachable metadata provider. Metadata browsing success remains pending.

## Device dump 20261026-064039-275: adapter startup regression

The newly launched app failed before tracker discovery: Guice requested
`Map<String, ? extends TrackerFactory>` while Bt binds the invariant
`Map<String, TrackerFactory>`. Both metadata and Ubuntu resume were affected.
The initial Kotlin provider used a covariant read-only map. A later suppression
annotation corrected the classfile signature, but the device dump still showed
that wildcard provider, so the injection boundary now uses a Java module with an
explicit invariant map and a new provider method name. The UDP implementation
remains Kotlin. This removes Kotlin variance from the reflective binding entirely.

This dump does not validate the new UDP parser: the earlier three-minute timeout
belongs to the old adapter; the new app's lookups failed at injector creation.
Next check is runtime startup, then `torrent.tracker.udp.response` and metadata.

## Device dump 20261026-064657-259: tracker discovery working

The Java provider starts successfully. Tracker responses now include both IPv4 and
IPv6 peers: open.stealth.si returned 10 IPv4 and 4 IPv6 peers; tracker.torrent.eu.org
returned 9 IPv4 and 3 IPv6 peers. The tested magnet still reached the three-minute
metadata timeout.

The next observed failure is peer handshaking. Connections to port 51413 negotiate
REQUIRE_ENCRYPTED and then fail with InvalidMessageException / unknown message IDs
(-21 and 56); another attempt fails to initialize after negotiation. Other candidates
refuse or time out. A successful IPv6 connection has the same port as this runtime's
listener (34441), so it may be a self-connection; endpoint redaction prevents proving
that from this dump alone. A successful socket is not proof of metadata exchange.

Do not mark magnet browsing fixed or disable encryption globally based on this dump.
Next investigation should isolate encrypted handshake/framing failures, distinguish
self-connections from remote peers, and record metadata extension negotiation.

Ubuntu resume is functioning again: 9873 verified pieces were reused without new
checkpoint saves during the startup pass, and incoming verified pieces subsequently
increased to 10016. This is independent evidence that the adapter binding regression
is resolved and saved verification was retained.

## Encrypted handshake handoff correction (2026-10-26)

Inspection of the tagged upstream implementation exposes a double-decryption path:
MSEHandshakeProcessor decrypts incoming over-read bytes, advances its receive cipher,
and compacts leftover payload into the inbound buffer. DefaultChannelPipeline then
processes that existing data through CipherBufferMutator again. This also advances
the cipher twice, corrupting later encrypted messages. The observed random invalid
message IDs after REQUIRE_ENCRYPTED negotiation are consistent with this path;
device confirmation of the correction remains pending.

MseSafeChannelPipelineFactory retains Bt's pipeline and cipher objects. At pipeline
creation it counts existing inbound bytes and skips exactly that prefix for cipher
decoders only. Future incoming bytes still pass through the original receive cipher;
outbound encryption and plaintext connections retain their original processing.
It does not disable encryption or switch to a different cipher policy. The adapter
uses a distinct class in bt.net.pipeline because its builder constructor is package
private; no library class is replaced or duplicated.

Connection-number diagnostics record encryption and buffered-prefix length, received
BitTorrent handshakes (including exact local peer-ID matches), extension support,
and metadata request/data/rejection messages. Metadata event logging is bounded per
connection. Exact self-connections are rejected before handshake completion. No raw
payload, peer ID, or peer IP is added to these diagnostics. The connection factory's
trace error path is enabled to retain underlying exceptions.

Upstream sources inspected:

- [MSEHandshakeProcessor](https://github.com/atomashpolskiy/bt/blob/bt-parent-1.10/bt-core/src/main/java/bt/net/crypto/MSEHandshakeProcessor.java)
- [DefaultChannelPipeline](https://github.com/atomashpolskiy/bt/blob/bt-parent-1.10/bt-core/src/main/java/bt/net/pipeline/DefaultChannelPipeline.java)
- [InboundMessageProcessor](https://github.com/atomashpolskiy/bt/blob/bt-parent-1.10/bt-core/src/main/java/bt/net/pipeline/InboundMessageProcessor.java)

Validation: debug APK build only; no tests run. Pending device check: encrypted
connections should produce a valid BitTorrent handshake and metadata exchange,
with buffered prefixes reported as skipSecondDecryption=true when present.

## Device dump 20261026-070914-127: metadata succeeds

The magnet received all three metadata blocks and emitted metadata.received with
five files after 16.8 seconds. A rejecting peer was bypassed in favor of a peer
providing DATA blocks. Exact self-connections were identified by the new diagnostics.
The next download client fetched those metadata blocks a second time, while the UI
misleadingly displayed checking/saving progress despite zero saved pieces.

The download client now uses the cached Torrent directly, attaching magnet tracker
URLs when the info dictionary has no announce list. This avoids the redundant fetch.
Empty startup pieces remain unknown and do not trigger hashing or verification UI.
Magnet metadata retrieval is now confirmed by device logs; successful file download
and the corrected empty-start status still require device confirmation.

## Device dump 20261026-072102-545: multi-file storage layout failure

Metadata retrieval succeeded in 14.3 seconds. The new transfer reported zero
available/trusted pieces and correctly entered the connection phase without startup
verification. Ubuntu continued receiving data concurrently. The selected multi-file
transfer then failed while building data ranges with `Insufficient data`, before any
peer download began. Its skipped storage units incorrectly advertised zero capacity,
so Bt's concatenated torrent range was shorter than its metadata. Bt swallowed the
initialization exception and completed its processing future normally; the app then
misreported this as a three-minute no-data/swarm timeout.

Skipped storage now uses TorrentBoundaryStorageUnit with each file's real virtual
capacity. It retains only bytes in pieces intersecting the selected file, with a
presence bitmap so unwritten bytes cannot be read as zeros. These neighboring bytes
are required for checksums spanning file boundaries. It creates no unselected files
and stores no whole download in cache. Sparse 64 KiB memory blocks are bounded to
64 MiB of byte/presence storage across skipped units in one transfer; exceeding that
limit fails explicitly instead of silently evicting bytes needed for verification.
The buffers are released on shutdown and are not persistent; boundary pieces lacking
neighboring data must be downloaded again on resume, while interior trusted pieces
remain reusable.

Download completion futures now end the wait immediately when processing stops
before the selected file completes, even if Bt returns without an exception. The
processing diagnostics retain the root error instead of blaming peer availability.
This applies to direct and external-reader download paths.

Validation: debug APK build only; multi-file copying through to completion still
requires device confirmation. No Clear completed event appears in this dump, so
history purging has not been validated from these logs.

User subsequently reported that the multi-file transfer now appears to be
downloading. Download startup is user-confirmed; full completion remains unconfirmed.

## Device dumps 20261026-073705/073706: resume had no connected peers

The metadata lookup succeeded in 16.4 seconds, and the selected download restored
13 trusted pieces (54,525,952 selected bytes). Its processing engine initialized
correctly and remained running, but connectedPeers and downloaded bytes stayed zero
until the three-minute no-data timeout. Ubuntu continued receiving data in parallel.
The selected download queried only the first successful multi-tracker endpoint,
opentrackr, whose returned IPv4 candidates refused or timed out. Metadata retrieval
had also queried other trackers and reached a usable peer. Separately, every fallback
refresh attempted to pass udp:// URLs into OkHttp, which immediately rejected them.
The failure was peer discovery/reachability after a valid resume, not lost checksums.

Public-torrent downloads now register each UDP tracker as an independent peer
source, using the existing dual-stack implementation and Bt's interval scheduler.
The public client descriptor suppresses its duplicate first-success aggregate
announce list; the original list remains available to explicit discovery. HTTP/HTTPS
fallback requests are filtered by scheme, so UDP never reaches OkHttp. Private
client descriptors retain the original tracker/tier behavior.

Up to 64 connected peers from successful metadata retrieval are retained in memory
and offered to the download runtime after initialization. Fresh Peer objects avoid
carrying another runtime's mutable connection state; only valid listening ports and
non-self peer IDs are retained. Logs record hint counts, not endpoints. They are
hints, not guarantees that a peer will remain available or serve file content.

Metadata-only clients also stop naturally once all files are skipped. The owner
allows their completion future up to two seconds to unwind before explicit teardown,
reducing shutdown races observed as RejectedExecutionException after metadata was
already received. Waits run on background workers, not the UI thread.

Validation: debug APK build succeeded and APK opened; device resume retry pending.
No downloaded data or checksum cache needs to be cleared.

## Handset confirmation (2026-10-26)

The user subsequently confirms BitTorrent and magnet-link downloads and resuming
working correctly. This supersedes the pending normal-download/resume retry above;
the older dumps remain historical evidence of the diagnosed failures. It does not
establish every swarm, private tracker, torrent variant, or failure-recovery case.
