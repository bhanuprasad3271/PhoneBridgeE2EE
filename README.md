# PhoneBridgeE2EE

PhoneBridge is a two-part Android application for sending information from a paired Sender device to a Receiver device. It uses end-to-end encryption for synced records, with Firebase used to coordinate pairing and delivery.

This repository contains the source code for both apps. You build and configure your own copies; there are no prebuilt APKs or shared Firebase credentials provided here.

## What the apps do

- **Sender** — runs on the device that shares information after the user grants the required Android permissions and approves pairing.
- **Receiver** — runs on the device that receives and displays the information shared by its paired Sender.
- **Encrypted transfer** — records are encrypted on the Sender and decrypted by the paired Receiver. Firebase carries the encrypted data; it is not the place where the app stores the plaintext content.
- **Pairing and device control** — devices use a pairing flow and server-checked pair ownership. A Receiver can manage its paired Sender devices.
- **Local history** — the Receiver stores its history in encrypted local storage.
- **Sender editions** — the Full edition includes location-related functionality; the Lite edition omits the location dependency.

PhoneBridge does not bypass Android permissions. The Sender user must approve pairing and grant the permissions required by the features they use. End-to-end encryption does not protect a device that is already compromised or unlocked by someone else.

## Requirements

You will need:

- A computer running Windows, macOS, or Linux.
- Android Studio and Android SDK Platform 36.
- JDK 17 or newer configured as Android Studio's Gradle JDK.
- An Android phone or emulator running Android 8.0 (API 26) or newer.
- A Google account and your own Firebase project.
- Internet access on both phones for Firebase authentication, pairing, and syncing.

The project includes the Gradle Wrapper. Open the project in Android Studio and allow Gradle sync to download the required Gradle and Android dependencies.

## Set up Firebase

Use a Firebase project that you own. Do not use someone else's Firebase configuration or service-account credentials.

1. Create a Firebase project in the [Firebase Console](https://console.firebase.google.com/).
2. Register two Android apps in that project with these exact application IDs:
   - Sender: `com.phonebridge.collector`
   - Receiver: `com.phonebridge.receiver`
3. Download the `google-services.json` file for each registered app.
4. Put each file in its matching module:
   - Sender: `collector/google-services.json`
   - Receiver: `receiver/google-services.json`
5. In Firebase Authentication, enable **Anonymous** sign-in.
6. Create a Cloud Firestore database.
7. From the repository root, install the Firebase CLI if needed, sign in, and deploy the included Firestore rules to your project:

```sh
firebase login
firebase deploy --only firestore:rules --project YOUR_FIREBASE_PROJECT_ID
```

Replace `YOUR_FIREBASE_PROJECT_ID` with your own project ID. Review the rules and the Firebase Console configuration before connecting real devices. The `.example` JSON files in this repository are templates with placeholders; they are not usable Firebase credentials.

**Keep your real configuration private.** The two real `google-services.json` paths are ignored by Git. Do not force-add them, commit them, or upload them to an issue or discussion. Never put Firebase Admin SDK service-account JSON, private keys, signing keystores, or passwords in the repository. A Firebase Android client configuration is not an Admin SDK credential, but it should still be configured and restricted appropriately in your own Firebase/Google Cloud project.

## Build the APKs

1. Clone this repository and open the folder containing `settings.gradle.kts` in Android Studio.
2. Add the two Firebase configuration files as described above.
3. Let Android Studio finish Gradle sync.
4. Build the Receiver debug variant and one Sender edition.

You can build all three APK variants from the project root using the terminal.

**Windows (PowerShell):**

```powershell
.\\gradlew.bat :receiver:assembleDebug :collector:assembleFullDebug :collector:assembleLiteDebug
```

**macOS / Linux:**

```sh
./gradlew :receiver:assembleDebug :collector:assembleFullDebug :collector:assembleLiteDebug
```

The generated APKs are written here:

| App | APK path |
|---|---|
| Receiver | `receiver/build/outputs/apk/debug/receiver-debug.apk` |
| Sender Full | `collector/build/outputs/apk/full/debug/collector-full-debug.apk` |
| Sender Lite | `collector/build/outputs/apk/lite/debug/collector-lite-debug.apk` |

These are local debug builds. They are not uploaded or published by the repository's GitHub Actions checks.

## Install and connect your devices

1. Install the Receiver APK on the phone that will receive the shared information.
2. Install either the Full or Lite Sender APK on the phone that will share it.
3. Make sure both phones have internet access and that both apps were built with the matching Firebase configuration from your project.
4. Open the apps and follow the in-app pairing instructions. Complete the pairing approval on the devices and grant Android permissions only for the features you intend to use.
5. Keep both apps updated from builds signed with the same signing key when installing an update over an existing installation.

Use only devices and accounts that you own or have permission to connect. Test pairing and syncing with non-sensitive test data before relying on the setup.

## Updating your own installation

Android only installs an APK update over an existing app when the application ID and signing certificate match and the new version code is compatible. A different signing key is treated as a different app publisher.

Debug builds use the local Android debug keystore on your computer:

- Windows: `%USERPROFILE%\\.android\\debug.keystore`
- macOS / Linux: `~/.android/debug.keystore`

Keep your own debug keystore safe if you want to install future debug builds over the same installation. Another developer's debug APK will normally have a different signing key and cannot update your copy. If you uninstall the app, Android may remove its local app data.

Debug builds are intended for development and testing. For distribution to other people, create and protect your own persistent release-signing key. Do not commit or share that key or its passwords. Do not change or replace a signing key used for existing installations unless you understand the update consequences.

## Security and limitations

Read [SECURITY.md](SECURITY.md) before using PhoneBridge with sensitive information. It documents the security design, test coverage, and items that still require production verification.

In particular, passing local emulator tests does not prove that your Firebase production rules are deployed correctly or that your Firebase project is configured securely. Each project owner is responsible for reviewing Firebase Authentication, Firestore rules, IAM permissions, API restrictions, and abuse protections. The current pairing design also has documented limitations around short invitation codes and first-pair identity verification.

## Development checks

The GitHub Actions workflow runs Android tests and lint checks, plus Firebase security-rules tests against an emulator. It intentionally does not build, upload, or release APKs.

For local checks, use the Android Studio Gradle tasks and the Firebase emulator test instructions in the repository's workflow and `security-tests` directory.

## License

PhoneBridgeE2EE is released under the MIT License. See [LICENSE](LICENSE).
