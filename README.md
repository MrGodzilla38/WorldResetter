# WorldResetter

A minimal Paper plugin that deletes the overworld folder on every server startup, forcing a fresh world with a new random seed each time the server boots.

## How It Works

On every startup, before the world loads, WorldResetter deletes the `world` folder entirely. Minecraft then generates a brand new world with a random seed — so every game session takes place on a completely different map.

`world_nether` and `world_the_end` are not touched.

## Installation

1. Drop `WorldResetter.jar` into your server's `plugins/` folder.
2. Start the server.
3. That's it — no configuration needed.

## Requirements

- Paper 1.21.4+
- Java 21+

## Compatibility

Built for use alongside [KteRising](https://github.com/KteProject/KteRising). Each time the lava rising game ends and the server restarts, players get a fresh world to compete on.

## Build

```bash
git clone https://github.com/MrGodzilla38/WorldResetter.git
cd WorldResetter
mvn clean package
```

The compiled jar will be at `target/WorldResetter-1.0.0.jar`.

## License

MIT
