# Spoon Browser

A lightweight, high-performance, and privacy-first web browser by **Plaban** for Android. Engineered to deliver a smooth mobile browsing experience while maintaining strict protection against cross-site tracking.

## Features

- 🔒 **Privacy First**: Blocks cross-site trackers and third-party cookies by default
- 🚫 **Built-in Ad Blocking**: Advanced ad-blocking engine with customizable filters
- 🔐 **Secure Credential Storage**: Encrypted password management using Android Keystore
- 📑 **Tab Management**: Efficient multi-tab browsing with visual tab switcher
- ⚡ **High Performance**: Optimized rendering and resource management
- 🎨 **Modern UI**: Material Design 3 interface with smooth animations
- 🌙 **Dark Mode**: Automatic theme switching based on system preferences
- 📥 **Download Manager**: Built-in download handling with notification support
- 🛡️ **Safe Browsing**: Protection against malicious websites and phishing attempts

## Requirements

- Android 6.0 (API level 23) or higher
- Android Studio Arctic Fox (2020.3.1) or newer
- JDK 11 or higher
- Gradle 8.14.3

## Installation

### Clone the Repository

```bash
git clone https://github.com/yourusername/spoon-browser.git
cd spoon-browser
```

### Build with Android Studio

1. Open Android Studio
2. Select **File > Open** and navigate to the `android` directory
3. Wait for Gradle sync to complete
4. Select **Build > Make Project** or press `Ctrl+F9` (Windows/Linux) / `Cmd+F9` (macOS)
5. Run the app on an emulator or physical device

### Build from Command Line

```bash
cd android

# On Windows
gradlew.bat assembleDebug

# On macOS/Linux
./gradlew assembleDebug
```

The APK will be generated at: `android/app/build/outputs/apk/debug/app-debug.apk`

### Build Release Version

```bash
# Create keystore.properties file first (see Signing section)
./gradlew assembleRelease
```

## Testing

### Run All Tests

```bash
# Run the test suite script
./run_tests.sh
```

### Run Unit Tests Only

```bash
cd android
./gradlew testDebugUnitTest
```

### Run Instrumented Tests

Requires an Android device or emulator connected:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

### View Test Reports

After running tests, reports are available at:
- **Unit Tests**: `android/app/build/reports/tests/testDebugUnitTest/index.html`
- **Instrumented Tests**: `android/app/build/reports/androidTests/connectedDebugAndroidTest/index.html`

## Project Structure

```
spoon-browser/
├── android/                 # Android native project
│   ├── app/
│   │   └── src/main/java/com/spoondon/browser/
│   │       ├── MainActivity.java          # Main browser activity
│   │       ├── AdBlockEngine.java         # Ad-blocking logic
│   │       ├── SecureCredentialManager.java  # Encrypted credential storage
│   │       ├── BrowserDatabaseHelper.java # SQLite database helper
│   │       ├── TabState.java              # Tab state management
│   │       └── ...                        # Other components
│   └── build.gradle
├── www/                     # Web assets (HTML/CSS/JS)
├── capacitor.config.json    # Capacitor configuration
├── package.json             # Node.js dependencies
└── run_tests.sh            # Test runner script
```

## Signing Configuration

To build signed release builds:

1. Generate a keystore:
```bash
keytool -genkey -v -keystore spoon-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias spoon
```

2. Create `android/keystore.properties`:
```properties
storeFile=/path/to/spoon-release-key.jks
storePassword=your_store_password
keyAlias=spoon
keyPassword=your_key_password
```

Or use environment variables:
- `SIGNING_STORE_FILE`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style

- Follow [Google's Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use meaningful variable and method names
- Add comments for complex logic
- Write unit tests for new features

## Technology Stack

- **Platform**: Android (Native Java)
- **WebView**: Android System WebView / Chrome
- **Database**: SQLite (via SQLiteOpenHelper)
- **Encryption**: AndroidX Security Crypto (AES-256-GCM)
- **UI**: Material Design Components, ViewPager2, RecyclerView
- **Build Tool**: Gradle 8.14.3
- **Min SDK**: API 23 (Android 6.0)
- **Target SDK**: API 35 (Android 15)

## Privacy Policy

Spoon Browser does not:
- Collect personal data
- Track browsing history
- Share data with third parties
- Include analytics or telemetry

All browsing data is stored locally on your device.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Thanks to all contributors
- Built with ❤️ by Plaban
