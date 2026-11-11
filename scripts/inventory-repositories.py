#!/usr/bin/env python3
"""Hash tracked production sources/resources in explicitly supplied checkouts.

Usage: python3 scripts/inventory-repositories.py name=/path/to/repo [...] > inventory.json
Build outputs, tests and untracked working files are deliberately excluded. This
inventory identifies exact matches, not proof that a resource is unused.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

SOURCE = {'.kt', '.java', '.c', '.cpp', '.h', '.hpp', '.rs'}
groups = {}
inventory = []
for argument in sys.argv[1:]:
    name, location = argument.split('=', 1)
    root = Path(location).resolve()
    paths = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z']).decode().split('\0')
    records = []
    for relative in paths:
        if not relative or '/src/main/' not in '/' + relative:
            continue
        path = root / relative
        if not path.is_file():
            continue
        data = path.read_bytes()
        item = {'repository': name, 'path': relative, 'bytes': len(data),
                'sha256': hashlib.sha256(data).hexdigest()}
        records.append(item)
        category = 'source' if path.suffix in SOURCE else 'resource'
        groups.setdefault((category, item['sha256']), []).append(item)
    inventory.append({'repository': name,
        'commit': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
        'production_files': len(records), 'bytes': sum(i['bytes'] for i in records),
        'largest': sorted(records, key=lambda i: i['bytes'], reverse=True)[:20]})
result = {'scope': 'Tracked src/main files only; includes native sources and third-party notices. '
                   'Tests/build outputs and untracked files excluded. Hash equality alone is not a removal criterion.',
          'inventory': inventory}
for category, key in [('source', 'exact_duplicate_production_sources'), ('resource', 'exact_duplicate_resources')]:
    result[key] = [items for (kind, digest), items in groups.items() if kind == category and len(items) > 1]
print(json.dumps(result, indent=2))
