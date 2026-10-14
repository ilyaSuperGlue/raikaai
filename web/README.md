# RaikaAI Hub — Galeri Karakter

Galeri web statis untuk berbagi persona/karakter RaikaAI sebagai berkas JSON.
Tanpa backend, tanpa framework: `index.html` + `styles.css` + `app.js` biasa,
dengan bahasa desain yang sama seperti portofolio (`data-theme` terang/gelap,
header sticky, card grid, toast).

## Menjalankan lokal

`fetch()` tidak bekerja lewat `file://`, jadi jalankan HTTP server lokal:

```bash
cd web
python3 -m http.server 8000
# buka http://localhost:8000
```

## Menambah karakter baru

1. Buat `characters/<id-kamu>.json` mengikuti aturan field di tab Panduan
   (atau mulai dari tab Template jika sudah ada isinya, lalu unduh).
2. Daftarkan nama berkasnya di `characters/index.json` (array `files`).
3. Kirim Pull Request. Karakter tampil di galeri setelah PR digabung.

Aturan berkas:

- `schemaVersion` wajib `1`.
- `id` unik, huruf kecil, tanpa spasi (contoh: `penasihat-keuangan`).
- `iconType` salah satu dari: `general`, `coder`, `writer`, `translator`,
  `reasoning`, `speed`, `shell`, `assistant`, `custom`.
- `avatarUri` harus kosong (avatar lokal HP tidak ikut terbagi).
- `temperature` 0–2 atau `null`; `topK` positif atau `null`.
- `author`, `lang`, `tags` hanya dipakai galeri dan diabaikan aplikasi
  saat impor (parser Gson + `normalize()` toleran terhadap field tambahan).

## Deploy

Folder ini bisa di-host di layanan statis apa pun (GitHub Pages repo terpisah,
Netlify, nginx). Cukup sajikan isi `web/` sebagai document root.
