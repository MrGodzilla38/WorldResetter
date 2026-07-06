# WorldResetter

A minimal Paper plugin that deletes the overworld folder on every server startup and creates a fresh world with configurable seed, type, gamerules, time, and weather.

## How It Works

On every startup (before worlds load), WorldResetter deletes the `world` folder, then programmatically creates a new overworld using `Bukkit.createWorld()` with your configured seed and world type. It applies gamerules, time, and weather settings, and writes the seed and spawn protection radius into `server.properties`.

`world_nether` and `world_the_end` are not touched.

## Why It Exists

Built for lava rising minigame servers where every match should feel different. Instead of playing the same map over and over, the world resets on every server restart — giving players a brand new terrain each time.

## Commands

| Command | Description |
|---|---|
| `/wr enable` | Enables world reset on next startup |
| `/wr disable` | Disables world reset on next startup |
| `/wr settings info` | Shows current settings |
| `/wr settings seed <seed\|random>` | Sets the world seed (or random) |
| `/wr settings worldtype <type>` | Sets world type (normal/flat/large_biomes/amplified) |
| `/wr settings gamerule <rule> <value>` | Sets a gamerule |
| `/wr settings gamerule list` | Lists configured gamerules |
| `/wr settings gamerule reset` | Clears all gamerules |
| `/wr settings time <day\|night\|0-24000>` | Sets world time |
| `/wr settings weather <clear\|rain\|thunder>` | Sets weather |
| `/wr settings spawnprotection <radius>` | Sets spawn protection radius |
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
  gamerules: {}
```

## Requirements

- Paper 1.21.4+
- Java 21+

## License

MIT
