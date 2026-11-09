<p align="center">
  <img src="docs/logo.png" alt="FineVolume Logo" width="180" style="border-radius: 36px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);"/>
</p>

<h1 align="center">FineVolume</h1>

<p align="center">
  <strong>Volume Panel with 100-/+ Steps Fine Adjustment</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-%237f7f7f?style=flat-square&logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Design-Material%20You%20%2F%20Pixel-7C4DFF?style=flat-square" alt="Samsung One UI inspired"/>
  <img src="https://img.shields.io/badge/Security-100%25%20Offline-00C853?style=flat-square" alt="100% Offline"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License"/>
</p>

---

# FineVolume (Personal Fork)

This repository is a personal fork ([`DyniW/finevolume`](https://github.com/DyniW/finevolume)) based on [`tteja619/finevolumex`](https://github.com/tteja619/finevolumex), which originally stems from [`broknhrt2562/finevolume`](https://github.com/broknhrt2562/finevolume).

---

## Personal Updates & Local Modifications

- **Project Modifications:** Just merged oneui-volume-controls feature and persistent-per-app-volume fix from @tteja619 and it seems to correct the issues I had with the FineVolume app from @broknhrt2562:
  - No more losing precise volume control whenever an audio focus change occurs (changing a track or switching apps)
  - Add fine-tuning buttons for in-app settings
- **Environment & Build Config:** Updated to align with Kotlin 2.4.0 and configured JitPack dependencies.

---

## Original Projects & Documentation

- **Direct Upstream Base:** [`tteja619/finevolumex`](https://github.com/tteja619/finevolumex)
- **Original Source Repo:** [`broknhrt2562/finevolume`](https://github.com/broknhrt2562/finevolume)

For full feature sets, setup instructions, and initial development documentation, please refer to the original repositories linked above.

---

## 🌟 Overview

Android's exposed media-volume range varies by device, firmware, route, and audio stack. FineVolume adds a fine-control layer on top of that native range.

**FineVolume** replaces your phone's native volume panel with an ultra-responsive volume slider powered by a Shizuku high-precision audio proxy that unlocks 100+ granular volume levels.

The interface is built with **Jetpack Compose**, with separated labels, readable volume tracks, touch adjustment, and hardware-key repeat.

---

## 📦 Installation & Setup

1. **Install APK**:
   - Download the latest **[`FineVolume.apk`](https://github.com/DyniW/finevolume/releases/tag/latest)**.
2. **Enable Accessibility Service**:
   - Open **Settings** → **Accessibility** → **FineVolume** → Turn on.
3. **Grant DND Access**:
   - Tap the Ringer button on the overlay or follow the in-app prompt to allow Do Not Disturb access.
4. **Start Shizuku** *(for 120-step audio proxy)*:
   - Ensure [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) is running via Wireless Debugging or ADB, then open FineVolume and grant permission.

---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/DyniW/finevolume.git
cd finevolume

# Build optimized release APK
./gradlew assembleRelease
```

The output APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
