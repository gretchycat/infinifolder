#!/usr/bin/env python3
"""Export the static format/routing inventory; this is not a codec acceptance test."""
import csv
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STORE = 'libraries/libinfinistore/store-android/src/main/java/com/infinifolder/'
VAULT = 'libraries/libinfinivault/vault-android/src/main/java/com/infinifolder/archive/'
MIME = STORE + 'mime/MimeRegistry.kt'
ARCHIVE = STORE + 'archive/ArchiveFormats.kt'
IMAGE = 'viewer-common/src/main/java/com/infinifolder/preview/ImageFormats.kt'
MEDIA = 'viewer-common/src/main/java/com/infinifolder/viewer/MediaTypeDetector.kt'
AUDIO = 'libraries/libinfinisound/audio-processing/src/main/java/com/infinifolder/audio/AudioProcessing.kt'
BUILTIN = 'libraries/libinfinisound/audio-core/src/main/java/com/infinifolder/audio/decoder/BuiltInAudioDecoders.kt'

def read(path):
    return (ROOT / path).read_text()

def literals(text):
    return re.findall(r'"([^"\n]*)"', re.sub(r'//[^\n]*', '', text))

def values(path, name):
    text = read(path)
    match = re.search(r'\b' + re.escape(name) + r'[^=\n]*=\s*(?:setOf|listOf)\(', text)
    if not match:
        raise ValueError(f'Cannot find literal collection {path}:{name}')
    return set(literals(text[match.end():text.index(')', match.end())]))

mime_text = read(MIME)
mapping = dict(re.findall(r'"([a-z0-9]+)"\s+to\s+"([a-z0-9.+-]+/[a-z0-9.+-]+)"',
    mime_text.split('private val extensions =', 1)[1].split('fun normalize', 1)[0]))
packages = {ext: (mime, container) for ext, mime, container in re.findall(
    r'"([a-z0-9]+)"\s+to\s+PackageFormat\("([^"]+)",\s*"([^"]+)"\)', mime_text)}
mapping.update({ext: value[0] for ext, value in packages.items()})
aliases = dict(re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"',
    mime_text.split('private val aliases =', 1)[1].split('private val extensions', 1)[0]))
images = values(IMAGE, 'decodableExtensions')
audio = values(MEDIA, 'AUDIO_EXTENSIONS') | values(AUDIO, 'platformExtensions') | values(BUILTIN, 'SUPPORTED_EXTENSIONS')
video = values(MEDIA, 'VIDEO_EXTENSIONS')
text = values(MEDIA, 'TEXT_EXTENSIONS')
markdown = values(MEDIA, 'MARKDOWN_EXTENSIONS')
html = values(MEDIA, 'HTML_EXTENSIONS')
discs = values(ARCHIVE, 'DISC_BASE_EXTENSIONS')
compression = values(ARCHIVE, 'COMPRESSION_SUFFIXES')
playlists = values(ARCHIVE, 'PLAYLIST_EXTENSIONS')
archives = values(ARCHIVE, 'SIMPLE_EXTENSIONS') | set(packages)
compounds = {item.lstrip('.') for item in values(ARCHIVE, 'TAR_COMPOUND_EXTENSIONS')}
compounds |= {f'{ext}.{suffix}' for ext in discs for suffix in compression}
rows = []

def add(category, identifier, extensions='', mime_type='', capabilities='', support_level='',
        write_support='not asserted', module='', notes='', source_paths=''):
    rows.append(dict(category=category, identifier=identifier, extensions=extensions,
        mime_type=mime_type, capabilities=capabilities, support_level=support_level,
        write_support=write_support, module=module, notes=notes, source_paths=source_paths))

def capability(ext, mime):
    if ext == 'bin': return 'generic binary identity; optional magic/container probe', 'Store/Vault', 'No disc-image identity is assumed from .bin alone.'
    if ext == 'torrent': return 'torrent metadata parse/browse/download', 'Store/host', 'Opened by the host torrent path; peer/tracker availability applies.'
    if ext == 'lnk': return 'legacy InfiniFolder JSON shortcut', 'Store/host', 'Not a general Windows Shell Link binary decoder.'
    if ext in {'ans', 'ansi', 'asc', 'diz', 'nfo'}:
        return 'text/CP437/ANSI viewer; art preview by content', 'Scribe/Canvas/Sound', 'ANSI music uses the dedicated CP437 viewer; byte/content detection controls art handling.'
    if mime == 'application/pdf': return 'PDF viewer', 'Scribe', 'Android PDF backend; not general PostScript typesetting.'
    if ext == 'epub': return 'EPUB viewer; ZIP browse', 'Scribe/Vault', 'Supported EPUB layout subset.'
    if ext in {'csv', 'tsv'}: return 'delimited spreadsheet viewer/editor', 'Scribe', ''
    if ext in markdown: return 'Markdown viewer', 'Scribe', ''
    if ext in html: return 'HTML viewer', 'Scribe', ''
    if ext in images: return 'image rendering/thumbnail', 'Canvas', 'Implemented subsets; platform RAW/modern codecs depend on device/API. See docs/image-formats.md.'
    if ext in playlists: return 'playlist parse/browse/playback routing', 'Vault/Sound', 'Referenced media needs its own working decoder.'
    if mime.startswith('video/') or ext in {'rm', 'rmvb'}:
        return 'video playback routing' + ('; audio decoder route' if ext in audio else ''), 'Video/Sound/libVLC', 'Container/codec variants must be supported by the registered backend.'
    if ext in audio or mime.startswith('audio/'):
        return 'audio playback/codec routing', 'Sound', 'Registered route, not a guarantee for every codec variant; unavailable codecs fail. See docs/audio-formats.md.'
    if mime.startswith('text/') or ext in text or mime in values(MEDIA, 'TEXT_MIME_TYPES'):
        return 'text/code viewer/editor', 'Scribe', 'Content detection/encoding and explicit MIME identity apply; .ts also names MPEG transport streams.' if ext == 'ts' else ''
    if ext in video or mime.startswith('video/') or ext in {'rm', 'rmvb'}:
        return 'video playback routing', 'Video/libVLC', 'Container/codec variants must be supported by the registered backend.'
    if ext in archives or ext in discs or ext in compounds:
        return 'container recognition; browse/extract when parser accepts', 'Vault', 'Recognition does not imply every variant is browsable or writable.'
    if ext == 'iflink': return 'InfiniFolder shortcut parse/open; embedded icons', 'Store/host', 'Destination capability is independent of local link-file identity.'
    if ext == 'incomplete': return 'torrent resume', 'Store', 'Requires compatible saved torrent/session metadata.'
    return 'MIME identity/icon; external open fallback', 'Store/host', 'No built-in viewer is asserted by this association alone.'

exts = set(mapping) | images | audio | video | text | markdown | html | archives | discs | compounds | {'csv', 'tsv', 'pdf', 'epub', 'iflink', 'torrent'}
by_mime = defaultdict(set)
for ext in sorted(exts):
    mime = mapping.get(ext, '')
    if mime: by_mime[mime].add(ext)
    caps, module, notes = capability(ext, mime)
    add('file_type', '.' + ext, '.' + ext, mime, caps,
        'registered association/route' if mime else 'registered extension/pattern', module=module,
        notes=notes + ((' ' if notes else '') + 'MIME may be supplied dynamically by Android/providers or content detection.' if not mime else ''),
        source_paths='|'.join([MIME, MEDIA, IMAGE, ARCHIVE] +
            (['app/src/main/java/com/infinifolder/MainActivity.kt'] if ext == 'torrent' else [])))

mime_sources = [MIME, MEDIA, IMAGE, ARCHIVE, STORE + 'storage/FileMagicDetector.kt',
    'viewer-common/src/main/java/com/infinifolder/preview/AudioIconFormats.kt']
all_mimes = set(by_mime) | set(aliases) | set(aliases.values())
for path in mime_sources:
    all_mimes.update(re.findall(r'"([a-z][a-z0-9.+-]*/[a-z0-9.+-]+)"', read(path)))
for mime in sorted(all_mimes):
    extensions = by_mime.get(mime, set())
    add('mime_type', mime, ';'.join('.' + ext for ext in sorted(extensions)), mime,
        'identity/association/content detection', 'recognized MIME', module='Store/host',
        notes=('Alias of ' + aliases[mime] + '. ' if mime in aliases else '') +
            'Recognition alone does not assert a decoder; consult matching file_type rows. Android/provider/user-defined MIME sets are open-ended.',
        source_paths='|'.join(path for path in mime_sources if mime in read(path)))

for ext in sorted(archives | discs | compounds | playlists):
    kind = 'disk-image/partition wrapper' if ext in discs or ext in compounds and ext.split('.')[0] in discs else 'playlist' if ext in playlists else 'archive/package'
    add('archive_type', '.' + ext, '.' + ext, mapping.get(ext, ''),
        'recognize; browse/extract supported structures', 'registered ' + kind,
        module='Vault' if kind != 'playlist' else 'Vault/Sound',
        notes='Content validation is required; writes, multipart/encryption and compression variants have separate restrictions. Generic .bin stays binary unless detected.',
        source_paths=ARCHIVE + '|' + MIME)
for pattern in ['.rar/.r00-.r99', '.partNN.rar/.NNN.rar', '.arj/.a00-.a99']:
    add('archive_type', pattern, pattern, capabilities='multipart archive browse/extract',
        support_level='supported subset', write_support='read-only', module='Vault/native archive backend',
        notes='Volumes must be available and supported; no native archive writes.', source_paths=STORE + 'archive/NativeArchiveNames.kt')

filesystems = [
    ('FAT12', 'fat;img;ima;flp', 'read/write; deleted-file recovery', 'supported subset', 'FatFileSystem.kt'),
    ('FAT16', 'fat;img;ima', 'read/write; deleted-file recovery', 'supported subset', 'FatFileSystem.kt'),
    ('FAT32/VFAT', 'fat;vfat;img', 'read/write; deleted-file recovery', 'supported subset', 'FatFileSystem.kt'),
    ('ext2', 'ext2;img', 'read/write', 'journal/feature-aware subset', 'ExtFileSystem.kt'),
    ('ext3', 'ext3;img', 'read/write', 'journal/feature-aware subset', 'ExtFileSystem.kt'),
    ('ext4', 'ext4;img', 'read/write', 'journal/feature-aware subset', 'ExtFileSystem.kt'),
    ('exFAT', 'exfat;img', 'browse/extract', 'read-only', 'DiscImageReader.kt'),
    ('ISO 9660/Joliet/Rock Ridge', 'iso;cdr', 'browse/extract', 'read-only', 'DiscImageReader.kt'),
    ('UFS1', 'ufs;ufs1;ffs;img', 'browse/extract', 'read-only subset', 'UfsFileSystem.kt'),
    ('UFS2', 'ufs;ufs2;ffs;img', 'browse/extract', 'read-only subset', 'UfsFileSystem.kt'),
    ('NTFS', 'ntfs;img', 'browse/extract', 'read-only subset', 'NtfsFileSystem.kt'),
    ('HFS', 'hfs;img', 'browse/extract', 'read-only subset', 'HfsFileSystem.kt'),
    ('HFS+', 'hfsplus;img', 'browse/extract', 'read-only subset', 'HfsFileSystem.kt'),
    ('HFSX', 'hfsx;img', 'browse/extract', 'read-only subset', 'HfsFileSystem.kt')]
for name, extensions, caps, level, source in filesystems:
    add('filesystem_type', name, ';'.join('.' + x for x in extensions.split(';')),
        capabilities=caps, support_level=level, write_support='supported subset' if 'write' in caps else 'read-only',
        module='Vault', notes='Image browsing through shared storage abstractions; not an Android kernel mount. Unsupported features fail explicitly.', source_paths=VAULT + source)
for name, notes in [('MBR/EBR', 'Partition slices; filesystem support is separate.'), ('GPT', 'Partition slices; filesystem support is separate.'),
                    ('APM', 'Apple partition-map slices; filesystem support is separate.'), ('LVM', 'Linear single-PV subset; not RAID/thin/snapshot/general LVM support.')]:
    add('volume_type', name, capabilities='volume/partition browsing', support_level='supported subset', module='Vault', notes=notes, source_paths=VAULT + 'DiscImageReader.kt|' + VAULT + 'LvmDecoder.kt')

uris = {
    'file': ('local file access', 'implemented', 'Store', 'Subject to Android access rights.'),
    'content': ('SAF/document provider access', 'implemented', 'Store', 'Opaque IDs; grants/provider capabilities apply; optional size.'),
    'archive': ('archive/filesystem/container view', 'implemented', 'Vault', 'Nested backing sources keep their provider access requirements.'),
    'apps': ('installed applications/categories', 'implemented', 'Store', 'Android application provider.'),
    'container-links': ('Virtual Root link storage', 'implemented', 'Store', 'Internal provider; .iflink destination is independent.'),
    'partial-downloads': ('partial torrent entries', 'implemented', 'Store', 'Internal session/checkpoint-backed provider.'),
    'magnet': ('BitTorrent metadata/browse/download', 'implemented subset', 'Store', 'Peer/tracker availability and supported metadata apply.'),
    'torrent': ('resolved torrent container', 'implemented subset', 'Store', 'Internal virtual torrent tree.'),
    'infinifolder-termux': ('explicit Termux command shortcut', 'implemented', 'host', 'Requires installed Termux, RUN_COMMAND permission and external-app configuration.'),
}
for scheme in ['sftp', 'ssh', 'sshfs', 'ftp', 'ftps', 'smb', 'cifs', 'webdav', 'dav', 'davs']:
    uris[scheme] = ('remote file browsing/transfers', 'implemented/alias', 'Store', 'Saved connection binding/credentials and backend capabilities apply; ssh/sshfs use the SFTP backend.')
for scheme in ['http', 'https', 'mailto', 'tel', 'sms', 'irc', 'ircs']:
    uris[scheme] = ('shortcut target/icon rules; external open', 'delegated target', 'host', 'Not a native filesystem backend; actual opening requires an Android handler. HTTP(S) may discover favicons.')
uris['nfs'] = ('URI icon rules/fallback only', 'no native NFS storage provider', 'host', 'Do not confuse URI recognition/icon support with filesystem browsing support.')
for scheme, (caps, level, module, notes) in sorted(uris.items()):
    add('uri_type', scheme + ':', capabilities=caps, support_level=level, module=module, notes=notes,
        source_paths=STORE + 'storage/ContainerUris.kt|app/src/main/java/com/infinifolder/ResourceIconResolver.kt|docs/file-formats.md')

destination = ROOT / 'docs/supported-types.csv'
with destination.open('w', encoding='utf-8-sig', newline='') as out:
    writer = csv.DictWriter(out, fieldnames=list(rows[0]), lineterminator='\n')
    writer.writeheader()
    writer.writerows(sorted(rows, key=lambda row: (row['category'], row['identifier'])))
print(f'{destination}: {len(rows)} rows; {dict(Counter(row["category"] for row in rows))}')
