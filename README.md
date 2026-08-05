# XMotion — Video Motion Editor (Android)

Aplikasi editor video bergaya motion-editor dengan **engine native C++ (NDK)**,
**versi RELEASE yang sudah ditandatangani (signed)** dengan keystore sendiri.

## 📦 Hasil Build
| Item | Nilai |
|---|---|
| APK Release | `XMotion-release.apk` (~4.5 MB) |
| Package | `com.xmotion.app` |
| Version | 3.0 (versionCode 3) |
| Min / Target SDK | 24 / 34 |
| Native (C++) | `libxmotion_native.so` (arm64-v8a, armeabi-v7a, x86_64) |
| Tipe build | **RELEASE** (minified + signed keystore) |

## 🔐 Keystore Release
- File: `keystore/xmotion-release.jks`
- Alias: `xmotion`
- Password: `xmotion-release-2026`
- Kredensial juga ada di `keystore.properties`.
> ⚠️ Untuk rilis Play Store, jangan pernah share keystore & password ini.
> Simpan aman. Kalau hilang, update app tidak bisa lagi.

## ✨ Fitur
- **Multi-layer overlay editor**: tambah lapisan teks, geser untuk posisi,
  atur skala & rotasi (slider), **dirender native C++** (affine transform
  `composeOverlay`).
- **Keyframe animasi**: timeline (SeekBar) → ketuk di waktu berbeda untuk
  set keyframe posisi/skala/rotasi; interpolasi linear antar keyframe.
- Filter native C++: Hitam Putih, Sepia, Invert, Vivid, Blur.
- Trim video, rasio (1:1 / 9:16 / 16:9 / 4:5), FPS (24/30/60/120), resolusi
  Original–4K, codec HEVC.
- **Convert video → Foto** (frame terbaik, dengan overlay).
- Preview video (ExoPlayer), UI hitam-putih bersih.
- Engine transcode **surface-based** (reliable, anti-crash).

## 🛠️ Build
Di GitHub Actions (`build-apk.yml`) atau lokal:
```bash
./gradlew assembleRelease   # hasil di app/build/outputs/apk/release/
./gradlew assembleDebug     # debug
```

## 🚀 Roadmap berikutnya
- Timeline multi-track sungguhan + drag ke urutan layer
- Render overlay ke **video export** (bukan cuma preview/foto) via OpenGL/EGL
- Efek GPU real-time (glow, warp, transition) via OpenGL shader
- Audio mixing & lebih banyak efek
