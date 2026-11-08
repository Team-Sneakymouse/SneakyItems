package com.sneakyitems.util.items;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent;

/**
 * Optional SCM character-load scan for expired items.
 * Uses typed SCM imports ({@code compileOnly} + soft runtime dep); no reflection.
 */
public final class CharacterExpirationHook implements Listener {

	private final Plugin owner;
	private final ItemExpirationListener expiration;
	private Listener registration;

	public CharacterExpirationHook(Plugin owner, ItemExpirationListener expiration) {
		this.owner = owner;
		this.expiration = expiration;
		ensureRegistered(Bukkit.getPluginManager().getPlugin("SneakyCharacterManager"));
	}

	@EventHandler
	public void onEnable(PluginEnableEvent event) {
		ensureRegistered(event.getPlugin());
	}

	@EventHandler
	public void onDisable(PluginDisableEvent event) {
		if (event.getPlugin().getName().equals("SneakyCharacterManager") && registration != null) {
			HandlerList.unregisterAll(registration);
			registration = null;
		}
	}

	private void ensureRegistered(Plugin scm) {
		if (registration != null || scm == null || !scm.isEnabled()
				|| !scm.getName().equals("SneakyCharacterManager")) {
			return;
		}
		Listener listener = new Listener() {
			@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
			public void onCharacterLoad(LoadCharacterEvent event) {
				Player player = event.getPlayer();
				Bukkit.getScheduler().runTask(owner, () -> {
					if (owner.isEnabled() && scm.isEnabled() && player.isOnline() && !event.isCancelled()) {
						expiration.joinOrLoadCharacter(player);
					}
				});
			}
		};
		Bukkit.getPluginManager().registerEvents(listener, owner);
		registration = listener;
	}
}
