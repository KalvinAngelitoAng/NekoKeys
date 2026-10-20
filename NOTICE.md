# Sumber data kamus

`app/src/main/assets/words_en.txt` (60.001 kata) dan `words_id.txt` (50.003 kata) adalah daftar kata yang
disusun oleh `tools/build_dictionaries.py` dari:

- **FrequencyWords** oleh Hermit Dave (https://github.com/hermitdave/FrequencyWords), frekuensi kata dari
  korpus subtitle **OpenSubtitles** (Tiedemann, 2016). Lisensi data: **CC BY-SA 4.0**.
  Dari sumber ini hanya urutan frekuensi kata yang dipakai. Karena itu kedua file kamus di atas
  diturunkan dari data CC BY-SA 4.0 dan dibagikan dengan lisensi yang sama.
- **Hunspell en_US** (wooorm/dictionaries) dan **hunspell-id** (LibreOffice/dictionaries, LGPL-3.0),
  serta daftar kata **KBBI** (damzaky/kumpulan-kata-bahasa-indonesia-KBBI) dan geovedi/indonesian-wordlist:
  hanya dipakai saat build sebagai penyaring ejaan (membuang typo, nama, dan sampah OCR).
  Data kamus ejaan itu sendiri tidak ikut dikirim di dalam aplikasi.

Frekuensi kontraksi Inggris yang terpecah di subtitle ("don" + "t") direkonstruksi dan sebagian diestimasi,
lihat komentar di `tools/build_dictionaries.py`.

Aplikasi tidak mengunduh apa pun: kamus hanya dibaca dari dalam APK.

# Sumber data emoji

`app/src/main/assets/emoji.tsv` (1.914 emoji sampai Emoji 17.0) dibuat oleh `tools/build_emoji.py` dari
**muan/unicode-emoji-json** (MIT), yang dihasilkan dari daftar emoji resmi **Unicode Consortium**.
Emoji yang tidak bisa digambar oleh font HP disaring otomatis saat dijalankan.

`app/src/main/assets/emoji_names.tsv` (nama dan kata kunci pencarian emoji, bahasa Inggris dan Indonesia) dibuat oleh
`tools/build_emoji_names.py` dari **Unicode CLDR annotations** (paket npm `cldr-annotations-full` dan
`cldr-annotations-derived-full`, lisensi **Unicode-3.0**). File ini hanya dibaca dari dalam APK, tidak ada yang diunduh.
