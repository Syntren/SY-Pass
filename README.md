# SY-Pass

**SY-Pass** is an open-source, client-side Minecraft mod for **Fabric, Quilt, and NeoForge (1.21 – 1.21.1)** designed to simplify and secure your in-game server authentication. It acts as an integrated password manager featuring robust AES-256-GCM local credential storage, automated server login, smart chat scanning, and optional cloud synchronization via the official **Bitwarden CLI**.

Starting with version **2.1.0**, SY-Pass features enhanced **Memory Sanitation (`char[]`)**, **Reflection-Proof Storage Protection**, optional **Master Password / PIN Lock (PBKDF2 + AES-GCM)**, **Universal CSV Import (KeePass, 1Password, Generic)**, and **Configurable Registration Templates & Custom Login Prompts**.

---

## 🚀 Features

- **⚡ Multi-Loader Support:** Fully compatible with both **Fabric** (and Quilt) and **NeoForge** on Minecraft 1.21.1 with shared common code.
- **🎨 Zero External UI Dependencies:** Built entirely with modern vanilla Minecraft GUI (`Screen`, `ContainerObjectSelectionList`, `EditBox`, `Button`, `Toast`), ensuring maximum performance, zero mod conflicts, and lightning-fast loading times.
- **🔒 Memory Sanitation & Reflection Protection:** Passwords are stored and processed in memory as `char[]` rather than immutable `String` objects, with sensitive buffers explicitly zeroed out (`Arrays.fill(arr, '\0')`) immediately after clipboard copies, command dispatch, or vault serialization. Public access via `PasswordManager.getAllData()` returns sanitized metadata with empty passwords, shielding your credentials from reflection and rogue mods.
- **🔐 Optional Master Password / PIN:** Protect local credentials with an optional Master Password or PIN (disabled by default). Uses envelope encryption with **PBKDF2WithHmacSHA256 (100,000 iterations)** and **AES-GCM**, locking the vault on startup until unlocked. Includes emergency reset and automatic restore via Bitwarden.
- **🔑 Hardened Local Storage:** Store, reveal, copy, edit, and manage credentials for each Minecraft server. All data is securely encrypted using **AES-256-GCM** in `config/sypass/sypass.json` with strict owner-only file permissions (`0600`), atomic writes to eliminate file truncation risks, and seamless automatic migration from legacy vaults.
- **🖼️ Real Server Icons & Favicons:** Displays real server icons (`server.png` / favicon) from your multiplayer list (`servers.dat`) or active server connection directly beside each entry. Includes GPU texture caching and zero-overhead memory management.
- **🛡️ Chat Password Leak Protection:** Client-side protection that intercepts outgoing chat messages before they leave your client. If you accidentally hit Enter on a message containing a saved password, sending is cancelled locally with an on-screen warning. Powered by the **Aho-Corasick** algorithm for instant $O(N)$ scanning with selectable scope: **Current Server Only** (default) or **All Servers (Global)**.
- **⭐ Favorites, Sorting & Fast Filters:** Pin favorite servers with a star (★), filter to favorites in 1 click, and cycle sorting modes: **Favorites First** (with dedicated sections), **Alphabetical (A-Z)**, or **Last Used (🕒)**.
- **🎯 Active Account Highlighting:** The account matching your currently active Minecraft username is highlighted in bright green with an ambient card outline and informative tooltip.
- **🔒 Process Argument Isolation (Zero-Leakage):** Communicates with Bitwarden CLI strictly via standard input (`stdin`) rather than command-line arguments. Passwords, session tokens, and vault payloads are completely hidden from system process monitors (`ps`, Process Explorer) and audit logs.
- **⚡ Smart & Automated Server Login:** Automatically logs into servers upon connection (can be fully toggled on/off in Settings). Includes **Smart Auto-Login** that scans server chat and action bar prompts (`/login`, `/l`, custom regex patterns) to authenticate dynamically without unnecessary delays, spoofing protection against player chat triggers, session timeout guard on licensed servers, and a dedicated **Quick Re-Login hotkey (`K`)** for on-demand authentication.
- **✨ Configurable Registration Templates:** Support for custom registration commands (e.g., `/register %password% %password%`, `/reg %password%`). Generates a cryptographically secure password, copies it to the clipboard, saves it locally, syncs to Bitwarden, and submits the configured command. Includes built-in **Overwrite Protection**.
- **🌐 Smart Server Address Matching:** Automatically normalizes server IP addresses and hostnames, seamlessly matching entries regardless of default ports (`:25565`) or case variations.
- **☁️ Optional Bitwarden Cloud Sync:** Full two-way synchronization (Pull, Push, Full Sync) with your Bitwarden vault via the official Bitwarden CLI. Disabled by default in Settings for a pure, local-first password management experience.
- **📥 Universal CSV Import & Export:** Export and import passwords across all major password managers with automatic header detection: **Bitwarden CSV**, **KeePass (1.x & 2.x) CSV**, **1Password CSV**, and **Generic/Browser CSVs**.
- **🛡️ Multi-Factor Authentication & Zero-Password Routing:** Native support for Authenticator App (TOTP), Email 2FA, API Key login, and direct **Session Key (`BW_SESSION`)** unlock — allowing you to unlock your vault without ever entering your master password inside the game!
- **🔒 Security & CurseForge Compliant (No Runtime Downloads):** SY-Pass never downloads external binaries or executables at runtime. It interfaces strictly with the official Bitwarden CLI installed on your system or manually placed in `config/sypass/bw`.
- **🎲 Password Generator:** Generate strong, cryptographically secure passwords (`/sypass generate [len]` or via GUI).
- **💾 Local Encrypted Backups:** Export and import password backups with optional password-based encryption (PBKDF2) or portable unencrypted backups with restricted local filesystem permissions.
- **💻 Client Commands:**
  - `/sypass set <password>` — Save password for the current server.
  - `/sypass setcustom <command> <password>` — Save password with a custom authentication command.
  - `/sypass register [length]` (or `/sypass quickreg`) — Quick-register on the current server with an auto-generated password.
  - `/sypass remove` — Remove saved password for the current server.
  - `/sypass generate [length]` — Generate a strong password and copy to clipboard.
- **🎨 ModMenu & NeoForge Mods Screen Integration:** Integrates into ModMenu on Fabric and the native Mods Config Screen on NeoForge. Default hotkeys: **`[`** (open GUI), **`K`** (quick login), and an optional customizable keybinding for **Quick Register**.
- **🌐 Multilingual & Clean Assets:** 100% synchronized, audited localization for Ukrainian (`uk_ua`), English (`en_us`), and Russian (`ru_ru`) with all unused keys purged.

---

## 🛠️ Requirements

- **Minecraft:** 1.21.1
- **Java:** 21+
- **Loader:**
  - **Fabric:** Fabric Loader (>= 0.16.0) + Fabric API
  - **NeoForge:** NeoForge (>= 21.1.0)
- *(Optional)* **Bitwarden CLI:** Only required if Bitwarden sync is enabled in settings. Can be installed on your system (`winget install Bitwarden.CLI`, `brew install bitwarden-cli`, or `npm install -g @bitwarden/cli`), or placed manually into `config/sypass/bw` (`bw.exe` on Windows).

---

## 📦 Building from Source

To compile both the Fabric and NeoForge versions, run:

```bash
./gradlew build
```

The compiled mod JAR files will be placed in:
```
fabric/build/libs/sypass-fabric-2.1.0-mc1.21-1.21.1.jar
neoforge/build/libs/sypass-neoforge-2.1.0-mc1.21-1.21.1.jar
```

---

## ⚖️ Disclaimer & Notice

This project is an unofficial open-source Minecraft mod and is **not affiliated with, endorsed by, or sponsored by Bitwarden Inc.** "Bitwarden" and associated trademarks belong to Bitwarden Inc. SY-Pass interacts with Bitwarden strictly as a client utility by orchestrating the official open-source Bitwarden CLI tool.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
