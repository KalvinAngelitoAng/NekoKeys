#!/usr/bin/env python3
"""
Builds app/src/main/assets/emoji_names.tsv: the names and search keywords used by the emoji search box.
One line per emoji that exists in emoji.tsv:  <emoji> TAB <names: English|Indonesian> TAB <keywords>

Source: Unicode CLDR annotations (English and Indonesian), npm packages `cldr-annotations-full` and
`cldr-annotations-derived-full` (license: Unicode-3.0).
    npm install cldr-annotations-full cldr-annotations-derived-full
    python3 tools/build_emoji_names.py node_modules
The emoji column is copied from emoji.tsv unchanged, so the app can match by exact string.
"""
import json, sys

root = sys.argv[1] if len(sys.argv) > 1 else "node_modules"
emoji_tsv = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/emoji.tsv"
out = sys.argv[3] if len(sys.argv) > 3 else "app/src/main/assets/emoji_names.tsv"


def norm(e):
    return e.replace("\ufe0f", "")


def load(loc):
    table = {}
    for pkg, sub, key in (("cldr-annotations-full", "annotations", "annotations"),
                          ("cldr-annotations-derived-full", "annotationsDerived", "annotationsDerived")):
        path = f"{root}/{pkg}/{sub}/{loc}/annotations.json"
        data = json.load(open(path, encoding="utf-8"))[key]["annotations"]
        for k, v in data.items():
            table.setdefault(norm(k), v)       # the main file wins over the derived one
    return table


en, idn = load("en"), load("id")
emojis = [l.split("\t")[1] for l in open(emoji_tsv, encoding="utf-8").read().split("\n") if l.count("\t") >= 2]

lines, missing = [], []
for e in emojis:
    k = norm(e)
    a, b = en.get(k), idn.get(k)
    if not a and not b:
        missing.append(e)
        continue
    names = [((x or {}).get("tts") or [""])[0].lower() for x in (a, b)]
    words, seen = [], set()
    for x in (a, b):
        for w in (x or {}).get("default", []):
            for t in w.lower().replace(":", " ").split():
                if t not in seen:
                    seen.add(t)
                    words.append(t)
    lines.append(f"{e}\t{'|'.join(names)}\t{' '.join(words)}")

open(out, "w", encoding="utf-8").write("\n".join(lines))
print(len(lines), "emoji with names,", len(missing), "without:", "".join(missing[:20]))
