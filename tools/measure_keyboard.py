#!/usr/bin/env python3
"""Measure keyboard key geometry from a screenshot, in dp.

Approach:
  1. Find key ROWS automatically: a scanline crossing a row of keys has many colour transitions
     (one pair per key edge); a scanline in the gap between rows has almost none.
  2. For each row, take the background colour from the gap line just above it, then split the
     row's mid-line into runs of non-background pixels — those are the keys.
  3. Report key width, column pitch, side margins and row height, all converted to dp.
"""
import sys
from PIL import Image

DENSITY = 420 / 160.0  # emulator: 420 dpi


def dp(v):
    return v / DENSITY


def diff(a, b):
    return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])


def transitions(px, w, y, thr=24):
    n = 0
    prev = px[0, y][:3]
    for x in range(1, w):
        c = px[x, y][:3]
        if diff(c, prev) > thr:
            n += 1
        prev = c
    return n


def find_rows(img, y_from, y_to, min_tx=8):
    """Bands of consecutive scanlines that look like a row of keys."""
    px = img.load()
    w = img.width
    active = [y for y in range(y_from, y_to) if transitions(px, w, y) >= min_tx]
    bands, start, prev = [], None, None
    for y in active:
        if start is None:
            start, prev = y, y
        elif y - prev > 4:            # a gap ends the band
            bands.append((start, prev))
            start, prev = y, y
        else:
            prev = y
    if start is not None:
        bands.append((start, prev))
    return [b for b in bands if b[1] - b[0] >= 12]


def runs_at(img, y, bg, tol=20, min_w=8):
    px = img.load()
    out, start = [], None
    for x in range(img.width):
        on = diff(px[x, y][:3], bg) > tol
        if on and start is None:
            start = x
        elif not on and start is not None:
            if x - start >= min_w:
                out.append((start, x))
            start = None
    if start is not None and img.width - start >= min_w:
        out.append((start, img.width))
    return out


def runs_same(img, y, key_c, tol=14, min_w=10):
    """Runs along scanline y whose colour matches the key colour."""
    px = img.load()
    out, start = [], None
    for x in range(img.width):
        on = diff(px[x, y][:3], key_c) <= tol
        if on and start is None:
            start = x
        elif not on and start is not None:
            if x - start >= min_w:
                out.append((start, x))
            start = None
    if start is not None and img.width - start >= min_w:
        out.append((start, img.width))
    return out


def analyse(path, label, y_from, y_to):
    img = Image.open(path).convert("RGB")
    px = img.load()
    print(f"\n=== {label} ===")
    print(f"screen {img.width}x{img.height}px = {dp(img.width):.1f}x{dp(img.height):.1f}dp")
    bands = find_rows(img, y_from, y_to)
    if not bands:
        print("  no key rows found")
        return
    print(f"keyboard top (first key row) = y{bands[0][0]}px, "
          f"bottom = y{bands[-1][1]}px, "
          f"key area height = {dp(bands[-1][1] - bands[0][0]):.1f}dp")
    prev_bottom = None
    for i, (top, bot) in enumerate(bands):
        # Scan the UPPER part of the key, above the glyph: there the key is a solid block, so a
        # key is one clean run. A mid-height scanline is cut in two by the letter drawn on it.
        # KEY colour = the most common colour in the band (keys cover far more area than the gaps
        # between them). Matching keys directly is robust; guessing the background is not, because
        # a sample taken just above the row can land on an antialiased key edge and invert everything.
        counts = {}
        for yy in range(top, bot):
            for xx in range(0, img.width, 3):
                c = px[xx, yy][:3]
                counts[c] = counts.get(c, 0) + 1
        key_c = max(counts.items(), key=lambda kv: kv[1])[0]
        # Vertical projection: a column INSIDE a key is key-coloured for most of the row's height;
        # a column in the gap between keys is key-coloured almost nowhere. Robust against the glyph
        # (which only eats part of a column) and against rounded corners (only the extreme rows).
        h = bot - top
        cols = []
        for xx in range(img.width):
            n = sum(1 for yy in range(top, bot)
                    if diff(px[xx, yy][:3], key_c) <= 14)
            cols.append(n)
        rs, start = [], None
        for xx, n in enumerate(cols):
            on = n > h * 0.35
            if on and start is None:
                start = xx
            elif not on and start is not None:
                if xx - start >= 10:
                    rs.append((start, xx))
                start = None
        if start is not None and img.width - start >= 10:
            rs.append((start, img.width))
        if not rs:
            print(f"  row{i}: y{top}-{bot} ({dp(bot-top):.1f}dp tall) — keys not resolved")
            continue
        widths = [dp(b - a) for a, b in rs]
        cs = [(a + b) / 2 for a, b in rs]
        pitches = [dp(cs[j + 1] - cs[j]) for j in range(len(cs) - 1)]
        pitch_txt = (f"{min(pitches):.1f}-{max(pitches):.1f}" if pitches else "n/a")
        step = f" | row step={dp(top - prev_bottom):.1f}dp gap" if prev_bottom else ""
        print(f"  row{i}: y{top}-{bot}  height={dp(bot-top):.1f}dp  keys={len(rs)}  "
              f"w={min(widths):.1f}-{max(widths):.1f}dp  pitch={pitch_txt}dp  "
              f"L={dp(rs[0][0]):.1f} R={dp(img.width-rs[-1][1]):.1f}dp{step}")
        print("        centres: " + " ".join(f"{dp(c):.0f}" for c in cs))
        prev_bottom = bot


if __name__ == "__main__":
    analyse(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
