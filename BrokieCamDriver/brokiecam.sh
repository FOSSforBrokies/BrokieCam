#!/bin/bash

# =====================================
# CONFIGURATION
# =====================================

# Lock execution to the script's folder so paths always work
cd "$(dirname "$0")"
VIDEO_DEVICE="/dev/video20"
PORT=5000

# =====================================
# COLORS
# =====================================
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# =====================================
# CLEANUP HANDLER
# =====================================

# Triggers automatically when Ctrl+C is pressed
cleanup() {
    echo -e "\n${YELLOW}[-] Stopping BrokieCam Server...${NC}"
    echo -n "[-] Tearing down ADB reverse tunnel... "
    adb reverse --remove tcp:$PORT 2>/dev/null
    echo -e "${GREEN}DONE${NC}"
    echo -e "${CYAN}[-] Bye!${NC}"
    exit 0
}

# Bind the cleanup function to SIGINT and SIGTERM
trap cleanup SIGINT SIGTERM

echo -e "${GREEN}BrokieCam Launcher${NC}"
echo "=============================="

# =====================================
# VIRTUAL CAMERA SETUP
# =====================================

echo -e "${YELLOW}[*] Resetting Virtual Camera ($VIDEO_DEVICE)...${NC}"

# Unload the camera to clear out broken states or old buffers
sudo modprobe -r v4l2loopback 2>/dev/null

# Rebuild it with strict exclusive_caps and low latency buffers
echo -n "    Loading v4l2loopback module... "
if sudo modprobe v4l2loopback video_nr=20 card_label="BrokieCam" exclusive_caps=1 max_buffers=2; then
    echo -e "${GREEN}DONE${NC}"
else
    echo -e "${RED}FAILED${NC}"
    echo "[!] Could not load v4l2loopback."
    echo "    Make sure you installed it: sudo pacman -S v4l2loopback-dkms"
    exit 1
fi

# =====================================
# 1. DEVICE DETECTION
# =====================================

# Check if ADB sees a device
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

# =====================================
# 2. ADB BRIDGE SETUP
# =====================================
echo -n "[+] Building TCP Bridge... "
adb reverse tcp:$PORT tcp:$PORT

if [ $? -eq 0 ]; then
    echo -e "${GREEN}DONE${NC}"
else
    echo -e "${RED}FAILED${NC}"
    echo "[!] ADB Reverse failed. Try unplugging/replugging."
    exit 1
fi

# =====================================
# 3. START VIDEO DRIVER
# =====================================
echo -e "${CYAN}[+] Starting Video Driver...${NC}"
echo -e "${YELLOW}   (Press Ctrl+C to stop)${NC}"
echo "=============================="

# Check for TypeScript in src/
if [ -f "src/index.ts" ]; then
    npx tsx src/index.ts

# Check for TypeScript in root
elif [ -f "index.ts" ]; then
    npx tsx index.ts

# Check for JavaScript
elif [ -f "index.js" ]; then
    node index.js

else
    echo -e "${RED}[!] Error: Could not find index.ts, src/index.ts, or index.js!${NC}"
    exit 1
fi