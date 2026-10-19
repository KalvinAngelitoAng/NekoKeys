# NekoKeys
Keyboard Android privat (Kotlin murni, tanpa library pihak ketiga, tanpa izin jaringan).

## Build & install ke Galaxy S24 Ultra
1. Install Android Studio terbaru. File > Open > pilih folder `NekoKeys`. Tunggu Gradle sync.
2. HP: Settings > About phone > Software information > ketuk "Build number" 7x, lalu
   Developer options > aktifkan USB debugging. Colok USB dan izinkan.
3. Android Studio: Build Variants > pilih `release` (teroptimasi, ditandatangani debug-key) > Run.
4. Buka app NekoKeys > "Aktifkan NekoKeys" > nyalakan, lalu "Pilih sebagai keyboard".
   Minimum Android 10 (API 29).

## Fitur
- Layout ala Gboard: huruf dengan petunjuk simbol/angka, 2 halaman simbol, numpad, tombol pill.
- Glide typing (geser jari), autocorrect sadar posisi tombol, saran kata, bahasa EN / ID / EN • ID.
- Tema default "Keqing" (palet ungu-lavender bergaya Genshin Impact) plus 6 tema lain, warna bebas (color wheel + kode hex), wallpaper foto sendiri.
- Emoji lengkap (1.914, sampai Emoji 17.0) dalam satu scroll panjang, ikon kategori untuk lompat cepat, skin tone, emoji terbaru, kaomoji, koleksi GIF/stiker sendiri.
- Clipboard multi-item (pin, hapus otomatis), screenshot terbaru (opsional).
- Mode satu tangan, geser tombol hapus untuk hapus beberapa kata, geser spasi untuk pindah kursor.

## Kamus
- Inggris 60 ribu kata, Indonesia 50 ribu kata (termasuk bahasa chat: gue, nggak, banget, yg, dgn ...),
  disusun dari frekuensi subtitle dan disaring dengan kamus ejaan. Sumber dan lisensi: `NOTICE.md`.
- Bangun ulang atau ganti ukuran: `python3 tools/build_dictionaries.py --src <folder sumber> --en 80000 --id 60000`
  (butuh `pip install spylls`). Format file: satu kata per baris, urut dari yang paling sering dipakai.
- Hasil ukur (typo buatan berbobot pemakaian nyata): autocorrect memperbaiki 90% (EN) / 93% (ID) typo campuran,
  0 dari 1600 kata valid yang salah dikoreksi. Glide top-1 82% (EN) / 86% (ID), top-3 93% / 96%.
  Kamus penuh memakai sekitar 7 MB memori.
- Mode EN • ID mengikuti bahasa yang sedang kamu ketik (dihitung di memori, tidak disimpan).

## Data belajar jangka panjang
Kamus bawaan (Inggris dan Indonesia) hanya dibaca dari dalam APK dan tidak pernah bertambah. Yang bertambah hanya
data belajar dari ketikanmu (kata baru dan pasangan kata), dan itu dibatasi keras:
- Maksimal 5.000 kata baru, 40.000 pasangan kata, 30 kata pengikut per kata. Melewati batas, entri yang paling
  jarang dipakai dibuang lebih dulu.
- Memudar sendiri: tiap sekitar 200 ribu kata yang diketik (2 sampai 4 bulan) semua hitungan dibagi dua,
  jadi kebiasaan lama yang ditinggalkan hilang dengan sendirinya, sedangkan yang masih dipakai tetap.
- Disimpan lewat file sementara + fsync + rename, hanya satu penulis, jadi mati mendadak tidak merusak file.
- Hasil simulasi 10 tahun (7,3 juta kata): file 0,1 sampai 0,7 MB, memori sekitar 3 MB, waktu belajar per kata
  datar di sekitar 1,5 mikrodetik. Ukuran data saat ini terlihat di Pengaturan > Privasi.
- Catatan: pencadangan sengaja dimatikan, jadi data belajar tidak ikut pindah ke HP baru dan hilang saat aplikasi
  dihapus.

## Cek memori dan baterai sendiri
    adb shell dumpsys meminfo com.nekokeys.keyboard
    adb shell dumpsys batterystats --reset   (pakai seharian)  lalu  adb shell dumpsys batterystats com.nekokeys.keyboard
