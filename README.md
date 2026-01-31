# 🧩 BackupPlugin

🚀 **BackupPlugin** is a high-performance PaperMC plugin that automatically and manually creates **ZIP backups** of your Minecraft worlds with **zero server lag**.

Designed for stability, safety, and ease of use.

Paper only 1.21 plugin!

---

## ✨ Features

✅ Automatic scheduled backups  
✅ Manual backup command (`/backup now`)  
✅ ZIP-compressed backups  
✅ Zero-lag **async I/O**  
✅ Safe autosave pause & restore  
✅ Per-world enable / disable  
✅ Discord webhook **EMBED** logging  
✅ Console + tellraw chat messages  
✅ Auto cleanup of old backups  
✅ Cleanup runs **only after successful backup**  
✅ Fully configurable  
✅ Production-ready

---

## ⏱️ How It Works

1️⃣ Warns players in chat before backup  
2️⃣ Waits a configurable delay  
3️⃣ Pauses world autosaving  
4️⃣ Zips enabled worlds asynchronously  
5️⃣ Restores autosaving  
6️⃣ Logs success in:
   - 🖥️ Console
   - 💬 In-game chat (tellraw)
   - 📡 Discord embed
7️⃣ Cleans old backups (only after success)

---

## 📁 Backup Location

Backups are saved as `.zip` files to a folder you choose.

📌 Configurable in `config.yml`:
```yml
backup:
  path: "C:/MinecraftBackups"
