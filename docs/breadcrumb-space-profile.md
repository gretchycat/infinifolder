# Breadcrumb disk space profile

The active folder breadcrumb shows a compact disk space profile beneath its path
name in the main browser and in the shared floating/embedded browser. The profile
uses the backing filesystem's reported capacity: used bytes are total bytes minus
available bytes, with the percentage calculated from that total. For example,
`23.4 GB / 128 GB (18%)`.

The profile layers bold colored text over a bold stroked copy of the same text,
keeping both layers' font metrics aligned. The outline is black at 50% alpha in
light mode and white at 50% alpha in dark mode. It is green at 75% or less,
yellow above 75% through 90%, and red above 90%.

This is a filesystem capacity summary, not a recursive size calculation of the
current directory. It is loaded in the background for the selected path. The
profile appears only when that storage provider reports both available and total
bytes; unsupported or unavailable capacity information leaves the breadcrumb
without a second line. Older results are discarded after navigation.

Local and Android external-storage paths obtain capacity from `StatFs`. Mounted
filesystem providers may report their own accounting when the format exposes it.
Provider permissions and source identities remain authoritative; this display
does not probe directory contents or expand access to neighboring resources.
