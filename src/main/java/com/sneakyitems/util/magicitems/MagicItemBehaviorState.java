package com.sneakyitems.util.magicitems;

import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.sneakyutils.util.items.ItemBehaviors;

/** Updater-local snapshot: never parse/rebind an owner or recompute a deadline. */
record MagicItemBehaviorState(String owner, Long deadline) {
    static MagicItemBehaviorState capture(PersistentDataContainer source) {
        return new MagicItemBehaviorState(
                source.has(ItemBehaviors.SOULBOUND_OWNER, PersistentDataType.STRING)
                        ? source.get(ItemBehaviors.SOULBOUND_OWNER, PersistentDataType.STRING) : null,
                source.has(ItemBehaviors.EXPIRES_AT, PersistentDataType.LONG)
                        ? source.get(ItemBehaviors.EXPIRES_AT, PersistentDataType.LONG) : null);
    }
    void restore(PersistentDataContainer target) {
        if (owner != null) target.set(ItemBehaviors.SOULBOUND_OWNER, PersistentDataType.STRING, owner);
        if (deadline != null) target.set(ItemBehaviors.EXPIRES_AT, PersistentDataType.LONG, deadline);
    }
}
