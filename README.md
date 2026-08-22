# SY-Pass

**SY-Pass** is a client-side Fabric mod for Minecraft designed to simplify and secure your in-game server authentication. It acts as an integrated password manager with native support for local credential storage and cloud synchronization via the official **Bitwarden CLI**.

---

## 🚀 Features

- **🔑 Local Password Manager:** Securely save, copy, and manage credentials for different Minecraft servers directly in-game.
- **☁️ Bitwarden Integration:** Sync your credentials to and from your Bitwarden vault using the official `bw` CLI.
- **🛡️ 2FA Support:** Complete multi-factor authentication seamlessly within the mod's GUI (supports both Email and Authenticator App verification methods).
- **⌨️ Easy Access:** Open the manager interface anytime in-game using a configurable hotkey (default: **`P`**).

---

## 🛠️ Requirements

- **Minecraft:** Fabric Loader setup.
- **Bitwarden CLI:** The executable (`bw` or `bw.exe`) should be placed in `.minecraft/config/sypass/` or installed globally in your system `PATH`.

---
## 📦 Building from Source

To build the mod for all supported Minecraft versions, run:

```bash
./gradlew build
```
The compiled .jar files will be located in:

versions/1.21.1/build/libs/ (for MC 1.21 – 1.21.1)
versions/1.21.11/build/libs/ (for MC 1.21.2 – 1.21.11+)


📄 License
This project is licensed under the MIT License - see the LICENSE file for details.
