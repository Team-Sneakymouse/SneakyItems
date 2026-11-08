package com.sneakyitems.util.magicitems;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import com.sneakyutils.util.items.ItemBehaviors;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;

/** SneakyItemsPlatform definition adapter; SneakyUtils owns persisted behavior and enforcement. */
public final class MagicItemBehaviors {
    private MagicItemBehaviors() {}
    public static void applySoulbound(ItemStack item, Player owner) { ItemBehaviors.applySoulbound(item, owner); }
    public static void applySoulbound(ItemStack item, UUID owner) { ItemBehaviors.applySoulbound(item, owner); }
    public static void applySoulboundOverlay(ItemStack item, Player owner) { ItemBehaviors.applySoulboundOverlay(item, owner); }
    public static void applySoulboundOverlay(ItemStack item, UUID owner) { ItemBehaviors.applySoulboundOverlay(item, owner); }
    public static void applyExpiration(ItemStack item, double durationMs) { ItemBehaviors.applyExpiration(item, durationMs); }
    public static void applyExpirationOverlay(ItemStack item, double durationMs) { ItemBehaviors.applyExpirationOverlay(item, durationMs); }
    public static boolean hasSoulbound(ItemStack item) { return ItemBehaviors.hasSoulbound(item); }
    public static boolean hasExpiration(ItemStack item) { return ItemBehaviors.hasExpiration(item); }
    public static String getExpiresText(long deadline) { return ItemBehaviors.getExpiresText(deadline); }
    public enum ExpirationResult { NO_UPDATE, UPDATE, EXPIRED }
    public static ExpirationResult updateExpiresLineIfNeeded(ItemStack item) { return updateExpiresLineIfNeeded(item, null); }
    public static ExpirationResult updateExpiresLineIfNeeded(ItemStack item, Player owner) {
        return ExpirationResult.valueOf(ItemBehaviors.updateExpiresLineIfNeeded(item, owner).name());
    }
	public static void applyFromData(ItemStack item, @Nullable MagicItemData data, @Nullable Player owner) {
		applyFromData(item, data, (LivingEntity) owner);
	}

	public static void applyFromData(ItemStack item, @Nullable MagicItemData data, @Nullable LivingEntity owner) {
		if (item == null || data == null)
			return;

		if (data.hasAttribute(MagicItemAttribute.SOULBOUND)
				&& Boolean.TRUE.equals(data.getAttribute(MagicItemAttribute.SOULBOUND))
				&& owner != null) {
			applySoulbound(item, owner.getUniqueId());
		}

		if (data.hasAttribute(MagicItemAttribute.EXPIRATION)) {
			double durationMs = (Double) data.getAttribute(MagicItemAttribute.EXPIRATION);
			if (durationMs > 0) {
				applyExpiration(item, durationMs);
				if (owner instanceof Player player)
					MagicItemExpirationScheduler.scheduleFromItem(player, item);
			}
		}
	}

	public static void applyConjureOverlay(ItemStack item, boolean soulbound, double expirationMs,
			@Nullable LivingEntity owner) {
		if (item == null)
			return;

		if (soulbound && owner != null)
			applySoulboundOverlay(item, owner.getUniqueId());
		if (expirationMs > 0) {
			applyExpirationOverlay(item, expirationMs);
			if (owner instanceof Player player)
				MagicItemExpirationScheduler.scheduleFromItem(player, item);
		}
	}

	public static void applyMissingFromRegistry(ItemStack item, @Nullable Player owner) {
		if (item == null || owner == null || !item.hasItemMeta())
			return;

		String internalName = item.getItemMeta().getPersistentDataContainer()
				.get(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING);
		if (internalName == null)
			return;

		MagicItem magicItem = MagicItems.getMagicItemByInternalName(internalName);
		if (magicItem == null)
			return;

		applyFromData(item, magicItem.getMagicItemData(), owner);
		MagicItemExpirationScheduler.scheduleFromItem(owner, item);
	}

}
