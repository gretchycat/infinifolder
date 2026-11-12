#!/usr/bin/env python3
"""Convert a palette-annotated SVG into a color-cycling Amiga ILBM IFF.

Requires Pillow and librsvg's ``rsvg-convert``. Set output dimensions explicitly.
Artwork is fitted without distortion and centered on the transparent canvas, or
within ``--content-box`` when provided. Oversized SVG palettes are sampled down
to IFF's 256-color limit; cycle ranges and speeds are remapped. Examples:

    python3 scripts/svg-to-iff.py --width 900 --height 450
    python3 scripts/svg-to-iff.py artwork.svg artwork.iff --width 1024 --height 1024
    python3 scripts/svg-to-iff.py artwork.svg overlay.iff --width 275 --height 116 \\
        --content-box 242 90 21 17
    python3 scripts/svg-to-iff.py artwork.svg composite.iff --width 275 --height 116 \\
        --content-box 242 90 21 17 --background main.bmp
"""

from __future__ import annotations

import argparse
import math
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

try:
    from PIL import Image
except ImportError as error:  # pragma: no cover - depends on the host setup
    raise SystemExit("Pillow is required; install it with: python3 -m pip install Pillow") from error


ROOT = Path(__file__).resolve().parents[1]
INFINI_NS = "https://infinicanvas.org/svg"
MAX_PIXELS = 4_000_000
MAX_DIMENSION = 32_767
MAX_SOURCE_COLORS = 1_000_000


@dataclass(frozen=True)
class CycleRange:
    start: int
    end: int
    steps_per_second: float
    direction: str


def parse_color(value: str) -> tuple[int, int, int]:
    """Parse an SVG #RGB or #RRGGBB palette value."""
    color = value.removeprefix("#")
    if len(color) == 3:
        color = "".join(component * 2 for component in color)
    if len(color) != 6:
        raise ValueError(f"Unsupported palette color {value!r}; expected #RGB or #RRGGBB")
    try:
        return tuple(int(color[offset:offset + 2], 16) for offset in (0, 2, 4))
    except ValueError as error:
        raise ValueError(f"Invalid palette color {value!r}") from error


def read_palette_and_cycles(svg_path: Path) -> tuple[list[tuple[int, int, int]], list[CycleRange]]:
    """Read the SVG's source palette and its Infini color-cycle definitions."""
    root = ET.parse(svg_path).getroot()
    palette_tag = f"{{{INFINI_NS}}}palette"
    color_tag = f"{{{INFINI_NS}}}color"
    cycle_tag = f"{{{INFINI_NS}}}cycle"
    palette_node = root.find(f".//{palette_tag}")
    if palette_node is None:
        raise ValueError("SVG has no Infini indexed palette metadata")

    color_nodes = palette_node.findall(color_tag)
    declared_size = palette_node.get("size")
    size = int(declared_size) if declared_size is not None else 1 + max(
        (int(node.attrib["index"]) for node in color_nodes), default=-1
    )
    if not 1 <= size <= MAX_SOURCE_COLORS:
        raise ValueError(f"SVG palettes must contain 1 to {MAX_SOURCE_COLORS:,} colors, got {size}")
    palette_id = palette_node.get("id")
    entries: list[tuple[int, int, int] | None] = [None] * size
    for node in color_nodes:
        index = int(node.attrib["index"])
        if not 0 <= index < size:
            raise ValueError(f"Palette index {index} is outside the declared palette")
        if entries[index] is not None:
            raise ValueError(f"Duplicate palette index {index}")
        entries[index] = parse_color(node.attrib["value"])
    missing = [index for index, color in enumerate(entries) if color is None]
    if missing:
        raise ValueError(f"Palette is missing color indices: {missing[:8]}")

    cycles = root.findall(f".//{cycle_tag}")
    cycle_ranges: list[CycleRange] = []
    ranges: list[tuple[int, int]] = []
    for node in cycles:
        if palette_id is not None and node.get("palette", palette_id) != palette_id:
            raise ValueError("All SVG cycle definitions must reference the declared palette")
        if node.get("unit", "steps-per-second") != "steps-per-second":
            raise ValueError("IFF CRNG conversion supports unit='steps-per-second'")
        if node.get("repeat", "indefinite") != "indefinite":
            raise ValueError("IFF CRNG ranges cycle continuously; finite repeat is unsupported")

        first = int(node.attrib["start"])
        last = int(node.attrib["end"])
        if not 0 <= first <= last < size:
            raise ValueError(f"Invalid color-cycle range {first}..{last}")
        if any(first <= prior_last and prior_first <= last for prior_first, prior_last in ranges):
            raise ValueError(f"Overlapping color-cycle range {first}..{last}")
        ranges.append((first, last))

        steps_per_second = float(node.attrib["rate"])
        if not math.isfinite(steps_per_second) or steps_per_second <= 0:
            raise ValueError("Color-cycle rate must be a positive finite number")
        # CRNG rate is measured in 1/16384 jiffies (1/60 second).
        direction = node.get("direction", "forward")
        if direction not in ("forward", "reverse"):
            raise ValueError(f"Unsupported color-cycle direction {direction!r}")
        cycle_ranges.append(CycleRange(first, last, steps_per_second, direction))

    source_palette = [color for color in entries if color is not None]
    return source_palette, cycle_ranges


def downsample_palette(source: list[tuple[int, int, int]],
                       cycles: list[CycleRange]) -> tuple[list[tuple[int, int, int]], int, list[bytes], list[str]]:
    """Reduce any source palette to 256 colors while retaining cycle ranges.

    Source ranges remain contiguous in the output palette. If a range is
    reduced, its CRNG rate is reduced proportionally so one full palette turn
    takes approximately the same time as it did before sampling.
    """
    ordered_cycles = sorted(cycles, key=lambda cycle: (cycle.start, cycle.end))
    groups: list[tuple[int, int, CycleRange | None]] = []
    cursor = 0
    for cycle in ordered_cycles:
        if cursor < cycle.start:
            groups.append((cursor, cycle.start - 1, None))
        # A one-color range cannot rotate. Keep that color, but omit its CRNG.
        groups.append((cycle.start, cycle.end, cycle if cycle.start < cycle.end else None))
        cursor = cycle.end + 1
    if cursor < len(source):
        groups.append((cursor, len(source) - 1, None))
    if not groups:
        groups.append((0, len(source) - 1, None))

    lengths = [end - start + 1 for start, end, _ in groups]
    target_count = min(256, len(source))
    slots = [min(length, 2 if cycle is not None else 1)
             for length, (_, _, cycle) in zip(lengths, groups)]
    if sum(slots) > target_count:
        raise ValueError("Too many separate cycle ranges to fit in a 256-color IFF palette")

    remaining = target_count - sum(slots)
    capacities = [length - allocated for length, allocated in zip(lengths, slots)]
    total_capacity = sum(capacities)
    if remaining and total_capacity:
        quotas = [remaining * capacity / total_capacity for capacity in capacities]
        additions = [min(capacity, math.floor(quota)) for capacity, quota in zip(capacities, quotas)]
        for index, addition in enumerate(additions):
            slots[index] += addition
        remaining -= sum(additions)
        order = sorted(range(len(groups)), key=lambda index: quotas[index] - math.floor(quotas[index]),
                       reverse=True)
        for index in order:
            if remaining == 0:
                break
            if slots[index] < lengths[index]:
                slots[index] += 1
                remaining -= 1

    selected: list[tuple[int, int, int]] = []
    output_cycles: list[bytes] = []
    warnings: list[str] = []
    for (start, end, cycle), count in zip(groups, slots):
        source_length = end - start + 1
        group_output_start = len(selected)
        if count == source_length:
            sample_indices = list(range(start, end + 1))
        elif count == 1:
            sample_indices = [(start + end) // 2]
        else:
            sample_indices = [start + round(index * (source_length - 1) / (count - 1))
                              for index in range(count)]
        selected.extend(source[index] for index in sample_indices)

        if cycle is None:
            continue
        output_start = group_output_start
        output_end = output_start + count - 1
        output_rate = cycle.steps_per_second * count / source_length
        rate = round(output_rate * 16384 / 60)
        if rate < 1:
            rate = 1
            warnings.append(f"Cycle {start}..{end} is slower than CRNG timing can encode; using its minimum rate")
        if rate > 65535:
            raise ValueError(f"Cycle {start}..{end} rate is outside the IFF CRNG range")
        flags = 1 | (2 if cycle.direction == "reverse" else 0)  # active, optionally reverse
        output_cycles.append(struct.pack(">HHHBB", 0, rate, flags, output_start, output_end))

    if len(source) > len(selected):
        warnings.insert(0, f"Reduced source palette from {len(source)} to {len(selected)} colors for 8-bit ILBM")
    # CMAP uses the same 256-register table for all outputs. Duplicate the last
    # real color in unused slots; indexed_pixels folds those padded indices back.
    padded = selected + [selected[-1]] * (256 - len(selected))
    return padded, len(selected), output_cycles, warnings


def svg_aspect_ratio(svg_path: Path) -> float:
    """Return the SVG's intrinsic viewport ratio, including simple unit suffixes."""
    root = ET.parse(svg_path).getroot()

    def length(value: str | None) -> float | None:
        if value is None:
            return None
        match = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:px)?\s*", value)
        return float(match.group(1)) if match else None

    width = length(root.get("width"))
    height = length(root.get("height"))
    if width and height:
        return width / height
    view_box = root.get("viewBox")
    if view_box:
        values = [float(value) for value in re.split(r"[\s,]+", view_box.strip())]
        if len(values) == 4 and values[2] > 0 and values[3] > 0:
            return values[2] / values[3]
    raise ValueError("SVG needs positive width/height or a valid viewBox to preserve its aspect ratio")


def render_svg(svg_path: Path, width: int, height: int, renderer: str,
               content_box: tuple[int, int, int, int] | None = None) -> Image.Image:
    """Render into a transparent canvas, fitting and centering the SVG without distortion."""
    box_x, box_y, box_width, box_height = content_box or (0, 0, width, height)
    aspect = svg_aspect_ratio(svg_path)
    if aspect > box_width / box_height:
        rendered_width = box_width
        rendered_height = max(1, round(box_width / aspect))
    else:
        rendered_height = box_height
        rendered_width = max(1, round(box_height * aspect))

    with tempfile.TemporaryDirectory(prefix="svg-to-iff-") as temp_dir:
        png_path = Path(temp_dir) / "render.png"
        subprocess.run(
            [renderer, "--format=png", "--width", str(rendered_width), "--height", str(rendered_height),
             "--output", str(png_path), str(svg_path)],
            check=True,
        )
        with Image.open(png_path) as rendered:
            artwork = rendered.convert("RGBA")
    if artwork.size != (rendered_width, rendered_height):
        raise ValueError(
            f"SVG renderer returned {artwork.width}x{artwork.height}, "
            f"expected {rendered_width}x{rendered_height}"
        )
    canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    paste_x = box_x + (box_width - rendered_width) // 2
    paste_y = box_y + (box_height - rendered_height) // 2
    canvas.alpha_composite(artwork, (paste_x, paste_y))
    return canvas


def indexed_pixels(image: Image.Image, palette: list[tuple[int, int, int]],
                   palette_size: int) -> tuple[bytes, bytes]:
    """Map rendered RGB pixels to the SVG palette and produce a binary mask."""
    palette_image = Image.new("P", (1, 1))
    palette_image.putpalette([component for color in palette for component in color])
    rgb = image.convert("RGB")
    indexed = rgb.quantize(palette=palette_image, dither=Image.Dither.NONE)
    indices = bytearray(indexed.tobytes())
    # Palette padding duplicates the final real entry. Canonicalize any such
    # indices so unused CMAP registers cannot fall outside active cycle ranges.
    for pixel, index in enumerate(indices):
        if index >= palette_size:
            indices[pixel] = palette_size - 1
    return bytes(indices), image.getchannel("A").tobytes()


def background_palette(image: Image.Image, color_count: int = 128) -> list[tuple[int, int, int]]:
    """Build a compact RGB palette for a raster background."""
    quantized = image.convert("RGB").quantize(
        colors=color_count, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE
    )
    colors = quantized.getpalette()
    used = sorted(set(quantized.tobytes()))
    return [tuple(colors[index * 3:index * 3 + 3]) for index in used]


def indexed_composite(overlay: Image.Image, background: Image.Image,
                      svg_palette: list[tuple[int, int, int]],
                      output_palette: list[tuple[int, int, int]], palette_size: int,
                      source_cycles: list[CycleRange], output_cycles: list[bytes],
                      alpha_threshold: int) -> tuple[bytes, bytes]:
    """Index a full background image while keeping SVG cycle pixels in CRNG ranges."""
    composite = Image.alpha_composite(background.convert("RGBA"), overlay)
    cycle_output_ranges = [struct.unpack(">HHHBB", chunk)[3:5] for chunk in output_cycles]
    classification_palette, _, classification_cycles, _ = downsample_palette(
        svg_palette, source_cycles
    )
    active_source_ranges = [struct.unpack(">HHHBB", chunk)[3:5]
                            for chunk in classification_cycles]
    static_indices = [index for index in range(palette_size)
                      if not any(start <= index <= end for start, end in cycle_output_ranges)]
    if not static_indices:
        raise ValueError("The output palette has no colors available for the background")

    static_palette = [output_palette[index] for index in static_indices]
    padded_static = static_palette + [static_palette[-1]] * (256 - len(static_palette))
    static_image = Image.new("P", (1, 1))
    static_image.putpalette([component for color in padded_static for component in color])
    static_quantized = composite.convert("RGB").quantize(
        palette=static_image, dither=Image.Dither.FLOYDSTEINBERG
    )
    indices = bytearray(static_indices[min(index, len(static_indices) - 1)]
                        for index in static_quantized.tobytes())

    if active_source_ranges:
        source_palette_image = Image.new("P", (1, 1))
        padded_source = classification_palette + [classification_palette[-1]] * (256 - len(classification_palette))
        source_palette_image.putpalette([component for color in padded_source for component in color])
        source_indices = overlay.convert("RGB").quantize(
            palette=source_palette_image, dither=Image.Dither.NONE
        ).tobytes()
        overlay_alpha = overlay.getchannel("A").tobytes()
        output_ranges = cycle_output_ranges
        output_start = 0
        for (source_start, source_end), (output_start, output_end) in zip(
                active_source_ranges, output_ranges):
            candidates = output_palette[output_start:output_end + 1]
            for pixel, (source_index, alpha) in enumerate(zip(source_indices, overlay_alpha)):
                if (alpha < alpha_threshold
                        or not source_start <= source_index <= source_end):
                    continue
                rgb = overlay.getpixel((pixel % overlay.width, pixel // overlay.width))[:3]
                closest = min(range(len(candidates)), key=lambda offset: sum(
                    (rgb[channel] - candidates[offset][channel]) ** 2 for channel in range(3)
                ))
                indices[pixel] = output_start + closest

    return bytes(indices), composite.getchannel("A").tobytes()


def planar_body(indices: bytes, alpha: bytes, width: int, height: int,
                alpha_threshold: int) -> bytes:
    """Encode 8 indexed bitplanes plus an ILBM transparency mask, uncompressed."""
    row_bytes = ((width + 15) // 16) * 2  # ILBM scanlines are word aligned.
    body = bytearray()
    for y in range(height):
        row_start = y * width
        index_row = indices[row_start:row_start + width]
        alpha_row = alpha[row_start:row_start + width]
        for plane in range(8):
            bit = 1 << plane
            for byte_start in range(0, row_bytes * 8, 8):
                packed = 0
                for bit_index in range(8):
                    x = byte_start + bit_index
                    if x < width and index_row[x] & bit:
                        packed |= 0x80 >> bit_index
                body.append(packed)
        # An ILBM mask plane can represent only fully transparent/opaque pixels.
        for byte_start in range(0, row_bytes * 8, 8):
            packed = 0
            for bit_index in range(8):
                x = byte_start + bit_index
                if x < width and alpha_row[x] >= alpha_threshold:
                    packed |= 0x80 >> bit_index
            body.append(packed)
    return bytes(body)


def iff_chunk(name: bytes, data: bytes) -> bytes:
    return name + struct.pack(">I", len(data)) + data + (b"\0" if len(data) & 1 else b"")


def write_ilbm(output: Path, width: int, height: int,
               palette: list[tuple[int, int, int]], cycles: list[bytes],
               body: bytes) -> None:
    # BMHD: 8 indexed planes, a separate transparency mask, and raw BODY bytes.
    bitmap_header = struct.pack(
        ">HHhhBBBBHBBhh", width, height, 0, 0, 8, 1, 0, 0, 0,
        1, 1, width, height,
    )
    color_map = bytes(component for color in palette for component in color)
    form_data = bytearray(b"ILBM")
    form_data.extend(iff_chunk(b"BMHD", bitmap_header))
    form_data.extend(iff_chunk(b"CMAP", color_map))
    for cycle in cycles:
        form_data.extend(iff_chunk(b"CRNG", cycle))
    form_data.extend(iff_chunk(b"BODY", body))
    output.write_bytes(b"FORM" + struct.pack(">I", len(form_data)) + form_data)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path, default=ROOT / "infini.svg",
                        help="source SVG (default: repository-root infini.svg)")
    parser.add_argument("output", nargs="?", type=Path, default=ROOT / "build/generated/infini.iff",
                        help="destination IFF (default: build/generated/infini.iff)")
    parser.add_argument("--width", type=int, required=True, help="output width in pixels")
    parser.add_argument("--height", type=int, required=True, help="output height in pixels")
    parser.add_argument("--content-box", type=int, nargs=4, metavar=("X", "Y", "WIDTH", "HEIGHT"),
                        help="optional canvas region in which to center the aspect-fitted SVG")
    parser.add_argument("--background", type=Path,
                        help="opaque image to use as the ILBM base, with palette colors reserved for it")
    parser.add_argument("--renderer", default=shutil.which("rsvg-convert") or "rsvg-convert",
                        help="librsvg rsvg-convert executable")
    parser.add_argument("--alpha-threshold", type=int, default=128,
                        help="alpha cutoff for the binary IFF mask (0-255; default: 128)")
    args = parser.parse_args()

    if not args.input.is_file():
        parser.error(f"input SVG does not exist: {args.input}")
    if args.width <= 0 or args.height <= 0 or max(args.width, args.height) > MAX_DIMENSION:
        parser.error(f"dimensions must be between 1 and {MAX_DIMENSION} pixels")
    if args.width * args.height > MAX_PIXELS:
        parser.error(f"dimensions exceed the {MAX_PIXELS:,}-pixel cycling limit")
    if args.content_box:
        x, y, box_width, box_height = args.content_box
        if (x < 0 or y < 0 or box_width <= 0 or box_height <= 0
                or x + box_width > args.width or y + box_height > args.height):
            parser.error("--content-box must be a positive rectangle within the output canvas")
    if not 0 <= args.alpha_threshold <= 255:
        parser.error("--alpha-threshold must be between 0 and 255")
    if args.input.resolve() == args.output.resolve():
        parser.error("input and output paths must differ")
    if args.background is not None and not args.background.is_file():
        parser.error(f"background image does not exist: {args.background}")
    if args.background is not None and args.background.resolve() == args.output.resolve():
        parser.error("background and output paths must differ")
    if shutil.which(args.renderer) is None and not Path(args.renderer).is_file():
        parser.error(f"SVG renderer not found: {args.renderer}")

    try:
        source_palette, source_cycles = read_palette_and_cycles(args.input)
        background = None
        palette_source = source_palette
        if args.background is not None:
            with Image.open(args.background) as base:
                background = base.convert("RGB")
            if background.size != (args.width, args.height):
                raise ValueError(
                    f"Background is {background.width}x{background.height}; "
                    f"expected {args.width}x{args.height}"
                )
            palette_source = source_palette + background_palette(background)
        palette, palette_size, cycles, warnings = downsample_palette(palette_source, source_cycles)
        rgba = render_svg(args.input, args.width, args.height, args.renderer,
                          tuple(args.content_box) if args.content_box else None)
        if background is None:
            indices, alpha = indexed_pixels(rgba, palette, palette_size)
        else:
            indices, alpha = indexed_composite(
                rgba, background, source_palette, palette, palette_size,
                source_cycles, cycles, args.alpha_threshold,
            )
        body = planar_body(indices, alpha, args.width, args.height, args.alpha_threshold)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        write_ilbm(args.output, args.width, args.height, palette, cycles, body)
    except (ET.ParseError, OSError, ValueError, KeyError, struct.error,
            subprocess.CalledProcessError) as error:
        print(f"SVG to IFF conversion failed: {error}", file=sys.stderr)
        return 1

    for warning in warnings:
        print(f"Warning: {warning}", file=sys.stderr)
    cycle_label = f" with {len(cycles)} color cycle(s)" if cycles else " (static palette)"
    print(f"Wrote {args.width}x{args.height} ILBM{cycle_label}: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
