# SY-Pass

**SY-Pass** is an open-source, client-side password manager and authentication utility for **Fabric, Quilt, and NeoForge (Minecraft 1.21 – 1.21.1)**. It provides hardened local credential storage (**AES-256-GCM**), automated server login, chat & command password leak protection, Master Password/PIN lock with Auto-Lock, Streamer Mode, and optional cloud synchronization via the official **Bitwarden CLI** or self-hosted **Vaultwarden** instances.

Starting with **v2.3.0**, SY-Pass ships as a **single universal Fabric + NeoForge JAR** (merged via [Forgix 2.0](https://github.com/PacifistMC/Forgix)) alongside standalone loader JARs, and features a fully modularized architecture, masked Master/Backup password inputs, two-step vault reset confirmation, O(1) batch disk persistence on imports, and unified UTF-8 atomic `0600` file writes across config and vault storage.

---

## 🚀 Key Features

- **⚡ Universal Single-JAR Multi-Loader Support:** One JAR works seamlessly on **Fabric**, **Quilt**, and **NeoForge** for Minecraft 1.21 – 1.21.1, powered by Architectury Loom and Forgix.
- **🎨 Zero External UI Dependencies:** Built entirely with vanilla Minecraft GUI components (`Screen`, `ContainerObjectSelectionList`, `EditBox`, `Button`, `Toast`), ensuring zero mod conflicts and native look-and-feel.
- **🔐 Master Password / PIN Lock with Auto-Lock:** Protect your local vault with PBKDF2-HMAC-SHA256 (100,000 iterations) + AES-256-GCM envelope encryption, masked password fields, configurable **Auto-Lock Timeout** (5–60 min), instant manual lock, two-step emergency reset, and Bitwarden recovery.
- **🔒 Memory Sanitation & Reflection Protection:** Credentials are stored as mutable `char[]` buffers and zeroed (`Arrays.fill`) right after use. `PasswordManager.getAllData()` returns sanitized metadata with blanked passwords to block rogue mods and reflection inspection.
- **🎥 Streamer Mode:** Masks server hostnames/IPs, replaces custom server favicons with neutral icons, and disables in-place password reveals while streaming or recording.
- **🔑 Hardened Local Vault (AES-256-GCM):** Encrypted in `config/sypass/sypass.json` with owner-only permissions (`0600`), atomic temp-file replacement, UTF-8 encoding, and seamless migration from legacy vaults.
- **🛡️ Chat & Command Leak Protection:** Client-side Aho-Corasick ($O(N)$) outgoing interception for both public chat and private commands (`/msg`, `/tell`, `/w`), with selectable scope: **Current Server Only** (default) or **All Servers (Global)**.
- **⚡ Smart & Automated Server Authentication:**
  - **Static Delay Login** & **Smart Auto-Login** (scans system chat & action bar for `/login`, `/l`, or validated custom regex patterns with pagination).
  - **Anti-Spoofing Hardening** on both Fabric and NeoForge.
  - **Quick Re-Login Hotkey (`K`)** and configurable **Quick Register** templates (`/register %password% %password%`, `/reg %p%`, etc.) with Overwrite Protection.
- **☁️ Safe Bitwarden & Vaultwarden Cloud Sync:** Optional two-way sync (Pull, Push, Full Sync) via official Bitwarden CLI using `stdin`/environment isolation (zero credentials in CLI arguments). Supports TOTP, Email 2FA, API Key, and direct `BW_SESSION` unlock.
- **📥 Universal CSV & Encrypted JSON Backups:** Auto-detects **Bitwarden**, **KeePass 1.x/2.x**, **1Password**, and **Generic/Browser CSVs**, plus PBKDF2-encrypted JSON backups.
- **🌐 Multilingual:** 100% synchronized localization for English (`en_us`), Ukrainian (`uk_ua`), and Russian (`ru_ru`).

---

## 💻 Client Commands

| Command | Description |
| :--- | :--- |
| `/sypass set <password>` | Save or overwrite password for the current server |
| `/sypass setcustom <command> <password>` | Save password with a custom login command |
| `/sypass register [length]` *(or `/sypass quickreg`)* | Generate a strong password, save, copy, and register |
| `/sypass remove` | Delete stored credentials for the current server |
| `/sypass generate [length]` | Generate a secure password and copy to clipboard |

---

## 🎮 Controls & Integration

- **`[`** — Open SY-Pass Vault Screen *(configurable)*
- **`K`** — Quick Re-Login *(configurable)*
- **Quick Register** — Optional customizable hotkey
- **ModMenu** (Fabric/Quilt) & native **NeoForge Mods Config Screen** integration

---

## 🛠️ Requirements

- **Minecraft:** 1.21 – 1.21.1
- **Java:** 21+
- **Mod Loader:** Fabric Loader (`>= 0.16.0`) + Fabric API, **or** NeoForge (`>= 21.1.0`)
- *(Optional)* **Bitwarden CLI:** Install via `winget install Bitwarden.CLI`, `brew install bitwarden-cli`, `npm install -g @bitwarden/cli`, or place the binary manually in `config/sypass/bw/` (`bw.exe` on Windows, `bw` on Linux/macOS).

> 🔒 **CurseForge Compliant:** SY-Pass never downloads external binaries or scripts at runtime.

---

## 🏗️ Architecture

```text
common/src/main/java/com/syntren/sypass/
├── SYPassCommon.java           # Common entrypoint
├── command/SYPassCommands.java # Brigadier client command tree
├── config/SYPassConfig.java    # Thread-safe UTF-8 atomic config
├── gui/                        # Modular vanilla GUI (Screen, Tabs, LockView, List, Toast, Icons)
├── handler/AutoLoginHandler.java
├── platform/PlatformHelper.java
├── security/VaultCrypto.java   # AES-256-GCM, PBKDF2, atomic 0600 file I/O
├── storage/                    # PasswordManager, AccountData, BackupService, CsvCodec, BitwardenManager
└── util/                       # ChatLeakGuard, ChatProtectionMatcher, PasswordGenerator, ServerAddressUtil
```

---

## 📦 Building from Source

```bash
./gradlew clean test build
```

Artifacts produced:
- **Universal Merged JAR (Fabric + NeoForge):** `build/forgix/sypass-2.3.0-fabric-neoforge-mc1.21-1.21.1.jar`
- **Standalone Fabric JAR:** `fabric/build/libs/sypass-fabric-2.3.0-mc1.21-1.21.1.jar`
- **Standalone NeoForge JAR:** `neoforge/build/libs/sypass-neoforge-2.3.0-mc1.21-1.21.1.jar`

---

## ⚖️ Disclaimer & License

Independent open-source project, **not affiliated with or endorsed by Bitwarden Inc.** Licensed under the [MIT License](LICENSE).
