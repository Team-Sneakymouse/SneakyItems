package com.sneakyitems.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys;

/** Runtime bridge for ported magic-item code (replaces SneakyItemsPlatform statics). */
public final class SneakyItemsPlatform {

    public static JavaPlugin plugin;
    private static boolean debug;
    private static boolean debugNull = true;
    private static boolean debugNumberFormat = true;
    private static boolean checkItemPersistentData = true;
    private static boolean hideMagicItemTooltips;
    private static boolean enableUpdateItemData = true;
    private static @NotNull String defaultPdcNamespace = "magicspells";

    private SneakyItemsPlatform() {}

    public static void init(@NotNull JavaPlugin sneakyItems) {
        plugin = sneakyItems;
        MagicItemBehaviorKeys.setPdcNamespace(defaultPdcNamespace);
    }

    public static @NotNull JavaPlugin getInstance() {
        return plugin;
    }

    public static @NotNull String getPdcNamespace() {
        return defaultPdcNamespace;
    }

    public static void setDefaultPdcNamespace(@NotNull String namespace) {
        defaultPdcNamespace = namespace;
        MagicItemBehaviorKeys.setPdcNamespace(namespace);
    }

    public static void applyOwnerNamespace(@NotNull String namespace) {
        MagicItemBehaviorKeys.setPdcNamespace(namespace);
    }

    public static void setDebug(boolean value) {
        debug = value;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static boolean isDebugNull() {
        return debugNull;
    }

    public static boolean isDebugNumberFormat() {
        return debugNumberFormat;
    }

    public static void setCheckItemPersistentData(boolean value) {
        checkItemPersistentData = value;
    }

    public static boolean checkItemPersistentData() {
        return checkItemPersistentData;
    }

    public static void setHideMagicItemTooltips(boolean value) {
        hideMagicItemTooltips = value;
    }

    public static boolean hideMagicItemTooltips() {
        return hideMagicItemTooltips;
    }

    public static void setEnableUpdateItemData(boolean value) {
        enableUpdateItemData = value;
    }

    public static boolean enableUpdateItemData() {
        return enableUpdateItemData;
    }

    public static void log(String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
        }
    }

    public static void error(String message) {
        if (plugin != null) {
            plugin.getLogger().severe(message);
        }
    }

    public static void scheduleDelayedTask(@NotNull Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static @Nullable BukkitTask scheduleSyncRepeating(@NotNull Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }
}
