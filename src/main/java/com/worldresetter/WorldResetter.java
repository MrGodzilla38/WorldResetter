package com.worldresetter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class WorldResetter extends JavaPlugin implements CommandExecutor, TabCompleter {

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

        File worldFolder = new File(getServer().getWorldContainer(), "world");

        if (!worldFolder.exists()) {
            return;
        }

        getLogger().info("Deleting world folder...");

        if (deleteRecursively(worldFolder)) {
            getLogger().info("World folder deleted. A fresh world will generate.");
        } else {
            getLogger().warning("Failed to delete world folder! Check file permissions.");
        }
    }

    @Override
    public void onEnable() {
        getCommand("worldresetter").setExecutor(this);
        getCommand("worldresetter").setTabCompleter(this);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("enable", "disable");
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /wr <enable|disable>");
            return true;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        switch (args[0].toLowerCase()) {
            case "enable" -> {
                config.set("enabled", true);
                try {
                    config.save(configFile);
                } catch (Exception e) {
                    getLogger().severe("Failed to save config: " + e.getMessage());
                    sender.sendMessage("§cFailed to save config.");
                    return true;
                }
                getLogger().info("World reset ENABLED. Takes effect on next restart.");
                sender.sendMessage("[WorldResetter] World reset ENABLED. Takes effect on next restart.");
            }
            case "disable" -> {
                config.set("enabled", false);
                try {
                    config.save(configFile);
                } catch (Exception e) {
                    getLogger().severe("Failed to save config: " + e.getMessage());
                    sender.sendMessage("§cFailed to save config.");
                    return true;
                }
                getLogger().info("World reset DISABLED. Takes effect on next restart.");
                sender.sendMessage("[WorldResetter] World reset DISABLED. Takes effect on next restart.");
            }
            default -> {
                sender.sendMessage("Usage: /wr <enable|disable>");
            }
        }

        return true;
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}