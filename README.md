# CameraGun AI - Smartphone Lightgun Controller (Sinden Clone via Bluetooth)

Sistem Lightgun nirkabel presisi tinggi berlatensi ultra-rendah (<10ms) berbasis kamera smartphone Android dan Bluetooth Low Energy (BLE) / RFCOMM untuk PC Windows. Sistem ini mengkloning cara kerja **Sinden Lightgun** menggunakan algoritma Computer Vision (OpenCV NDK), Homography Perspective Mapping, dan filter anti-jitter **One Euro Filter ($1€$)**, dilengkapi fitur **Customizable Screen Border** (warna border monitor PC dapat diubah secara dinamis agar menyatu dengan visual game).

---

## 🎮 Fitur Utama

1. **Dynamic & Customizable Screen Border**:
   - Window overlay transparent click-through Win32/Direct2D tanpa mengganggu game.
   - Pilihan warna instan: **Neon Green (`#00FF44`)**, **Electric Cyan (`#00E5FF`)**, **Vivid Magenta (`#FF0066`)**, dan **Pure White (`#FFFFFF`)**.
   - Kalibrasi batas HSV secara instan dikirim dari PC ke HP via paket biner Bluetooth tanpa perlu recompile APK.
2. **Ultra-Low Latency (<10ms) Binary Protocol**:
   - Paket biner kompak 16-byte (`#pragma pack(push, 1)`) dengan validasi integritas CRC8 Dallas/Maxim.
   - Non-blocking asynchronous transmit/receive queue.
3. **Native Vision Pipeline (C++ NDK + OpenCV)**:
   - Zero-copy YUV420_888 / NV21 frame extraction.
   - Deteksi poligon quadrilateral 4 sudut layar monitor dengan `approxPolyDP` dan convex hull check.
   - Transformasi Homografi `getPerspectiveTransform` dari koordinat kamera ke resolusi native PC.
   - Deteksi **Off-Screen Reload** saat laras diarahkan ke bawah/luar layar.
4. **Anti-Jitter One Euro Filter**:
   - Menghilangkan tremor/getaran tangan saat membidik diam, namun menghasilkan lag mendekati nol ($0\,\text{ms}$) saat gerakan tembak menyentak cepat (*flick shot*).
5. **Driver Emulation PC Ganda**:
   - **Absolute Mouse Pointer (`SendInput`)**: Untuk Teknoparrot, Dolphin, Model 2/3 Emulator, dan MAME.
   - **Virtual Controller (`ViGEmClient`)**: Emulasi gamepad Xbox 360 / DualShock 4 otomatis terbaca di PCSX2, RPCS3, dan RetroArch.

---

## 📁 Struktur Workspace

```
d:\CameraGun\
├── .github\workflows\                      # CI/CD Workflows (Android APK, Windows EXE, Release)
├── app\                                    # Modul Android (Native Kotlin + C++ NDK)
│   ├── src\main\
│   │   ├── AndroidManifest.xml
│   │   ├── cpp\
│   │   │   ├── CMakeLists.txt              # Build script NDK & OpenCV linkage
│   │   │   ├── CommonPacketDef.h           # Struct 16-byte biner & CRC8
│   │   │   ├── OneEuroFilter.hpp           # Implementasi matematis One Euro Filter
│   │   │   └── vision_pipeline.cpp         # Pipeline OpenCV (HSV, Contours, Homography)
│   │   ├── java\com\cameragun\lightgun\
│   │   │   ├── MainActivity.kt             # Camera2 frame loop & PlayStation overlay
│   │   │   ├── NativeVisionBridge.kt       # JNI Bridge Kotlin <-> C++
│   │   │   ├── BluetoothTransmitter.kt     # BLE GATT Server / L2CAP Transmitter
│   │   │   ├── SensorsManager.kt           # IMU Gyroscope/Accelerometer
│   │   │   └── CornerOverlayView.kt        # Real-time HUD canvas (Corner box & crosshair)
│   │   └── res\
│   │       ├── mipmap-*/                   # Launcher Icons (ic_launcher & ic_launcher_round)
│   │       └── layout, values, drawable/   # PlayStation-style layout & vectors
│   └── build.gradle.kts
├── PC_Server\                              # Aplikasi Driver Windows (.NET 8 WPF GUI)
│   ├── PC_Server.csproj                    # Konfigurasi project & ApplicationIcon app.ico
│   ├── Resources\                          # Windows Multi-Res app.ico & app.png
│   ├── App.xaml / App.xaml.cs              # Entry point aplikasi WPF GUI
│   ├── MainWindow.xaml / .xaml.cs          # Dashboard Cyberpunk, Radar HUD & Color Controller
│   ├── BorderOverlay.cs                    # Transparent Click-Through Border Form (Win32)
│   ├── BluetoothReceiver.cs                # Windows WinRT BLE & RFCOMM Receiver
│   ├── InputInjection.cs                   # Win32 SendInput + ViGEmBus Gamepad
│   ├── PacketStructs.cs                    # Struct marshaling & CRC8
│   └── app.manifest                        # Windows PerMonitorV2 manifest
├── scripts\
│   └── generate_icons.py                   # Script konversi logo ke ikon Android & Windows
├── Logo.jpg                                # Logo mentah sumber resolusi tinggi
├── build.gradle.kts                        # Root Gradle Android
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 🛠️ Persyaratan & Dependencies

### 1. Sisi PC (Windows 10 / 11)
* **.NET 8.0 SDK** (atau Visual Studio 2022 dengan workload *.NET Desktop Development*).
* **ViGEmBus Driver (Wajib untuk emulasi gamepad)**:
  - Unduh dan install installer resmi: [ViGEmBus Release (GitHub)](https://github.com/nefarius/ViGEmBus/releases).
  - *Catatan: Jika ViGEmBus belum diinstall, PC Server akan otomatis fallback ke mode Absolute Mouse Pointer saja.*
* Dongle Bluetooth 4.0 / 5.0+ yang mendukung BLE & RFCOMM.

### 2. Sisi Android (Smartphone)
* **Android Studio Iguana / Jellyfish** atau lebih baru.
* **Android NDK** (r25c atau r26b) + CMake 3.22.1+.
* **OpenCV Android SDK (v4.8.0 atau v4.9.0)**:
  1. Unduh OpenCV Android SDK dari [OpenCV Releases](https://opencv.org/releases/).
  2. Ekstrak folder `OpenCV-android-sdk` ke direktori root proyek atau sejajar dengan direktori `CameraGun` (misalnya `d:\CameraGun\OpenCV-android-sdk`).
  3. Konfigurasi path pada `app/src/main/cpp/CMakeLists.txt` jika diletakkan di folder lain.

---

## 🚀 Langkah Build & Kompilasi

### A. Kompilasi Android App
1. Buka folder `d:\CameraGun` di **Android Studio**.
2. Pastikan file `local.properties` telah memuat lokasi Android SDK dan NDK:
   ```properties
   sdk.dir=C\:\\Users\\<Username>\\AppData\\Local\\Android\\Sdk
   ndk.dir=C\:\\Users\\<Username>\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653
   ```
3. Sync Gradle Project (`File` $\rightarrow$ `Sync Project with Gradle Files`).
4. Hubungkan smartphone Android via kabel USB dengan opsi **USB Debugging** aktif.
5. Klik **Run 'app'** (`Shift + F10`) untuk menginstall aplikasi pada HP.
6. Berikan izin **Camera**, **Bluetooth/Nearby Devices**, dan **Location** saat aplikasi pertama kali dibuka.

### B. Kompilasi PC Server
1. Buka PowerShell atau Command Prompt di folder `d:\CameraGun\PC_Server`.
2. Jalankan perintah kompilasi:
   ```powershell
   dotnet build -c Release
   ```
3. Atau buka file proyek di Visual Studio 2022 dan tekan `Ctrl + Shift + B`.
4. File executable akan berada di: `d:\CameraGun\PC_Server\bin\Release\net8.0-windows10.0.19041.0\PC_Server.exe`.

---

## 📡 Panduan Pairing & Koneksi Pertama Kali

1. **Jalankan PC Server (WPF Cyberpunk GUI)**:
   ```powershell
   cd d:\CameraGun\PC_Server
   dotnet run -c Release
   ```
   * Window dashboard modern bertema Cyberpunk Dark Mode akan terbuka.
   * Window border persegi (click-through overlay) akan muncul otomatis di tepi monitor PC.
2. **Buka Aplikasi di HP Android**:
   * Posisikan smartphone Landscape menghadap layar monitor PC.
   * Aplikasi otomatis mengaktifkan BLE Peripheral Advertisement.
3. **Koneksi Otomatis**:
   * PC Server akan mendeteksi HP dan menghubungkan GATT notification stream secara otomatis.
   * Di dashboard PC: Badge Bluetooth berubah menjadi `CONNECTED` (Hijau Neon), pointer tracking radar aktif, dan rate stream menampilkan `60 - 120 Hz`.
   * Di layar HP: HUD atas menampilkan `BT: CONNECTED` (Hijau) dan status target berubah menjadi `TARGET: LOCKED` saat 4 sudut layar terdeteksi.

---

## 🎯 Panduan Kalibrasi & Mengubah Warna Border (GUI Visual)

Di panel sebelah kiri dashboard PC Server (**Border Control**):
* **Color Preset Buttons**: Klik tombol preset warna **C (Cyan)**, **G (Green)**, **M (Magenta)**, atau **W (White)**.
* **Custom Hex Color**: Masukkan kode hex 6 digit (contoh: `00E5FF`, `FF0066`, `00FF44`) lalu klik tombol `SET`.
* **Thickness Slider**: Geser slider ketebalan border (2 px - 24 px) sesuai jarak duduk dan ukuran monitor Anda.
* **Toggle Border**: Tombol `⊘ HIDE BORDER` / `⊕ SHOW BORDER` untuk menyembunyikan border sementara.
* **Sync to Phone**: Klik tombol `⟳ SYNC TO PHONE` untuk mengirim rentang threshold HSV dan resolusi target ke smartphone Android via Bluetooth.

Di panel sebelah kanan (**Settings**):
* **Filter Min Cutoff & Beta**: Atur sensitivitas One Euro Filter untuk menghilangkan getaran tangan (*hand jitter*) tanpa menambah input latency.
* **Emulator Profiles**: Pilih preset kontrol untuk Teknoparrot, Dolphin, PCSX2, MAME, atau RPCS3.
* **Minimize to Tray**: Sembunyikan window dashboard ke system tray saat sedang asyik bermain game. Double-click icon tray untuk menampilkan kembali.

---

## 🕹️ Konfigurasi Emulator Populer

| Emulator | Mode Input yang Digunakan | Konfigurasi di Emulator |
| :--- | :--- | :--- |
| **Teknoparrot** | Absolute Mouse Pointer / RawInput | Atur Gun X/Y ke `Cursor X / Cursor Y`, Trigger ke `Left Mouse Button`, Reload ke `Right Mouse Button`. |
| **Dolphin (Wii Remote)** | Emulated Wii Remote Pointer | Pada menu Controllers $\rightarrow$ Emulated Wii Remote $\rightarrow$ Point, assign sumbu ke `Cursor X- / X+` dan `Cursor Y- / Y+`. Tombol B di-assign ke Trigger (L2). |
| **PCSX2 (GunCon 2)** | ViGEmBus Gamepad / Mouse | Masuk ke Controller Settings $\rightarrow$ USB Port 1 $\rightarrow$ GunCon 2. Pilih Mouse sebagai pointer atau Virtual Xbox 360 controller. |
| **MAME** | Lightgun Absolute Device | Pada `mame.ini`, set: `lightgun 1`, `lightgun_device mouse`, `offscreen_reload 1`. |
| **Model 2 Emulator** | RawInput Mouse | Pada `EMULATOR.INI`, pastikan `UseRawInput=1`. |

---

## 📐 Spesifikasi Protokol Biner (16-Byte Struct)

```c
#pragma pack(push, 1)
struct LightgunInputPacket {
    uint8_t  header;             // 0xAA (Magic sync byte)
    uint8_t  flags;              // Bit 0: Locked, Bit 1: Reload, Bit 2: Low Conf
    uint16_t pointerX;           // 0 - 65535 (Normalized screen X)
    uint16_t pointerY;           // 0 - 65535 (Normalized screen Y)
    uint16_t buttonMask;         // PlayStation Button Bitmask
    int16_t  gyroPitch;          // Kemiringan vertikal
    int16_t  gyroRoll;           // Rotasi laras
    uint16_t timestampMs;        // Wrapping millisecond clock
    uint8_t  trackingConfidence; // 0 - 100%
    uint8_t  checksum;           // CRC8 Dallas/Maxim
};
#pragma pack(pop)
```
Semua paket tervalidasi menggunakan algoritma CRC8 Dallas/Maxim polinomial `0x31` (`0x8C` reversed), memastikan nol toleransi terhadap kesalahan data nirkabel.