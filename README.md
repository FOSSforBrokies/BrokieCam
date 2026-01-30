# BrokieCam

> **Turn your Android phone into a webcam over USB** — Free, open-source, and I use Arch BTW:3.

BrokieCam is a lightweight Android app that streams your phone's camera to your Linux computer via USB, creating a virtual webcam device. Perfect for video calls, streaming, or recording when you don't have a dedicated webcam.

![License](https://img.shields.io/badge/License-GPL%20v2-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)
![Server](https://img.shields.io/badge/server-Node.js%20%7C%20Linux-orange.svg)

---

## Features

- **Low Latency** — Real-time streaming optimized for video calls
- **USB Connection** — No WiFi lag, no network setup required
- **720p Streaming** — Smooth 30 FPS at 1280x720 resolution **(can be upgraded in configurations)**
- **Virtual Webcam** — Works with Zoom, Teams, OBS, and any app that supports V4L2
- **Battery Friendly** — USB connection also charges your phone
- **Privacy First** — All processing happens locally, no cloud services
- **100% Free** — No ads, no subscriptions, no bullshit

---

## Requirements

### Android App
- **OS**: Android 8.0 (API 26) or higher
- **Camera**: Any device with CameraX support
- **USB Debugging**: Enabled in Developer Options

### Computer
- **OS**: Any Linux distro with V4L2 support
- **Node.js**: 18.0 or higher
- **FFmpeg**: Latest version with v4l2loopback support
- **v4l2loopback**: Virtual camera kernel module
- **ADB (Android Debug Bridge)**

---

## How to Run?

### One-Time Setup

**1. Install Dependencies**

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y nodejs npm ffmpeg adb v4l2loopback-dkms

# Arch Linux
sudo pacman -S nodejs npm ffmpeg android-tools v4l2loopback-dkms linux-headers

# Fedora
sudo dnf install -y nodejs npm ffmpeg android-tools v4l2loopback
```

**2. Clone and install:**

```bash
git clone https://github.com/FOSSforBrokies/BrokieCam.git
cd BrokieCamDriver
npm install
```

**3. Install Android app:**

- Enable "Install via USB" in Developer Options
- Run the project from Android Studio **OR**
- Download the latest APK from the [Releases](link-to-releases) page (Coming Soon)

**4. Enable Developer Mode:**
1. Go to **Settings** → **About Phone**
2. Tap **Build Number** 7 times
3. Go back to **Settings** → **System** → **Developer Options**
4. Enable **USB Debugging**

### Every Time You Want to Stream

**1. Connect phone to the computer via USB**

**2. Run the launcher script:**

```bash
cd brokiecam/server
./brokiecam.sh
```

The script automatically handles:
- Loading v4l2loopback kernel module (`/dev/video20`)
- Detecting your phone via ADB
- Setting up reverse tunnel (`adb reverse tcp:5000 tcp:5000`)
- Starting the server (TypeScript or JavaScript)

**3. Open BrokieCam app on phone and tap "START USB STREAM"**

**4. Done!** Use `/dev/video20` in Zoom, Discord, OBS, etc

**Expected output:**

```
🎥 BrokieCam Launcher
==============================
[*] Virtual camera (/dev/video20) not found.
    Creating it now (Password may be required)... [sudo] password for maxim: 
DONE
[*] Looking for phone... FOUND
[+] Building TCP Bridge... DONE
[+] Starting Video Driver...
   (Press Ctrl+C to stop)
==============================
[OK] BrokieCam Server running on port 5000
[OK] Target: /dev/video20
```

### Quick Test

```bash
# Test the virtual camera
vlc v4l2:///dev/video20
# or
ffplay /dev/video20
```

---

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the GNU v2 License - see the [LICENSE](LICENSE) file for details.

---