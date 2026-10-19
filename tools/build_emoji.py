#!/usr/bin/env python3
"""
Builds app/src/main/assets/emoji.tsv from muan/unicode-emoji-json (data-by-group.json, generated from the
official Unicode emoji list). One line per emoji: <group 0-8> TAB <emoji> TAB <1 if it accepts a skin tone>.
Download: https://raw.githubusercontent.com/muan/unicode-emoji-json/main/data-by-group.json
"""
import json, sys
src = sys.argv[1] if len(sys.argv) > 1 else "data-by-group.json"
out = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/emoji.tsv"
order = ["Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places",
         "Activities", "Objects", "Symbols", "Flags"]
lines = []
for grp in json.load(open(src, encoding="utf-8")):
    if grp["name"] not in order: continue            # skips the "Component" group (skin swatches, hair)
    gi = order.index(grp["name"])
    for e in grp["emojis"]:
        lines.append(f'{gi}\t{e["emoji"]}\t{1 if e.get("skin_tone_support") else 0}')
open(out, "w", encoding="utf-8").write("\n".join(lines))
print(len(lines), "emoji,", sum(l.endswith("\t1") for l in lines), "accept a skin tone")
