#!/bin/bash

# --- CONFIGURATION ---
# Lock execution to the script's folder so paths always work
cd "$(dirname "$0")"
VIDEO_DEVICE="/dev/video20"

# --- COLORS ---
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🎥 BrokieCam Launcher${NC}"
echo "=============================="

# 0. Check for Virtual Camera (Reboot Handler)
if [ ! -e "$VIDEO_DEVICE" ]; then
    echo -e "${YELLOW}[*] Virtual camera ($VIDEO_DEVICE) not found.${NC}"
    echo -n "    Creating it now (Password may be required)... "
    
    # Try to unload first just in case it's in a bad state
    sudo modprobe -r v4l2loopback 2>/dev/null
    
    # Load it with correct settings
    if sudo modprobe v4l2loopback video_nr=20 card_label="BrokieCam" exclusive_caps=1; then
        echo -e "${GREEN}DONE${NC}"
    else
        echo -e "${RED}FAILED${NC}"
        echo "[!] Could not load v4l2loopback."
        echo "    Make sure you installed it: sudo pacman -S v4l2loopback-dkms"
        exit 1
    fi
else
    echo -e "[+] Virtual camera found ($VIDEO_DEVICE)"
fi

# 1. Check if ADB sees a device
echo -n "[*] Looking for phone... "
DEVICE_CHECK=$(adb devices | grep -w "device")

if [ -z "$DEVICE_CHECK" ]; then
    echo -e "${RED}FAILED${NC}"
    echo "[!] No phone found!" 
    echo "   1. Plug in USB cable."
    echo "   2. Enable USB Debugging."
    exit 1
else
    echo -e "${GREEN}FOUND${NC}"
fi

# 2. Setup the ADB Bridge
echo -n "[+] Building TCP Bridge... "
adb reverse tcp:5000 tcp:5000

if [ $? -eq 0 ]; then
    echo -e "${GREEN}DONE${NC}"
else
    echo -e "${RED}FAILED${NC}"
    echo "[!] ADB Reverse failed. Try unplugging/replugging."
    exit 1
fi

# 3. Start the Driver (Auto-detects TS vs JS)
echo "[+] Starting Video Driver..."
echo "   (Press Ctrl+C to stop)"
echo "=============================="

# Check for TypeScript in src/ (Standard structure)
if [ -f "src/index.ts" ]; then
    npx ts-node src/index.ts

# Check for TypeScript in root
elif [ -f "index.ts" ]; then
    npx ts-node index.ts

# Check for JavaScript (if you built it)
elif [ -f "index.js" ]; then
    node index.js

else
    echo -e "${RED}[!] Error: Could not find index.ts or src/index.ts!${NC}"
    exit 1
fi

# 4. Cleanup
echo ""
echo "[-] Stopping..."
echo "[-] Bye!"