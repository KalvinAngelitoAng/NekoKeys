#!/usr/bin/env python3
"""
Builds app/src/main/assets/emoji_words.tsv: which emoji the suggestion bar offers after you type a word.   word <TAB> emoji

Precision comes first: showing no emoji is better than showing a silly one. Matching raw CLDR keywords is NOT good enough
("love" would give a love letter and a love hotel), so the list has two parts:
  1. CURATED: words people really type, English and Indonesian, mapped by hand to the emoji they expect. Edit freely.
  2. EXACT NAMES: a word that is the complete, single-word name of exactly one emoji (pizza, fire, banana, ambulance...).
Every emoji is checked against emoji.tsv, so nothing the keyboard cannot show is ever offered.
    python3 tools/build_emoji_words.py
"""
import os, re

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets")
norm = lambda e: e.replace("\ufe0f", "")

have = {}   # normalised -> the exact string used in emoji.tsv
for l in open(os.path.join(root, "emoji.tsv"), encoding="utf-8").read().split("\n"):
    p = l.split("\t")
    if len(p) >= 2:
        have[norm(p[1])] = p[1]

CURATED = """
love ❤️|sayang ❤️|cinta ❤️|heart ❤️|hati ❤️|kangen 🥺|rindu 🥺
happy 😊|smile 😊|senang 😊|bahagia 😊|gembira 😊|glad 😊
sad 😢|sedih 😢|cry 😭|crying 😭|nangis 😭|tangis 😭
laugh 😂|lol 😂|haha 😂|hahaha 😂|funny 😂|lucu 😂|tertawa 😂|ketawa 😂|wkwk 😂|wkwkwk 😂|hehe 😁|hihi 😁
angry 😡|mad 😡|marah 😡|kesal 😠|annoyed 😠
tired 😫|capek 😫|lelah 😫|sleepy 😴|sleep 😴|ngantuk 😴|tidur 😴
scared 😨|afraid 😨|takut 😨|omg 😱|kaget 😱|shocked 😱|wow 😮
sorry 😔|maaf 🙏|thanks 🙏|thank 🙏|makasih 🙏|please 🙏|tolong 🙏|pray 🙏|doa 🙏|terimakasih 🙏
cool 😎|keren 😎|awesome 🤩|amazing 🤩|mantap 👍|good 👍|nice 👍|great 👍|bagus 👍|yes 👍|ok 👌|okay 👌|oke 👌|perfect 👌
thinking 🤔|think 🤔|hmm 🤔|bingung 🤔|mikir 🤔|confused 😕
sick 🤒|sakit 🤒|ill 🤒|hot 🥵|panas 🥵|cold 🥶|dingin 🥶
kiss 😘|cium 😘|hug 🤗|hugs 🤗|peluk 🤗
party 🎉|congrats 🎉|congratulations 🎉|celebrate 🎉|yay 🎉|birthday 🎂|cake 🎂|kue 🍰|gift 🎁|kado 🎁|hadiah 🎁
fire 🔥|api 🔥|hundred 💯|strong 💪|kuat 💪|muscle 💪|win 🏆|winner 🏆|trophy 🏆|juara 🏆|menang 🏆
money 💰|uang 💰|duit 💰|cash 💵|rich 🤑
coffee ☕|kopi ☕|tea 🍵|teh 🍵|beer 🍺|wine 🍷
pizza 🍕|burger 🍔|hamburger 🍔|fries 🍟|icecream 🍦|cookie 🍪|apple 🍎|banana 🍌|rice 🍚|nasi 🍚|ayam 🍗|chicken 🍗|bakso 🍜|mie 🍜|noodles 🍜|sate 🍢
food 🍽️|eat 🍽️|makan 🍽️|dinner 🍽️|minum 🥤|drink 🥤
home 🏠|house 🏠|rumah 🏠|car 🚗|mobil 🚗|motor 🏍️|bus 🚌|train 🚆|kereta 🚆|plane ✈️|pesawat ✈️|flight ✈️|bike 🚲|sepeda 🚲
beach 🏖️|pantai 🏖️|sun ☀️|sunny ☀️|matahari ☀️|rain 🌧️|rainy 🌧️|hujan 🌧️|moon 🌙|bulan 🌙|star ⭐|bintang ⭐|snow ❄️|cloud ☁️
flower 🌸|bunga 🌸|rose 🌹|mawar 🌹|tree 🌳|pohon 🌳
dog 🐶|puppy 🐶|anjing 🐶|cat 🐱|kitten 🐱|kucing 🐱|bird 🐦|burung 🐦|fish 🐟|ikan 🐟|monkey 🐵|monyet 🐵
phone 📱|hp 📱|call 📞|telepon 📞|computer 💻|laptop 💻|book 📖|buku 📖|books 📚
music 🎵|song 🎵|lagu 🎵|musik 🎵|sing 🎤|nyanyi 🎤|dance 💃|joget 💃|movie 🎬|film 🎬
soccer ⚽|football ⚽|bola ⚽|basketball 🏀|game 🎮
work 💼|kerja 💼|office 🏢|kantor 🏢|school 🏫|sekolah 🏫|study 📚|belajar 📚|exam 📝|ujian 📝
time ⏰|clock ⏰|alarm ⏰|jam ⏰|morning 🌅|pagi 🌅|night 🌙|malam 🌙|goodnight 🌙
wedding 💒|nikah 💒|baby 👶|bayi 👶|girl 👧|boy 👦|mom 👩|ibu 👩|dad 👨|ayah 👨|family 👪|keluarga 👪
hi 👋|hello 👋|hey 👋|bye 👋|goodbye 👋|halo 👋|hai 👋|dadah 👋
"""

curated = {}
for chunk in CURATED.strip().replace("\n", "|").split("|"):
    chunk = chunk.strip()
    if not chunk:
        continue
    word, emoji = chunk.split(" ", 1)
    key = norm(emoji.strip())
    if key not in have:
        print("not in emoji.tsv, skipped:", word, emoji)
        continue
    curated[word.lower()] = have[key]

# exact single-word names that belong to exactly one emoji
names = {}
for l in open(os.path.join(root, "emoji_names.tsv"), encoding="utf-8").read().split("\n"):
    p = l.split("\t")
    if len(p) < 3:
        continue
    for nm in p[1].split("|"):
        if re.fullmatch(r"[a-z]{3,}", nm):
            names.setdefault(nm, set()).add(p[0])
SKIP = {"new", "top", "end", "back", "soon", "free", "cool", "sos", "atm", "key", "man", "woman", "person", "people", "baby",
        "boy", "girl", "old", "face", "hand", "fist", "pointing", "waving", "ring", "ten", "one", "two", "red", "blue"}
exact = {w: next(iter(es)) for w, es in names.items() if len(es) == 1 and w not in SKIP}

out = dict(exact)
out.update(curated)             # the hand-made list wins
with open(os.path.join(root, "emoji_words.tsv"), "w", encoding="utf-8") as f:
    f.write("\n".join(f"{w}\t{e}" for w, e in sorted(out.items())))
print(len(curated), "curated +", len(exact), "exact names ->", len(out), "words written")
