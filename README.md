# SendTo ScreenMate & Receiver 🚀

A lightweight, seamless solution to share Google Maps locations and web links between Android devices instantly. 

This project consists of two separate applications: **Sender** (SendTo ScreenMate) and **Receiver**. They work together to bridge the gap between your primary phone and a secondary screen (like a tablet, infotainment system, or a fixed secondary device).

> [!IMPORTANT]
> **AI-Generated Project:** This entire project, including the logic for background persistence, QR configuration, and UI, was designed and developed by AI.

---

## 🏗️ The Apps

### 1. SendTo ScreenMate (Sender)
Install this on your primary phone. It allows you to "Share" any text, URL, or Google Maps location directly to your secondary device.
- **Features:** QR Code scanner for instant pairing, support for `ntfy.sh` (default) and `MacroDroid` webhooks, and automatic URL extraction.
- **Compatibility:** Fully compliant with **Android 15 (16KB page size)** requirements.

### 2. Receiver
Install this on the device that should open the links (e.g., your car's tablet).
- **Features:** Generates a secure random Topic, displays a configuration QR Code, and runs a persistent background service to open incoming links instantly.
- **Reliability:** Built with `Foreground Service`, `WakeLock`, and custom permission management to ensure it stays active even when the screen is off or the app is minimized.

---

## 🛠️ Setup Instructions

### Step 1: Configure the Receiver
1. Open the **Receiver** app on your secondary device.
2. Click **"Generate Random Topic"** to create a unique, secure connection ID.
3. **Grant Permissions (Critical):**
   - Click **"Enable Overlay"** and allow "Display over other apps". This allows the app to jump from the background to open Waze or Maps.
   - Click **"Disable Battery Optimization"** to prevent Android from killing the background listener.
4. Click **"Start"**. You will see a green status dot saying "Service is running".

### Step 2: Configure the Sender
1. Open **SendTo ScreenMate** on your primary phone.
2. Tap **"Scan Receiver QR"** and point your camera at the QR code displayed on the Receiver device.
3. The server and topic will be filled automatically.

### Step 3: Start Sharing!
- Open **Google Maps** on your phone, pick a place, tap **"Share"**, and select **SendTo ScreenMate**.
- The location will instantly pop up on your Receiver device.

---

## 💡 Pro Tip: GeoShare Integration
For the ultimate experience, we highly recommend using **[GeoShare](https://github.com/jakubvalenta/geoshare)** on your primary phone.
GeoShare is excellent at converting complex map links into clean coordinates. You can share from Maps to GeoShare, and then from GeoShare to **SendTo ScreenMate** for 100% reliability with any navigation app.

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
