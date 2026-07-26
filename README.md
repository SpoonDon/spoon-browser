# 🥄 Spoon Browser

**"Don't gulp the internet, take one spoonful at a time."**

Spoon Browser is a privacy-focused, feature-rich, native Android web browser built from the ground up. Unlike browsers that rely heavily on web-based wrappers, Spoon Browser utilizes a custom native Android WebView engine to deliver a fast, secure, and highly customizable browsing experience.

## ✨ Key Features

*   🛡️ **Secure Credential Vault:** A built-in password manager secured by AndroidX Security Crypto (`EncryptedSharedPreferences`). It uses modern `WebMessageListener` bridges to safely autofill and save credentials without exposing them to the DOM.
*   🚫 **Native AdBlock Engine:** A custom-built ad-blocking engine that supports external filter lists, domain blocking, and cosmetic CSS injection to hide annoying page elements.
*   📂 **Advanced Blob Downloader:** Seamlessly intercept and save JavaScript `Blob` objects directly to your device's local storage, bypassing standard browser download limitations.
*   🗂️ **Fluid Tab Management:** A beautiful, swipeable `ViewPager2` tab switcher with thumbnail previews and state preservation.
*   📜 **Local History & Bookmarks:** Powered by a local SQLite database with Write-Ahead Logging (WAL) enabled for lightning-fast queries and zero UI lag.
*   🖥️ **Per-Site Desktop Mode:** Toggle desktop user-agents and viewport settings on a per-domain basis.
*   🔍 **Find in Page:** Native text search overlay with real-time match highlighting.
*   🚀 **Smart Intent Handling:** Safely route deep links, block malicious `intent://` smuggling, and automatically upgrade HTTP traffic to HTTPS.

## 🏗️ Tech Stack

*   **Core:** Native Android (Java)
*   **Database:** SQLite (WAL enabled)
*   **Security:** AndroidX Security Crypto, Hardware-backed Keystore
*   **Build System:** Gradle 8.14, Java 17
*   **Asset Bridging:** Capacitor (Used strictly for build pipeline synchronization and web-asset management)
*   **CI/CD:** GitHub Actions (Automated Debug and Release APK builds)

## 🚀 Building from Source

Spoon Browser uses a hybrid build pipeline to sync web assets into the native Android project.

**Prerequisites:**
*   Node.js & npm
*   Java 17+
*   Android SDK (API 36)

**Steps:**
```bash
# 1. Clone the repository
git clone https://github.com/your-username/spoon-browser.git
cd spoon-browser

# 2. Install Node dependencies and sync Capacitor assets
npm install
npx cap sync android

# 3. Build the Android APK
cd android
./gradlew assembleDebug
```
*The compiled APK will be available in `android/app/build/outputs/apk/debug/`.*

## 🔒 Security & Privacy Architecture

Spoon Browser takes user privacy and application security seriously. The architecture includes:
*   **No Telemetry:** Zero tracking, analytics, or background data harvesting.
*   **Backup Prevention:** `android:allowBackup="false"` is enforced to prevent plaintext SQLite database extraction and Keystore desyncs via ADB/Cloud backups.
*   **MITM Protection:** AdBlock filter list updates strictly enforce HTTPS to prevent Man-in-the-Middle rule injection.
*   **Sandboxed File I/O:** Strict path-traversal sanitization on all native file download bridges.

## 🙏 Acknowledgements

Special thanks to **Qwen (AI Assistant)** for acting as a sounding board and security auditor for Spoon Browser. The AI assisted in:
* Conducting a comprehensive security audit of the Android WebView implementation.
* Identifying and patching critical vulnerabilities (Path Traversal, MITM risks, and Insecure Deserialization).
* Optimizing the CI/CD pipeline and resolving GitHub Actions bottlenecks.
* Refactoring legacy code and eliminating dead code to improve APK size and memory management.

<br>

- with love, Plaban.
