#!/usr/bin/env python3
"""
Builds app/src/main/assets/words_en.txt and words_id.txt (one word per line, most frequent first).

Sources (download once into --src):
  FrequencyWords 2018 (OpenSubtitles, CC-BY-SA 4.0): en_full.txt, id_full.txt   -> ranking = how people really write/chat
  hunspell en_US (wooorm/dictionaries) and hunspell-id (LibreOffice) + KBBI lists  -> spell-check filter only
A subtitle token is kept only if a real spelling dictionary accepts it, so typos ("teh"), names and
OCR junk never become "valid words" that autocorrect would refuse to fix.
"""
import re, sys, argparse
from spylls.hunspell import Dictionary

ap = argparse.ArgumentParser()
ap.add_argument("--src", default="/tmp/dict")
ap.add_argument("--out", default="app/src/main/assets")
ap.add_argument("--en", type=int, default=60000)
ap.add_argument("--id", type=int, default=50000)
a = ap.parse_args()

EN_EXTRA = """ok okay yeah yep nope gonna wanna gotta lemme gimme dunno kinda sorta cuz lol omg btw idk imo fyi asap
whatsapp instagram google youtube facebook tiktok email online wifi app apps emoji selfie podcast hashtag
hi hey hello bye thanks thx pls plz yes no""".split()

ID_INFORMAL = """gue gua lo lu loe elo nggak ngga nggk gak ga enggak enggan udah udh dah nih tuh itu banget bgt aja doang sih deh kok
dong loh lho kan yaudah yasudah makasih makasi thanks gimana gmn kayak kayaknya kek emang emg mager baper gabut santuy
bentar sebentar bokap nyokap cowok cewek temen kalo klo gitu gini begini gitu biar soalnya padahal pokoknya cuma cuman
doang abis habis banget bgt sama-sama pas lagi lg masa mulu terus trus tapi tp jadi jd sama sm yang yg dengan dgn untuk utk
karena krn tidak tdk belum blm sudah sdh dari dr dalam dlm pada pd juga jg jangan jgn kamu kmu aku aq saya sy dia dy
mereka kita kami bisa bs mau mw lagi lgi sampai smp nanti ntar besok bsk kemarin kmrn hari hr jam jm""".split()

# OpenSubtitles tokenizes "don't" as "don" + "t". Stem tokens like "don"/"didn" keep an accurate count, but the
# one-letter pieces ("s", "m", "ll", ...) are heavily under-counted, so those contractions get an estimated
# popularity as a share of the count of "you" (rough subtitle statistics; only their rank matters).
CONTRACTIONS = [
  ("don't","don",1),("didn't","didn",1),("doesn't","doesn",1),("isn't","isn",1),("wasn't","wasn",1),
  ("aren't","aren",1),("weren't","weren",1),("couldn't","couldn",1),("wouldn't","wouldn",1),
  ("shouldn't","shouldn",1),("hasn't","hasn",1),("haven't","haven",1),("hadn't","hadn",1),("ain't","ain",1),
  ("i'm","you",0.243),("it's","you",0.278),("that's","you",0.156),("what's","you",0.07),("he's","you",0.042),
  ("she's","you",0.024),("there's","you",0.035),("here's","you",0.017),("let's","you",0.059),("who's","you",0.010),
  ("you're","you",0.139),("we're","you",0.042),("they're","you",0.031),("i'll","you",0.07),("you'll","you",0.021),
  ("we'll","you",0.021),("it'll","you",0.007),("he'll","you",0.005),("she'll","you",0.0035),("they'll","you",0.010),
  ("i've","you",0.059),("you've","you",0.021),("we've","you",0.017),("they've","you",0.010),("i'd","you",0.031),
  ("you'd","you",0.012),("he'd","you",0.007),("we'd","you",0.009),("they'd","you",0.007),("can't","you",0.07),
  ("won't","you",0.024),("o'clock","clock",0.05)]

def load_hun(path):
    return Dictionary.from_files(path)

def tokens(path, limit, pattern):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            w = line.split(" ")[0].strip()
            if pattern.match(w):
                out.append(w)
            if len(out) >= limit: break
    return out

def counts(path, limit=400000):
    c = {}
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            parts = line.split(" ")
            if len(parts) == 2: c[parts[0]] = int(parts[1])
            if i >= limit: break
    return c

def build_en():
    hun = load_hun(f"{a.src}/en")
    pat = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
    raw = tokens(f"{a.src}/en_full.txt", 400000, pat)
    cnt = counts(f"{a.src}/en_full.txt")
    synth = {w: int(cnt.get(piece, 0) * share) for (w, piece, share) in CONTRACTIONS if cnt.get(piece, 0) > 0}
    # merge: walk the real ranking and drop each contraction in at its estimated position
    pending = sorted(synth.items(), key=lambda kv: -kv[1])
    merged = []
    for w in raw:
        while pending and pending[0][1] >= cnt.get(w, 0):
            merged.append(pending.pop(0)[0])
        merged.append(w)
    merged += [w for w, _ in pending]
    raw = merged
    seen, keep = set(), []
    for w in raw:
        if w in seen or len(w) > 24: continue
        if len(w) == 1 and w not in ("a", "i"): continue
        if re.search(r"(.)\1\1", w): continue                       # sooo, aaah
        ok = hun.lookup(w) or (w.startswith("i'") and hun.lookup(w.capitalize())) or w in synth
        if ok or w in EN_EXTRA:
            seen.add(w); keep.append(w)
        if len(keep) >= a.en: break
    for w in EN_EXTRA:                                              # make sure chat words exist even if rare
        if w not in seen: keep.append(w); seen.add(w)
    return keep

def build_id():
    hun = load_hun(f"{a.src}/id_lo")
    kbbi = {l.strip().lower() for l in open(f"{a.src}/kbbi.txt", encoding="utf-8", errors="ignore")}
    geo = {l.strip().lower() for l in open(f"{a.src}/geo.txt", encoding="utf-8", errors="ignore")}
    pat = re.compile(r"^[a-z]+$")
    raw = tokens(f"{a.src}/id_full.txt", 300000, pat)
    inf = set(ID_INFORMAL)
    seen, keep = set(), []
    for w in raw:
        if w in seen or len(w) > 24: continue
        if len(w) == 1: continue
        if re.search(r"(.)\1\1", w): continue
        if w in inf or hun.lookup(w) or (len(w) >= 4 and (w in kbbi or w in geo)):
            seen.add(w); keep.append(w)
        if len(keep) >= a.id: break
    for w in ID_INFORMAL:
        if w not in seen and pat.match(w): keep.append(w); seen.add(w)
    return keep

for name, fn in (("en", build_en), ("id", build_id)):
    words = fn()
    open(f"{a.out}/words_{name}.txt", "w", encoding="utf-8").write("\n".join(words) + "\n")
    print(name, len(words), "words; first:", words[:12])
