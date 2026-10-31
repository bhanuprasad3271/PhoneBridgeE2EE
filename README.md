# PhoneBridgeE2EE

PhoneBridge contains Android Sender and Receiver apps. Build your own copy and connect it to your own Firebase project; this repository does not provide an official APK or Firebase service.

## Build your own copy

### 1. Install Android Studio

Install Android Studio and Android SDK Platform 36. Open the cloned `PhoneBridgeE2EE` folder (the one containing `settings.gradle.kts`). Android Studio will use the included Gradle Wrapper to download the project's required Gradle version. Set the Gradle JDK to Java 17 or newer.

### 2. Create and configure your Firebase project

Create a Firebase project in your Google account. In that project, register two Android apps with these package names:

- Sender: `com.phonebridge.collector`
- Receiver: `com.phonebridge.receiver`

This repository contains safe templates named `google-services.json.example`; they contain placeholders only and cannot connect to Firebase as-is. For each Android app, download its own real `google-services.json` from your Firebase project and put it in the matching module:

- Sender: `collector/google-services.json`
- Receiver: `receiver/google-services.json`

You can copy the matching `.json.example` file as a starting point, but replace its entire contents with the real file downloaded from Firebase. Keep the real files local: Git ignores both paths, so do not force-add or commit them. Never put Firebase Admin SDK/service-account credentials, signing keys, passwords, or private keys in this repository.

Enable Anonymous sign-in and create a Cloud Firestore database. Deploy the included Firestore rules to your own project with Firebase CLI:

```powershell
firebase login
firebase deploy --only firestore:rules --project YOUR_FIREBASE_PROJECT_ID
```

Replace `YOUR_FIREBASE_PROJECT_ID` with your Firebase project ID. Check the deployed rules in the Firebase Console before using real data. The app's security review describes important limitations and what has not been verified: [SECURITY.md](SECURITY.md).

### 3. Build the APKs

In Android Studio, open **Build > Select Build Variant**. Choose `debug` for `receiver` and choose either `fullDebug` or `liteDebug` for `collector`. Then build those variants.

Or, on Windows, open Android Studio's Terminal at the project root and run:

```powershell
.\\gradlew.bat :receiver:assembleDebug :collector:assembleFullDebug :collector:assembleLiteDebug
```

On macOS or Linux, run:

```sh
./gradlew :receiver:assembleDebug :collector:assembleFullDebug :collector:assembleLiteDebug
```

The APKs are created at:

- Receiver: `receiver/build/outputs/apk/debug/receiver-debug.apk`
- Sender Full: `collector/build/outputs/apk/full/debug/collector-full-debug.apk`
- Sender Lite: `collector/build/outputs/apk/lite/debug/collector-lite-debug.apk`

Install the Receiver APK and one Sender edition on your devices, then pair them.

## Updates and signing

Android Studio's debug builds use the debug signing key on your computer. Keep that key at `%USERPROFILE%\\.android\\debug.keystore` on Windows (or `~/.android/debug.keystore` on macOS/Linux) and back it up securely if you need to keep installing updates over the same app. If the key is lost or replaced, Android will not install the new APK over the old one; uninstalling first can erase app data.

Debug APKs are for personal testing. Never commit or share a private signing key. A separately managed, persistent key is required for a release build that should update existing installations.

## Security

Read [SECURITY.md](SECURITY.md) before using the app with sensitive data. Public source code does not protect a Firebase database by itself: each project owner must configure and verify their own Firebase Authentication and Firestore Security Rules.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
