package com.worldresetter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class WorldResetter extends JavaPlugin implements Listener, BasicCommand {

    private static final String PREFIX = "§e[WorldResetter] ";
    private static final UUID CONSOLE_UUID = new UUID(0, 0);

    private final Map<UUID, String> selectedWorlds = new ConcurrentHashMap<>();

    private volatile String latestVersion;
    private volatile String latestDownloadUrl;
    private volatile String latestFileName;
    private volatile String latestSha1;

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        migrateOldConfig();

        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        if (!worldsConfigFile.exists()) return;

        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        ConfigurationSection worldsSection = worldsConfig.getConfigurationSection("worlds");
        if (worldsSection == null) return;

        for (String worldName : worldsSection.getKeys(false)) {
            ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldName);
            if (worldConfig == null) continue;
            if (!worldConfig.getBoolean("enabled", false)) continue;

            getLogger().info("World reset is ENABLED for world: " + worldName);

            File worldFolder = new File(getServer().getWorldContainer(), worldName);
            boolean shouldCreate = false;

            if (worldFolder.exists()) {
                getLogger().info("Deleting world folder for '" + worldName + "'...");

                if (deleteRecursively(worldFolder)) {
                    getLogger().info("World folder deleted for '" + worldName + "'.");
                    shouldCreate = true;
                } else {
                    getLogger().warning("Failed to delete world folder for '" + worldName + "'! Check file permissions.");
                }
            } else {
                shouldCreate = true;
            }

            if (shouldCreate) {
                World world = createConfiguredWorld(worldConfig, worldName);
                if (world != null) {
                    applyGameRules(worldConfig, world);
                    applyTime(worldConfig, world);
                    applyWeather(worldConfig, world);
                    applyPerformanceSettings(worldConfig, world);
                }
            }
        }
    }

    @Override
    public void onEnable() {
        registerCommand("worldresetter", "Manages WorldResetter settings", List.of("wr"), this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::checkForUpdates, 0L, 432000L);
    }

    @Override
    public void onDisable() {
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        if (!worldsConfigFile.exists()) return;

        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);

        String defaultWorldName = getLevelName();
        ConfigurationSection worldSection = worldsConfig.getConfigurationSection("worlds." + defaultWorldName);
        if (worldSection != null && worldSection.getBoolean("enabled", false)) {
            writeSeedAndSpawnProtection(worldSection);
        }
    }

    private String getLevelName() {
        File propertiesFile = new File(getServer().getWorldContainer(), "server.properties");
        if (propertiesFile.exists()) {
            try {
                for (String line : Files.readAllLines(propertiesFile.toPath())) {
                    if (line.startsWith("level-name=")) {
                        String name = line.substring("level-name=".length()).trim();
                        if (!name.isEmpty()) return name;
                    }
                }
            } catch (IOException e) {
                getLogger().warning("Failed to read server.properties: " + e.getMessage());
            }
        }
        return "world";
    }

    @Override
    public String permission() {
        return "worldresetter.admin";
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();

        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        if (!worldsConfigFile.exists()) return;

        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        ConfigurationSection worldConfig = worldsConfig.getConfigurationSection("worlds." + worldName);
        if (worldConfig == null) return;

        World world = event.getWorld();
        applyGameRules(worldConfig, world);
        applyTime(worldConfig, world);
        applyWeather(worldConfig, world);
        applyPerformanceSettings(worldConfig, world);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (latestVersion == null) return;
        String current = getPluginMeta().getVersion();
        if (compareVersions(latestVersion, current) <= 0) return;
        if (event.getPlayer().isOp() || event.getPlayer().hasPermission("worldresetter.admin")) {
            event.getPlayer().sendMessage("§e[WorldResetter] Update available. Latest: §a" + latestVersion + "§e, Current: §c" + current + "§e. Use §a/wr version update§e to install.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        selectedWorlds.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("toggle", "settings", "version", "world");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("version")) {
            return List.of("update");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return List.of("on", "off");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return getAvailableWorlds();
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
            sender.sendMessage(PREFIX + "§eUsage: /wr <toggle|settings|version|world>");
            return;
        }

        if (args[0].equalsIgnoreCase("settings")) {
            handleSettings(sender, args);
            return;
        }

        if (args[0].equalsIgnoreCase("world")) {
            handleWorld(sender, args);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> handleToggle(sender, args);
            case "version" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("update")) {
                    handleVersionUpdate(sender);
                } else {
                    handleVersion(sender);
                }
            }
            default -> sender.sendMessage(PREFIX + "§eUsage: /wr <toggle|settings|version|world>");
        }
    }

    private void handleWorld(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "§eUsage: /wr world <name>");
            return;
        }

        String worldName = args[1];
        List<String> available = getAvailableWorlds();
        if (!available.contains(worldName)) {
            sender.sendMessage("§c[WorldResetter] Unknown world: " + worldName);
            return;
        }

        UUID key = sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
        selectedWorlds.put(key, worldName);
        sender.sendMessage("§a[WorldResetter] Selected world: " + worldName);
    }

    private void handleToggle(CommandSender sender, String[] args) {
        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        String path = "worlds." + worldName + ".enabled";
        String suffix = worldSuffix(sender);

        if (args.length == 1) {
            boolean current = worldsConfig.getBoolean(path, false);
            worldsConfig.set(path, !current);
            saveAndNotify(worldsConfig, worldsConfigFile, sender);
            String state = !current ? "ENABLED" : "DISABLED";
            getLogger().info("World reset " + state + " for '" + worldName + "'. Takes effect on next restart.");
            sender.sendMessage("§a[WorldResetter] Toggled: World reset " + state + " for '" + worldName + "'. Takes effect on next restart." + suffix);
        } else if (args.length == 2) {
            boolean enable = args[1].equalsIgnoreCase("on");
            if (!enable && !args[1].equalsIgnoreCase("off")) {
                sender.sendMessage(PREFIX + "§eUsage: /wr toggle [on|off]");
                return;
            }
            worldsConfig.set(path, enable);
            saveAndNotify(worldsConfig, worldsConfigFile, sender);
            String state = enable ? "ENABLED" : "DISABLED";
            getLogger().info("World reset " + state + " for '" + worldName + "'. Takes effect on next restart.");
            sender.sendMessage("§a[WorldResetter] World reset " + state + " for '" + worldName + "'. Takes effect on next restart." + suffix);
        } else {
            sender.sendMessage(PREFIX + "§eUsage: /wr toggle [on|off]");
        }
    }

    private static final String SETTINGS_USAGE =
        "§eUsage: /wr settings <seed|gamerule|worldtype|time|weather|spawnprotection|"
        + "viewdistance|simulationdistance|structures|info|reset>";

    private void handleVersion(CommandSender sender) {
        String current = getPluginMeta().getVersion();
        if (latestVersion == null) {
            sender.sendMessage(PREFIX + "§eVersion information not yet available. Please try again later.");
            return;
        }
        if (compareVersions(latestVersion, current) > 0) {
            sender.sendMessage("§e[WorldResetter] Update available. Latest: §a" + latestVersion + "§e, Current: §c" + current + "§e. Use §a/wr version update§e to install.");
        } else {
            sender.sendMessage("§a[WorldResetter] Version is up to date: §f" + current);
        }
    }

    private void handleVersionUpdate(CommandSender sender) {
        if (latestVersion == null || latestDownloadUrl == null) {
            sender.sendMessage(PREFIX + "§eVersion information not yet available. Please try again later.");
            return;
        }

        String current = getPluginMeta().getVersion();
        if (compareVersions(latestVersion, current) <= 0) {
            sender.sendMessage("§a[WorldResetter] Version is already up to date: §f" + current);
            return;
        }

        sender.sendMessage(PREFIX + "§eDownloading update (v" + latestVersion + ")...");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(latestDownloadUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenAccept(response -> {
                try {
                    byte[] data = response.body();
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    byte[] digest = md.digest(data);
                    String sha1Hex = HexFormat.of().formatHex(digest);

                    if (!sha1Hex.equalsIgnoreCase(latestSha1)) {
                        throw new SecurityException("SHA-1 mismatch");
                    }

                    String fileName = (latestFileName != null && !latestFileName.isEmpty())
                        ? latestFileName : "WorldResetter-" + latestVersion + ".jar";
                    Path pluginsDir = getDataFolder().getParentFile().toPath();
                    Path newJarPath = pluginsDir.resolve(fileName);

                    Files.write(newJarPath, data);

                    File currentJar = getFile();
                    boolean oldDeleted = true;
                    try {
                        Files.deleteIfExists(currentJar.toPath());
                    } catch (IOException e) {
                        oldDeleted = false;
                        getLogger().warning("Failed to delete old jar: " + currentJar.getAbsolutePath() + " - " + e.getMessage());
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§e[WorldResetter] New version downloaded but old file could not be deleted. You may need to remove it manually: §f" + currentJar.getAbsolutePath()));
                    }

                    if (oldDeleted) {
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§a[WorldResetter] Update downloaded successfully! Version v" + latestVersion + " will be active after server restart."));
                    }
                } catch (SecurityException e) {
                    getLogger().warning("Update download failed SHA-1 verification.");
                    Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§c[WorldResetter] Download verification failed. Update was cancelled."));
                } catch (NoSuchAlgorithmException e) {
                    getLogger().severe("SHA-1 algorithm not available: " + e.getMessage());
                    Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§c[WorldResetter] SHA-1 algorithm unavailable. Update cannot proceed."));
                } catch (IOException e) {
                    getLogger().severe("Failed to write update file: " + e.getMessage());
                    Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§c[WorldResetter] Failed to save update file: " + e.getMessage()));
                } catch (Exception e) {
                    getLogger().severe("Unexpected error during update: " + e.getMessage());
                    Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("§c[WorldResetter] An unexpected error occurred: " + e.getMessage()));
                }
            })
            .exceptionally(e -> {
                getLogger().warning("Update download failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage("§c[WorldResetter] Download failed: " + e.getMessage()));
                return null;
            });
    }

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

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        String path = "worlds." + worldName + ".seed";
        String suffix = worldSuffix(sender);

        String value = args[2];
        if (value.equalsIgnoreCase("random")) {
            worldsConfig.set(path, "");
            sender.sendMessage("§a[WorldResetter] Seed cleared. A random seed will be used." + suffix);
        } else {
            worldsConfig.set(path, value);
            sender.sendMessage("§a[WorldResetter] Seed set to: " + value + suffix);
        }

        saveAndNotify(worldsConfig, worldsConfigFile, sender);
    }

    private void handleGamerule(CommandSender sender, String[] args) {
        String worldName = resolveSelectedWorld(sender);
        String suffix = worldSuffix(sender);

        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings gamerule <rule> <value>");
            return;
        }

        if (args[2].equalsIgnoreCase("list")) {
            FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "worlds-config.yml"));
            ConfigurationSection section = worldsConfig.getConfigurationSection("worlds." + worldName + ".gamerules");
            if (section == null || section.getKeys(false).isEmpty()) {
                sender.sendMessage(PREFIX + "§eNo gamerules configured." + suffix);
                return;
            }
            sender.sendMessage(PREFIX + "§eSaved gamerules:");
            for (String rule : section.getKeys(false)) {
                sender.sendMessage("  §e" + rule + ": §f" + section.getString(rule));
            }
            return;
        }

        if (args[2].equalsIgnoreCase("reset")) {
            File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
            FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
            worldsConfig.set("worlds." + worldName + ".gamerules", null);
            worldsConfig.set("worlds." + worldName + ".gamerules", Map.of());
            saveAndNotify(worldsConfig, worldsConfigFile, sender);
            sender.sendMessage("§a[WorldResetter] All gamerules cleared." + suffix);
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

        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        worldsConfig.set("worlds." + worldName + ".gamerules." + rule, value);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Gamerule " + rule + " set to: " + value + suffix);
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

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        String path = "worlds." + worldName + ".time";
        String suffix = worldSuffix(sender);

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

        worldsConfig.set(path, timeValue);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Time set to: " + timeStr.toLowerCase() + suffix);
    }

    private void handleWeather(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings weather <clear|rain|thunder>");
            return;
        }

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        String path = "worlds." + worldName + ".weather";
        String suffix = worldSuffix(sender);

        String weather = args[2].toLowerCase();
        if (!weather.equals("clear") && !weather.equals("rain") && !weather.equals("thunder")) {
            sender.sendMessage("§c[WorldResetter] Invalid weather. Valid values: clear, rain, thunder.");
            return;
        }

        worldsConfig.set(path, weather);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Weather set to: " + weather + suffix);
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

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        worldsConfig.set("worlds." + worldName + ".spawn-protection", radius);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Spawn protection radius set to: " + radius + worldSuffix(sender));
    }

    private void handleViewDistance(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings viewdistance <2-32|default>");
            return;
        }

        int distance = parseDistanceArg(sender, args[2], "view distance", 2, 32);
        if (distance == Integer.MIN_VALUE) return;

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        worldsConfig.set("worlds." + worldName + ".view-distance", distance);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] View distance set to: " + (distance == -1 ? "server default" : distance) + worldSuffix(sender));
    }

    private void handleSimulationDistance(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§eUsage: /wr settings simulationdistance <2-32|default>");
            return;
        }

        int distance = parseDistanceArg(sender, args[2], "simulation distance", 2, 32);
        if (distance == Integer.MIN_VALUE) return;

        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        worldsConfig.set("worlds." + worldName + ".simulation-distance", distance);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Simulation distance set to: " + (distance == -1 ? "server default" : distance) + worldSuffix(sender));
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
        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        worldsConfig.set("worlds." + worldName + ".generate-structures", value);
        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] Structure generation set to: " + value + worldSuffix(sender));
    }

    private void handleInfo(CommandSender sender) {
        String worldName = resolveSelectedWorld(sender);
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "worlds-config.yml"));
        ConfigurationSection worldSection = worldsConfig.getConfigurationSection("worlds." + worldName);

        sender.sendMessage("§eSettings for world: §f" + worldName);

        if (worldSection == null) {
            sender.sendMessage("  §eNo settings configured.");
            return;
        }

        String seed = worldSection.getString("seed", "");
        sender.sendMessage("  §eSeed: §f" + (seed.isEmpty() ? "random" : seed));

        sender.sendMessage("  §eWorld Type: §6⚠ Under maintenance");

        long time = worldSection.getLong("time", -1);
        sender.sendMessage("  §eTime: §f" + (time == -1 ? "not set" : time));

        String weather = worldSection.getString("weather", "");
        sender.sendMessage("  §eWeather: §f" + (weather.isEmpty() ? "not set" : weather));

        int spawnProtection = worldSection.getInt("spawn-protection", -1);
        sender.sendMessage("  §eSpawn Protection: §f" + (spawnProtection == -1 ? "not set" : spawnProtection));

        int viewDistance = worldSection.getInt("view-distance", -1);
        sender.sendMessage("  §eView Distance: §f" + (viewDistance == -1 ? "server default" : viewDistance));

        int simulationDistance = worldSection.getInt("simulation-distance", -1);
        sender.sendMessage("  §eSimulation Distance: §f" + (simulationDistance == -1 ? "server default" : simulationDistance));

        sender.sendMessage("  §eGenerate Structures: §f" + worldSection.getBoolean("generate-structures", true));

        ConfigurationSection gamerules = worldSection.getConfigurationSection("gamerules");
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
        String worldName = resolveSelectedWorld(sender);
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        FileConfiguration worldsConfig = YamlConfiguration.loadConfiguration(worldsConfigFile);
        String path = "worlds." + worldName;
        String suffix = worldSuffix(sender);

        worldsConfig.set(path + ".seed", "");
        worldsConfig.set(path + ".world-type", "normal");
        worldsConfig.set(path + ".time", -1);
        worldsConfig.set(path + ".weather", "");
        worldsConfig.set(path + ".spawn-protection", -1);
        worldsConfig.set(path + ".view-distance", -1);
        worldsConfig.set(path + ".simulation-distance", -1);
        worldsConfig.set(path + ".generate-structures", true);
        worldsConfig.set(path + ".gamerules", null);
        worldsConfig.set(path + ".gamerules", Map.of());
        worldsConfig.set(path + ".enabled", false);

        saveAndNotify(worldsConfig, worldsConfigFile, sender);
        sender.sendMessage("§a[WorldResetter] All settings reset to defaults for world '" + worldName + "'." + suffix);
    }

    private void migrateOldConfig() {
        File worldsConfigFile = new File(getDataFolder(), "worlds-config.yml");
        if (worldsConfigFile.exists()) return;

        File oldConfigFile = new File(getDataFolder(), "config.yml");
        FileConfiguration newConfig = new YamlConfiguration();

        if (oldConfigFile.exists()) {
            FileConfiguration oldConfig = YamlConfiguration.loadConfiguration(oldConfigFile);
            ConfigurationSection oldSettings = oldConfig.getConfigurationSection("settings");

            getLogger().info("Migrating old config.yml to worlds-config.yml...");

            newConfig.set("worlds.world.enabled", oldConfig.getBoolean("enabled", false));

            if (oldSettings != null) {
                for (String key : oldSettings.getKeys(true)) {
                    newConfig.set("worlds.world." + key, oldSettings.get(key));
                }
            }
        } else {
            newConfig.set("worlds", Map.of());
        }

        try {
            newConfig.save(worldsConfigFile);
            if (oldConfigFile.exists()) {
                getLogger().info("Migration complete. Old config.yml is preserved for backup.");
            }
        } catch (IOException e) {
            getLogger().severe("Failed to save worlds-config.yml: " + e.getMessage());
        }
    }

    private List<String> getAvailableWorlds() {
        Set<String> worlds = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            worlds.add(world.getName());
        }

        File container = getServer().getWorldContainer();
        File[] dirs = container.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                if (new File(dir, "level.dat").exists()) {
                    worlds.add(dir.getName());
                }
            }
        }

        return worlds.stream().sorted().toList();
    }

    private String resolveSelectedWorld(CommandSender sender) {
        UUID key = sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
        String selected = selectedWorlds.get(key);
        if (selected != null) return selected;

        if (sender instanceof Player player) {
            return player.getWorld().getName();
        }

        return getLevelName();
    }

    private String worldSuffix(CommandSender sender) {
        UUID key = sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
        if (selectedWorlds.containsKey(key)) return "";
        return " §7(world: " + resolveSelectedWorld(sender) + ")";
    }

    private void writeSeedAndSpawnProtection(ConfigurationSection worldConfig) {
        String seed = worldConfig.getString("seed", "");
        int spawnProtection = worldConfig.getInt("spawn-protection", -1);

        Map<String, String> entries = new HashMap<>();
        entries.put("level-seed", seed);

        if (spawnProtection >= 0) {
            entries.put("spawn-protection", String.valueOf(spawnProtection));
        }

        modifyServerProperties(entries);
    }

    private World createConfiguredWorld(ConfigurationSection worldConfig, String worldName) {
        String worldType = worldConfig.getString("world-type", "normal");
        String seed = worldConfig.getString("seed", "");

        WorldCreator creator = new WorldCreator(worldName);

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

        boolean generateStructures = worldConfig.getBoolean("generate-structures", true);
        creator.generateStructures(generateStructures);

        World world = Bukkit.createWorld(creator);
        if (world != null) {
            getLogger().info("Created world '" + worldName + "' with type: " + worldType
                + (seed.isEmpty() ? "" : ", seed: " + seed));
        } else {
            getLogger().severe("Failed to create world '" + worldName + "'.");
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
    private void applyGameRules(ConfigurationSection worldConfig, World world) {
        ConfigurationSection section = worldConfig.getConfigurationSection("gamerules");
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

    private void applyPerformanceSettings(ConfigurationSection worldConfig, World world) {
        int viewDistance = worldConfig.getInt("view-distance", -1);
        if (viewDistance >= 2) {
            world.setViewDistance(viewDistance);
            getLogger().info("Set view distance to " + viewDistance);
        }

        int simulationDistance = worldConfig.getInt("simulation-distance", -1);
        if (simulationDistance >= 2) {
            world.setSimulationDistance(simulationDistance);
            getLogger().info("Set simulation distance to " + simulationDistance);
        }
    }

    private void applyTime(ConfigurationSection worldConfig, World world) {
        long time = worldConfig.getLong("time", -1);
        if (time < 0) return;

        world.setTime(time);
        getLogger().info("Set world time to " + time);
    }

    private void applyWeather(ConfigurationSection worldConfig, World world) {
        String weather = worldConfig.getString("weather", "");
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

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = parsePart(parts1, i);
            int p2 = parsePart(parts2, i);
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

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

    private void checkForUpdates() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.modrinth.com/v2/project/worldresetter-plugin/version"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    try {
                        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
                        JsonObject best = null;
                        String bestDate = null;

                        for (JsonElement elem : versions) {
                            JsonObject ver = elem.getAsJsonObject();
                            String type = ver.get("version_type").getAsString();
                            String date = ver.get("date_published").getAsString();

                            if ("release".equals(type)) {
                                if (best == null || date.compareTo(bestDate) > 0) {
                                    best = ver;
                                    bestDate = date;
                                }
                            }
                        }

                        if (best == null && versions.size() > 0) {
                            best = versions.get(0).getAsJsonObject();
                        }

                        if (best != null) {
                            String version = best.get("version_number").getAsString();
                            String downloadUrl = null;
                            String fileName = null;
                            String sha1 = null;

                            JsonArray files = best.getAsJsonArray("files");
                            if (files != null && files.size() > 0) {
                                JsonObject primaryFile = null;
                                for (JsonElement fileElem : files) {
                                    JsonObject fileObj = fileElem.getAsJsonObject();
                                    if (fileObj.get("primary").getAsBoolean()) {
                                        primaryFile = fileObj;
                                        break;
                                    }
                                }
                                if (primaryFile == null) {
                                    primaryFile = files.get(0).getAsJsonObject();
                                }
                                downloadUrl = primaryFile.get("url").getAsString();
                                fileName = primaryFile.get("filename").getAsString();
                                JsonObject hashes = primaryFile.getAsJsonObject("hashes");
                                if (hashes != null) {
                                    sha1 = hashes.get("sha1").getAsString();
                                }
                            }

                            latestVersion = version;
                            latestDownloadUrl = downloadUrl;
                            latestFileName = fileName;
                            latestSha1 = sha1;
                            getLogger().info("Latest version: " + version);
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to parse Modrinth response: " + e.getMessage());
                    }
                } else {
                    getLogger().warning("Modrinth API returned status " + response.statusCode());
                }
            })
            .exceptionally(e -> {
                getLogger().warning("Failed to check for updates: " + e.getMessage());
                return null;
            });
    }
}
