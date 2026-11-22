# Aphodius

Aplikasi streaming Android (tema biru laut) oleh SengkuniDeveloper.
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

Folder `portrait/`, `landscape/`, `00-design-system.svg`, `preview.html` adalah desain UI kit.

## v1.2.0
- Nama sapaan saat pertama kali dipasang, profil lokal, dan kunci aplikasi (PIN 4 digit + sidik jari opsional).
- Pemutar: crop manual (tombol Crop) — geser 4 garis, simpan, berlaku global; bisa dimatikan/dihapus (mati = video tampil apa adanya).
- Menu bawah "Cari" diganti "Movie" (film dikelompokkan per genre); pencarian tetap ada lewat ikon di Beranda.
- Pembaruan dalam aplikasi: banner info + unduh & pasang langsung dari rilis GitHub.

## v1.2.1
- Crop otomatis dihapus; diganti crop manual yang bisa disimpan dan dimatikan.
- Sinopsis film dari web ditampilkan di halaman detail.
