#pragma once
#include <cstdint>
#include <cstddef>

#pragma pack(push, 1)

// Button Bitmask constants (PlayStation-style)
namespace LightgunButtons {
    constexpr uint16_t TRIGGER_L2       = 1 << 0;  // Primary Fire (Left Click / GunCon Trigger)
    constexpr uint16_t CROSS            = 1 << 1;  // PS Cross (A button)
    constexpr uint16_t CIRCLE           = 1 << 2;  // PS Circle (B button / GunCon A)
    constexpr uint16_t SQUARE           = 1 << 3;  // PS Square (X button / GunCon B)
    constexpr uint16_t TRIANGLE         = 1 << 4;  // PS Triangle (Y button)
    constexpr uint16_t DPAD_UP          = 1 << 5;  // D-Pad Up
    constexpr uint16_t DPAD_DOWN        = 1 << 6;  // D-Pad Down
    constexpr uint16_t DPAD_LEFT        = 1 << 7;  // D-Pad Left
    constexpr uint16_t DPAD_RIGHT       = 1 << 8;  // D-Pad Right
    constexpr uint16_t RELOAD           = 1 << 9;  // Offscreen Reload trigger
    constexpr uint16_t START_OPTIONS    = 1 << 10; // Start / Option
    constexpr uint16_t SELECT_SHARE     = 1 << 11; // Select / Share
    constexpr uint16_t TRIGGER_R2       = 1 << 12; // Secondary Trigger / Grenade
}

// Packet flags
namespace LightgunFlags {
    constexpr uint8_t TRACKING_LOCKED   = 1 << 0;  // 1 = Border 4 sudut valid terdeteksi
    constexpr uint8_t OFFSCREEN_RELOAD  = 1 << 1;  // 1 = Pointer diarahkan ke luar layar bawah
    constexpr uint8_t LOW_CONFIDENCE    = 1 << 2;  // 1 = Deteksi kontur marginal
    constexpr uint8_t PLAYER_2          = 1 << 3;  // 1 = Player 2 (0 = Player 1)
}

// Struct 16 Byte: Android -> PC Telemetry Packet
struct LightgunInputPacket {
    uint8_t  header;             // 0xAA (Magic sync byte)
    uint8_t  flags;              // Tracking & status flags (LightgunFlags)
    uint16_t pointerX;           // 0 - 65535 (Normalized screen X)
    uint16_t pointerY;           // 0 - 65535 (Normalized screen Y)
    uint16_t buttonMask;         // Bitmask tombol PlayStation
    int16_t  gyroPitch;          // Sensor Tilt vertikal (-32768 to 32767)
    int16_t  gyroRoll;           // Sensor Tilt rotasi laras
    uint16_t timestampMs;        // 16-bit wrapping millisecond timer
    uint8_t  trackingConfidence; // 0 - 100% confidence
    uint8_t  checksum;           // CRC8-Dallas/Maxim
};

// Struct 16 Byte: PC -> Android Configuration Packet
struct LightgunConfigPacket {
    uint8_t  header;             // 0xBB (Magic sync byte)
    uint8_t  hMin;               // HSV Lower Bound
    uint8_t  sMin;
    uint8_t  vMin;
    uint8_t  hMax;               // HSV Upper Bound
    uint8_t  sMax;
    uint8_t  vMax;
    uint16_t targetWidth;        // Lebar monitor PC (e.g. 1920)
    uint16_t targetHeight;       // Tinggi monitor PC (e.g. 1080)
    uint8_t  borderThicknessPct; // Ketebalan border overlay (e.g. 2%)
    uint16_t oneEuroMinCutoff;   // Scaled x 100 (e.g. 120 = 1.2 Hz)
    uint8_t  oneEuroBeta;        // Scaled x 10  (e.g. 50 = 5.0)
    uint8_t  checksum;           // CRC8
};

#pragma pack(pop)

static_assert(sizeof(LightgunInputPacket) == 16, "LightgunInputPacket must be exactly 16 bytes!");
static_assert(sizeof(LightgunConfigPacket) == 16, "LightgunConfigPacket must be exactly 16 bytes!");

// CRC8 Dallas/Maxim implementation (Polynomial: 0x31, Init: 0x00)
inline uint8_t ComputeCRC8(const uint8_t* buffer, size_t length) {
    uint8_t crc = 0x00;
    for (size_t i = 0; i < length; ++i) {
        uint8_t extract = buffer[i];
        for (uint8_t j = 8; j > 0; --j) {
            uint8_t sum = (crc ^ extract) & 0x01;
            crc >>= 1;
            if (sum) {
                crc ^= 0x8C; // Reversed polynomial for 0x31
            }
            extract >>= 1;
        }
    }
    return crc;
}
