# SVG-to-IFF authoring tool

`scripts/svg-to-iff.py` imports the revised converter supplied with Media's custom theme. It requires Python 3, Pillow and `rsvg-convert`; these are authoring prerequisites, not Android build dependencies. Pass source/output paths explicitly.

```sh
python3 scripts/svg-to-iff.py artwork.svg overlay.iff --width 275 --height 116 \
  --content-box 242 90 21 17
python3 scripts/svg-to-iff.py artwork.svg faceplate.iff --width 275 --height 116 \
  --content-box 242 90 21 17 --background main.bmp
```

The SVG is fitted and centered without stretching, either across the output or inside the requested content box. A background must match the output dimensions. Its quantized palette slots stay outside active CRNG ranges; foreground cycling retains its ranges and timing. SVG palettes larger than 256 entries are downsampled with remapped range rates. Output uses an indexed ILBM with a binary transparency mask. The background option produces a merged opaque faceplate. Source SVG/background paths cannot also be the output path.

Run `python3 scripts/test-svg-to-iff.py` to exercise the real renderer, fit/centering, static background indices, cycling foreground indices, alpha mask and invalid inputs. Test outputs are disposable. This script does not need to generate runtime themes: Canvas also supports the indexed SVG namespace directly.
