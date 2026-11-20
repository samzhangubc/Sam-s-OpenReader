# Build, install, and compatibility

## Supported baseline

The current application ID is org.sam.openreader. The Android minimum is API 29 (Android 10); compile and target SDK are API 36. The intended compatibility baseline for this release is Android 16/API 36 with One UI 8, interpreted in the SDD as the previous annual generation. A Samsung Galaxy A56 5G North American edition is the primary certification device. Android 17/API 37 evidence is useful for forward compatibility but does not replace API 36 acceptance.

The project targets phones first. A tablet, foldable, split-screen, and unusual font scale should degrade gracefully, but are not implied to be certified by phone testing. Android 10 compatibility requires API-gated fallbacks; it is not established just because the APK can be installed.

## Local build prerequisites

Use JDK 17, Android SDK Platform 36, Android Build Tools 35.0.0, and Python 3. The Gradle wrapper is checked in. The build invokes tools/fonts.py to fetch the pinned open-source font assets and verify hashes from tools/fonts.lock.json before packaging them. This is a build-time network dependency. The installed app does not download fonts while reading.

On Windows, from the repository root:

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

The connected instrumentation suite can be run with:

~~~powershell
.\gradlew.bat :app:connectedDebugAndroidTest
~~~

Connected tests require a compatible physical device or configured emulator. Local debug installation is not a published release. Preserve app ID and signing continuity when preparing an upgradeable release; credentials and private signing keys must not be committed.

## Compatibility expectations

Samsung TTS is optional at install time. Listening is available only when Samsung's official engine is installed, selected as the Android default, and has an offline voice suitable for the requested language. No app feature should require network permission. PDF text speech and extraction are conditional on Android version and an accessible text layer; PDF viewing itself uses Android's PDF renderer.

### Evidence today

The verification report records a debug build, unit tests, lint, and six connected checks on an SM-A566W running Android 17/API 37. This is a forward-compatibility run. API 29 and Android 16/One UI 8 acceptance remain separate gates. The same report lists unmeasured performance budgets and incomplete walkthroughs. Consult [verification status](../VERIFICATION.md) before describing any compatibility claim as certified.

See [repository architecture](03-repository-architecture.md) for dependencies and [verification](21-known-limits-and-index.md) for the remaining test matrix.
