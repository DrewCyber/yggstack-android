#!/usr/bin/env python3
import subprocess
import sys
import os

# Check if we can use sips (built-in macOS tool)
try:
    # First, let's try using qlmanage to convert SVG to PNG, then resize with sips
    svg_file = "app_icon.svg"
    
    # Create temporary 200x200 PNG using qlmanage
    temp_png = "temp_icon.png"
    
    print("Converting SVG to PNG using macOS qlmanage...")
    subprocess.run([
        "qlmanage", "-t", "-s", "200", "-o", ".", svg_file
    ], check=True)
    
    # qlmanage creates a file with .png extension added
    generated_file = f"{svg_file}.png"
    if os.path.exists(generated_file):
        os.rename(generated_file, temp_png)
    
    # Icon sizes for Android
    sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192
    }
    
    for density, size in sizes.items():
        output_dir = f"app/src/main/res/mipmap-{density}"
        os.makedirs(output_dir, exist_ok=True)
        
        output_file = f"{output_dir}/ic_launcher.png"
        print(f"Creating {output_file} ({size}x{size})...")
        
        subprocess.run([
            "sips", "-z", str(size), str(size), temp_png, "--out", output_file
        ], check=True)
        
        # Also create round icon (same image)
        output_file_round = f"{output_dir}/ic_launcher_round.png"
        subprocess.run([
            "sips", "-z", str(size), str(size), temp_png, "--out", output_file_round
        ], check=True)
    
    # Clean up
    if os.path.exists(temp_png):
        os.remove(temp_png)
    
    print("\n✓ All icons created successfully!")
    
except subprocess.CalledProcessError as e:
    print(f"Error: {e}")
    sys.exit(1)
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
