# Aphodius

Aplikasi streaming Android (tema biru laut) oleh SengkuniDeveloper.
Memutar video dari koleksi link X (Twitter) yang disimpan di web **Master Stream**.

## Cara kerja
- Daftar film dibaca dari `data.json` Master Stream
  (`efendiyhy/ek-web` → `public/master-stream/data.json`), diperbarui otomatis setiap aplikasi dibuka.
- Link X diekstrak menjadi URL video langsung (`video.twimg.com`), lalu diputar penuh layar — tanpa tampilan X.
- Pemutar: ketuk dua kali kiri/kanan = mundur/maju 10 dtk, geser vertikal kiri = kecerahan, kanan = suara,
  pilih kualitas, kecepatan, rasio layar, lanjut menonton otomatis.

## Build
CI GitHub Actions (`.github/workflows/build.yml`) membangun APK. Rilis dibuat otomatis dari `version.txt`.

Rilis baru: naikkan angka di `version.txt` lalu push ke `main` — CI membuat tag dan rilis otomatis.

Folder `portrait/`, `landscape/`, `00-design-system.svg`, `preview.html` adalah desain UI kit.
