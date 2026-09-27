# SendTo ScreenMate & Receiver 🚀

A lightweight, seamless solution to share Google Maps locations and web links between Android devices instantly. 

## 📥 Download
You can download the latest APK binaries for both apps from the **[Releases Page](https://github.com/EvanOurania/SendTo-ScreenMate/releases/latest)**.

> [!NOTE]
> **Upgrading from v1.1.116 or earlier:** the apps' package names changed, so the new versions install as separate apps. Uninstall the old Sender and Receiver, install the new ones and pair them again (step 2 and 3 below).

---

This project consists of two separate applications: **Sender** (SendTo ScreenMate) and **Receiver**. They work together to bridge the gap between your primary phone and a secondary screen (like a tablet, infotainment system, or a fixed secondary device).

> [!IMPORTANT]
> **AI-Generated Project:** This entire project, including the logic for background persistence, QR configuration, and UI, was designed and developed by AI.

---

## 🏗️ Features


- **Send Text/Link:** Perfect for text, websites, or generic URLs.
- **Send Address:** Use this option to send text addresses.
- **Auto-Open:** Automatically launch received links with your preferred navigator (Waze or Google Maps) with custom delays.
- **Smart History:** Local log of all received data with automatically extracted place names for quick one-tap re-access.
- **Persistent Notification:** Control center in your status bar to re-open the last location without entering the app.
- **Universal Auto-Copy:** Full Android 10+ support for invisible and lightning-fast clipboard sync of any data.

---

## 🛠️ Setup Instructions
### Step 1: Installation
- Install the **Sender** on your primary phone. It allows you to "Share" any text, URL, or Google Maps location directly to your secondary device.
- Install the **Receiver** on you secondary device

### Step 2: Configure the Receiver
1. Open the **Receiver** app on your secondary device.
2. **Grant Permissions (Critical):**
   - Click **"Enable Overlay"** and allow "Display over other apps". This allows the app to jump from the background to open Waze or Maps.
   - Click **"Disable Battery Optimization"** to prevent Android from killing the background listener.
3. Click **"Generate Topic and Key"** to create a unique, secure connection ID. The listener starts automatically (green status dot saying "Service is running") and the pairing QR code appears.

### Step 3: Configure the Sender
1. Open **SendTo ScreenMate** on your primary phone.
2. Tap **"Scan Receiver QR"** and point your camera at the QR code displayed on the Receiver device.
3. The server, topic and encryption key will be filled automatically.
4. Repeat these steps for every other phone you might have. To show the QR code again, tap **"Show QR code for the Sender"** on the Receiver.

### Step 4: Start Sharing!
- Open **Google Maps** on your phone, pick a place, tap **"Share"**, and select **SendTo ScreenMate**.
- The location will instantly pop up on your Receiver device.

---

## 🔒 Privacy & Security (E2EE)
- **End-to-End Encryption:** Your links are encrypted using **AES-256-GCM** on the Sender device and decrypted only on the Receiver.
- **Privacy Note:** Encryption is exclusive to the **ntfy.sh** service. MacroDroid webhooks do not support E2EE in this app.
- **No Accounts Required:** No registration, no email, no passwords.
- **Open Source:** You can host your own `ntfy` server for total control over your data.

---

## ⚙️ Technical Details (for Developers)
- **Language:** 100% Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Networking:** OkHttp (streaming/long-polling)
- **QR Engine:** ZXing (Pure Java/Kotlin implementation for 16KB alignment compatibility)
- **Architecture:** Clean MVVM with DataStore for persistent settings.

## 🚢 Releasing
Every push runs the unit tests and lint on GitHub Actions (`.github/workflows/build.yml`). Pushing a tag that starts with `v` also builds both APKs, signs them and publishes them as a GitHub release.

**One-time setup:** in the repository go to *Settings → Secrets and variables → Actions* and add these secrets. Use the same keystore as the previous releases, otherwise the new APKs can't be installed over the old ones.
- `SIGNING_KEYSTORE_BASE64`: the keystore file encoded in Base64 (on macOS: `base64 -i my-keystore.jks | pbcopy`)
- `SIGNING_STORE_PASSWORD`: the keystore password
- `SIGNING_KEY_ALIAS`: the key alias
- `SIGNING_KEY_PASSWORD`: the key password

**For each release:**
1. Increase `versionCode` and `versionName` in `receiver/build.gradle.kts` and `sender/build.gradle.kts`, then commit.
2. Tag and push: `git tag v1.2.0 && git push origin main v1.2.0`

---
*Created with ❤️ by AI for a seamless Android experience.*
