#!/usr/bin/env python3
"""Create original, self-contained EPUB reader samples; never overwrite existing files."""
import hashlib
import io
import json
import zipfile
from fixture_paths import ROOT, update_manifest

OUT = ROOT / 'documents'


def package(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in files.items():
            entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED if name == 'mimetype' else zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data.encode('utf-8') if isinstance(data, str) else data)
    return output.getvalue()


def container(path):
    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            f'<rootfiles><rootfile full-path="{path}" media-type="application/oebps-package+xml"/></rootfiles></container>')


def page(title, body):
    return ('<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">'
            f'<head><title>{title}</title><link rel="stylesheet" href="../styles/reader.css"/></head>'
            f'<body>{body}</body></html>')


def fixtures():
    main = {
        'mimetype': 'application/epub+zip',
        'META-INF/container.xml': container('OEBPS/content.opf'),
        'OEBPS/content.opf': '''<?xml version="1.0" encoding="UTF-8"?>
<package version="2.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">infinistream-synthetic-epub2</dc:identifier>
<dc:title>Café — EPUB migration</dc:title><dc:creator>Synthetic Author</dc:creator><dc:language>en</dc:language>
</metadata><manifest>
<item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/>
<item id="two" href="text/second%20chapter.xhtml" media-type="application/xhtml+xml"/>
<item id="extra" href="text/appendix+notes.xhtml" media-type="application/xhtml+xml"/>
<item id="css" href="styles/reader.css" media-type="text/css"/>
<item id="image" href="images/shapes.svg" media-type="image/svg+xml"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
</manifest><spine toc="ncx"><itemref idref="one"/><itemref idref="two"/><itemref idref="extra" linear="no"/></spine></package>''',
        'OEBPS/toc.ncx': '''<?xml version="1.0"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Migration book</text></docTitle><navMap>
<navPoint id="one" playOrder="1"><navLabel><text>First chapter (NCX)</text></navLabel><content src="text/one.xhtml#start"/></navPoint>
<navPoint id="two" playOrder="2"><navLabel><text>Second chapter (NCX)</text></navLabel><content src="text/second%20chapter.xhtml"/></navPoint>
</navMap></ncx>''',
        'OEBPS/styles/reader.css': 'body { font-family: serif; } .sample { border: 2px solid #2780b9; padding: 8px; }',
        'OEBPS/images/shapes.svg': '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="100"><rect x="5" y="5" width="100" height="90" fill="#2670c0"/><circle cx="175" cy="50" r="40" fill="#c04020"/></svg>',
        'OEBPS/text/one.xhtml': page('First HTML title', '''<h1 id="start">First chapter</h1><p class="sample">Café — Ελληνικά — 日本語.</p>
<p><strong>Bold</strong>, <em>italic</em>, and <code>monospace</code>.</p>
<img src="../images/shapes.svg" alt="Blue rectangle and red circle"/>
<p><a href="second%20chapter.xhtml#second">Go to chapter two</a></p>
<p><a href="#note">Jump to footnote</a></p>''' + '<p>Scrolling and font size check.</p>' * 30 +
            '<p id="note">FOOTNOTE MARKER <a href="#start">Return to start</a></p><p>END OF CHAPTER ONE</p>'),
        'OEBPS/text/second chapter.xhtml': page('Second HTML title', '<h1 id="second">Second chapter</h1><p>END OF CHAPTER TWO</p><p><a href="one.xhtml#start">Back to chapter one</a></p><p><a href="appendix+notes.xhtml">Non-linear appendix</a></p>'),
        'OEBPS/text/appendix+notes.xhtml': page('Appendix', '<h1>Non-linear appendix</h1><p>APPENDIX MARKER</p>'),
    }
    fallback = {
        'mimetype': 'application/epub+zip', 'META-INF/container.xml': container(''),
        'book.opf': '<package><manifest><item id="a" href="one.html"/><item id="missing" href="missing.html"/><item id="b" href="two.xhtml"/></manifest><spine/></package>',
        'one.html': '<html><head><title>Untitled</title></head><body><h1>Heading <em>fallback</em></h1><p>FIRST FALLBACK MARKER</p></body></html>',
        'two.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head/><body><p>SECOND FALLBACK MARKER</p></body></html>',
    }
    epub3 = {
        'mimetype': 'application/epub+zip', 'META-INF/container.xml': container('EPUB/package.opf'),
        'EPUB/package.opf': '''<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">infinistream-epub3</dc:identifier><dc:title>EPUB 3 smoke check</dc:title><dc:creator>Synthetic Author</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-31T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="a" href="one.xhtml" media-type="application/xhtml+xml"/><item id="b" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>''',
        'EPUB/nav.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">NAV label one</a></li><li><a href="two.xhtml">NAV label two</a></li></ol></nav></body></html>',
        'EPUB/one.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>EPUB 3 first title</title></head><body><h1>EPUB 3 chapter one</h1></body></html>',
        'EPUB/two.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head/><body><h2>EPUB 3 second heading</h2></body></html>',
    }
    return {
        'sample-book.epub': (package(main), 'Café — EPUB migration', 'Synthetic Author', ['First chapter (NCX)', 'Second chapter (NCX)']),
        'sample-fallback-book.epub': (package(fallback), 'EPUB Reader', '', ['Heading fallback', 'Chapter 3']),
        'sample-epub3.epub': (package(epub3), 'EPUB 3 smoke check', 'Synthetic Author', ['EPUB 3 first title', 'EPUB 3 second heading']),
        'invalid-book.epub': (package({'not-a-book.txt': 'Deliberately missing EPUB container metadata.\n'}), None, None, []),
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    originals = fixtures()
    files = {name: data for name, (data, _, _, _) in originals.items()}
    files['epub-checks.zip'] = package({'documents/' + name: data for name, data in files.items()})
    expected = {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                       'title': title, 'author': author, 'chapters': chapters, 'valid': title is not None}
                for name, (data, title, author, chapters) in originals.items()}
    files['epub-expected.json'] = (json.dumps({'schema': 1, 'files': expected}, ensure_ascii=False, indent=2) + '\n').encode()
    created = []
    for name, data in files.items():
        path = OUT / name
        try:
            with path.open('xb') as output:
                output.write(data)
        except FileExistsError:
            print('Preserved ' + str(path.relative_to(ROOT)))
        else:
            created.append(path)
            print('Created ' + str(path.relative_to(ROOT)))
    if created:
        update_manifest(created)


if __name__ == '__main__':
    main()
