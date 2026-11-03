#!/usr/bin/env python3
"""Package the supplied Freeicon SVG/CSV artwork as a freedesktop icon theme.

Reads themes/default theme only; never regenerates or replaces the source art.
Outputs an importable directory/ZIP; Gradle requests assets and Kotlin under build/.
"""
from pathlib import Path
import csv
import argparse
import hashlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "themes/default theme"
THEME_ID = "infinifolder-default"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", type=Path, help="Build asset output directory")
    parser.add_argument("--kotlin-dir", type=Path, help="Build Kotlin source output directory")
    parser.add_argument("--no-layout", action="store_true", help="Package without writing the importable source layout")
    args = parser.parse_args(argv)
    asset = (args.assets_dir / "icon-themes" / f"{THEME_ID}.zip" if args.assets_dir
             else ROOT / "themes/exports" / f"{THEME_ID}.zip")
    with (SOURCE / "metadata.csv").open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    artwork = {}
    aliases = {}

    def alias(name, filename, replace=False):
        if not re.fullmatch(r"[a-zA-Z0-9+_.-]+", name):
            raise ValueError(f"Invalid logical icon name: {name}")
        if filename not in artwork:
            raise ValueError(f"Missing source icon: {filename}")
        if replace or name not in aliases:
            aliases[name] = filename

    for row in rows:
        name = row["filename"]
        if Path(name).name != name or not name.endswith(".svg"):
            raise ValueError(f"Invalid artwork filename: {name}")
        artwork[name] = (SOURCE / "svg" / name).read_bytes()
        if not artwork[name]:
            raise ValueError(f"Empty artwork: {name}")
    for row in sorted(rows, key=lambda row: row["filename"]):
        filename = row["filename"]
        alias(Path(filename).stem, filename)
        category = row["category"]
        if category == "file_type":
            ext = row["identifier"].lstrip(".")
            for prefix in ("application-x-extension-", "application-x-", "text-x-"):
                alias(prefix + ext, filename)
            mime = row["mime_type"]
            if mime:
                alias(mime.replace("/", "-"), filename)
                if mime.startswith("audio/") or mime in ("application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp"):
                    alias("audio-" + ext, filename)
                    alias("audio-x-" + ext, filename)
        elif category == "directory_type":
            alias("folder-" + row["identifier"].split(":", 1)[1], filename)
        elif category == "uri_type":
            alias("x-scheme-handler-" + row["identifier"].rstrip(":"), filename)

    # Shared MIME records retain distinct extension aliases. Pick familiar art
    # deterministically where a MIME family has multiple source extensions.
    # Prefer the subtype's own extension instead of whichever alias sorts first.
    for row in rows:
        mime = row["mime_type"]
        if row["category"] == "file_type" and mime:
            subtype = mime.split("/", 1)[1].split("+", 1)[0].removeprefix("x-").removesuffix("-compressed")
            filename = "file-" + subtype.rsplit(".", 1)[-1] + ".svg"
            if filename in artwork:
                alias(mime.replace("/", "-"), filename, replace=True)
    preferred = {"audio/mpeg": "file-mp3.svg", "image/jpeg": "file-jpeg.svg",
                 "text/plain": "file-txt.svg", "application/octet-stream": "base-fallback-binary.svg",
                 "application/java-archive": "file-jar.svg", "application/gzip": "file-gz.svg",
                 "application/x-bzip2": "file-bz2.svg", "application/x-compress": "file-z.svg",
                 "application/x-raw-disk-image": "file-img.svg", "application/x-iso9660-image": "file-iso.svg",
                 "application/zstd": "file-zst.svg", "audio/basic": "file-au.svg", "audio/midi": "file-mid.svg",
                 "audio/mp4": "file-m4a.svg", "audio/x-aiff": "file-aiff.svg", "audio/x-mpegurl": "file-m3u.svg",
                 "image/vnd.adobe.photoshop": "file-psd.svg", "image/x-degas": "file-pi1.svg",
                 "image/x-cmu-raster": "file-ras.svg", "image/x-koala": "file-koa.svg",
                 "image/x-macpaint": "file-mac.svg", "text/markdown": "file-md.svg", "text/x-kotlin": "file-kt.svg"}
    for mime, filename in preferred.items():
        if filename in artwork:
            alias(mime.replace("/", "-"), filename, replace=True)

    groups = {
        "base-fallback-folder.svg": ["folder", "inode-directory", "folder-directory"],
        "directory-home.svg": ["user-home", "folder-home"],
        "directory-desktop.svg": ["user-desktop"],
        "directory-downloads.svg": ["folder-download", "folder-downloads"],
        "directory-images.svg": ["folder-images", "folder-pictures"],
        "directory-videos.svg": ["folder-movies", "folder-videos"],
        "directory-audio.svg": ["folder-music", "folder-audio"],
        "directory-public.svg": ["folder-publicshare"],
        "base-fallback-archive.svg": ["folder-archive", "package-x-generic"],
        "directory-apps.svg": ["start-here-android", "view-app-grid", "applications-all", "applications-other", "folder-android"],
        "directory-trash.svg": ["user-trash", "user-trash-full"],
        "base-fallback-document.svg": ["text-x-generic", "x-office-document"],
        "base-fallback-binary.svg": ["unknown", "application-x-generic"],
        "base-fallback-image.svg": ["image-x-generic"],
        "base-fallback-audio.svg": ["audio-x-generic"],
        "base-fallback-video.svg": ["video-x-generic"],
        "base-fallback-disk.svg": ["drive-harddisk", "drive-removable-media", "media-optical"],
        "base-fallback-link.svg": ["emblem-symbolic-link"],
        "uri-https.svg": ["web-browser", "applications-internet"],
        "uri-ftp.svg": ["folder-remote", "network-server"],
        "uri-sftp.svg": ["utilities-terminal"],
        "uri-smb.svg": ["folder-network", "network-workgroup"],
        "uri-mailto.svg": ["internet-mail", "mail-message-new"],
        "uri-tel.svg": ["phone", "call-start"],
        "uri-irc.svg": ["internet-group-chat", "im-message-new"],
        "uri-magnet.svg": ["magnet"],
    }
    for filename, names in groups.items():
        for name in names:
            alias(name, filename, replace=True)

    index = """[Icon Theme]
Name=InfiniFolder Default
Comment=File, directory and URI artwork supplied with InfiniFolder
Inherits=hicolor
Directories=scalable
Example=folder

[scalable]
Size=128
Type=Scalable
MinSize=16
MaxSize=1024
"""
    files = {"index.theme": index.encode(), "metadata.csv": (SOURCE / "metadata.csv").read_bytes()}
    files.update({f"scalable/{name}.svg": artwork[filename] for name, filename in aliases.items()})
    revision = hashlib.sha256()
    for name, data in sorted(files.items()):
        revision.update(name.encode() + b"\0" + hashlib.sha256(data).digest())
    version = revision.hexdigest()
    extensions = {row["identifier"].lstrip("."): row["mime_type"] for row in rows
                  if row["category"] == "file_type" and row["mime_type"]}
    extension_source = ",\n".join(f'        "{ext}" to "{mime}"' for ext, mime in sorted(extensions.items()))
    if not args.no_layout:
        (SOURCE / "index.theme").write_text(index, encoding="utf-8")
        # Aliases are standard theme filenames, not a second copy of the art.
        # ZIP exports below dereference them, so imports also work without symlinks.
        scalable = SOURCE / "scalable"
        if scalable.exists():
            if scalable.is_symlink():
                raise ValueError("Generated scalable directory must not be a symlink")
            shutil.rmtree(scalable)
        scalable.mkdir()
        for name, filename in sorted(aliases.items()):
            (scalable / f"{name}.svg").symlink_to(Path("../svg") / filename)
    asset.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(asset, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items(), key=lambda item: (item[0] != "index.theme", item[0])):
            entry = zipfile.ZipInfo(f"{THEME_ID}/{name}", (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    if args.kotlin_dir:
        info = args.kotlin_dir / "com/infinifolder/theme/BundledDefaultThemeInfo.kt"
        info.parent.mkdir(parents=True, exist_ok=True)
        info.write_text(f'''package com.infinifolder.theme

// Generated by themes/icon/scripts/build_icon_theme.py from themes/default theme.
internal object BundledDefaultThemeInfo {{
    const val ID = "{THEME_ID}"
    const val PREFERENCE_ID = "builtin:{THEME_ID}"
    const val NAME = "InfiniFolder Default"
    const val REVISION = "{version}"
    val EXTENSION_MIME_TYPES = mapOf(
{extension_source}
    )
}}
''')
    print(f"Packaged {len(artwork)} source SVGs as {len(aliases)} logical icons: {asset}")


if __name__ == "__main__":
    main()
