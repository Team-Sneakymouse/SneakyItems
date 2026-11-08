package com.sneakyitems.util.itemreader;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.configuration.ConfigurationSection;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.util.magicitems.MagicItemData;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.TEXTURE;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.SIGNATURE;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.SKULL_OWNER;

public class SkullHandler {

	private static final String SKULL_OWNER_CONFIG_NAME = SKULL_OWNER.toString();
	private static final String UUID_CONFIG_NAME = MagicItemAttribute.UUID.toString();
	private static final String SIGNATURE_CONFIG_NAME = SIGNATURE.toString();
	private static final String TEXTURE_CONFIG_NAME = TEXTURE.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SkullMeta skullMeta)) return;

		String signature = null, skullOwner = null, texture = null;
		UUID uuid = null;

		if (config.isString(UUID_CONFIG_NAME)) {
			String uuidString = config.getString(UUID_CONFIG_NAME, "");

			try {
				uuid = UUID.fromString(uuidString);
			} catch (IllegalArgumentException e) {
				DebugHandler.debugIllegalArgumentException(e);
			}

			data.setAttribute(MagicItemAttribute.UUID, uuid);
		}

		if (config.isString(TEXTURE_CONFIG_NAME)) {
			texture = config.getString(TEXTURE_CONFIG_NAME);
			data.setAttribute(TEXTURE, texture);
		}

		if (config.isString(SIGNATURE_CONFIG_NAME)) {
			signature = config.getString(SIGNATURE_CONFIG_NAME);
			data.setAttribute(SIGNATURE, signature);
		}

		if (config.isString(SKULL_OWNER_CONFIG_NAME)) {
			skullOwner = config.getString(SKULL_OWNER_CONFIG_NAME);
			data.setAttribute(SKULL_OWNER, skullOwner);
		}

		if ((uuid != null || skullOwner != null) && texture != null) {
			PlayerProfile profile = Bukkit.createProfile(uuid, skullOwner);

			profile.setProperty(new ProfileProperty("textures", texture, signature));
			skullMeta.setPlayerProfile(profile);
		}
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SkullMeta)) return;

		String signature = null, skullOwner = null, texture = null;
		UUID uuid = null;

		if (data.hasAttribute(SKULL_OWNER)) skullOwner = (String) data.getAttribute(SKULL_OWNER);
		if (data.hasAttribute(SIGNATURE)) signature = (String) data.getAttribute(SIGNATURE);
		if (data.hasAttribute(TEXTURE)) texture = (String) data.getAttribute(TEXTURE);

		if (data.hasAttribute(MagicItemAttribute.UUID)) {
			try {
				uuid = UUID.fromString((String) data.getAttribute(MagicItemAttribute.UUID));
			} catch (IllegalArgumentException e) {
				DebugHandler.debugIllegalArgumentException(e);
			}
		}

		if ((uuid != null || skullOwner != null) && texture != null) {
			PlayerProfile profile = Bukkit.createProfile(uuid, skullOwner);

			profile.setProperty(new ProfileProperty("textures", texture, signature));
			((SkullMeta) meta).setPlayerProfile(profile);
		}
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SkullMeta)) return;

		PlayerProfile profile = ((SkullMeta) meta).getPlayerProfile();
		if (profile == null) return;

		UUID id = profile.getId();
		if (id != null) data.setAttribute(MagicItemAttribute.UUID, id.toString());

		String name = profile.getName();
		if (name != null) data.setAttribute(SKULL_OWNER, name);

		if (profile.hasTextures()) {
			for (ProfileProperty property : profile.getProperties()) {
				if (property.getName().equals("textures")) {
					data.setAttribute(TEXTURE, property.getValue());
					if (property.isSigned()) data.setAttribute(SIGNATURE, property.getSignature());
					break;
				}
			}
		}
	}
	
}
