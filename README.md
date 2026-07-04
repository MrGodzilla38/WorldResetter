# WorldResetter

A minimal Paper plugin that deletes the overworld folder on every server startup, forcing a fresh world with a new random seed each time the server boots.

## How It Works

On every startup, before the world loads, WorldResetter deletes the `world` folder entirely. Minecraft then generates a brand new world with a random seed — so every game session takes place on a completely different map.

`world_nether` and `world_the_end` are not touched.

## Why It Exists

Built for lava rising minigame servers where every match should feel different. Instead of playing the same map over and over, the world resets on every server restart — giving players a brand new terrain each time.

## Commands

| Command | Description |
|---|---|
| `/wr enable` | Enables world reset on next startup |
| `/wr disable` | Disables world reset on next startup |

Aliases: `/worldresetter enable`, `/worldresetter disable`

**Permission:** `worldresetter.admin` (default: op)

## Requirements

- Paper 1.21.4+
- Java 21+

## License

MIT
