package com.sneakyitems.util.itemreader;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataContainer;

import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys;
import com.sneakyitems.util.magicitems.MagicItemData;
import com.sneakyitems.util.magicitems.MagicItemTags;

import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.TAGS;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.PERMANENT_DATA;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.PERSISTENT_DATA;

public class PersistentDataHandler {

    private static final String REGULAR_PREFIX = "magicspelldata_";
    private static final String PERMANENT_PREFIX = "magicspellpermanentdata_";
    private static NamespacedKey nameKey() {
        return MagicItemBehaviorKeys.magicItem();
    }

    public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
        meta.getPersistentDataContainer().set(nameKey(), PersistentDataType.STRING, config.getName());
        data.setAttribute(MAGIC_ITEM_NAME, config.getName());

        Map<String, String> pdcMap = parseMap(config, "pdc", "persistent-data", "persistent_data");
        if (!pdcMap.isEmpty()) {
            data.setAttribute(PERSISTENT_DATA, pdcMap);
            for (Map.Entry<String, String> entry : pdcMap.entrySet()) {
                meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.key(REGULAR_PREFIX + entry.getKey()), PersistentDataType.STRING, entry.getValue());
            }
        }

        Map<String, String> permanentPdcMap = parseMap(config, "pdc-permanent", "pdc_permanent", "permanent-data", "permanent_data", "persistent-data-permanent", "persistent_data-permanent");
        if (!permanentPdcMap.isEmpty()) {
            data.setAttribute(PERMANENT_DATA, permanentPdcMap);
            for (Map.Entry<String, String> entry : permanentPdcMap.entrySet()) {
                meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.key(PERMANENT_PREFIX + entry.getKey()), PersistentDataType.STRING, entry.getValue());
            }
        }

        Set<String> tags = parseTags(config);
        if (!tags.isEmpty()) {
            data.setAttribute(TAGS, tags);
            MagicItemTags.write(meta, tags);
        }
    }

    private static Set<String> parseTags(ConfigurationSection config) {
        if (config.isList("tags")) {
            return MagicItemTags.fromConfigList(config.getStringList("tags"));
        }
        if (config.isString("tags")) {
            return MagicItemTags.deserialize(config.getString("tags"));
        }
        return Set.of();
    }

    private static Map<String, String> parseMap(ConfigurationSection config, String... keys) {
        for (String key : keys) {
            if (config.isConfigurationSection(key)) {
                ConfigurationSection section = config.getConfigurationSection(key);
                Map<String, String> map = new HashMap<>();
                for (String k : section.getKeys(false)) {
                    map.put(k, section.getString(k));
                }
                return map;
            } else if (config.isList(key)) {
                Map<String, String> map = new HashMap<>();
                for (String line : config.getStringList(key)) {
                    String[] split = line.split(":", 2);
                    if (split.length == 2) map.put(split[0], split[1]);
                }
                return map;
            }
        }
        return new HashMap<>();
    }

    public static void processItemMeta(ItemMeta meta, MagicItemData data) {
        if (data.hasAttribute(MAGIC_ITEM_NAME)) {
            meta.getPersistentDataContainer().set(nameKey(), PersistentDataType.STRING, (String) data.getAttribute(MAGIC_ITEM_NAME));
        }

        if (data.hasAttribute(PERSISTENT_DATA)) {
            Map<String, String> pdcMap = data.getAttribute(PERSISTENT_DATA);
            for (Map.Entry<String, String> entry : pdcMap.entrySet()) {
                meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.key(REGULAR_PREFIX + entry.getKey()), PersistentDataType.STRING, entry.getValue());
            }
        }

        if (data.hasAttribute(PERMANENT_DATA)) {
            Map<String, String> permanentPdcMap = data.getAttribute(PERMANENT_DATA);
            for (Map.Entry<String, String> entry : permanentPdcMap.entrySet()) {
                meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.key(PERMANENT_PREFIX + entry.getKey()), PersistentDataType.STRING, entry.getValue());
            }
        }

        if (data.hasAttribute(TAGS)) {
            MagicItemTags.write(meta, (Set<String>) data.getAttribute(TAGS));
        }
    }

    public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (container.has(nameKey(), PersistentDataType.STRING)) {
            String magicitemName = container.get(nameKey(), PersistentDataType.STRING);
            data.setAttribute(MAGIC_ITEM_NAME, magicitemName);
        }

        Map<String, String> pdcMap = new HashMap<>();
        Map<String, String> permanentPdcMap = new HashMap<>();

        for (NamespacedKey key : container.getKeys()) {
            if (!key.getNamespace().equals(MagicItemBehaviorKeys.getPdcNamespace())) continue;
            
            String keyStr = key.getKey();
            if (keyStr.startsWith(REGULAR_PREFIX)) {
                pdcMap.put(keyStr.substring(REGULAR_PREFIX.length()), container.get(key, PersistentDataType.STRING));
            } else if (keyStr.startsWith(PERMANENT_PREFIX)) {
                permanentPdcMap.put(keyStr.substring(PERMANENT_PREFIX.length()), container.get(key, PersistentDataType.STRING));
            }
        }

        if (!pdcMap.isEmpty()) data.setAttribute(PERSISTENT_DATA, pdcMap);
        if (!permanentPdcMap.isEmpty()) data.setAttribute(PERMANENT_DATA, permanentPdcMap);

        Set<String> tags = MagicItemTags.readFromMeta(meta);
        if (!tags.isEmpty()) data.setAttribute(TAGS, tags);
    }

}
