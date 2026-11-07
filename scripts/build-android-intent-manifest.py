#!/usr/bin/env python3
"""Generate Android intent filters from the linked runtime capability catalog."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

# A handler can advertise several independent abilities for the same format.
# Only handlers that consume or present an input are relevant to Open with.
INPUT_OPERATIONS = {
    'decode', 'view', 'edit', 'play', 'list', 'read', 'extract',
    'metadata', 'thumbnail', 'identify', 'reformat', 'extract-image',
}
OPAQUE_MIMES = {'application/octet-stream', 'application/binary'}
GENERIC_MIMES = OPAQUE_MIMES | {'application/x-unknown'}


def attr(name):
    return '{' + ANDROID + '}' + name


def add_filter(activity, mimes=(), schemes=(), extensions=(), browsable=True, action='VIEW'):
    if not mimes and not schemes and not extensions:
        return None
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
            # legacy patterns remain necessary on older Android versions.
            for suffix in sorted({'.' + extension.lower(), '.' + extension.upper()}):
                pattern = '.*' + suffix.replace('.', chr(92) * 2 + '.')
                ET.SubElement(node, 'data', {
                    attr('pathPattern'): pattern,
                    attr('pathSuffix'): suffix,
                })
    return node


def load_catalog(path):
    if path is None:
        raise ValueError('Android file registrations require --capabilities-json')
    catalog = json.loads(path.read_text())
    if catalog.get('schemaVersion') != 1:
        raise ValueError('Unsupported runtime capability catalog schema')
    if catalog.get('failures'):
        raise ValueError('Incomplete runtime capability catalog: ' + json.dumps(catalog['failures']))
    if not isinstance(catalog.get('handlers'), list):
        raise ValueError('Runtime capability catalog has no handler list')
    return catalog


def relevant_handlers(catalog, host, viewer):
    selected = []
    for handler in catalog['handlers']:
        operations = set(handler.get('operations', []))
        attributes = handler.get('attributes', {})
        library = handler.get('library', '')
        if not operations.intersection(INPUT_OPERATIONS):
            continue

        if host == 'app':
            # InfiniFolder can dispatch to every linked viewer/library and to
            # registered storage/container handlers.
            selected.append(handler)
        elif host == 'infiniscribe':
            # Scribe's own processors are authoritative. The linked Scribe
            # viewer components also report formats that they can present.
            scribe_library = library == 'scribe' and bool(operations & {'decode', 'view'})
            scribe_viewer = (library == 'viewers' and attributes.get('formatLibrary') == 'scribe'
                and 'view' in operations)
            if scribe_library or scribe_viewer:
                selected.append(handler)
        else:
            if library != 'viewers' or 'view' not in operations:
                continue
            if viewer == 'all' or attributes.get('viewerKind') == viewer:
                selected.append(handler)
    return selected


def collect_formats(handlers):
    mimes, extensions, editable_mimes, editable_extensions, schemes = set(), set(), set(), set(), set()
    for handler in handlers:
        operations = set(handler.get('operations', []))
        for support in handler.get('formats', []):
            for mime in support.get('mimeTypes', []):
                if isinstance(mime, str) and mime.strip():
                    normalized = mime.strip().lower()
                    mimes.add(normalized)
                    if 'edit' in operations:
                        editable_mimes.add(normalized)
            for extension in support.get('extensions', []):
                if isinstance(extension, str):
                    normalized = extension.strip().lower().lstrip('.')
                    # Malformed descriptors must not create broad path filters.
                    if normalized and '/' not in normalized and '\\' not in normalized:
                        extensions.add(normalized)
                        if 'edit' in operations:
                            editable_extensions.add(normalized)
            for scheme in support.get('uriSchemes', []):
                if isinstance(scheme, str) and scheme.strip():
                    schemes.add(scheme.strip().lower().rstrip(':'))
    return mimes, extensions, editable_mimes, editable_extensions, schemes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--host', choices=['app', 'viewer', 'infiniscribe'], required=True)
    parser.add_argument('--capabilities-json', type=Path, required=True)
    parser.add_argument('--viewer', default='all')
    args = parser.parse_args()

    catalog = load_catalog(args.capabilities_json)
    handlers = relevant_handlers(catalog, args.host, args.viewer)
    mimes, extensions, editable_mimes, editable_extensions, schemes = collect_formats(handlers)

    # These MIME values identify arbitrary binary payloads. Keep them as a
    # generic fallback filter, rather than treating them as format identities.
    if not (args.host == 'viewer' and args.viewer == 'hex'):
        mimes.difference_update(OPAQUE_MIMES)
        editable_mimes.difference_update(OPAQUE_MIMES)

    if args.host == 'app':
        # These app-owned routes are UI actions rather than library storage
        # providers. Read their constants from the implementation that handles them.
        for source in (
            ROOT / 'app/src/main/java/com/infinifolder/BrowserLocations.kt',
            ROOT / 'app/src/main/java/com/infinifolder/TermuxCommandLink.kt',
        ):
            match = re.search(r'const val SCHEME = "([a-z0-9+-]+)"', source.read_text())
            if not match:
                raise ValueError(f'Cannot find SCHEME constant in {source}')
            schemes.add(match.group(1))

    tree = ET.parse(args.template)
    if args.host == 'app':
        activity_name = '.MainActivity'
    elif args.host == 'infiniscribe':
        activity_name = 'com.infiniscribe.InfiniScribeActivity'
    else:
        activity_name = 'com.infinifolder.standalone.ViewerHostActivity'
    activity = next(a for a in tree.getroot().find('application').findall('activity')
        if a.get(attr('name')) == activity_name)

    add_filter(activity, mimes=mimes)
    if args.host in {'app', 'infiniscribe'}:
        # Providers often supply opaque content IDs, no MIME, or a generic
        # binary MIME. The actual content is validated by the runtime library.
        add_filter(activity, schemes={'content', 'file'})
        add_filter(activity, mimes={'*/*'}, schemes={'content', 'file'})
        add_filter(activity, mimes=GENERIC_MIMES, schemes={'content', 'file'})
        if extensions:
            add_filter(activity, schemes={'content', 'file'}, extensions=extensions)
            add_filter(activity, mimes=GENERIC_MIMES, schemes={'content', 'file'}, extensions=extensions)
    elif extensions:
        add_filter(activity, schemes={'content', 'file'}, extensions=extensions)
        add_filter(activity, mimes=GENERIC_MIMES, schemes={'content', 'file'}, extensions=extensions)

    if args.host == 'app':
        # Registered storage handlers own their URI schemes. MainActivity can
        # also receive external shares with opaque or provider-defined MIME.
        add_filter(activity, schemes=schemes)
        if schemes:
            add_filter(activity, schemes=schemes, mimes={'*/*'})
        add_filter(activity, mimes={'*/*'}, browsable=False, action='SEND')
        add_filter(activity, mimes={'*/*'}, browsable=False, action='SEND_MULTIPLE')
    elif args.host == 'infiniscribe':
        if editable_mimes:
            add_filter(activity, mimes=editable_mimes, action='EDIT')
        if editable_extensions:
            add_filter(activity, schemes={'content', 'file'}, extensions=editable_extensions, action='EDIT')
            add_filter(activity, mimes=GENERIC_MIMES, schemes={'content', 'file'},
                extensions=editable_extensions, action='EDIT')
        if mimes:
            add_filter(activity, mimes=mimes, browsable=False, action='SEND')
    elif editable_mimes:
        add_filter(activity, mimes=editable_mimes, action='EDIT')
        add_filter(activity, schemes={'content', 'file'}, extensions=editable_extensions,
            mimes={'application/octet-stream'}, action='EDIT')

    ET.indent(tree, space='    ')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tree.write(args.output, encoding='utf-8', xml_declaration=True)
    summary = {
        'host': args.host,
        'viewer': args.viewer,
        'mimeTypes': sorted(mimes),
        'extensions': sorted(extensions),
        'editableMimeTypes': sorted(editable_mimes),
        'editableExtensions': sorted(editable_extensions),
        'uriSchemes': sorted(schemes),
        'source': 'linked runtime capability catalog',
        'catalogFingerprint': catalog.get('fingerprint'),
        'handlers': sorted(handler['id'] for handler in handlers),
        'libraries': sorted({handler['library'] for handler in handlers}),
    }
    args.output.with_suffix('.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(f'{args.host}/{args.viewer}: {len(mimes)} MIME types, {len(extensions)} extensions '
        f'from {len(handlers)} handlers in {len(summary["libraries"])} libraries')


if __name__ == '__main__':
    main()
