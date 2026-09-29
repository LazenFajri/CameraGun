using System;
using System.Runtime.InteropServices;

namespace CameraGun.Server
{
    [Flags]
    public enum LightgunButtons : ushort
    {
        None         = 0,
        TriggerL2    = 1 << 0,  // Primary Trigger (Left Mouse / GunCon Trigger)
        Cross        = 1 << 1,  // PS Cross (A)
        Circle       = 1 << 2,  // PS Circle (B)
        Square       = 1 << 3,  // PS Square (X)
        Triangle     = 1 << 4,  // PS Triangle (Y)
        DpadUp       = 1 << 5,
        DpadDown     = 1 << 6,
        DpadLeft     = 1 << 7,
        DpadRight    = 1 << 8,
        Reload       = 1 << 9,  // Off-screen Reload
        OptionsStart = 1 << 10,
        SelectShare  = 1 << 11,
        TriggerR2    = 1 << 12  // Secondary Trigger / Right Click
    }

    [Flags]
    public enum LightgunFlags : byte
    {
        None            = 0,
        TrackingLocked  = 1 << 0,
        OffscreenReload = 1 << 1,
        LowConfidence   = 1 << 2,
        Player2         = 1 << 3  // 0 = Player 1, 1 = Player 2
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct LightgunInputPacket
    {
        public byte Header;             // 0xAA
        public byte Flags;              // LightgunFlags
        public ushort PointerX;         // 0 - 65535
        public ushort PointerY;         // 0 - 65535
        public ushort ButtonMask;       // LightgunButtons
        public short GyroPitch;         // Tilt pitch
        public short GyroRoll;          // Tilt roll
        public ushort TimestampMs;      // Monotonic ms
        public byte TrackingConfidence; // 0 - 100%
        public byte Checksum;           // CRC8
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct LightgunConfigPacket
    {
        public byte Header;             // 0xBB
        public byte HMin;
        public byte SMin;
        public byte VMin;
        public byte HMax;
        public byte SMax;
        public byte VMax;
        public ushort TargetWidth;
        public ushort TargetHeight;
        public byte BorderThicknessPct;
        public ushort OneEuroMinCutoff;
        public byte OneEuroBeta;
        public byte Checksum;           // CRC8
    }

    public static class PacketUtils
    {
        public static byte ComputeCrc8(ReadOnlySpan<byte> buffer, int length)
        {
            byte crc = 0x00;
            for (int i = 0; i < length; i++)
            {
                byte extract = buffer[i];
                for (int j = 8; j > 0; j--)
                {
                    byte sum = (byte)((crc ^ extract) & 0x01);
                    crc >>= 1;
                    if (sum != 0)
                    {
                        crc ^= 0x8C;
                    }
                    extract >>= 1;
                }
            }
            return crc;
        }

        public static byte[] SerializeConfig(LightgunConfigPacket config)
        {
            int size = Marshal.SizeOf<LightgunConfigPacket>();
            byte[] arr = new byte[size];
            IntPtr ptr = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(config, ptr, false);
                Marshal.Copy(ptr, arr, 0, size);
            }
            finally
            {
                Marshal.FreeHGlobal(ptr);
            }
            arr[size - 1] = ComputeCrc8(arr.AsSpan(0, size - 1), size - 1);
            return arr;
        }
    }
}
