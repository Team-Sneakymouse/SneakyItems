package com.sneakyitems.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.handlers.PotionEffectHandler;
import com.sneakyitems.platform.SneakyItemsPlatform;
import com.sneakyutils.util.ColorUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Item/YAML parsing helpers ported from SneakyItemsPlatform {@code Util} (subset). */
public final class ItemParseUtil {

    private static final Pattern WEIRD_HEX_PATTERN =
            Pattern.compile("[&§]x(([&§][0-9a-f]){6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_PATTERN = Pattern.compile("[&§]([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_PATTERN = Pattern.compile("[&§](#[0-9a-f]{6})", Pattern.CASE_INSENSITIVE);

    private ItemParseUtil() {}

    public static Material getMaterial(String name) {
        return Material.matchMaterial(name);
    }

    public static PotionEffect buildPotionEffect(String effectString) {
        String[] data = effectString.split(" ");
        PotionEffectType t = getPotionEffectType(data[0]);

        if (t == null) {
            SneakyItemsPlatform.error('\'' + data[0] + "' could not be connected to a potion effect type");
            return null;
        }

        int level = 0;
        if (data.length > 1) {
            try {
                level = Integer.parseInt(data[1]);
            } catch (NumberFormatException ex) {
                DebugHandler.debugNumberFormat(ex);
            }
        }

        int duration = 600;
        if (data.length > 2) {
            try {
                duration = Integer.parseInt(data[2]);
            } catch (NumberFormatException ex) {
                DebugHandler.debugNumberFormat(ex);
            }
        }

        boolean ambient = data.length > 3 && (BooleanUtils.isYes(data[3]) || data[3].equalsIgnoreCase("ambient"));
        boolean particles = data.length > 4 && (BooleanUtils.isYes(data[4]) || data[4].equalsIgnoreCase("particles"));
        boolean icon = data.length > 5 && (BooleanUtils.isYes(data[5]) || data[5].equalsIgnoreCase("icon"));

        return new PotionEffect(t, duration, level, ambient, particles, icon);
    }

    public static PotionEffect buildSuspiciousStewPotionEffect(String effectString) {
        String[] data = effectString.split(" ");
        PotionEffectType t = getPotionEffectType(data[0]);

        if (t == null) {
            SneakyItemsPlatform.error('\'' + data[0] + "' could not be connected to a potion effect type");
            return null;
        }

        int duration = 600;
        if (data.length > 1) {
            try {
                duration = Integer.parseInt(data[1]);
            } catch (NumberFormatException ex) {
                DebugHandler.debugNumberFormat(ex);
            }
        }
        return new PotionEffect(t, duration, 0, true);
    }

    public static Color[] getColorsFromString(String str) {
        int[] colors = new int[]{0xFF0000};
        String[] args = str.replace(" ", "").split(",");
        if (args.length > 0) {
            colors = new int[args.length];
            for (int i = 0; i < colors.length; i++) {
                try {
                    colors[i] = Integer.parseInt(args[i], 16);
                } catch (NumberFormatException e) {
                    colors[i] = 0;
                }
            }
        }

        Color[] c = new Color[colors.length];
        for (int i = 0; i < colors.length; i++) {
            c[i] = Color.fromRGB(colors[i]);
        }
        return c;
    }

    public static PotionEffectType getPotionEffectType(String type) {
        return PotionEffectHandler.getPotionEffectType(type);
    }

    public static String getLegacyFromComponent(Component component) {
        if (component == null) return "";
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static Component getMiniMessage(String input) {
        if (input == null) return null;
        if (input.isEmpty()) return Component.empty();

        Component component = MiniMessage.miniMessage().deserialize(getMiniMessageFromLegacy(input));
        return component.decoration(TextDecoration.ITALIC, component.hasDecoration(TextDecoration.ITALIC));
    }

    public static String getStringFromComponent(Component component) {
        return component == null ? "" : MiniMessage.miniMessage().serialize(component);
    }

    public static String colorize(String string) {
        return ColorUtil.colorize(string);
    }

    public static String getMiniMessageFromLegacy(String input) {
        StringBuilder builder = new StringBuilder();

        Matcher matcher = WEIRD_HEX_PATTERN.matcher(input);
        while (matcher.find()) {
            matcher.appendReplacement(builder, "<#" + matcher.group(1).replaceAll("[§&]", "") + ">");
        }
        matcher.appendTail(builder);

        matcher = HEX_PATTERN.matcher(builder.toString());
        builder.setLength(0);
        while (matcher.find()) {
            matcher.appendReplacement(builder, "<" + matcher.group(1) + ">");
        }
        matcher.appendTail(builder);

        matcher = COLOR_PATTERN.matcher(builder.toString());
        builder.setLength(0);
        while (matcher.find()) {
            String tag = ColorUtil.miniMessageTagFor(matcher.group(1).charAt(0));
            if (tag == null) continue;
            matcher.appendReplacement(builder, "<" + tag + ">");
        }
        matcher.appendTail(builder);

        return builder.toString();
    }

    public static String getPlainString(Component component) {
        if (component == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
