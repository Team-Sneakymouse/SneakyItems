package com.sneakyitems.util;

import java.util.List;

/** Text helpers used by magic item serialization. */
public final class TxtUtil {

    private TxtUtil() {}

    public static String getStringNumber(double number, int places) {
        if (places < 0) return number + "";
        if (places == 0) return (int) Math.round(number) + "";
        int x = (int) Math.pow(10, places);
        return ((double) Math.round(number * x) / x) + "";
    }

    public static String escapeJSON(String str) {
        if (str == null) return "";
        return str.replaceAll("[\"\\\\]", "\\\\$0");
    }

    public static String getPossessiveName(String name) {
        name = name.trim();
        return name + "'" + (name.endsWith("s") ? "" : "s");
    }

    public static List<String> tabCompleteSpellName(org.bukkit.command.CommandSender sender, String partial) {
        return List.of();
    }
}
