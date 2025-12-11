<p align="center">
  <h1 align="center">🕶️ FrameFlow</h1>
  <p align="center">
    <strong>Stream from your Ray-Ban Meta glasses to any platform!</strong>
  </p>
  <p align="center">
    <a href="#-supported-platforms">Platforms</a> •
    <a href="#-quick-start">Quick Start</a> •
    <a href="#-features">Features</a> •
    <a href="#-architecture">Architecture</a>
  </p>
</p>

---

<p align="center">
  <img src="https://img.shields.io/badge/Android-12%2B-green?style=for-the-badge&logo=android" alt="Android 12+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.1-purple?style=for-the-badge&logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material3-blue?style=for-the-badge" alt="Compose"/>
  <img src="https://img.shields.io/badge/Meta%20DAT%20SDK-0.2.1-orange?style=for-the-badge" alt="Meta DAT"/>
</p>

---

## 📺 Supported Platforms

<table>
<tr>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-Twitch-9146FF?style=for-the-badge&logo=twitch&logoColor=white" alt="Twitch"/><br/>
<sub><b>Twitch</b></sub>
</td>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-YouTube-FF0000?style=for-the-badge&logo=youtube&logoColor=white" alt="YouTube"/><br/>
<sub><b>YouTube Live</b></sub>
</td>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-Kick-53FC18?style=for-the-badge&logo=kick&logoColor=black" alt="Kick"/><br/>
<sub><b>Kick</b></sub>
</td>
</tr>
<tr>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-Facebook-1877F2?style=for-the-badge&logo=facebook&logoColor=white" alt="Facebook"/><br/>
<sub><b>Facebook Live</b></sub>
</td>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-TikTok-000000?style=for-the-badge&logo=tiktok&logoColor=white" alt="TikTok"/><br/>
<sub><b>TikTok Live</b></sub>
</td>
<td align="center" width="150">
<img src="https://img.shields.io/badge/-Custom-gray?style=for-the-badge&logo=settings&logoColor=white" alt="Custom"/><br/>
<sub><b>Custom RTMP</b></sub>
</td>
</tr>
</table>

---

## ✨ Features

- 🕶️ **Ray-Ban Meta Integration** - Direct video stream from your smart glasses
- 🎤 **Audio Streaming** - Capture audio from glasses' 5-microphone array via Bluetooth
- 📹 **Local Recording** - Save videos locally while streaming or record-only mode
- 📡 **Multi-Platform Streaming** - One app for all major platforms
- 🎨 **Modern UI** - Material 3 design with Jetpack Compose
- ⚙️ **Custom RTMP** - Use your own streaming server
- 🔒 **Secure** - Stream keys stored locally on device
- 📱 **Live Preview** - See what you're streaming in real-time
- 📝 **Video Metadata** - Automatic Ray-Ban glasses EXIF data in recordings

---

## 🎬 Recording Modes

| Mode | Description | Use Case |
|:----:|-------------|----------|
| **A** | Stream Only | Live broadcast without local save |
| **B** | Stream + Record | Save a copy while streaming |
| **C** | Record Only | High quality (4Mbps) local recording, no network |

Recordings are saved to `Movies/FrameFlow/` with metadata files (`.xmp` and `_metadata.json`).

---

## 🚀 Quick Start

### Prerequisites

| Requirement | Details |
|-------------|---------|
| 📱 **Phone** | Android 12+ (API 31) |
| 🕶️ **Glasses** | Ray-Ban Meta (Gen 1 or 2) |
| 💻 **IDE** | Android Studio Hedgehog+ |
| 📦 **Apps** | Meta AI app (paired with glasses) |

### 1️⃣ Enable Developer Mode on Glasses

```
Meta AI App → Settings → App Info → Tap version 5x → Enable Developer Mode
```

### 2️⃣ Get GitHub Token

1. Go to [GitHub Settings → Tokens](https://github.com/settings/tokens)
2. Generate token with `read:packages` scope
3. Add to `local.properties` (in project root):

```properties
github_token=YOUR_GITHUB_TOKEN_HERE
```

### 3️⃣ Build & Run

```bash
# Clone the repo
git clone https://github.com/KilimcininKorOglu/FrameFlow.git
cd FrameFlow

# Build
./gradlew assembleDebug

# Or open in Android Studio and run
```

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        📱 FrameFlow App                               │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────────┐            │
│  │   🕶️        │    │     📺       │    │      📡       │            │
│  │  Glasses    │───▶│   Preview    │───▶│    Stream     │──▶ Platform│
│  │  Manager    │    │   Display    │    │    Manager    │            │
│  └─────────────┘    └──────────────┘    └───────┬───────┘            │
│         │                                        │                    │
│         │           Meta DAT SDK                 ▼                    │
│         ▼                                 ┌───────────────┐           │
│  ┌─────────────┐                          │   📹 Local    │           │
│  │ Ray-Ban     │                          │   Recording   │──▶ MP4   │
│  │ Meta        │                          │   Manager     │           │
│  │ Glasses     │                          └───────────────┘           │
│  └─────────────┘                                 ▲                    │
│         │                                        │                    │
│         │ Bluetooth SCO              ┌───────────┴───────┐           │
│         ▼                            │                   │           │
│  ┌─────────────┐                     │    🎤 Audio      │           │
│  │  5-Mic      │────────────────────▶│    Manager       │           │
│  │  Array      │                     │   (Bluetooth)    │           │
│  └─────────────┘                     └───────────────────┘           │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

### 📁 Project Structure

```
com.keremgok.frameflow/
│
├── 📂 data/
│   ├── PreferencesManager.kt     # 💾 Config storage (DataStore)
│   └── StreamingPlatform.kt      # 📺 Platform definitions
│
├── 📂 streaming/
│   ├── GlassesStreamManager.kt   # 🕶️ Meta DAT SDK integration
│   ├── RtmpStreamManager.kt      # 📡 H.264/AAC encoding & RTMP
│   ├── BluetoothAudioManager.kt  # 🎤 Glasses mic via Bluetooth SCO
│   └── LocalRecordingManager.kt  # 📹 MP4 recording with metadata
│
├── 📂 ui/
│   ├── SetupScreen.kt            # ⚙️ Platform & key setup
│   ├── StreamingScreen.kt        # 🎬 Live preview & controls
│   └── theme/Theme.kt            # 🎨 Material3 theming
│
├── 📂 util/
│   └── NetworkMonitor.kt         # 🌐 Connectivity monitoring
│
├── MainActivity.kt               # 🚀 Entry point
└── FrameFlowApplication.kt       # 📱 App class
```

---

## 🔧 RTMP Server URLs

| Platform | URL | Stream Key Location |
|----------|-----|---------------------|
| **Twitch** | `rtmp://live.twitch.tv/app` | [Dashboard](https://dashboard.twitch.tv/settings/stream) |
| **YouTube** | `rtmp://a.rtmp.youtube.com/live2` | [Studio](https://studio.youtube.com) |
| **Kick** | `rtmp://fa723fc1b171.global-contribute.live-video.net/app` | [Dashboard](https://kick.com/dashboard) |
| **Facebook** | `rtmps://live-api-s.facebook.com:443/rtmp` | [Live Producer](https://www.facebook.com/live/producer) |
| **TikTok** | `rtmp://rtmp-push.tiktok.com/live` | [Studio](https://www.tiktok.com/studio) |

---

## 📦 Dependencies

| Library | Version | Purpose |
|---------|:-------:|---------|
| Meta DAT Core | `0.2.1` | 🕶️ Glasses SDK |
| Meta DAT Camera | `0.2.1` | 📹 Video streaming |
| RootEncoder | `2.5.3` | 📡 RTMP protocol |
| Jetpack Compose | `BOM` | 🎨 Modern UI |
| DataStore | `1.1.1` | 💾 Preferences |

---

## 📝 Video Metadata

Recorded videos include Ray-Ban Meta glasses metadata:

| Field | Value |
|-------|-------|
| Make | Meta AI |
| Model | Ray-Ban Meta Smart Glasses |
| Focal Length | 4.7 mm |
| F-Number | f/2.2 |
| ISO | 200 |
| Exposure | 1/30s |

Each recording generates:
- `FrameFlow_YYYYMMDD_HHmmss.mp4` - Video file
- `FrameFlow_YYYYMMDD_HHmmss.xmp` - XMP sidecar (EXIF-compatible)
- `FrameFlow_YYYYMMDD_HHmmss_metadata.json` - JSON metadata

---

## 📸 Screenshots

<p align="center">
  <i>Coming soon...</i>
</p>

---

## 🤝 Contributing

Contributions are welcome! Feel free to open issues or submit PRs.

---

## 📄 License

```
MIT License

Copyright (c) 2025 Kerem Gök
```

---

<p align="center">
  Made with ❤️ for the streaming community
</p>
