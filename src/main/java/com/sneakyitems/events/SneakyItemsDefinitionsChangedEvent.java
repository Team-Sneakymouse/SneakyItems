package com.sneakyitems.events;

import java.util.Set;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread once a burst of magic-item registrations has settled (for example after
 * {@code /ms reload}). Lists the internal names that were added, changed, or removed. SneakyItems
 * queues its own re-check of loaded item entities, item displays and online players right after.
 */
public class SneakyItemsDefinitionsChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<String> changedNames;

    public SneakyItemsDefinitionsChangedEvent(@NotNull Set<String> changedNames) {
        super(false);
        this.changedNames = Set.copyOf(changedNames);
    }

    /** Added, changed, or removed internal names. */
    public @NotNull Set<String> getChangedNames() {
        return changedNames;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
