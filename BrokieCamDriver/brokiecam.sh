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

    echo -n "[-] Removing Virtual Microphone... "
    # Unload the PulseAudio modules
    pactl unload-module "$PA_SOURCE_ID" 2>/dev/null
    pactl unload-module "$PA_SINK_ID" 2>/dev/null
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
# NOTE: It is highly recommended to configure the virtual camera to start permanently
# on boot so you don't have to enter your sudo password every time.
#
# If you want the script to create and destroy the camera every time,
# uncomment the block below:

# echo -e "${YELLOW}[*] Resetting Virtual Camera ($VIDEO_DEVICE)...${NC}"

# # Unload the camera to clear out broken states or old buffers
# sudo modprobe -r v4l2loopback 2>/dev/null

# # Rebuild it with strict exclusive_caps and low latency buffers
# echo -n "    Loading v4l2loopback module... "
# if sudo modprobe v4l2loopback video_nr=20 card_label="BrokieCam" exclusive_caps=1 max_buffers=2; then
#     echo -e "${GREEN}DONE${NC}"
# else
#     echo -e "${RED}FAILED${NC}"
#     echo "[!] Could not load v4l2loopback."
#     echo "    Make sure you installed it: sudo pacman -S v4l2loopback-dkms"
#     exit 1
# fi

# =====================================
# VIRTUAL MICROPHONE SETUP
# =====================================
echo -e "${YELLOW}[*] Setting up Virtual Microphone...${NC}"

# Clear out any old instances if the script crashed previously
for id in $(pactl list short modules | grep "BrokieCam" | awk '{print $1}'); do
    pactl unload-module "$id" 2>/dev/null
done

# Create a Null Sink
PA_SINK_ID=$(pactl load-module module-null-sink sink_name=BrokieCam_Sink sink_properties=device.description="BrokieCam_Sink")

# Create a Virtual Source
PA_SOURCE_ID=$(pactl load-module module-virtual-source source_name=BrokieCam_Mic master=BrokieCam_Sink.monitor source_properties=device.description="BrokieCam_Mic")

echo -e "    ${GREEN}Virtual Microphone 'BrokieCam_Mic' Created!${NC}"

# =====================================
# DEVICE DETECTION
# =====================================

# Check if ADB sees a device
echo -n "[*] Looking for phone... "
DEVICE_CHECK=$(adb devices | grep -w "device")

if [ -z "$DEVICE_CHECK" ]; then
    echo -e "${RED}FAILED${NC}"
    echo "[!] No phone found! Plug in USB and enable Debugging."

    # Clean up mic
    pactl unload-module "$PA_SOURCE_ID" 2>/dev/null
    pactl unload-module "$PA_SINK_ID" 2>/dev/null

    exit 1
else
    echo -e "${GREEN}FOUND${NC}"
fi

# =====================================
# ADB BRIDGE SETUP
# =====================================
echo -n "[+] Building TCP Bridge... "
adb reverse tcp:$PORT tcp:$PORT

if [ $? -eq 0 ]; then
    echo -e "${GREEN}DONE${NC}"
else
    echo -e "${RED}FAILED${NC}"
    exit 1
fi

# =====================================
# START SERVER
# =====================================
echo -e "${CYAN}[+] Starting BrokieCam Server...${NC}"
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
    echo -e "${RED}FAILED[!] Error: Could not find index file!${NC}"
    exit 1
fi