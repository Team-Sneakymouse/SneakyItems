package com.sneakyitems.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after SneakyItems finishes (re)loading definitions and legacy tag mappings. */
public class SneakyItemsLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public SneakyItemsLoadedEvent() {
        super(false);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
