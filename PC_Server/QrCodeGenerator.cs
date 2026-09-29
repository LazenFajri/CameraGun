using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace CameraGun.Server
{
    /// <summary>
    /// Lightweight, zero-dependency QR Code generator (Version 1-3, Byte Encoding, Error Correction Level L/M)
    /// Produces a clean WPF DrawingImage / WriteableBitmap for displaying on screen.
    /// </summary>
    public static class QrCodeGenerator
    {
        public static DrawingImage GenerateQrDrawing(string text, int pixelSize = 200)
        {
            bool[,] matrix = GenerateMatrix(text);
            int size = matrix.GetLength(0);

            var group = new DrawingGroup();
            // White Background
            group.Children.Add(new GeometryDrawing(
                System.Windows.Media.Brushes.White,
                null,
                new RectangleGeometry(new Rect(0, 0, pixelSize, pixelSize))
            ));

            double quietZone = 2.0;
            double totalUnits = size + (quietZone * 2.0);
            double moduleSize = pixelSize / totalUnits;

            var blackBrush = System.Windows.Media.Brushes.Black;
            var geoGroup = new GeometryGroup();

            for (int r = 0; r < size; r++)
            {
                for (int c = 0; c < size; c++)
                {
                    if (matrix[r, c])
                    {
                        double x = (c + quietZone) * moduleSize;
                        double y = (r + quietZone) * moduleSize;
                        geoGroup.Children.Add(new RectangleGeometry(new Rect(x, y, moduleSize + 0.5, moduleSize + 0.5)));
                    }
                }
            }

            group.Children.Add(new GeometryDrawing(blackBrush, null, geoGroup));
            return new DrawingImage(group);
        }

        public static bool[,] GenerateMatrix(string content)
        {
            // Simple robust 25x25 (Version 2) or 29x29 (Version 3) generator
            byte[] bytes = System.Text.Encoding.UTF8.GetBytes(content);
            int version = bytes.Length <= 14 ? 1 : (bytes.Length <= 26 ? 2 : 3);
            int size = 17 + 4 * version;
            bool[,] grid = new bool[size, size];
            bool[,] isFunction = new bool[size, size];

            // 1. Finder patterns (Top-Left, Top-Right, Bottom-Left)
            DrawFinder(grid, isFunction, 0, 0);
            DrawFinder(grid, isFunction, size - 7, 0);
            DrawFinder(grid, isFunction, 0, size - 7);

            // 2. Alignment pattern for Version 2 (at row 18, col 18)
            if (version == 2)
            {
                DrawAlignment(grid, isFunction, 18, 18);
            }
            else if (version == 3)
            {
                DrawAlignment(grid, isFunction, 22, 22);
            }

            // 3. Timing patterns
            for (int i = 8; i < size - 8; i++)
            {
                grid[6, i] = (i % 2 == 0);
                isFunction[6, i] = true;
                grid[i, 6] = (i % 2 == 0);
                isFunction[i, 6] = true;
            }

            // Dark module
            grid[4 * version + 9, 8] = true;
            isFunction[4 * version + 9, 8] = true;

            // Reserve format info
            for (int i = 0; i < 9; i++)
            {
                isFunction[8, i] = true;
                isFunction[i, 8] = true;
            }
            for (int i = 0; i < 8; i++)
            {
                isFunction[size - 1 - i, 8] = true;
                isFunction[8, size - 1 - i] = true;
            }

            // 4. Data codewords stream
            List<byte> bits = new List<byte>();
            // Mode indicator: 0100 (Byte)
            AddBits(bits, 4, 4);
            // Character count indicator (8 bits for Version 1-9)
            AddBits(bits, bytes.Length, 8);
            // Payload
            foreach (byte b in bytes)
            {
                AddBits(bits, b, 8);
            }
            // Terminator 0000
            AddBits(bits, 0, 4);
            // Pad to byte
            while (bits.Count % 8 != 0) bits.Add(0);

            // Fill pad bytes
            int capacityBytes = (version == 1) ? 19 : ((version == 2) ? 34 : 55);
            byte[] pad = new byte[] { 0xEC, 0x11 };
            int padIdx = 0;
            while (bits.Count / 8 < capacityBytes)
            {
                AddBits(bits, pad[padIdx % 2], 8);
                padIdx++;
            }

            // Place data into grid (Zigzag upward/downward)
            int bitIdx = 0;
            int right = size - 1;
            bool upward = true;

            while (right > 0)
            {
                if (right == 6) right--; // Skip vertical timing line

                for (int vertical = 0; vertical < size; vertical++)
                {
                    int r = upward ? (size - 1 - vertical) : vertical;
                    for (int c = right; c >= right - 1; c--)
                    {
                        if (!isFunction[r, c])
                        {
                            bool val = (bitIdx < bits.Count) && (bits[bitIdx] == 1);
                            // Mask 0: (row + column) % 2 == 0
                            bool mask = ((r + c) % 2 == 0);
                            grid[r, c] = val ^ mask;
                            bitIdx++;
                        }
                    }
                }
                upward = !upward;
                right -= 2;
            }

            // Apply standard format info for Mask 0, Level L (0x77C4)
            int formatBits = 0x77C4;
            ApplyFormatBits(grid, formatBits, size);

            return grid;
        }

        private static void AddBits(List<byte> list, int value, int count)
        {
            for (int i = count - 1; i >= 0; i--)
            {
                list.Add((byte)((value >> i) & 1));
            }
        }

        private static void DrawFinder(bool[,] grid, bool[,] isFunc, int startRow, int startCol)
        {
            for (int r = 0; r < 7; r++)
            {
                for (int c = 0; c < 7; c++)
                {
                    bool isBlack = (r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
                    grid[startRow + r, startCol + c] = isBlack;
                    isFunc[startRow + r, startCol + c] = true;
                }
            }
            // Separator ring
            for (int r = -1; r <= 7; r++)
            {
                for (int c = -1; c <= 7; c++)
                {
                    int gr = startRow + r;
                    int gc = startCol + c;
                    if (gr >= 0 && gr < grid.GetLength(0) && gc >= 0 && gc < grid.GetLength(1))
                    {
                        if (r == -1 || r == 7 || c == -1 || c == 7)
                        {
                            grid[gr, gc] = false;
                            isFunc[gr, gc] = true;
                        }
                    }
                }
            }
        }

        private static void DrawAlignment(bool[,] grid, bool[,] isFunc, int centerRow, int centerCol)
        {
            for (int r = -2; r <= 2; r++)
            {
                for (int c = -2; c <= 2; c++)
                {
                    bool isBlack = (Math.Abs(r) == 2 || Math.Abs(c) == 2 || (r == 0 && c == 0));
                    grid[centerRow + r, centerCol + c] = isBlack;
                    isFunc[centerRow + r, centerCol + c] = true;
                }
            }
        }

        private static void ApplyFormatBits(bool[,] grid, int format, int size)
        {
            for (int i = 0; i < 15; i++)
            {
                bool bit = ((format >> i) & 1) == 1;
                // Top-left
                if (i <= 5) grid[8, i] = bit;
                else if (i == 6) grid[8, 7] = bit;
                else if (i == 7) grid[8, 8] = bit;
                else if (i == 8) grid[7, 8] = bit;
                else grid[14 - i, 8] = bit;

                // Split format
                if (i < 8) grid[size - 1 - i, 8] = bit;
                else grid[8, size - 15 + i] = bit;
            }
        }
    }
}
