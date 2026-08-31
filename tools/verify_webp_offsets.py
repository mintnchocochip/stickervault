"""Verifies the byte offsets used by WebpProbe.kt against real WebP files.

This is a faithful port of the Kotlin parser. If the offsets are wrong, the
dimensions and animation flags will disagree with what Pillow reports, which is
exactly the bug that would silently mislabel an entire sticker library.
"""
import io
import os
import sys

from PIL import Image

OUT = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- the port ---

def u8(b, i):
    return b[i] & 0xFF


def le16(b, i):
    return u8(b, i) | (u8(b, i + 1) << 8)


def le24(b, i):
    return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16)


def le32(b, i):
    return (u8(b, i) | (u8(b, i + 1) << 8)
            | (u8(b, i + 2) << 16) | (u8(b, i + 3) << 24))


def tag_equals(b, off, tag):
    if len(b) < off + 4:
        return False
    return all(u8(b, off + i) == ord(tag[i]) for i in range(4))


def parse(b):
    """Mirrors WebpProbe.parse. Returns (w, h, animated, alpha) or None."""
    if len(b) < 16:
        return None
    if not tag_equals(b, 0, 'RIFF'):
        return None
    if not tag_equals(b, 8, 'WEBP'):
        return None

    if tag_equals(b, 12, 'VP8X'):
        if len(b) < 30:
            return None
        flags = u8(b, 20)
        return (le24(b, 24) + 1, le24(b, 27) + 1,
                (flags & 0x02) != 0, (flags & 0x10) != 0)

    if tag_equals(b, 12, 'VP8L'):
        if len(b) < 25:
            return None
        if u8(b, 20) != 0x2F:
            return None
        bits = le32(b, 21)
        return ((bits & 0x3FFF) + 1,
                ((bits >> 14) & 0x3FFF) + 1,
                False,
                ((bits >> 28) & 0x1) != 0)

    if tag_equals(b, 12, 'VP8 '):
        if len(b) < 30:
            return None
        if u8(b, 23) != 0x9D or u8(b, 24) != 0x01 or u8(b, 25) != 0x2A:
            return None
        return (le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF, False, False)

    return None


# ------------------------------------------------------------- test corpus ---

def make(name, size, mode, lossless=False, animated=False):
    w, h = size
    path = os.path.join(OUT, name)
    if animated:
        frames = []
        for shift in range(3):
            im = Image.new('RGBA', size, (10 * shift, 120, 200, 255))
            frames.append(im)
        frames[0].save(path, format='WEBP', save_all=True,
                       append_images=frames[1:], duration=120, loop=0)
    else:
        im = Image.new(mode, size, (200, 80, 60) if mode == 'RGB'
                       else (200, 80, 60, 128))
        im.save(path, format='WEBP', lossless=lossless)
    return path


def fourcc(b):
    return bytes(b[12:16]).decode('latin-1')


def main():
    cases = [
        # name,                       size,       mode,   lossless, animated
        ('static_lossy_512.webp',     (512, 512), 'RGB',  False, False),
        ('static_lossy_400x300.webp', (400, 300), 'RGB',  False, False),
        ('static_lossless.webp',      (512, 512), 'RGB',  True,  False),
        ('lossless_alpha.webp',       (256, 128), 'RGBA', True,  False),
        ('lossy_alpha.webp',          (512, 512), 'RGBA', False, False),
        ('animated.webp',             (512, 512), 'RGBA', False, True),
        ('animated_300x200.webp',     (300, 200), 'RGBA', False, True),
    ]

    failures = 0
    print('%-26s %-6s %-11s %-11s %-9s %s'
          % ('file', 'chunk', 'expected', 'probed', 'animated', 'result'))
    print('-' * 82)

    for name, size, mode, lossless, animated in cases:
        path = make(name, size, mode, lossless, animated)
        raw = io.open(path, 'rb').read()
        got = parse(raw)

        with Image.open(path) as im:
            real = im.size
            real_animated = getattr(im, 'n_frames', 1) > 1

        if got is None:
            print('%-26s %-6s %-11s %-11s %-9s FAIL (unparsed)'
                  % (name, fourcc(raw), '%dx%d' % real, '-', real_animated))
            failures += 1
            continue

        w, h, anim, alpha = got
        dims_ok = (w, h) == real
        anim_ok = anim == real_animated
        ok = dims_ok and anim_ok
        if not ok:
            failures += 1

        print('%-26s %-6s %-11s %-11s %-9s %s'
              % (name, fourcc(raw), '%dx%d' % real, '%dx%d' % (w, h),
                 str(anim), 'ok' if ok else
                 ('DIMS' if not dims_ok else '') + ('ANIM' if not anim_ok else '')))

    print()
    # A truncated or non-WebP file must be rejected, not misread.
    for junk, label in [(b'', 'empty'),
                        (b'not a webp file at all', 'garbage'),
                        (b'RIFF\x00\x00\x00\x00WEBPVP8 ', 'truncated')]:
        r = parse(junk)
        status = 'ok (rejected)' if r is None else 'FAIL accepted %s' % (r,)
        if r is not None:
            failures += 1
        print('%-26s %s' % (label, status))

    print()
    print('FAILURES: %d' % failures)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
