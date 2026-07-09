# 🌍 WorldResetter

A minimal Paper plugin that deletes the overworld folder on every server startup and creates a fresh world with configurable seed, type, gamerules, time, weather, and Paper-specific performance tuning.

---

## ⚙️ How It Works

On every startup (before worlds load), WorldResetter deletes the `world` folder, then programmatically creates a new overworld using `Bukkit.createWorld()` with your configured seed and world type.

It also applies your gamerules, time, weather, and per-world performance settings, and writes the seed and spawn protection radius into `server.properties`.

> ℹ️ `world_nether` and `world_the_end` are **not** touched.

---

## 💡 Why It Exists

Built for lava rising minigame servers where every match should feel different. Instead of playing the same map over and over, the world resets on every server restart — giving players a brand new terrain each time.

---

## 📜 Commands

| Command | Description |
|---|---|
| 🔀 `/wr toggle` | Toggles world reset on/off |
| ✅ `/wr toggle on` | Enables world reset on next startup |
| ❌ `/wr toggle off` | Disables world reset on next startup |
| ℹ️ `/wr settings info` | Shows current settings |
| 🌱 `/wr settings seed <seed\|random>` | Sets the world seed (or random) |
| 🗺️ `/wr settings worldtype <type>` | Sets world type (normal/flat/large_biomes/amplified) |
| 🎲 `/wr settings gamerule <rule> <value>` | Sets a gamerule |
| 🎲 `/wr settings gamerule list` | Lists configured gamerules |
| 🎲 `/wr settings gamerule reset` | Clears all gamerules |
| ☀️ `/wr settings time <day\|night\|0-24000>` | Sets world time |
| ⛈️ `/wr settings weather <clear\|rain\|thunder>` | Sets weather |
| 🛡️ `/wr settings spawnprotection <radius>` | Sets spawn protection radius |
| 🖥️ `/wr settings viewdistance <2-32\|default>` | Sets this world's view distance (Paper-only) |
| 🌐 `/wr settings simulationdistance <2-32\|default>` | Sets this world's simulation distance (Paper-only) |
| 🏛️ `/wr settings structures <true\|false>` | Toggles structure generation (villages, strongholds, etc.) |
| ♻️ `/wr settings reset` | Resets all settings to defaults |

**Alias:** `/worldresetter` can be used in place of `/wr`
**Permission:** `worldresetter.admin` (default: op)

---

## 🛠️ Configuration (`config.yml`)

```yaml
enabled: false
settings:
  seed: ''
  world-type: 'normal'
  time: -1
  weather: ''
  spawn-protection: -1
  view-distance: -1
  simulation-distance: -1
  generate-structures: true
  gamerules: {}
```

---

## 📋 Requirements

- ☕ Java 21+
- 📦 Paper or a Paper-based fork (e.g. Purpur) — **1.21.11+ recommended**

> ⚠️ Not compatible with vanilla Spigot or CraftBukkit — see [Platform Support](#-platform-support) below.

---

## 🧩 Platform Support

WorldResetter only works on **Paper** and Paper-based servers like **Purpur** — it does not work on plain Spigot or CraftBukkit.

This isn't a bug, it's intentional: some of the plugin's features (like per-world view distance) simply don't exist outside of Paper. If your server runs on Purpur, you're fine — Purpur is built on top of Paper, so everything works the same.

---

## 🚀 Performance Optimizations

A few things this plugin does to keep your server running smoothly:

- **⚡ Faster restarts** — Deleting the old world used to happen one file at a time, which could take a while on large maps. Now it deletes everything at once using multiple CPU cores, so your server starts back up much faster — especially noticeable on SSD/NVMe drives.
- **🪶 Lighter load per world** — You can lower the view distance and simulation distance just for this world, without affecting your other worlds. Small minigame maps don't need long render distances, so turning this down reduces lag with no downside.
- **⏭️ Skip unnecessary generation** — If players never explore for loot, you can turn off village/stronghold generation entirely — this makes new worlds generate faster.

---

## 📄 License

MIT
