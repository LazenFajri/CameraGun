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
├── PC_Server\                              # Aplikasi Driver Windows (.NET 8 Desktop)
│   ├── PC_Server.csproj                    # Konfigurasi project & ApplicationIcon app.ico
│   ├── Resources\                          # Windows Multi-Res app.ico & app.png
│   ├── Program.cs                          # CLI Controller, HUD metrics & color sync
│   ├── BorderOverlay.cs                    # Transparent Click-Through Border Form
│   ├── BluetoothReceiver.cs                # Windows WinRT BLE & RFCOMM Receiver
│   ├── InputInjection.cs                   # Win32 SendInput + ViGEmBus Gamepad
│   ├── PacketStructs.cs                    # Struct marshaling & CRC8
│   └── app.manifest                        # PerMonitorV2 High-DPI scaling
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

1. **Jalankan PC Server**:
   ```powershell
   cd d:\CameraGun\PC_Server
   dotnet run -c Release
   ```
   * Window border persegi berwarna hijau neon akan langsung muncul di tepi monitor PC.
   * Window ini bersifat **click-through** (klik mouse Anda akan tetap menembus game di baliknya).
2. **Buka Aplikasi di HP Android**:
   * Posisikan smartphone dalam posisi Landscape menghadap ke layar monitor PC.
   * Aplikasi akan otomatis menyiarkan BLE Advertisement dengan nama `CameraGun-XXXX`.
3. **Koneksi Otomatis**:
   * PC Server akan mendeteksi HP dan melakukan koneksi GATT secara instan.
   * Di layar HP, indikator status atas akan berubah dari `BT: DISCONNECTED` menjadi `BT: CONNECTED` (Hijau).
   * Status di console PC akan menampilkan rate streaming aktif: `[Stream] Rate: 60-120 Hz`.

---

## 🎯 Panduan Kalibrasi & Mengubah Warna Border

Jika game yang dimainkan memiliki elemen grafis warna hijau yang dominan, Anda dapat mengganti warna border secara instan langsung dari console PC:

* Tekan **`1`** pada keyboard PC $\rightarrow$ **Neon Green (`#00FF44`)** *(Default, ideal untuk House of the Dead / Time Crisis)*.
* Tekan **`2`** pada keyboard PC $\rightarrow$ **Electric Cyan (`#00E5FF`)** *(Ideal untuk game bernuansa gelap atau bertema darah)*.
* Tekan **`3`** pada keyboard PC $\rightarrow$ **Vivid Magenta (`#FF0066`)** *(Ideal untuk game berlatar hutan/alam seperti Jurassic Park)*.
* Tekan **`4`** pada keyboard PC $\rightarrow$ **Pure White (`#FFFFFF`)** *(Standar border Sinden Lightgun)*.
* Tekan **`+`** atau **`-`** $\rightarrow$ Menambah atau mengurangi ketebalan border layar.

> Begitu tombol ditekan di PC, paket `LightgunConfigPacket` langsung dikirim via Bluetooth ke HP, dan pipeline OpenCV di HP langsung menyesuaikan rentang threshold HSV tanpa jeda!

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