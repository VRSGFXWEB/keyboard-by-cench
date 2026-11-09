# V4RR Keyboard v1.1
Keyboard Android gaya iOS, Bahasa Indonesia: QWERTY, angka/simbol, emoji + cari emoji, mic (input suara),
terang/gelap, balon preview huruf, kata saran + koreksi otomatis.

## Build (tanpa PC)
1. Upload isi folder ini ke repo GitHub (branch main).
2. Actions -> "Build V4RR Keyboard APK" -> Run workflow -> download artifact -> install APK.

## Fitur di dalam app
- **Ukuran tombol**: tinggi, lebar, ukuran huruf, jarak, lengkung sudut, transparansi tombol (slider, langsung berlaku).
  Tahan spasi (tulisan V4RR) buat ubah ukuran & posisi pakai tangan.
- **Background**: bawaan (kaca/blur), warna pilihan (swatch / kode HEX), atau foto dari galeri (gelapkan + blur foto).
- **Font**: pilih .ttf / .otf / .zip. Jenis (emoji / huruf) dideteksi otomatis. Pilihan: pakai di keyboard,
  simpan ke perangkat sebagai modul Magisk (.zip) atau file .ttf. Ada pratinjau.
- **Kata saran Indonesia**: kamus ~2300 kata + belajar dari kata yang sering diketik. Typo dikoreksi pas spasi,
  backspace sekali = batalin. Ada pintasan teks buatan sendiri (yg=yang).
- **Balik ke huruf otomatis** setelah ketik simbol / spasi di layar angka.

## Edit kamus
`app/src/main/assets/words.txt` = satu kata per baris (yang paling sering di atas).
`app/src/main/assets/bigrams.txt` = kata berikutnya: `kata<TAB>saran1<TAB>saran2...`
