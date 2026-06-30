# Spoon Browser 

A lightweight, high-performance, and privacy-first web browser for Android. Engineered to deliver a smooth desktop/mobile browsing experience while maintaining strict protection against cross-site tracking.

---

## ⚡ Key Features

*   🖥️ **On-Demand Desktop Mode** – Instantly switch viewport scaling, zoom rules, and User-Agent signatures to view full desktop layouts.
*   🛡️ **Smart Anti-Tracking** – Blocks cross-site cookies by default while seamlessly processing complex authentication handshakes (like Cloudflare and XenForo) using clean browser signatures.
*   🚀 **Hardware Acceleration** – Offloads page animations and layouts directly to the GPU, removing scroll micro-stutters.
*   🔋 **Thermal Optimization** – Disables heavy legacy drawing caches to dramatically reduce CPU overhead, RAM consumption, and battery heat.
*   🎯 **Intelligent Address Bar** – Smart focus management targets the URL on the first tap for instant clearing, while allowing precise cursor editing on a second tap.

---

## ⚙️ Core Technical Specifications

| System Layer | Implementation Strategy | Impact |
| :--- | :--- | :--- |
| **Graphics Engine** | `LAYER_TYPE_HARDWARE` | Zero-lag scrolling and rendering |
| **Storage Model** | Custom Partitioned Contexts | Retains logins securely across app lifecycles |
| **Memory Blueprint** | Disabled Drawing Cache | Lower device operating temperatures |
| **Interface Input** | Asynchronous Focus Tracker | Frictionless URL typing and navigation |

---

## 🏗️ Building From Source

### Requirements
* Android SDK 21+
* Gradle 8.0+

```bash
# Clone the repository
git clone [https://github.com/spoondon/spoon-browser.git](https://github.com/spoondon/spoon-browser.git)

# Navigate to the project folder
cd spoon-browser/android

# Build the production APK
./gradlew assembleRelease

—with love, Plaban.
