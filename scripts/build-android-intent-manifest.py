#!/usr/bin/env python3
"""Generate Android Open-with registration from the live supported-type inventory.

No checked-in CSV, theme metadata, codec probes or user settings are changed.
"""
import argparse
import json
import re
import runpy
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)


def attr(name):
    return '{' + ANDROID + '}' + name


def add_filter(activity, mimes=(), schemes=(), extensions=(), browsable=True, action="VIEW"):
    node = ET.SubElement(activity, 'intent-filter')
    ET.SubElement(node, 'action', {attr('name'): 'android.intent.action.' + action})
    ET.SubElement(node, 'category', {attr('name'): 'android.intent.category.DEFAULT'})
    if browsable:
        ET.SubElement(node, 'category', {attr('name'): 'android.intent.category.BROWSABLE'})
    for scheme in sorted(schemes):
        ET.SubElement(node, 'data', {attr('scheme'): scheme})
    for mime in sorted(mimes):
        ET.SubElement(node, 'data', {attr('mimeType'): mime})
    if extensions:
        ET.SubElement(node, 'data', {attr('host'): '*'})
        for extension in sorted(extensions):
            # pathSuffix handles arbitrary dotted names on Android 12+. The
            # legacy patterns remain necessary on older Android: an unknown
            # suffix attribute alone would turn into an unconstrained filter.
            for suffix in sorted({'.' + extension.lower(), '.' + extension.upper()}):
                pattern = '.*' + suffix.replace('.', chr(92) * 2 + '.')
                ET.SubElement(node, 'data', {attr('pathPattern'): pattern, attr('pathSuffix'): suffix})

    return node


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--host', choices=['app', 'viewer'], required=True)
    parser.add_argument('--capabilities-json', type=Path)
    parser.add_argument('--viewer', default='all')
    args = parser.parse_args()
    inventory = runpy.run_path(str(ROOT / 'scripts/export-supported-types.py'))
    rows = inventory['rows']
    file_rows = {r['identifier'].lstrip('.'): r for r in rows if r['category'] == 'file_type'}
    formats = inventory['document_formats']
    text_mimes = inventory['text_mimes']

    def families(extension, row):
        if extension in formats:
            return {'csv' if formats[extension] in {'SHEET', 'ODF_SHEET'} else 'epub'}
        mime = row['mime_type']
        if extension == 'pdf':
            return {'pdf'}
        if extension == 'epub':
            return {'epub'}
        if extension in {'csv', 'tsv'}:
            return {'csv'}
        if extension in inventory['markdown']:
            return {'markdown'}
        if extension in inventory['html']:
            return {'html'}
        result = set()
        if extension in inventory['fonts'] or mime in inventory['font_mimes']:
            result.add('font')
        if extension in inventory['images'] or mime.startswith('image/'):
            result.add('text' if mime == 'image/x-ansi' else 'image')
        if extension in inventory['audio'] or mime.startswith('audio/'):
            result.add('audio')
        if extension in inventory['video'] or mime.startswith('video/') and extension not in inventory['images'] or extension in {'rm', 'rmvb'}:
            result.add('video')
        if extension in inventory['text'] or mime.startswith('text/') or mime in text_mimes:
            result.add('text')
        return result

    editable_extensions = set()
    editable_mimes = set()
    extensions = set()
    mimes = set()
    for extension, row in file_rows.items():
        groups = families(extension, row)
        actionable = bool(groups) or not row['capabilities'].startswith('MIME identity/icon')
        if args.host == 'app' and actionable or args.host == 'viewer' and (args.viewer == 'all' and groups or args.viewer in groups):
            extensions.add(extension)
            if groups & {'image', 'text', 'markdown', 'html', 'csv'} and extension not in formats:
                editable_extensions.add(extension)
                if row['mime_type']:
                    editable_mimes.add(row['mime_type'])
            if row['mime_type']:
                mimes.add(row['mime_type'])
    if args.host == 'app':
        extensions.update(r['identifier'].lstrip('.') for r in rows if r['category'] == 'archive_type'
            and '/' not in r['identifier'])
        extensions.update(inventory['compression'] & set(file_rows))
        extensions.discard('bin')  # Generic binary data is not a disc image.
        extensions.update(f'{prefix}{volume:02d}' for prefix in ('r', 'a') for volume in range(100))
        extensions.update({'iflink', 'lnk', 'torrent', 'incomplete', 'wsz'})
        mimes.update(inventory['values'](inventory['ARCHIVE'], 'ARCHIVE_MIME_TYPES'))
        mimes.update(inventory['values'](inventory['ARCHIVE'], 'DISC_MIME_TYPES'))
        mimes.update({'application/x-bittorrent', 'application/vnd.infinifolder.torrent-partial',
            'application/vnd.infinifolder.shortcut+json', 'application/x-ms-shortcut', 'application/x-winamp-skin',
            'vnd.android.document/directory', 'inode/directory', 'resource/folder'})

    # Keep the existing standalone major-type contracts, and cover generic text
    # in the app without claiming every application/* or unknown binary payload.
    wildcard_groups = {'text': 'text/*', 'image': 'image/*', 'audio': 'audio/*', 'video': 'video/*'}
    if args.host == 'viewer':
        mimes.update(wildcard_groups.values() if args.viewer == 'all' else
            ([wildcard_groups[args.viewer]] if args.viewer in wildcard_groups else []))
    else:
        mimes.add('text/*')
    if args.host == 'app' or args.viewer in {'all', 'text'}:
        mimes.update(text_mimes)
    if args.host == 'app' or args.viewer in {'all', 'font'}:
        mimes.update(inventory['font_mimes'])
    if args.host == 'app' or args.viewer in {'all', 'audio'}:
        mimes.update(inventory['audio_mimes'])
    if args.host == 'viewer':
        if args.viewer in {'all', 'text'}:
            editable_mimes.update({'text/*'} | text_mimes)
        if args.viewer in {'all', 'image'}:
            editable_mimes.add('image/*')
    doc_mimes = re.findall(r'"([a-z0-9.+-]+/[a-z0-9.+-]+)"\s+to\s+Kind\.([A-Z0-9_]+)',
        inventory['read'](inventory['DOCUMENTS']).split('val mimeKinds', 1)[1].split('fun forMime', 1)[0])
    for mime, kind in doc_mimes:
        group = 'csv' if kind in {'SHEET', 'ODF_SHEET'} else 'epub'
        if args.host == 'app' or args.viewer in {'all', group}:
            mimes.add(mime)
    for alias, canonical in inventory['aliases'].items():
        if canonical in mimes:
            mimes.add(alias)
        if canonical in editable_mimes:
            editable_mimes.add(alias)
    for row in rows:
        mime = row['mime_type']
        if row['category'] == 'mime_type' and mime.startswith(('image/', 'audio/', 'video/', 'text/')):
            group = mime.split('/')[0]
            if args.host == 'app' or args.viewer in {'all', group}:
                mimes.add(mime)
    mimes.discard('application/octet-stream')
    mimes.discard('application/binary')
    # Schemes whose only implementation is an icon or external-app delegation
    # must not loop back into InfiniFolder as a purported browser/mail/IRC app.
    schemes = {r['identifier'].rstrip(':') for r in rows
        if r['category'] == 'uri_type' and r['support_level'].startswith('implemented')}
    schemes.difference_update({'content', 'file'})

    if args.capabilities_json:
        catalog = json.loads(args.capabilities_json.read_text())
        if catalog.get('schemaVersion') != 1 or catalog.get('failures'):
            raise ValueError('Invalid or incomplete registered capability catalog')
        group_for_media = {'IMAGE': 'image', 'TEXT': 'text', 'MARKDOWN': 'markdown',
            'HTML': 'html', 'PDF': 'pdf', 'FONT': 'font', 'SPREADSHEET': 'csv',
            'EPUB': 'epub', 'EBOOK': 'epub', 'DOCUMENT': 'epub', 'AUDIO': 'audio', 'VIDEO': 'video'}
        delegated_schemes = {'http', 'https', 'mailto', 'tel', 'sms', 'irc', 'ircs', 'file', 'content'}
        for handler in catalog['handlers']:
            operations = set(handler['operations'])
            attributes = handler.get('attributes', {})
            groups = {group_for_media[t] for t in attributes.get('mediaTypes', '').split(',') if t in group_for_media}
            usable = args.host == 'app' and bool(operations & {'decode', 'view', 'play', 'list'}) or (
                args.host == 'viewer' and handler['library'] == 'viewers' and (args.viewer == 'all' or args.viewer in groups or attributes.get('viewerKind') == args.viewer))
            for support in handler['formats']:
                if usable:
                    mimes.update(support['mimeTypes'])
                    extensions.update(support['extensions'])
                    if handler['library'] == 'viewers' and 'edit' in operations:
                        editable_mimes.update(support['mimeTypes'])
                        editable_extensions.update(support['extensions'])
                if args.host == 'app' and handler['library'] == 'store' and {'list', 'read'} <= operations:
                    schemes.update(set(support['uriSchemes']) - delegated_schemes)
        if args.host != 'viewer' or args.viewer != 'hex':
            mimes.difference_update({'application/octet-stream', 'application/binary'})

    tree = ET.parse(args.template)
    name = '.MainActivity' if args.host == 'app' else 'com.infinifolder.standalone.ViewerHostActivity'
    activity = next(a for a in tree.getroot().find('application').findall('activity') if a.get(attr('name')) == name)
    add_filter(activity, mimes=mimes)  # MIME-only filters accept content/file URIs, including opaque content IDs.
    add_filter(activity, schemes={'content', 'file'}, extensions=extensions)
    add_filter(activity, mimes={'application/octet-stream', 'application/binary', 'application/x-unknown'},
        schemes={'content', 'file'}, extensions=extensions)
    if args.host == 'app':
        add_filter(activity, schemes=schemes)
        add_filter(activity, schemes=schemes, mimes={'*/*'})
        # Link files are JSON, but other file managers may attach an unrelated
        # MIME type. Keep this fallback constrained to the filename; opaque
        # provider IDs still use the canonical shortcut MIME registration above.
        # Also preserve the previous any-MIME .incomplete resume registration.
        add_filter(activity, schemes={'content', 'file'}, mimes={'*/*'}, extensions={'iflink', 'incomplete'})
    elif editable_mimes:
        add_filter(activity, mimes=editable_mimes, action='EDIT')
        add_filter(activity, schemes={'content', 'file'}, extensions=editable_extensions,
            mimes={'application/octet-stream'}, action='EDIT')
    ET.indent(tree, space='    ')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tree.write(args.output, encoding='utf-8', xml_declaration=True)
    summary = {'host': args.host, 'viewer': args.viewer, 'mimeTypes': sorted(mimes),
        'extensions': sorted(extensions), 'editableMimeTypes': sorted(editable_mimes) if args.host == 'viewer' else [], 'uriSchemes': sorted(schemes | {'content', 'file'}) if args.host == 'app' else ['content', 'file']}
    args.output.with_suffix('.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'{args.host}/{args.viewer}: {len(mimes)} MIME types, {len(extensions)} extensions, '
        f'{len(summary["uriSchemes"])} URI schemes')


if __name__ == '__main__':
    main()
