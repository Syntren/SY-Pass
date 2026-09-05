# SY-Pass

**SY-Pass** is a client-side Fabric mod for Minecraft (1.21 – 1.21.1) designed to simplify and secure your in-game server authentication. It acts as an integrated password manager featuring robust AES-256-GCM local credential storage, automated server login, smart chat scanning, and cloud synchronization via the official **Bitwarden CLI**.

---

## 🚀 Features

- **🔑 Local Password Storage:** Store, reveal, copy, edit, and manage credentials for each Minecraft server. All data is securely encrypted using **AES-256-GCM** in `config/sypass/sypass.json`.
- **⚡ Smart & Automated Server Login:** Automatically logs into servers upon connection (can be fully toggled on/off in Settings). Includes **Smart Auto-Login** that scans server chat and action bar prompts (`/login`, `/l`) to authenticate dynamically without unnecessary delays, as well as a dedicated **Quick Re-Login hotkey (`K`)** for on-demand authentication.
- **✨ Quick & Smart Auto-Registration:** One-step registration on servers (`/sypass register [len]` or `/sypass quickreg`). Generates a cryptographically secure password, copies it to the clipboard, saves it locally, syncs to Bitwarden, and submits the `/register <password> <password>` command. Enable **Smart Auto-Register** in Settings to perform this automatically when registration prompts are detected!
- **🌐 Smart Server Address Matching:** Automatically normalizes server IP addresses and hostnames, seamlessly matching entries regardless of default ports (`:25565`) or case variations.
- **☁️ Bitwarden Cloud Sync:** Full two-way synchronization (Pull, Push, Full Sync) with your Bitwarden vault. Includes one-click deletion of specific cloud entries directly from the password list.
- **🛡️ Multi-Factor Authentication (2FA):** Native support for both **Authenticator App (TOTP)** and **Email 2FA** (with in-GUI "Send Code" support) as well as API Key login.
- **📥 In-Game CLI Downloader:** Automatically downloads, extracts, and configures the official Bitwarden CLI executable for your platform (Linux, Windows, macOS) with animated progress indicators.
- **🎲 Password Generator:** Generate strong, cryptographically secure passwords (`/sypass generate [len]` or via GUI).
- **💾 Local Encrypted Backups:** Export and import password backups with optional password-based encryption.
- **💻 Client Commands:**
  - `/sypass set <password>` — Save password for the current server.
  - `/sypass setcustom <command> <password>` — Save password with a custom authentication command.
  - `/sypass register [length]` (or `/sypass quickreg`) — Quick-register on the current server with an auto-generated password.
  - `/sypass remove` — Remove saved password for the current server.
  - `/sypass generate [length]` — Generate a strong password and copy to clipboard.
- **🎨 Polished UI & ModMenu:** Built with **oωo-lib** with animated status indicators, configurable in-game notifications (toasts toggle), and full **ModMenu** support. Default hotkeys: **`P`** (open GUI), **`K`** (quick login), and an optional customizable keybinding for **Quick Register**.
- **🌐 Multilingual:** Full localization for Ukrainian (`uk_ua`), English (`en_us`), and Russian (`ru_ru`).

---

## 🛠️ Requirements

- **Minecraft:** 1.21 – 1.21.1
- **Fabric Loader:** >= 0.16.0
- **Fabric API**
- **oωo-lib:** >= 0.12.15
- **Java:** 21+
- *(Optional)* **Bitwarden CLI:** Auto-downloaded by the mod on demand, or placed in `config/sypass/bw` (`bw.exe` on Windows) / system `PATH`.

---

## 📦 Building from Source

To compile the mod, run:

```bash
./gradlew build
```

The compiled mod JAR will be placed in:
```
build/libs/sypass-fabric-1.1.0-mc1.21-1.21.1.jar
```

---

## ⚖️ Disclaimer & Notice

This project is an unofficial open-source Minecraft mod and is **not affiliated with, endorsed by, or sponsored by Bitwarden Inc.** "Bitwarden" and associated trademarks belong to Bitwarden Inc. SY-Pass interacts with Bitwarden strictly as a client utility by orchestrating the official open-source Bitwarden CLI tool.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
