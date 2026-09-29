#!/usr/bin/env python3
"""
CameraGun Icon Generator Utility
Converts a raw square logo image (e.g. Logo.jpg) into:
1. Android standard & round launcher icons for all screen densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi).
2. Windows multi-resolution .ico file (16, 24, 32, 48, 64, 128, 256 px) and high-res PNG for PC Server.
"""

import os
import sys
from PIL import Image, ImageDraw

def create_circular_mask(size):
    """Creates a smooth anti-aliased circular alpha mask using 4x supersampling."""
    scale = 4
    mask_size = (size[0] * scale, size[1] * scale)
    mask = Image.new("L", mask_size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, mask_size[0] - 1, mask_size[1] - 1), fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)

def generate_icons(logo_path="Logo.jpg"):
    if not os.path.exists(logo_path):
        print(f"Error: File '{logo_path}' tidak ditemukan!")
        sys.exit(1)

    print(f"Memuat logo sumber: {logo_path}")
    base_img = Image.open(logo_path).convert("RGBA")
    print(f"Ukuran logo sumber: {base_img.size[0]}x{base_img.size[1]} ({base_img.mode})")

    # ==========================================
    # 1. GENERATE ANDROID MIPMAP ICONS
    # ==========================================
    android_densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    android_res_base = os.path.join("app", "src", "main", "res")

    print("\n--- [1/2] Membuat Android Launcher Icons ---")
    for density, size in android_densities.items():
        density_dir = os.path.join(android_res_base, density)
        os.makedirs(density_dir, exist_ok=True)

        # Standard Launcher Icon
        resized_standard = base_img.resize((size, size), Image.Resampling.LANCZOS)
        standard_path = os.path.join(density_dir, "ic_launcher.png")
        resized_standard.save(standard_path, "PNG", optimize=True)
        print(f"  [OK] {standard_path} ({size}x{size})")

        # Round Launcher Icon with anti-aliased circular mask
        round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_img.paste(resized_standard, (0, 0))
        circle_mask = create_circular_mask((size, size))
        round_img.putalpha(circle_mask)

        round_path = os.path.join(density_dir, "ic_launcher_round.png")
        round_img.save(round_path, "PNG", optimize=True)
        print(f"  [OK] {round_path} ({size}x{size}) [Circular]")

    # ==========================================
    # 2. GENERATE WINDOWS MULTI-RES ICO
    # ==========================================
    print("\n--- [2/2] Membuat Windows Multi-Resolution App Icon ---")
    pc_resources_dir = os.path.join("PC_Server", "Resources")
    os.makedirs(pc_resources_dir, exist_ok=True)

    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    ico_path = os.path.join(pc_resources_dir, "app.ico")

    # Pillow generates embedded multi-resolution icon structures
    base_img.save(
        ico_path,
        format="ICO",
        sizes=ico_sizes
    )
    print(f"  [OK] {ico_path} (Berisi resolusi: {', '.join([f'{w}x{h}' for w, h in ico_sizes])})")

    # Juga simpan versi PNG 256x256 untuk UI / Form icon
    png_path = os.path.join(pc_resources_dir, "app.png")
    base_img.resize((256, 256), Image.Resampling.LANCZOS).save(png_path, "PNG", optimize=True)
    print(f"  [OK] {png_path} (256x256)")

    print("\nSelesai! Seluruh aset ikon aplikasi telah berhasil di-generate secara sempurna.")

if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "Logo.jpg"
    generate_icons(src)
