"""Throws malformed WebP payloads at the rule enforced by WebpValidator.kt.

WebpValidator is the gate that keeps anything that is not a structurally sound
WebP from being written into a sticker pack and served to WhatsApp's decoder -
the input class CVE-2023-4863 (libwebp) was exploited through. WebpProbe.kt, by
contrast, is a permissive sniffer and must not be relied on for this.

This is a faithful port of the Kotlin walk. It validates the *rule*; keep the
two in step by hand. Real WebP files are generated with Pillow and must pass;
a battery of crafted, truncated and inconsistent blobs must be rejected.

Run: python tools/verify_webp_validator.py    (requires Pillow)
"""
import io
import os
import struct
import sys

from PIL import Image

MIN_SIZE = 21
MAX_DIMENSION_SIMPLE = 1 << 14
MAX_DIMENSION_EXTENDED = 1 << 24


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


def tag(b, off, s):
    if len(b) < off + 4:
        return False
    return all(u8(b, off + i) == ord(s[i]) for i in range(4))


def ascii4(b, off):
    out = []
    for k in range(4):
        c = u8(b, off + k)
        if c < 0x20 or c > 0x7E:
            return None
        out.append(chr(c))
    return "".join(out)


def validate(b):
    """Mirrors WebpValidator.validate. Returns (w, h, animated, alpha) or a
    string reason for rejection."""
    if len(b) < MIN_SIZE:
        return "file too small"
    if not tag(b, 0, "RIFF"):
        return "missing RIFF signature"
    if not tag(b, 8, "WEBP"):
        return "missing WEBP signature"

    riff_size = le32(b, 4)
    expected = len(b) - 8
    if riff_size != expected and riff_size != expected - 1:
        return "RIFF size mismatch"

    present = []
    i = 12
    limit = len(b)
    while i + 8 <= limit:
        fourcc = ascii4(b, i)
        if fourcc is None:
            return "non-ASCII chunk id"
        size = le32(b, i + 4)
        if i + 8 + size > limit:
            return "chunk overruns file"
        present.append(fourcc)
        i += 8 + size + (size & 1)
    if i != limit and i != limit + 1:
        return "chunk walk did not consume file"
    if not present:
        return "no chunks"

    first = present[0]
    if first in ("VP8 ", "VP8L"):
        return _simple(b, first, present)
    if first == "VP8X":
        return _extended(b, present)
    return "first chunk not a bitstream"


def _simple(b, kind, present):
    if len(present) != 1:
        return "simple form has extra chunks"
    if kind == "VP8L":
        if len(b) < 25:
            return "VP8L truncated"
        if u8(b, 20) != 0x2F:
            return "bad VP8L signature"
        bits = le32(b, 21)
        w = (bits & 0x3FFF) + 1
        h = ((bits >> 14) & 0x3FFF) + 1
        if not (1 <= w <= MAX_DIMENSION_SIMPLE) or not (1 <= h <= MAX_DIMENSION_SIMPLE):
            return "implausible VP8L dims"
        return (w, h, False, ((bits >> 28) & 1) != 0)
    # "VP8 "
    if len(b) < 30:
        return "VP8 truncated"
    if u8(b, 23) != 0x9D or u8(b, 24) != 0x01 or u8(b, 25) != 0x2A:
        return "bad VP8 start code"
    w = le16(b, 26) & 0x3FFF
    h = le16(b, 28) & 0x3FFF
    if not (1 <= w < MAX_DIMENSION_SIMPLE) or not (1 <= h < MAX_DIMENSION_SIMPLE):
        return "implausible VP8 dims"
    return (w, h, False, False)


def _extended(b, present):
    if len(b) < 30:
        return "VP8X truncated"
    if le32(b, 16) != 10:
        return "VP8X chunk size not 10"
    flags = u8(b, 20)
    if flags & 0xC1:
        return "VP8X reserved bits set"
    animated = (flags & 0x02) != 0
    has_alpha = (flags & 0x10) != 0
    flag_exif = (flags & 0x08) != 0
    flag_xmp = (flags & 0x04) != 0
    flag_icc = (flags & 0x20) != 0

    w = le24(b, 24) + 1
    h = le24(b, 27) + 1
    if not (1 <= w <= MAX_DIMENSION_EXTENDED) or not (1 <= h <= MAX_DIMENSION_EXTENDED):
        return "implausible canvas"

    def has(x):
        return x in present

    anmf = present.count("ANMF")
    stills = present.count("VP8 ") + present.count("VP8L")

    if animated:
        if not has("ANIM"):
            return "anim flag, no ANIM"
        if anmf == 0:
            return "anim flag, no ANMF"
        if stills > 0:
            return "animated with top-level still"
    else:
        if stills != 1:
            return "expected one still bitstream"
        if has("ANIM") or anmf > 0:
            return "still with animation chunks"

    if has_alpha and not animated and has("VP8 ") and not has("ALPH"):
        return "alpha flag, no ALPH"
    if has("EXIF") != flag_exif:
        return "EXIF chunk/flag mismatch"
    if has("XMP ") != flag_xmp:
        return "XMP chunk/flag mismatch"
    if has("ICCP") != flag_icc:
        return "ICCP chunk/flag mismatch"

    return (w, h, animated, has_alpha)


# ------------------------------------------------------------- test corpus ---

def real_webp(size=(512, 512), mode="RGBA", lossless=False, animated=False):
    buf = io.BytesIO()
    if animated:
        frames = [Image.new("RGBA", size, (10 * s, 120, 200, 255)) for s in range(3)]
        frames[0].save(buf, format="WEBP", save_all=True, append_images=frames[1:],
                       duration=120, loop=0)
    else:
        Image.new(mode, size, (200, 80, 60, 255) if mode == "RGBA" else (200, 80, 60)) \
            .save(buf, format="WEBP", lossless=lossless)
    return buf.getvalue()


def riff(payload):
    return b"RIFF" + struct.pack("<I", len(payload)) + payload


def chunk(fourcc, data):
    out = fourcc + struct.pack("<I", len(data)) + data
    if len(data) & 1:
        out += b"\x00"
    return out


def vp8x_payload(flags, w, h):
    return (struct.pack("<B", flags) + b"\x00\x00\x00"
            + struct.pack("<I", w - 1)[:3] + struct.pack("<I", h - 1)[:3])


def main():
    real_static = real_webp()
    real_lossless = real_webp(lossless=True)
    real_anim = real_webp(animated=True)
    real_small = real_webp(size=(96, 96))

    # A minimal but structurally valid VP8X still: VP8X header + a fake-but-
    # framed VP8L chunk. Used as a base for "container ok, flags lie" cases.
    good_vp8x_still = b"WEBP" + chunk(b"VP8X", vp8x_payload(0x00, 400, 400)) \
        + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)

    cases = [
        # --- real files: must pass ---
        ("real lossy 512",        real_static,   True),
        ("real lossless 512",     real_lossless, True),
        ("real animated 512",     real_anim,     True),
        ("real static 96",        real_small,    True),

        # --- WhatsApp's own sticker shape: VP8X with alpha + embedded EXIF ---
        ("WhatsApp-style static (VP8X+ALPH+VP8 +EXIF)",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x10 | 0x08, 512, 512))
              + chunk(b"ALPH", b"\x00" * 10) + chunk(b"VP8 ", b"\x00" * 20)
              + chunk(b"EXIF", b'{"sticker-pack-name":"x"}')), True),
        ("WhatsApp-style animated (VP8X+ANIM+2xANMF+EXIF)",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x02 | 0x10 | 0x08, 512, 512))
              + chunk(b"ANIM", b"\x00" * 6) + chunk(b"ANMF", b"\x00" * 32)
              + chunk(b"ANMF", b"\x00" * 32) + chunk(b"EXIF", b"{}")), True),

        # --- the crafted-blob attack WebpProbe waved through ---
        ("VP8X header + padding, no bitstream",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x00, 512, 512))), False),
        ("VP8X animation flag set, no ANIM/ANMF",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x02, 512, 512))
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)), False),

        # --- truncation / lying sizes ---
        ("empty", b"", False),
        ("garbage", b"not a webp at all, really not", False),
        ("RIFF/WEBP only", b"RIFF\x04\x00\x00\x00WEBP", False),
        ("chunk size overruns file",
         riff(b"WEBP" + b"VP8 " + struct.pack("<I", 9999) + b"\x00" * 8), False),
        ("RIFF size larger than file",
         b"RIFF" + struct.pack("<I", 9999) + b"WEBP"
         + chunk(b"VP8L", b"\x2f" + b"\x00" * 8), False),
        ("RIFF size smaller than file",
         b"RIFF" + struct.pack("<I", 4) + b"WEBP"
         + chunk(b"VP8L", b"\x2f" + b"\x00" * 8), False),
        ("trailing garbage after last chunk",
         riff(b"WEBP" + chunk(b"VP8L", b"\x2f" + b"\x00" * 8) + b"TRAILING!"), False),
        ("truncated mid VP8L header",
         riff(b"WEBP" + b"VP8L" + struct.pack("<I", 5) + b"\x2f\x00"), False),

        # --- type confusion ---
        ("PNG renamed",
         b"\x89PNG\r\n\x1a\n" + b"\x00" * 40, False),
        ("first chunk is EXIF",
         riff(b"WEBP" + chunk(b"EXIF", b"{}") + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)),
         False),

        # --- VP8X flag / chunk inconsistency ---
        ("VP8X wrong header length",
         riff(b"WEBP" + b"VP8X" + struct.pack("<I", 8) + b"\x00" * 8
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)), False),
        ("VP8X reserved bit set",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x01, 400, 400))
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)), False),
        ("EXIF flag set, no EXIF chunk",
         riff(good_vp8x_still.replace(
             chunk(b"VP8X", vp8x_payload(0x00, 400, 400)),
             chunk(b"VP8X", vp8x_payload(0x08, 400, 400)))), False),
        ("EXIF chunk present, flag not set",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x00, 400, 400))
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8) + chunk(b"EXIF", b"{}")), False),
        ("two still bitstreams",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x00, 400, 400))
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)), False),
        ("still VP8X carrying an ANMF frame",
         riff(b"WEBP" + chunk(b"VP8X", vp8x_payload(0x00, 400, 400))
              + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)
              + chunk(b"ANMF", b"\x00" * 16)), False),
        ("simple VP8L with an extra chunk",
         riff(b"WEBP" + chunk(b"VP8L", b"\x2f" + b"\x00" * 8)
              + chunk(b"EXIF", b"{}")), False),
        ("bad VP8L signature byte",
         riff(b"WEBP" + chunk(b"VP8L", b"\x00" + b"\x00" * 8)), False),
    ]

    failures = 0
    print("%-6s %-8s %s" % ("result", "expect", "case"))
    print("-" * 72)
    for name, data, should_pass in cases:
        got = validate(bytearray(data))
        passed = isinstance(got, tuple)
        ok = passed == should_pass
        if not ok:
            failures += 1
        detail = "" if ok else ("  <- got %r" % (got,))
        print("%-6s %-8s %s%s" % (
            "ok" if ok else "FAIL",
            "accept" if should_pass else "reject",
            name, detail))

    # Real files must also report the right shape.
    print()
    checks = [
        ("real lossy 512", real_static, (512, 512, False)),
        ("real animated 512", real_anim, (512, 512, True)),
    ]
    for name, data, expect in checks:
        got = validate(bytearray(data))
        if not isinstance(got, tuple) or got[:3] != expect:
            print("FAIL %s: got %r, expected %r" % (name, got, expect))
            failures += 1
        else:
            print("ok   %s -> %r" % (name, got))

    print()
    print("%d cases, %d failures" % (len(cases) + len(checks), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
