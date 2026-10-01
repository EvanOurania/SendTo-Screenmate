# SendTo ScreenMate 🚗📍

Share a place, a route or a link from your phone, and it opens by itself on another Android screen: your car's head unit, a tablet, a second phone.

## 📥 Download
Download both APKs from the **[Releases page](https://github.com/EvanOurania/SendTo-ScreenMate/releases/latest)**:
- **SendTo ScreenMate** (the Sender): install it on your phone.
- **ScreenMate Receiver**: install it on the screen that should open the links.

Both need Android 7.0 or newer. To navigate, the Receiver device needs Waze and/or Google Maps.

> [!NOTE]
> **Upgrading from v1.1.116 or earlier:** the apps' package names changed, so the new versions install as separate apps. Uninstall the old Sender and Receiver, install the new ones and pair them again (see **Setup** below).

> [!IMPORTANT]
> **AI-Generated Project:** This entire project, including the logic for background persistence, QR configuration, and UI, was designed and developed by AI.

---

## 🔄 How it works
```
Phone                                                        Screen
SendTo ScreenMate ──(encrypted)──▶ ntfy server ──▶ ScreenMate Receiver ──▶ Waze / Google Maps / browser
        ▲                                                   │
        └──────────────────── "received ✓" ─────────────────┘
```
The two apps talk through [ntfy](https://ntfy.sh), a free push notification service: no account and no server of your own needed. The Receiver keeps a connection open in the background and reacts as soon as something arrives.

---

## ✨ Features

**Sender (phone)**
- **Share from any app** with Android's *Share* menu: places and routes from Google Maps, web pages, text.
- **Type or paste** a link, some text or an address and send it from the app.
- **Delivery confirmation:** "✓ Received by the Receiver", or a warning if the Receiver is off or offline.
- **End-to-end encryption** of everything you send.

**Receiver (screen)**
- **Opens places and routes by itself** in Waze or Google Maps, after a short countdown you can cancel. Web links open in the browser.
- **Routes:** Waze navigates to the route's destination.
- **Nothing gets lost:** links sent while the screen was off arrive as soon as it's back online. Only the latest one opens by itself, and only if it's less than 30 minutes old; the others go to the history.
- **History** of everything received, with place names, to reopen it with a tap.
- **Notification** with *Restart* and *Reopen last link* buttons.
- **Auto-copy** of what arrives to the clipboard.
- **Always listening:** reconnects by itself when the network comes back or changes (Wi-Fi ↔ mobile data), and starts when the device boots or the app is updated.

---

## 🛠️ Setup
1. **Install** the Sender on your phone and the Receiver on the screen.
2. **On the Receiver:**
   - If the **"Enable Overlay"** and **"Disable Battery Optimization"** buttons appear, tap them and grant the permissions. They let the app open links from the background and keep it listening.
   - Tap **"Generate Topic and Key"**. The listener starts by itself (green "Service is running") and the pairing QR code appears.
3. **On your phone:** open SendTo ScreenMate, go to **Sending Settings**, tap **"Scan Receiver QR"** and point the camera at the QR code. Server, topic and encryption key are filled in automatically.
4. **Share!** In Google Maps pick a place or a route, tap **Share** and choose **SendTo ScreenMate**.

To pair more phones later, tap **"Show QR code for the Sender"** on the Receiver and scan it from each phone.

---

## ⚙️ Receiver settings
| Setting | Default | What it does |
|---|---|---|
| Auto-open (Google Maps links / addresses and coordinates) | Waze | App used to open locations: Waze, Google Maps, the default app, or ask every time |
| Delay | 5 seconds | Countdown before opening. With 0 the app opens immediately, without the chooser (this keeps split-screen layouts intact) |
| Auto-copy to clipboard | On | Copies every received link or text |
| Accept only encrypted messages | Off | Ignores links sent with encryption turned off in the Sender |
| Notification buttons | Restart, Reopen | *Stop* can be added too |

---

## 🔒 Privacy & security
- **End-to-end encryption (AES-256-GCM):** the key is generated on the Receiver and reaches the phone only through the QR code. The ntfy server only ever sees encrypted data.
- **ntfy.sh is a public server:** whoever knows a topic name can read its (encrypted) messages and publish to it. That's why the Receiver generates a random topic, and why you can turn on *Accept only encrypted messages*.
- **Encryption can be turned off** in the Sender: links then travel in plain text, readable by anyone who knows the topic.
- **Delivery confirmations** contain only the id of the received message, nothing about its content.
- **Self-hosting:** you can run your own [ntfy server](https://docs.ntfy.sh/install/) and enter its address in both apps.
- **MacroDroid mode:** the Sender can also call a MacroDroid webhook instead of ntfy. In that mode there is no encryption and no delivery confirmation.
- No accounts, no registration.

---

## 🧰 Troubleshooting
- **"Sent, but the Receiver didn't confirm":** the Receiver is off, offline or its service is stopped. It will get the link when it's back online.
- **Links arrive but don't open by themselves:** on the Receiver, grant *Display over other apps* ("Enable Overlay").
- **The Receiver stops listening after a while:** disable battery optimization for it. Some manufacturers add their own background limits: see [dontkillmyapp.com](https://dontkillmyapp.com).
- **"Invalid topic" or "No encryption key" on the phone, or "Message ignored: wrong encryption key" on the Receiver:** scan the Receiver's QR code again.

---

## 👩‍💻 For developers
- **Modules:** `:sender` and `:receiver`, two separate Android apps.
- **Stack:** Kotlin, Jetpack Compose (Material 3), OkHttp, DataStore, ZXing. Min SDK 24, target SDK 37.
- **Protocol:**
  - The Sender publishes `{"url": ..., "title": ...}` to `https://<server>/<topic>`, encrypted with AES-256-GCM (Base64 of IV + ciphertext) when encryption is on.
  - The Receiver streams `https://<server>/<topic>/json` and resumes after a disconnection with `since=<last message id>`.
  - Delivery confirmations: the Receiver publishes the id of each received message to `<topic>_ack`. The Sender subscribes there *before* publishing, because ntfy.sh stores messages a moment after receiving them.
- **Build:** `./gradlew assembleDebug`
- **Tests and lint:** `./gradlew testDebugUnitTest lintDebug`

### 🚢 Releasing
GitHub Actions (`.github/workflows/build.yml`) runs the unit tests and lint on every push to `main` and every pull request. Pushing a `v*` tag also builds both APKs, signs them and publishes them as a GitHub release.

**Signing:** the workflow reads the keystore from these repository secrets:
- `SIGNING_KEYSTORE_BASE64`: the keystore file, Base64-encoded
- `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`

To sign local builds with the same key, so they install over the released APKs, put the same values in `~/.gradle/gradle.properties`, with `SIGNING_KEYSTORE_PATH` (the keystore's path) instead of `SIGNING_KEYSTORE_BASE64`.

**For each release:**
1. Bump `versionCode` and `versionName` in both `sender/build.gradle.kts` and `receiver/build.gradle.kts` (the two apps share the same version), then commit.
2. Push a tag named `v` + `versionName`: `git tag v1.2.2 && git push origin main v1.2.2`. The tag names the release and its APKs; nothing checks it against `versionName`.

---
*Created by AI*
