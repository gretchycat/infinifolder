"""Shared layout for fixture creators. Paths describe formats, not exclusive OS support."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def fixture_path(name):
    lower = name.lower()
    if '/' in name:
        return ROOT / name
    if lower.startswith(('fat', 'exfat', 'ntfs')):
        category = 'filesystems/windows'
    elif lower.startswith(('ext2', 'ext3', 'ext4')):
        category = 'filesystems/linux'
    elif lower.startswith(('ufs', 'ffs')):
        category = 'filesystems/bsd'
    elif lower.startswith('hfs') and ('.img' in lower or lower.endswith(('.hfs', '.hfsplus', '.hfsx'))):
        category = 'filesystems/apple'
    elif '.iso' in lower:
        category = 'filesystems/optical'
    elif lower.endswith(('.zip', '.love')):
        category = 'archives/zip'
    elif lower.endswith('.7z'):
        category = 'archives/7z'
    elif '.tar' in lower:
        category = 'archives/tar'
    elif lower.endswith('.cab'):
        category = 'archives/cab'
    elif lower.endswith('.cpio'):
        category = 'archives/cpio'
    elif lower.endswith(('.ar', '.deb')):
        category = 'archives/ar'
    elif lower.endswith('.zst'):
        category = 'compression/zstandard'
    else:
        return ROOT / name
    return ROOT / category / name


def update_manifest(paths):
    """Merge generated files without replacing unrelated fixture inventory entries."""
    import hashlib
    import json
    target = ROOT / 'manifest.json'
    manifest = json.loads(target.read_text()) if target.exists() else {}
    for path in paths:
        if path.is_file():
            with path.open('rb') as source:
                digest = hashlib.file_digest(source, 'sha256').hexdigest()
            manifest[path.relative_to(ROOT).as_posix()] = {'bytes': path.stat().st_size, 'sha256': digest}
    target.write_text(json.dumps(dict(sorted(manifest.items())), indent=2) + '\n')
