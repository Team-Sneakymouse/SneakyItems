package com.sneakyitems.legacy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * TEMPORARY (added 2026-11-20, remove after about 2026-12-20).
 *
 * <p>SneakyPrototypeKit items ignore {@code ITEM_MODEL} and {@code CUSTOM_MODEL_DATA}, so the normal updater
 * never touches their look. Copies made before the integer custom model data was retired still carry the old
 * integer, which the pack no longer resolves. This swaps that integer for the {@code item_model} it used to
 * show, using the old pack's table bundled at {@value #RESOURCE}.
 *
 * <p>To remove: delete this class, {@code src/main/resources/legacy/prototypekit-item-models.csv},
 * {@code PrototypeKitLegacyModelsTest}, and the one call in {@code MagicItemUpdater}.
 */
public final class PrototypeKitLegacyModels {

    /** Covers {@code item-prototypekit}, {@code item-prototypekit-test} and {@code item-prototypekit-created-*}. */
    private static final String NAME_PREFIX = "item-prototypekit";
    private static final String RESOURCE = "/legacy/prototypekit-item-models.csv";

    private record Route(int maxExclusive, NamespacedKey model) {}

    private static volatile Map<Material, NavigableMap<Integer, Route>> table;

    private PrototypeKitLegacyModels() {}

    /**
     * Returns a copy of {@code stack} with its legacy integer custom model data replaced by the matching
     * item model, or {@code stack} itself when it is not a prototype-kit item, already has an item model,
     * has no plain integer custom model data, or the integer had no model in the old pack.
     */
    public static ItemStack migrate(ItemStack stack, String magicItemName) {
        if (stack == null || magicItemName == null || !magicItemName.startsWith(NAME_PREFIX)) return stack;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.hasItemModel() || !meta.hasCustomModelDataComponent()) return stack;

        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        List<Float> floats = cmd.getFloats();
        if (floats.size() != 1 || !cmd.getStrings().isEmpty() || !cmd.getFlags().isEmpty()
                || !cmd.getColors().isEmpty()) return stack;
        float value = floats.get(0);
        if (value != Math.rint(value)) return stack;

        NamespacedKey model = lookup(stack.getType(), (int) value);
        if (model == null) return stack;

        ItemStack copy = stack.clone();
        copy.editMeta(m -> {
            m.setItemModel(model);
            m.setCustomModelDataComponent(null);
        });
        return copy;
    }

    static NamespacedKey lookup(Material carrier, int cmd) {
        NavigableMap<Integer, Route> routes = table().get(carrier);
        if (routes == null) return null;
        Map.Entry<Integer, Route> entry = routes.floorEntry(cmd);
        if (entry == null || cmd >= entry.getValue().maxExclusive()) return null;
        return entry.getValue().model();
    }

    private static Map<Material, NavigableMap<Integer, Route>> table() {
        Map<Material, NavigableMap<Integer, Route>> t = table;
        if (t == null) {
            synchronized (PrototypeKitLegacyModels.class) {
                t = table;
                if (t == null) table = t = load();
            }
        }
        return t;
    }

    private static Map<Material, NavigableMap<Integer, Route>> load() {
        Map<Material, NavigableMap<Integer, Route>> out = new EnumMap<>(Material.class);
        InputStream in = PrototypeKitLegacyModels.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            Logger.getLogger("SneakyItems").warning("Missing " + RESOURCE + "; prototype-kit legacy models disabled.");
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split(",", -1);
                if (cols.length != 4) continue;
                Material carrier = Material.matchMaterial(cols[0]);
                NamespacedKey model = NamespacedKey.fromString(cols[3]);
                if (carrier == null || model == null) continue;
                int max = cols[2].isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(cols[2]);
                out.computeIfAbsent(carrier, k -> new TreeMap<>()).put(Integer.parseInt(cols[1]), new Route(max, model));
            }
        } catch (IOException | NumberFormatException e) {
            Logger.getLogger("SneakyItems").warning("Could not read " + RESOURCE + ": " + e);
        }
        return out;
    }
}
