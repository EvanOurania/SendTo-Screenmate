# SendTo ScreenMate & Receiver 🚀

A lightweight, seamless solution to share Google Maps locations and web links between Android devices instantly. 

## 📥 Download
You can download the latest APK binaries for both apps from the **[Releases Page](https://github.com/EvanOurania/SendTo-ScreenMate/releases/latest)**.

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
2. Click **"Generate Random Topic"** to create a unique, secure connection ID.
3. **Grant Permissions (Critical):**
   - Click **"Enable Overlay"** and allow "Display over other apps". This allows the app to jump from the background to open Waze or Maps.
   - Click **"Disable Battery Optimization"** to prevent Android from killing the background listener.
4. Click **"Start"**. You will see a green status dot saying "Service is running".

### Step 3: Configure the Sender
1. Open **SendTo ScreenMate** on your primary phone.
2. Tap **"Scan Receiver QR"** and point your camera at the QR code displayed on the Receiver device.
3. The server and topic will be filled automatically.
4. Repeat these steps for every other phones you might have.

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

---
*Created with ❤️ by AI for a seamless Android experience.*
