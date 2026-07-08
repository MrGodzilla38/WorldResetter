# WorldResetter

A minimal Paper plugin that deletes the overworld folder on every server startup and creates a fresh world with configurable seed, type, gamerules, time, weather, and Paper-specific performance tuning.

## How It Works

On every startup (before worlds load), WorldResetter deletes the `world` folder, then programmatically creates a new overworld using `Bukkit.createWorld()` with your configured seed and world type. It applies gamerules, time, weather, and per-world performance settings, and writes the seed and spawn protection radius into `server.properties`.

`world_nether` and `world_the_end` are not touched.

## Why It Exists

Built for lava rising minigame servers where every match should feel different. Instead of playing the same map over and over, the world resets on every server restart — giving players a brand new terrain each time.

## Commands

| Command | Description |
|---|---|
| `/wr toggle` | Toggles world reset on/off |
| `/wr toggle on` | Enables world reset on next startup |
| `/wr toggle off` | Disables world reset on next startup |
| `/wr settings info` | Shows current settings |
| `/wr settings seed <seed\|random>` | Sets the world seed (or random) |
| `/wr settings worldtype <type>` | Sets world type (normal/flat/large_biomes/amplified) |
| `/wr settings gamerule <rule> <value>` | Sets a gamerule |
| `/wr settings gamerule list` | Lists configured gamerules |
| `/wr settings gamerule reset` | Clears all gamerules |
| `/wr settings time <day\|night\|0-24000>` | Sets world time |
| `/wr settings weather <clear\|rain\|thunder>` | Sets weather |
| `/wr settings spawnprotection <radius>` | Sets spawn protection radius |
| `/wr settings viewdistance <2-32\|default>` | Sets this world's view distance (Paper-only) |
| `/wr settings simulationdistance <2-32\|default>` | Sets this world's simulation distance (Paper-only) |
| `/wr settings structures <true\|false>` | Toggles structure generation (villages, strongholds, etc.) |
| `/wr settings reset` | Resets all settings to defaults |

Aliases: `/worldresetter` can be used in place of `/wr`.

**Permission:** `worldresetter.admin` (default: op)

## Configuration (`config.yml`)

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

## Requirements

- Paper or a Paper-based fork (e.g. Purpur) — **1.21.11+ recommended**
- Java 21+

Not compatible with vanilla Spigot or CraftBukkit — see [Platform Support](#platform-support) above.

## Platform Support

WorldResetter targets **Paper and Paper-based forks (Purpur, etc.) only** — it is not compatible with vanilla Spigot or CraftBukkit.

This is a deliberate choice, not a limitation: the plugin uses Paper-exclusive per-world API (`World#setViewDistance`, `World#setSimulationDistance`) that doesn't exist on Spigot's API surface at all. Purpur is a drop-in fork of Paper — it inherits the full Paper API unchanged — so anything that runs on Paper runs on Purpur with no extra work.

## Performance Optimizations (Paper-only)

These are the reasons this plugin specifically targets Paper rather than plain Bukkit/Spigot:

- **Parallelized world deletion.** Deleting the old `world` folder happens synchronously at startup and directly delays boot time — the bigger the world, the longer players wait. WorldResetter now deletes all region/entity/POI files in parallel across CPU cores (instead of one file at a time), then removes the now-empty directory tree. On an SSD/NVMe with a large previous world, this can cut deletion time dramatically compared to a naive recursive delete.
- **Per-world view/simulation distance.** `settings.view-distance` and `settings.simulation-distance` let this world override the server-wide values from `server.properties`, using Paper's per-world API. Lava-rising-style minigame maps are small and don't need the server's default render distance — dropping it cuts chunk-loading and entity-tracking overhead specifically for this world, without touching your other worlds.
- **Optional structure generation toggle.** `settings.generate-structures` (default `true`) lets you disable village/stronghold/etc. generation for faster chunk generation on maps where players never explore for loot.

## License

MIT
