package com.sneakyitems.util.compat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class CompatBasics {

    private CompatBasics() {}

    public static boolean pluginEnabled(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }

    public static Plugin getPlugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name);
    }
}
