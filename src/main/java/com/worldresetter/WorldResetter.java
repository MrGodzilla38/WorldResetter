package com.worldresetter;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class WorldResetter extends JavaPlugin {

    @Override
    public void onLoad() {
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
