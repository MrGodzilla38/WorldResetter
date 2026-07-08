package com.worldresetter;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class WorldResetter extends JavaPlugin implements Listener, BasicCommand {

    private static final String WORLD_NAME = "world";
    private static final String PREFIX = "§e[WorldResetter] ";

    private static final List<String> COMMON_GAMERULES = List.of(
        "announceAdvancements", "blockExplosionDropDecay", "commandBlockOutput",
        "disableElytraMovementCheck", "disableRaids", "doDaylightCycle",
        "doEntityDrops", "doFireTick", "doImmediateRespawn", "doInsomnia",
        "doLimitedCrafting", "doMobLoot", "doMobSpawning", "doPatrolSpawning",
        "doTileDrops", "doTraderSpawning", "doVinesSpread", "doWeatherCycle",
        "doWardenSpawning", "drowningDamage", "enderPearlsVanishOnDeath",
        "fallDamage", "fireDamage", "forgiveDeadPlayers", "freezeDamage",
        "globalSoundEvents", "keepInventory",
        "lavaSourceConversion", "logAdminCommands", "maxCommandChainLength",
        "maxEntityCramming", "mobExplosionDropDecay", "mobGriefing",
        "naturalRegeneration", "playersSleepingPercentage",
        "projectilesCanBreakBlocks", "randomTickSpeed", "reducedDebugInfo",
        "respawnBlocksExplode", "sendCommandFeedback", "showBorderEffect",
        "showCoordinates", "showDeathMessages", "showRecipeMessages",
        "showTags", "snowAccumulationHeight", "spawnRadius",
        "spectatorsGenerateChunks", "tntExplosionDropDecay", "universalAnger",
        "waterSourceConversion"
    );

    @Override
    public void onLoad() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!configFile.exists()) saveDefaultConfig();
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        boolean enabled = config.getBoolean("enabled", false);

        if (!enabled) {
            getLogger().info("World reset is DISABLED. Skipping.");
            return;
        }

        getLogger().info("World reset is ENABLED.");

        File worldFolder = new File(getServer().getWorldContainer(), WORLD_NAME);
        boolean shouldCreate = false;

        if (worldFolder.exists()) {
            getLogger().info("Deleting world folder...");

            if (deleteRecursively(worldFolder)) {
                getLogger().info("World folder deleted.");
                shouldCreate = true;
            } else {
                getLogger().warning("Failed to delete world folder! Check file permissions.");
            }
        } else {
            shouldCreate = true;
        }

        if (shouldCreate) {
            World world = createConfiguredWorld(config);
            if (world != null) {
                applyGameRules(config, world);
                applyTime(config, world);
                applyWeather(config, world);
                applyPerformanceSettings(config, world);
            }
        }
    }

    @Override
    public void onEnable() {
        registerCommand("worldresetter", "Manages WorldResetter settings", List.of("wr"), this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.getBoolean("enabled", false)) return;
        writeSeedAndSpawnProtection(config);
    }

    @Override
    public String permission() {
        return "worldresetter.admin";
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (!event.getWorld().getName().equals(WORLD_NAME)) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        if (!config.getBoolean("enabled", false)) return;

        World world = event.getWorld();
        applyGameRules(config, world);
        applyTime(config, world);
        applyWeather(config, world);
        applyPerformanceSettings(config, world);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("toggle", "settings");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return List.of("on", "off");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("settings")) {
            return List.of("seed", "gamerule", "worldtype", "time", "weather", "spawnprotection",
                "viewdistance", "simulationdistance", "structures", "info", "reset");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("settings")) {
            String sub = args[1].toLowerCase();
            switch (sub) {
                case "gamerule" -> {
                    List<String> options = new ArrayList<>(COMMON_GAMERULES);
                    options.add("list");
                    options.add("reset");
                    return options;
                }
                case "worldtype" -> {
                    return List.of("normal", "flat", "large_biomes", "amplified");
                }
                case "time" -> {
                    return List.of("day", "night");
                }
                case "weather" -> {
                    return List.of("clear", "rain", "thunder");
                }
                case "viewdistance", "simulationdistance" -> {
                    return List.of("default", "4", "6", "8", "10");
                }
                case "structures" -> {
                    return List.of("true", "false");
                }
            }
        }

        return List.of();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();

        if (args.length == 0) {
            sender.sendMessage(PREFIX + "§eUsage: /wr <toggle|settings>");
            return;
        }

        if (args[0].equalsIgnoreCase("settings")) {
            handleSettings(sender, args);
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        switch (args[0].toLowerCase()) {
            // yorum satırındaki kodları test amaçlı yorum satırı yaptım sonra silinecekler.
            // case "enable" -> {
            //     config.set("enabled", true);
            //     try {
            //         config.save(configFile);
            //     } catch (Exception e) {
            //         getLogger().severe("Failed to save config: " + e.getMessage());
            //         sender.sendMessage("§c[WorldResetter] Failed to save config.");
            //         return;
            //     }
            //     getLogger().info("World reset ENABLED. Takes effect on next restart.");
            //     sender.sendMessage("§a[WorldResetter] World reset ENABLED. Takes effect on next restart.");
            // }
            // case "disable" -> {
            //     config.set("enabled", false);
            //     try {
            //         config.save(configFile);
            //     } catch (Exception e) {
            //         getLogger().severe("Failed to save config: " + e.getMessage());
            //         sender.sendMessage("§c[WorldResetter] Failed to save config.");
            //         return;
            //     }
            //     getLogger().info("World reset DISABLED. Takes effect on next restart.");
            //     sender.sendMessage("§a[WorldResetter] World reset DISABLED. Takes effect on next restart.");
            // }
            case "toggle" -> {
                if (args.length == 1) {
                    boolean current = config.getBoolean("enabled", false);
                    config.set("enabled", !current);
                    try {
                        config.save(configFile);
                    } catch (Exception e) {
                        getLogger().severe("Failed to save config: " + e.getMessage());
                        sender.sendMessage("§c[WorldResetter] Failed to save config.");
                        return;
                    }
                    String state = !current ? "ENABLED" : "DISABLED";
                    getLogger().info("World reset " + state + ". Takes effect on next restart.");
                    sender.sendMessage("§a[WorldResetter] Toggled: World reset " + state + ". Takes effect on next restart.");
                } else if (args.length == 2) {
                    boolean enable = args[1].equalsIgnoreCase("on");
                    if (!enable && !args[1].equalsIgnoreCase("off")) {
                        sender.sendMessage(PREFIX + "§eUsage: /wr toggle <on|off>");
                        return;
                    }
                    config.set("enabled", enable);
                    try {
                        config.save(configFile);
                    } catch (Exception e) {
                        getLogger().severe("Failed to save config: " + e.getMessage());
                        sender.sendMessage("§c[WorldResetter] Failed to save config.");
                        return;
                    }
                    String state = enable ? "ENABLED" : "DISABLED";
                    getLogger().info("World reset " + state + ". Takes effect on next restart.");
                    sender.sendMessage("§a[WorldResetter] World reset " + state + ". Takes effect on next restart.");
                } else {
                    sender.sendMessage(PREFIX + "§eUsage: /wr toggle [on|off]");
                }
            }
            default -> sender.sendMessage(PREFIX + "§eUsage: /wr <toggle|settings>");
        }
    }

    private static final String SETTINGS_USAGE =
        "§eUsage: /wr settings <seed|gamerule|worldtype|time|weather|spawnprotection|"
        + "viewdistance|simulationdistance|structures|info|reset>";

    private boolean handleSettings(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + SETTINGS_USAGE);
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "seed" -> handleSeed(sender, args);
            case "gamerule" -> handleGamerule(sender, args);
            case "worldtype" -> handleWorldType(sender, args);
            case "time" -> handleTime(sender, args);
            case "weather" -> handleWeather(sender, args);
            case "spawnprotection" -> handleSpawnProtection(sender, args);
            case "viewdistance" -> handleViewDistance(sender, args);
            case "simulationdistance" -> handleSimulationDistance(sender, args);
            case "structures" -> handleStructures(sender, args);
            case "info" -> handleInfo(sender);
            case "reset" -> handleReset(sender);
            default -> sender.sendMessage(PREFIX + SETTINGS_USAGE);
        }

        return true;
    }

    private void handleSeed(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings seed <seed|random>");
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        String value = args[2];
        if (value.equalsIgnoreCase("random")) {
            config.set("settings.seed", "");
            sender.sendMessage("§a[WorldResetter] Seed cleared. A random seed will be used.");
        } else {
            config.set("settings.seed", value);
            sender.sendMessage("§a[WorldResetter] Seed set to: " + value);
        }

        saveAndNotify(config, configFile, sender);
    }

    private void handleGamerule(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings gamerule <rule> <value>");
            return;
        }

        if (args[2].equalsIgnoreCase("list")) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
            ConfigurationSection section = config.getConfigurationSection("settings.gamerules");
            if (section == null || section.getKeys(false).isEmpty()) {
                sender.sendMessage(PREFIX + "§eNo gamerules configured.");
                return;
            }
            sender.sendMessage(PREFIX + "§eSaved gamerules:");
            for (String rule : section.getKeys(false)) {
                sender.sendMessage("  §e" + rule + ": §f" + section.getString(rule));
            }
            return;
        }

        if (args[2].equalsIgnoreCase("reset")) {
            File configFile = new File(getDataFolder(), "config.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            config.set("settings.gamerules", null);
            config.set("settings.gamerules", Map.of());
            saveAndNotify(config, configFile, sender);
            sender.sendMessage("§a[WorldResetter] All gamerules cleared.");
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings gamerule <rule> <value>");
            return;
        }

        String rule = args[2];
        String value = args[3];

        GameRule<?> gameRule = GameRule.getByName(rule);
        if (gameRule == null) {
            sender.sendMessage("§c[WorldResetter] Unknown gamerule: " + rule);
            return;
        }

        if (gameRule.getType() == Boolean.class) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                sender.sendMessage("§c[WorldResetter] Invalid value for gamerule " + rule + ". Expected true or false.");
                return;
            }
        } else if (gameRule.getType() == Integer.class) {
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c[WorldResetter] Invalid value for gamerule " + rule + ". Expected a number.");
                return;
            }
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.gamerules." + rule, value);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Gamerule " + rule + " set to: " + value);
    }

    private void handleWorldType(CommandSender sender, String[] args) {
        sender.sendMessage("§e[WorldResetter] ⚠ World type setting is currently under maintenance.");
        sender.sendMessage("§7This feature is not yet supported by Paper at startup.");
        sender.sendMessage("§7It will be re-enabled in a future update.");
    }

    private void handleTime(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings time <day|night|0-24000>");
            return;
        }

        String timeStr = args[2];
        long timeValue;

        switch (timeStr.toLowerCase()) {
            case "day" -> timeValue = 1000;
            case "night" -> timeValue = 13000;
            default -> {
                try {
                    timeValue = Long.parseLong(timeStr);
                    if (timeValue < 0 || timeValue > 24000) {
                        sender.sendMessage("§c[WorldResetter] Time must be between 0 and 24000.");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c[WorldResetter] Invalid time. Use day, night, or a number (0-24000).");
                    return;
                }
            }
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.time", timeValue);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Time set to: " + timeStr.toLowerCase());
    }

    private void handleWeather(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings weather <clear|rain|thunder>");
            return;
        }

        String weather = args[2].toLowerCase();
        if (!weather.equals("clear") && !weather.equals("rain") && !weather.equals("thunder")) {
            sender.sendMessage("§c[WorldResetter] Invalid weather. Valid values: clear, rain, thunder.");
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.weather", weather);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Weather set to: " + weather);
    }

    private void handleSpawnProtection(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings spawnprotection <radius>");
            return;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[2]);
            if (radius < 0) {
                sender.sendMessage("§c[WorldResetter] Spawn protection radius must be a non-negative integer.");
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c[WorldResetter] Invalid radius. Must be a non-negative integer.");
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.spawn-protection", radius);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Spawn protection radius set to: " + radius);
    }

    private void handleViewDistance(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings viewdistance <2-32|default>");
            return;
        }

        int distance = parseDistanceArg(sender, args[2], "view distance", 2, 32);
        if (distance == Integer.MIN_VALUE) return;

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.view-distance", distance);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] View distance set to: " + (distance == -1 ? "server default" : distance));
    }

    private void handleSimulationDistance(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings simulationdistance <2-32|default>");
            return;
        }

        int distance = parseDistanceArg(sender, args[2], "simulation distance", 2, 32);
        if (distance == Integer.MIN_VALUE) return;

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.simulation-distance", distance);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Simulation distance set to: " + (distance == -1 ? "server default" : distance));
    }

    private int parseDistanceArg(CommandSender sender, String value, String label, int min, int max) {
        if (value.equalsIgnoreCase("default")) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                sender.sendMessage("§c[WorldResetter] " + label + " must be between " + min + " and " + max + ".");
                return Integer.MIN_VALUE;
            }
            return parsed;
        } catch (NumberFormatException e) {
            sender.sendMessage("§c[WorldResetter] Invalid " + label + ". Use a number (" + min + "-" + max + ") or 'default'.");
            return Integer.MIN_VALUE;
        }
    }

    private void handleStructures(CommandSender sender, String[] args) {
        if (args.length < 3 || (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false"))) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings structures <true|false>");
            return;
        }

        boolean value = Boolean.parseBoolean(args[2]);
        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("settings.generate-structures", value);
        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] Structure generation set to: " + value);
    }

    private void handleInfo(CommandSender sender) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));

        sender.sendMessage(PREFIX + "§eCurrent settings:");

        String seed = config.getString("settings.seed", "");
        sender.sendMessage("  §eSeed: §f" + (seed.isEmpty() ? "random" : seed));

        sender.sendMessage("  §eWorld Type: §6⚠ Under maintenance");

        long time = config.getLong("settings.time", -1);
        sender.sendMessage("  §eTime: §f" + (time == -1 ? "not set" : time));

        String weather = config.getString("settings.weather", "");
        sender.sendMessage("  §eWeather: §f" + (weather.isEmpty() ? "not set" : weather));

        int spawnProtection = config.getInt("settings.spawn-protection", -1);
        sender.sendMessage("  §eSpawn Protection: §f" + (spawnProtection == -1 ? "not set" : spawnProtection));

        int viewDistance = config.getInt("settings.view-distance", -1);
        sender.sendMessage("  §eView Distance: §f" + (viewDistance == -1 ? "server default" : viewDistance));

        int simulationDistance = config.getInt("settings.simulation-distance", -1);
        sender.sendMessage("  §eSimulation Distance: §f" + (simulationDistance == -1 ? "server default" : simulationDistance));

        sender.sendMessage("  §eGenerate Structures: §f" + config.getBoolean("settings.generate-structures", true));

        ConfigurationSection gamerules = config.getConfigurationSection("settings.gamerules");
        if (gamerules != null && !gamerules.getKeys(false).isEmpty()) {
            sender.sendMessage("  §eGamerules:");
            for (String rule : gamerules.getKeys(false)) {
                sender.sendMessage("    §e" + rule + ": §f" + gamerules.getString(rule));
            }
        } else {
            sender.sendMessage("  §eGamerules: §fnone");
        }
    }

    private void handleReset(CommandSender sender) {
        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        config.set("settings.seed", "");
        config.set("settings.world-type", "normal");
        config.set("settings.time", -1);
        config.set("settings.weather", "");
        config.set("settings.spawn-protection", -1);
        config.set("settings.view-distance", -1);
        config.set("settings.simulation-distance", -1);
        config.set("settings.generate-structures", true);
        config.set("settings.gamerules", null);
        config.set("settings.gamerules", Map.of());

        saveAndNotify(config, configFile, sender);
        sender.sendMessage("§a[WorldResetter] All settings reset to defaults.");
    }

    private void writeSeedAndSpawnProtection(FileConfiguration config) {
        String seed = config.getString("settings.seed", "");
        int spawnProtection = config.getInt("settings.spawn-protection", -1);

        Map<String, String> entries = new HashMap<>();
        entries.put("level-seed", seed);

        if (spawnProtection >= 0) {
            entries.put("spawn-protection", String.valueOf(spawnProtection));
        }

        modifyServerProperties(entries);
    }

    private World createConfiguredWorld(FileConfiguration config) {
        String worldType = config.getString("settings.world-type", "normal");
        String seed = config.getString("settings.seed", "");

        WorldCreator creator = new WorldCreator(WORLD_NAME);

        switch (worldType.toLowerCase()) {
            case "flat" -> creator.type(WorldType.FLAT);
            case "large_biomes" -> creator.type(WorldType.LARGE_BIOMES);
            case "amplified" -> creator.type(WorldType.AMPLIFIED);
            default -> creator.type(WorldType.NORMAL);
        }

        if (!seed.isEmpty()) {
            try {
                creator.seed(Long.parseLong(seed));
            } catch (NumberFormatException e) {
                creator.seed(seed.hashCode());
            }
        }

        boolean generateStructures = config.getBoolean("settings.generate-structures", true);
        creator.generateStructures(generateStructures);

        World world = Bukkit.createWorld(creator);
        if (world != null) {
            getLogger().info("Created world '" + WORLD_NAME + "' with type: " + worldType
                + (seed.isEmpty() ? "" : ", seed: " + seed));
        } else {
            getLogger().severe("Failed to create world '" + WORLD_NAME + "'.");
        }
        return world;
    }

    private void modifyServerProperties(Map<String, String> entries) {
        try {
            File propertiesFile = new File(getServer().getWorldContainer(), "server.properties");
            if (!propertiesFile.exists()) {
                getLogger().warning("server.properties not found. Skipping server.properties modifications.");
                return;
            }

            Path path = propertiesFile.toPath();
            List<String> lines = new ArrayList<>(Files.readAllLines(path));
            Map<String, String> remaining = new HashMap<>(entries);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eqIdx = line.indexOf('=');
                if (eqIdx == -1) continue;

                String key = line.substring(0, eqIdx).trim();
                if (remaining.containsKey(key)) {
                    lines.set(i, key + "=" + remaining.remove(key));
                }
            }

            for (Map.Entry<String, String> entry : remaining.entrySet()) {
                lines.add(entry.getKey() + "=" + entry.getValue());
            }

            Files.write(path, lines);
            getLogger().info("Updated server.properties with: " + String.join(", ", entries.keySet()));
        } catch (IOException e) {
            getLogger().severe("Failed to modify server.properties: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGameRules(FileConfiguration config, World world) {
        ConfigurationSection section = config.getConfigurationSection("settings.gamerules");
        if (section == null) return;

        for (String ruleName : section.getKeys(false)) {
            String value = section.getString(ruleName);
            if (value == null) continue;

            GameRule<?> rule = GameRule.getByName(ruleName);
            if (rule == null) {
                getLogger().warning("Unknown gamerule: " + ruleName);
                continue;
            }

            try {
                if (rule.getType() == Boolean.class) {
                    world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
                    getLogger().info("Set gamerule " + ruleName + " to " + value);
                } else if (rule.getType() == Integer.class) {
                    world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
                    getLogger().info("Set gamerule " + ruleName + " to " + value);
                }
            } catch (Exception e) {
                getLogger().warning("Failed to set gamerule " + ruleName + " to " + value + ": " + e.getMessage());
            }
        }
    }

    private void applyPerformanceSettings(FileConfiguration config, World world) {
        int viewDistance = config.getInt("settings.view-distance", -1);
        if (viewDistance >= 2) {
            world.setViewDistance(viewDistance);
            getLogger().info("Set view distance to " + viewDistance);
        }

        int simulationDistance = config.getInt("settings.simulation-distance", -1);
        if (simulationDistance >= 2) {
            world.setSimulationDistance(simulationDistance);
            getLogger().info("Set simulation distance to " + simulationDistance);
        }
    }

    private void applyTime(FileConfiguration config, World world) {
        long time = config.getLong("settings.time", -1);
        if (time < 0) return;

        world.setTime(time);
        getLogger().info("Set world time to " + time);
    }

    private void applyWeather(FileConfiguration config, World world) {
        String weather = config.getString("settings.weather", "");
        if (weather.isEmpty()) return;

        switch (weather.toLowerCase()) {
            case "clear" -> {
                world.setStorm(false);
                world.setThundering(false);
                getLogger().info("Set weather to clear");
            }
            case "rain" -> {
                world.setStorm(true);
                world.setThundering(false);
                getLogger().info("Set weather to rain");
            }
            case "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
                getLogger().info("Set weather to thunder");
            }
        }
    }

    private void saveAndNotify(FileConfiguration config, File configFile, CommandSender sender) {
        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save config: " + e.getMessage());
            sender.sendMessage("§c[WorldResetter] Failed to save config.");
        }
    }

    private boolean deleteRecursively(File root) {
        if (!root.exists()) return true;

        List<Path> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root.toPath())) {
            walk.forEach(path -> {
                if (Files.isDirectory(path)) {
                    directories.add(path);
                } else {
                    files.add(path);
                }
            });
        } catch (IOException e) {
            getLogger().warning("Failed to scan world folder for deletion: " + e.getMessage());
            return false;
        }

        boolean[] success = {true};
        files.parallelStream().forEach(path -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                success[0] = false;
                getLogger().warning("Failed to delete file: " + path + " (" + e.getMessage() + ")");
            }
        });

        directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path dir : directories) {
            try {
                Files.delete(dir);
            } catch (IOException e) {
                success[0] = false;
                getLogger().warning("Failed to delete directory: " + dir + " (" + e.getMessage() + ")");
            }
        }

        return success[0];
    }
}
