#!/usr/bin/env python3
"""Hash tracked files in explicitly supplied checkouts.

Usage: python3 scripts/inventory-repositories.py name=/path/to/repo [...] > inventory.json
Default scope is src/main; --all-tracked also inventories root assets, test
fixtures and accidentally tracked outputs. Untracked files and submodule contents
are excluded; supply each library separately. Hash equality does not prove disuse.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

SOURCE = {'.kt', '.java', '.c', '.cpp', '.h', '.hpp', '.rs'}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--all-tracked', action='store_true')
parser.add_argument('repositories', nargs='+', metavar='NAME=PATH')
args = parser.parse_args()
groups = {}
inventory = []
for argument in args.repositories:
    name, location = argument.split('=', 1)
    root = Path(location).resolve()
    paths = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z']).decode().split('\0')
    records = []
    for relative in paths:
        if not relative or (not args.all_tracked and '/src/main/' not in '/' + relative):
            continue
        path = root / relative
        if not path.is_file():
            continue
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        item = {'repository': name, 'path': relative, 'bytes': path.stat().st_size,
                'sha256': digest}
        records.append(item)
        category = 'source' if path.suffix in SOURCE else 'resource'
        groups.setdefault((category, item['sha256']), []).append(item)
    inventory.append({'repository': name,
        'commit': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        'tracked_worktree_changes': subprocess.run(['git', '-C', str(root), 'diff', '--quiet', 'HEAD']).returncode != 0,
        ('tracked_files' if args.all_tracked else 'production_files'): len(records), 'bytes': sum(i['bytes'] for i in records),
        'largest': sorted(records, key=lambda i: i['bytes'], reverse=True)[:20]})
result = {'scope': ('All tracked regular files, including root assets, test fixtures, documentation and tracked outputs. '
                   'Untracked files and nested repositories excluded; each repository is supplied explicitly. '
                   if args.all_tracked else 'Tracked src/main files only; tests/root assets/build outputs excluded. ') +
                   'Hash equality alone is not a removal criterion.',
          'inventory': inventory}
for category, key in [('source', 'exact_duplicate_tracked_sources' if args.all_tracked else 'exact_duplicate_production_sources'), ('resource', 'exact_duplicate_other_tracked_files' if args.all_tracked else 'exact_duplicate_resources')]:
    result[key] = [items for (kind, digest), items in groups.items() if kind == category and len(items) > 1]
print(json.dumps(result, indent=2))
