# Aphodius

Aplikasi streaming Android (tema biru laut, logo kumbang tahi polygon) oleh SengkuniDeveloper.
Memutar video dari koleksi link X (Twitter) yang disimpan di web **Master Stream**.

## Cara kerja
- Daftar film dibaca dari `data.json` Master Stream
  (repo `efendiyhy/master-stream-data` → `data.json`, harus public), diperbarui otomatis setiap aplikasi dibuka.
- Link X diekstrak menjadi URL video langsung (`video.twimg.com`), lalu diputar penuh layar — tanpa tampilan X.
- Pemutar: ketuk dua kali kiri/kanan = mundur/maju 10 dtk, geser vertikal kiri = kecerahan, kanan = suara,
  pilih kualitas, kecepatan, rasio layar, lanjut menonton otomatis.

## Build
CI GitHub Actions (`.github/workflows/build.yml`) membangun APK. Rilis dibuat otomatis dari `version.txt`.

Rilis baru: naikkan angka di `version.txt` lalu push ke `main` — CI membuat tag dan rilis otomatis.

## Desain
- Logo: kumbang tahi bergaya polygon (low-poly). Aset ada di `branding/`; dibangkitkan ulang dengan `python3 tools/branding/build.py` (perlu `fonttools`, dan `rsvg-convert` untuk PNG).
- Mockup UI: `portrait/`, `landscape/`, `00-design-system.svg`; buka `preview.html` untuk melihat semuanya. Bangkitkan ulang dengan `python3 tools/branding/mockups.py`.
- Warna utama Aqua `#22C7EC`, aksen emas scarab `#F2A33A`, latar `#050D16`. Detail ada di `00-design-system.svg`.

## v1.2.0
- Nama sapaan saat pertama kali dipasang, profil lokal, dan kunci aplikasi (PIN 4 digit + sidik jari opsional).
- Pemutar: crop manual (tombol Crop) — geser 4 garis, simpan, berlaku global; bisa dimatikan/dihapus (mati = video tampil apa adanya).
- Menu bawah "Cari" diganti "Movie" (film dikelompokkan per genre); pencarian tetap ada lewat ikon di Beranda.
- Pembaruan dalam aplikasi: banner info + unduh & pasang langsung dari rilis GitHub.

## v1.2.1
- Crop otomatis dihapus; diganti crop manual yang bisa disimpan dan dimatikan.
- Sinopsis film dari web ditampilkan di halaman detail.

## v1.3.0
- Desain baru penuh: logo kumbang tahi polygon, ikon launcher, UI baru di semua layar.
- Fitur Adults (Profil): tombol on/off, butuh password; film bergenre `21+` hanya tampil bila Adults aktif. Mengaktifkan Adults mewajibkan kunci aplikasi (PIN) dan kunci tidak bisa dimatikan selama Adults menyala.
