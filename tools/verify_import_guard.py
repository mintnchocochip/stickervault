"""Tests the archive entry-name allowlist used by VaultImporter.

The importer accepts an entry only if its name matches, exactly:

    ^stickers/([0-9a-f]{64})\\.webp$

This mirrors that rule and throws a battery of path-traversal, type-confusion and
encoding payloads at it. It validates the *rule*, not the Kotlin - the two must
be kept in step by hand.

Run: python tools/verify_import_guard.py
"""
import re
import sys

RULE = re.compile(r"stickers/([0-9a-f]{64})\.webp")


def accepts(name):
    """Mirrors VaultImporter.stickerHashOrNull."""
    if len(name) > 512:
        return None
    # Control characters are refused before the pattern is consulted.
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in name):
        return None
    # fullmatch models Kotlin's matchEntire: the whole string must match.
    m = RULE.fullmatch(name)
    return m.group(1) if m else None
GOOD = "a" * 64

# (entry name, should be accepted)
CASES = [
    # --- legitimate ---
    ("stickers/" + GOOD + ".webp", True),
    ("stickers/" + "0123456789abcdef" * 4 + ".webp", True),

    # --- path traversal ---
    ("../../../etc/passwd", False),
    ("stickers/../../../evil.webp", False),
    ("stickers/../" + GOOD + ".webp", False),
    ("../stickers/" + GOOD + ".webp", False),
    ("stickers/subdir/" + GOOD + ".webp", False),
    ("/absolute/stickers/" + GOOD + ".webp", False),
    ("/stickers/" + GOOD + ".webp", False),
    ("C:/windows/system32/" + GOOD + ".webp", False),
    ("\\\\server\\share\\" + GOOD + ".webp", False),
    ("stickers\\..\\..\\evil.webp", False),
    ("stickers/" + GOOD + "/../../evil.webp", False),

    # --- type confusion / double extension ---
    ("stickers/" + GOOD + ".webp.exe", False),
    ("stickers/" + GOOD + ".exe", False),
    ("stickers/" + GOOD + ".webp.sh", False),
    ("stickers/" + GOOD + ".WEBP", False),
    ("stickers/" + GOOD, False),

    # --- hash shape violations ---
    ("stickers/" + "a" * 63 + ".webp", False),
    ("stickers/" + "a" * 65 + ".webp", False),
    ("stickers/" + "A" * 64 + ".webp", False),      # uppercase hex
    ("stickers/" + "g" * 64 + ".webp", False),      # non-hex
    ("stickers/" + "a" * 32 + "-" * 32 + ".webp", False),

    # --- encoding and control characters ---
    ("stickers/" + GOOD + ".webp\x00.exe", False),
    ("stickers/%2e%2e/" + GOOD + ".webp", False),
    ("stickers/" + GOOD + ".webp\n", False),
    ("stickers/" + GOOD + ".webp ", False),
    (" stickers/" + GOOD + ".webp", False),
    ("stickers//" + GOOD + ".webp", False),

    # --- wrong container / other files ---
    ("manifest.json", False),        # handled separately, not as a sticker
    ("stickers/", False),
    ("", False),
    ("stickers/" + GOOD + ".webp/", False),
    ("x" * 5000 + ".webp", False),
]


def main():
    failures = 0
    print("%-6s %-8s %s" % ("result", "expect", "entry"))
    print("-" * 78)
    for name, expected in CASES:
        accepted = accepts(name) is not None
        ok = accepted == expected
        if not ok:
            failures += 1
        shown = name if len(name) <= 46 else name[:43] + "..."
        shown = shown.replace("\x00", "\\0").replace("\n", "\\n")
        print("%-6s %-8s %s" % (
            "ok" if ok else "FAIL",
            "accept" if expected else "reject",
            shown,
        ))

    # A matched name must yield a hash usable as a filename and nothing else.
    print()
    for name, expected in CASES:
        h = accepts(name)
        if h:
            if not re.fullmatch(r"[0-9a-f]{64}", h):
                print("FAIL captured hash is not clean hex:", h)
                failures += 1
            if "/" in h or "\\" in h or ".." in h:
                print("FAIL captured hash contains path characters:", h)
                failures += 1

    print("%d cases, %d failures" % (len(CASES), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
