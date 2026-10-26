#!/usr/bin/env python3
"""Create a separate ext4 metadata-copy fixture; never overwrite an existing image."""
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile

root = Path(__file__).resolve().parent
image = root / "filesystems/linux/ext4-metadata-tests.img"
manifest = root / "filesystems/linux/ext4-metadata-tests.json"
if image.exists() or manifest.exists():
    raise SystemExit("Fixture or manifest already exists; nothing was changed.")

files = {
    "plain-0644.txt": (0o644, b"Plain readable file for metadata copying.\n"),
    "private-0600.txt": (0o600, b"Owner-only permission test.\n"),
    "executable-0755.sh": (0o755, b"#!/bin/sh\nprintf 'metadata fixture\\n'\n"),
    "acl-and-xattrs-0640.txt": (0o640, b"POSIX ACL and extended attribute test.\n"),
    "nested/nested-0640.txt": (0o640, b"File inside the nested directory.\n"),
}
uid, gid = 1000, 1000
timestamp = 1700000000
label = b"InfiniFolder metadata fixture"
large = bytes(i % 251 for i in range(400))
# ext4's on-disk POSIX ACL format: owner rw, named uid 1234 r,
# owning group r, mask r, and other no access, consistent with mode 0640.
acl = struct.pack("<I", 1) + b"".join([
    struct.pack("<HH", 1, 6), struct.pack("<HHI", 2, 4, 1234),
    struct.pack("<HH", 4, 4), struct.pack("<HH", 16, 4), struct.pack("<HH", 32, 0),
])

def command(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    return result.stdout

with tempfile.TemporaryDirectory(prefix="if-metadata-fixture-") as tmp:
    work = Path(tmp)
    tree = work / "tree"
    source = tree / "copy-from"
    (source / "nested").mkdir(parents=True)
    (tree / "copy-back-here").mkdir()
    os.chmod(tree, 0o755)
    for directory in (source, source / "nested", tree / "copy-back-here"):
        os.chmod(directory, 0o755)
    for name, (mode, data) in files.items():
        path = source / name
        path.write_bytes(data)
        os.chmod(path, mode)
    (work / "acl.bin").write_bytes(acl)
    (work / "label.bin").write_bytes(label)
    (work / "large.bin").write_bytes(large)
    image.parent.mkdir(parents=True, exist_ok=True)
    with image.open("xb") as out:
        out.truncate(24 * 1024 * 1024)
    try:
        command("mke2fs", "-q", "-t", "ext4", "-b", "1024", "-L", "metadata-tests", "-d", str(tree), str(image))
        paths = ["/copy-from", "/copy-from/nested", "/copy-back-here"] + ["/copy-from/" + name for name in files]
        for path in paths:
            for field, value in (("uid", uid), ("gid", gid), ("mtime", timestamp), ("atime", timestamp)):
                output = command("debugfs", "-w", "-R", f"set_inode_field {path} {field} {value}", str(image))
                if "Usage:" in output or "not found" in output.lower():
                    raise RuntimeError(output)
            # mke2fs -d can copy Android's host SELinux label into the image.
            # The fixture uses explicitly defined attributes only.
            command("debugfs", "-w", "-R", f"ea_rm {path} security.selinux", str(image))
        for name, content_file in (("system.posix_acl_access", "acl.bin"), ("user.test_label", "label.bin"), ("user.large", "large.bin")):
            path = "/copy-from/acl-and-xattrs-0640.txt"
            output = command("debugfs", "-w", "-R", f"ea_set -f {work / content_file} -r {path} {name}", str(image))
            if "Usage:" in output or "failed" in output.lower() or "not found" in output.lower():
                raise RuntimeError(output)
            extracted = work / (content_file + ".actual")
            output = command("debugfs", "-R", f"ea_get -f {extracted} -r {path} {name}", str(image))
            if not extracted.is_file() or extracted.read_bytes() != (work / content_file).read_bytes():
                raise RuntimeError("Attribute readback mismatch: " + name + " " + output)
        report = command("e2fsck", "-fn", str(image))
        print(report.strip())
        records = {name: {"mode": format(mode, "04o"), "uid": uid, "gid": gid, "mtime_unix": timestamp,
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, (mode, data) in files.items()}
        records["acl-and-xattrs-0640.txt"]["xattrs"] = {
            "user.test_label": label.decode(), "user.large_bytes": len(large),
            "user.large_sha256": hashlib.sha256(large).hexdigest(),
            "system.posix_acl_access": "owner rw-, user:1234 r--, group r--, mask r--, other ---"}
        manifest.write_text(json.dumps({"image": image.name, "source_directory": "copy-from",
            "return_directory": "copy-back-here", "files": records}, indent=2) + "\n")
    except BaseException:
        image.unlink(missing_ok=True)
        manifest.unlink(missing_ok=True)
        raise
print(image)
print(manifest)
