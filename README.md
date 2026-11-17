# Sam’s OpenReader

An offline reader for DRM-free EPUB, TXT and PDF books, designed for the North American Samsung Galaxy A56. The Kotlin rebuild uses Compose, Readium Kotlin, Room, DataStore and Android’s PDF renderer. It installs on Android 10 (API 29) and later. The primary certification target is Android 16 / One UI 8.

Import a file from the system document picker, Open with, or Share. Choose **Open once** or **Add to library**. A library entry owns its original file. Tap the center of a page for **Contents**, **Aa**, and **Listen**. The top menu includes TXT chapter detection, bulk cleanup, and export. Listening requires Samsung TTS as the system default and an installed offline voice.

## Build and verification

Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0, and Python 3. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` (Windows: `gradlew.bat`). On a connected Android device or emulator, run `./gradlew :app:connectedDebugAndroidTest`. The build fetches the licensed reading faces and verifies their checksums against [the font manifest](tools/fonts.lock.json). Fonts and their upstream license notices are packaged in the APK. Runtime reading requires no font downloads.

The [software design document](docs/SDD.md) defines the full release and its acceptance gates. [Verification status](docs/VERIFICATION.md) records outstanding behavior and hardware coverage. The [HTML design preview](docs/preview.html) is an earlier interactive mockup.

## License and support

OpenReader is a free-of-charge personal project under the [MIT License](LICENSE), provided without a service-level agreement, guaranteed response time, or promise of indefinite maintenance. Third-party fonts and dependencies retain their respective licenses.
