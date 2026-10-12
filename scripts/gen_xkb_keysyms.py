"""Regenerates core/language/.../layout/XkbKeysyms.kt from xorgproto's keysymdef.h.

Usage:
    python3 scripts/gen_xkb_keysyms.py /opt/homebrew/include/X11/keysymdef.h \
        core/language/src/main/java/com/wasimaster/wmkeyboard/core/layout/XkbKeysyms.kt

keysymdef.h comes with xorgproto (`brew install xorgproto`, or any Linux box's
/usr/include/X11). Only names whose comment gives a Unicode code point are kept.
"""
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
pat = re.compile(r'^#define XK_([A-Za-z0-9_]+)\s+0x([0-9a-fA-F]+)\s*/\*\s*\(?U\+([0-9A-Fa-f]{4,6})', re.M)
seen = {}
for name, val, u in pat.findall(src):
    if len(name) == 1:  # a-z, A-Z, 0-9: the name is the character
        continue
    if name in seen: continue
    seen[name] = int(u, 16)
# Group by code point prefix: emit "name hex" lines
lines = [f"{n} {cp:x}" for n, cp in seen.items()]
body = "\n".join(lines)
half = len(lines)//2
a = "\n".join(lines[:half]); b = "\n".join(lines[half:])
print(len(seen), len(a.encode()), len(b.encode()), file=sys.stderr)
out = []
out.append("package com.wasimaster.wmkeyboard.core.layout\n")
out.append("""/*
 * X keysym names and the Unicode character each one stands for, from
 * xorgproto's keysymdef.h, whose comments give the code point beside every
 * name. Generated; do not edit by hand.
 *
 * keysymdef.h: Copyright 1987, 1994, 1998 The Open Group; Copyright 1987 by
 * Digital Equipment Corporation. Permission to use, copy, modify, distribute,
 * and sell this software and its documentation for any purpose is hereby
 * granted without fee, provided that the above copyright notice appear in all
 * copies and that both that copyright notice and this permission notice appear
 * in supporting documentation. The full notice ships as
 * app/src/main/assets/licenses/x11-keysymdef.txt.
 *
 * One `name hex` pair per line. The one-character names (a to z, A to Z, 0 to
 * 9) are left out: such a name is its own character. Read once, into a map,
 * by `XkbSymbolsReader`.
 */
""")
out.append('internal const val XKB_KEYSYMS: String = """')
out.append(body)
out.append('"""\n')
open(sys.argv[2], 'w', encoding='utf-8').write("\n".join(out))
