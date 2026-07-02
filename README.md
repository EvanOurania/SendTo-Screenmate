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

### 🚀 Instant Sharing (Sender)
Il Sender offre due modalità di invio intelligenti per coprire ogni scenario:
- **Invia Testo/Link:** Ideale per inviare siti web, messaggi o link generici. Se il destinatario ha attiva la "Copia Automatica", il testo verrà salvato direttamente negli appunti.
- **Invia Indirizzo:** Ottimizzato per la navigazione. Converte indirizzi testuali in coordinate reali e gestisce i link di Google Maps con precisione millimetrica, assicurando che il navigatore si apra esattamente sulla destinazione.

### 📥 Smart Reception (Receiver)
- **Apertura Automatica:** Configura l'app per aprire istantaneamente i link ricevuti con il tuo navigatore preferito (Waze o Google Maps). Puoi impostare un ritardo personalizzato o l'apertura immediata.
- **Motore di Precisione:** L'app analizza i link di Google Maps estraendo le coordinate reali (`!3d/!4d`) e i dati DMS dai titoli, superando i limiti dei link web standard.
- **Ottimizzazione Waze:** Supporto nativo per il protocollo `waze://` per garantire che lo split-screen non venga interrotto e che la cronologia di Waze mostri il nome corretto del locale.

### 🛠️ Controllo e Gestione
- **Cronologia Intelligente:** Ogni link o testo ricevuto viene salvato localmente con il nome del posto estratto automaticamente, permettendoti di recuperare vecchie destinazioni in un tocco.
- **Notifica Persistente:** Un centro di controllo sempre attivo nella barra di stato che mostra l'ultima posizione ricevuta e permette di riaprirla istantaneamente senza entrare nell'app.
- **Copia Automatica Universale:** Supporto completo per Android 10+ per copiare negli appunti qualsiasi dato ricevuto (testo o link) in modo del tutto invisibile e veloce.

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
