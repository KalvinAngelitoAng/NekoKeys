# Tes logika di JVM (tanpa HP)
Menguji tata letak, autocorrect, glide typing, kamus, penyimpanan clipboard dan data belajar memakai
Context palsu. Butuh `kotlinc` 1.9.x dan `android.jar` (API 34).

    kotlinc -no-stdlib -cp android.jar:kotlin-stdlib.jar -d out $(find app/src/main/java -name '*.kt')
    kotlinc -no-stdlib -cp out:android.jar:kotlin-stdlib.jar -d testout tools/jvm-tests/Test.kt tools/jvm-tests/Test2.kt tools/jvm-tests/Test7.kt tools/jvm-tests/Test8.kt tools/jvm-tests/Test10.kt
    java -cp testout:out:android.jar:kotlin-stdlib.jar TestKt     # layout, autocorrect, glide
    java -cp testout:out:android.jar:kotlin-stdlib.jar Test2Kt    # clipboard + data belajar
    java -cp testout:out:android.jar:kotlin-stdlib.jar Test7Kt    # kamus: ukuran, memori, isi
    java -cp testout:out:android.jar:kotlin-stdlib.jar Test8Kt    # emoji: kelengkapan dan warna kulit
    java -cp testout:out:android.jar:kotlin-stdlib.jar Test10Kt   # ketahanan: mati mendadak, file rusak, data lama, thread, batas clipboard

Simulasi pemakaian 10 tahun (7,3 juta kata, sekitar 40 detik) dan ukuran memori data belajar:

    kotlinc ... -d testout9 tools/jvm-tests/Test9.kt && java -Xmx2g -cp testout9:out:android.jar:kotlin-stdlib.jar Test9Kt 2000 10
    kotlinc ... -d testout11 tools/jvm-tests/Test11.kt && java -cp testout11:out:android.jar:kotlin-stdlib.jar Test11Kt

`Test3` (akurasi massal), `Test5` (penyetelan autocorrect) dan `Test6` (penyetelan glide) dikompilasi terpisah
karena nama fungsi bantunya sama. Jalankan semua perintah dari akar proyek (path aset bersifat relatif).
