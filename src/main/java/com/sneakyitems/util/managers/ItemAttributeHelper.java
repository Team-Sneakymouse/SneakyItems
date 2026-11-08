package com.sneakyitems.util.managers;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.platform.SneakyItemsPlatform;

/** Minimal attribute-on-item helpers used by magic item build/update code. */
public final class ItemAttributeHelper {

    private static final ItemAttributeHelper INSTANCE = new ItemAttributeHelper();

    private ItemAttributeHelper() {}

    public static ItemAttributeHelper getInstance() {
        return INSTANCE;
    }

    public ItemMeta addMetaAttribute(ItemMeta meta, Attribute attribute, AttributeModifier modifier) {
        if (meta == null) throw new NullPointerException("itemMeta");
        if (attribute == null) throw new NullPointerException("attribute");
        if (modifier == null) throw new NullPointerException("modifier");
        try {
            meta.addAttributeModifier(attribute, modifier);
        } catch (IllegalArgumentException exception) {
            SneakyItemsPlatform.log("That attribute has already been applied!");
            DebugHandler.debugIllegalArgumentException(exception);
        }
        return meta;
    }

    public void addItemAttribute(ItemStack item, Attribute attribute, AttributeModifier modifier) {
        if (item == null) throw new NullPointerException("itemStack");
        item.setItemMeta(addMetaAttribute(item.getItemMeta(), attribute, modifier));
    }
}
