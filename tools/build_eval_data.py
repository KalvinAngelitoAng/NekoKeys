#!/usr/bin/env python3
"""
Builds the data used by tools/jvm-tests/Test14.kt (the autocorrect "exam"). Needs:  pip install codespell faker
Writes into tools/eval-data/ :
  typos_en.tsv   real English misspellings:  wrong <TAB> right   (from the codespell dictionary, which is built from
                 Wikipedia's list of common misspellings; CC BY-SA 3.0 -- used only for testing, never shipped in the APK)
  names_en.txt, names_id.txt   first names (Faker, fixed seed): valid words that dictionaries usually do not contain
  slang.txt      slang, brands and new words people really type that dictionaries usually miss
"""
import os, random, re, sys
import codespell_lib
from faker import Faker

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval-data")
os.makedirs(out, exist_ok=True)
rnd = random.Random(7)

# --- real misspellings
path = os.path.join(os.path.dirname(codespell_lib.__file__), "data", "dictionary.txt")
pairs = []
for line in open(path, encoding="utf-8"):
    if "->" not in line:
        continue
    wrong, right = line.rstrip("\n").split("->", 1)
    right = right.split(",")[0].strip() if right.count(",") == 0 else None   # keep only unambiguous entries
    if not right or not re.fullmatch(r"[a-z]{3,}", wrong) or not re.fullmatch(r"[a-z]{3,}", right) or wrong == right:
        continue
    pairs.append((wrong, right))
rnd.shuffle(pairs)
with open(os.path.join(out, "typos_en.tsv"), "w", encoding="utf-8") as f:
    for w, r in pairs[:6000]:
        f.write(f"{w}\t{r}\n")
print(len(pairs), "unambiguous misspellings,", min(len(pairs), 6000), "written")

# --- names
for loc, name in (("en_US", "names_en.txt"), ("id_ID", "names_id.txt")):
    fk = Faker(loc)
    Faker.seed(7)
    seen = set()
    for _ in range(40000):
        n = fk.first_name()
        if re.fullmatch(r"[A-Za-z]{4,}", n):
            seen.add(n)
        if len(seen) >= 1500:
            break
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(seen)))
    print(name, len(seen))

# --- slang, brands, new words (hand-picked; the harness keeps only the ones the dictionaries lack)
slang = """gojek grab tokopedia shopee bukalapak traveloka tiktok whatsapp instagram youtube netflix spotify telegram
bitcoin crypto blockchain podcast selfie vlog vlogger youtuber influencer streamer gamer hashtag unfollow
ngab anjay mantul gabut baper mager santuy receh kepo alay jomblo bucin gemoy cuan healing ghosting skuy gaskeun
kuy mantap kocak lebay galau nongkrong ngopi ngegas ngabuburit gabisa gapapa gatau udahan wkwk wkwkwk hehehe haha
bestie bestfriend yolo fomo ootd lowkey highkey vibes cringe slay sus rizz bruh
okelah sipp noted otw ootd dm pm cod preorder restock checkout unboxing
smartphone wifi hotspot bluetooth emoji emoticon meme gif
covid pandemi lockdown""".split()
with open(os.path.join(out, "slang.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(dict.fromkeys(slang)))
print("slang", len(set(slang)))
