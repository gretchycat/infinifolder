#!/usr/bin/env python3
"""Synthetic PDF/PostScript/LaTeX fixtures; preserve existing (possibly edited) files."""
import hashlib
import json
import zipfile
import io

from fixture_paths import ROOT, update_manifest

OUT = ROOT / "documents"


def pdf():
    """Two pages, explicit byte offsets, built-in Helvetica, no external resources."""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [4 0 R 6 0 R] /Count 2 >>",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for width, height, title, marker in [
            (612, 792, "InfiniStream PDF - portrait page 1", "END OF PAGE ONE"),
            (792, 612, "InfiniStream PDF - landscape page 2", "END OF PAGE TWO")]:
        content_id = len(objects) + 2
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width} {height}] /Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>".encode())
        content = (f"BT /F1 22 Tf 48 {height - 60} Td ({title}) Tj ET\n"
                   "0.1 0.4 0.8 rg 48 180 180 120 re f\n"
                   "0.8 0.2 0.1 rg 260 180 100 120 re f\n"
                   f"0 g BT /F1 14 Tf 48 48 Td ({marker}) Tj ET\n").encode()
        objects.append(f"<< /Length {len(content)} >>\nstream\n".encode() + content + b"endstream")
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


def fixtures():
    ps = b"""%!PS-Adobe-3.0
%%Title: InfiniStream PostScript document check
%%Pages: 2
%%BoundingBox: 0 0 612 792
%%DocumentMedia: Letter 612 792 0 () ()
%%EndComments
%%Page: 1 1
<< /PageSize [612 792] >> setpagedevice
/Helvetica findfont 22 scalefont setfont
48 732 moveto (InfiniStream PostScript - page 1) show
0.1 0.4 0.8 setrgbcolor 48 180 180 120 rectfill
0.8 0.2 0.1 setrgbcolor 260 180 100 120 rectfill
0 setgray /Helvetica findfont 14 scalefont setfont
48 48 moveto (END OF PAGE ONE) show
showpage
%%Page: 2 2
<< /PageSize [792 612] >> setpagedevice
/Helvetica findfont 22 scalefont setfont
48 552 moveto (InfiniStream PostScript - landscape page 2) show
0.1 0.4 0.8 setrgbcolor 48 180 180 120 rectfill
0.8 0.2 0.1 setrgbcolor 260 180 100 120 rectfill
0 setgray /Helvetica findfont 14 scalefont setfont
48 48 moveto (END OF PAGE TWO) show
showpage
%%Trailer
%%EOF
"""
    eps = b"""%!PS-Adobe-3.0 EPSF-3.0
%%Title: InfiniStream vector EPS check
%%BoundingBox: 0 0 300 200
%%Pages: 1
%%EndComments
gsave
0.1 0.4 0.8 setrgbcolor 20 40 120 100 rectfill
0.8 0.2 0.1 setrgbcolor newpath 210 90 45 0 360 arc fill
0 setgray /Helvetica findfont 14 scalefont setfont
20 165 moveto (Vector EPS - text and shapes) show
grestore
showpage
%%EOF
"""
    tex = r"""\documentclass[12pt]{article}
\title{InfiniStream LaTeX document check}
\author{Synthetic test fixture}
\date{}
\begin{document}
\maketitle
\section{Text and formatting}
Normal text, \textbf{bold}, \textit{italic}, and \texttt{monospace}.
Escaped symbols: \% \& \_ \# \$ \{ \}.
\begin{itemize}
\item First list item.
\item Second list item with a footnote.\footnote{A local footnote.}
\end{itemize}
\section{Equations}
Inline math: $E=mc^2$. Display math:
\[
\sum_{k=1}^{n} k = \frac{n(n+1)}{2}, \qquad
\int_0^1 x^2\,dx = \frac{1}{3}.
\]
\begin{tabular}{|l|r|}\hline
Name & Value \\ \hline
Alpha & 1 \\ Beta & 2 \\ \hline
\end{tabular}
\par END OF PAGE ONE
\newpage
\section{Second page}
Navigation must reach this page and return to the first.
This document needs no images, bibliography, shell commands or network access.
\par END OF PAGE TWO
\end{document}
""".encode()
    return {
        "sample-pages.pdf": (pdf(), "Two pages: portrait 612x792 and landscape 792x612; blue/red rectangles, headings and end markers.", True),
        "sample-pages.ps": (ps, "Two PostScript pages matching the PDF shapes and markers; full vector/document rendering is planned.", False),
        "sample-vector.eps": (eps, "Single 300x200 vector EPS: blue rectangle, red circle and heading; full vector rendering is planned.", False),
        "sample-equations.tex": (tex, "Self-contained two-page LaTeX article: formatting, equations, list, table and footnote; typeset rendering is planned.", False),
        "invalid-document.pdf": (b"This is deliberately not a PDF.\n", "Negative check: clear open error; no crash or stuck spinner.", False),
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    originals = fixtures()
    files = {name: data for name, (data, _, _) in originals.items()}
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, data in files.items():
            entry = zipfile.ZipInfo("documents/" + name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, data)
    files["paged-document-checks.zip"] = archive.getvalue()
    expected = {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                       "purpose": purpose, "rendering_implemented": implemented}
                for name, (data, purpose, implemented) in originals.items()}
    files["paged-expected.json"] = (json.dumps({"schema": 1, "files": expected}, indent=2) + "\n").encode()
    created = []
    for name, data in files.items():
        path = OUT / name
        try:
            with path.open("xb") as output:
                output.write(data)
        except FileExistsError:
            print("Preserved " + str(path.relative_to(ROOT)))
        else:
            created.append(path)
            print("Created " + str(path.relative_to(ROOT)))
    if created:
        update_manifest(created)


if __name__ == "__main__":
    main()
