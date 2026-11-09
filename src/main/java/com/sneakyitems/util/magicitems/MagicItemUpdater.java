package com.sneakyitems.util.magicitems;

import java.io.File;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.configuration.file.YamlConfiguration;

import net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent;

import org.jetbrains.annotations.Nullable;

import com.sneakyitems.platform.SneakyItemsPlatform;
import com.sneakyitems.api.SneakyItemsHooks;
import com.sneakyutils.util.io.AtomicFiles;
import com.sneakyitems.util.compat.CompatBasics;
import com.sneakyitems.util.performance.PerformanceDiagnostics;
import com.sneakyitems.events.SneakyItemsLoadedEvent;
import com.sneakyitems.util.magicitems.MagicItemBehaviors.ExpirationResult;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;

import static com.sneakyitems.platform.SneakyItemsPlatform.setCheckItemPersistentData;
import static com.sneakyitems.util.magicitems.MagicItems.getMagicItems;

public class MagicItemUpdater {
    // This file is my own person nightmare of not knowing how multi-threading works
    // There is a chance that this may crash if you have too many players,
    // characters, or vaults
    private static int itemUpdateCount = 0;
    private static boolean countItemUpdates = false;

    private static MagicItemUpdater.PersistentDataUpdater persistentDataUpdater = new PersistentDataUpdater();

    private static final Map<String, MagicItem> magicItems = getMagicItems();
    private static final Map<String, MagicItemData> magicItemsCache = new HashMap<>();
    private static final NamespacedKey NAME_KEY = MagicItemBehaviorKeys.magicItem();
    private static final NamespacedKey VERSION_KEY = MagicItemBehaviorKeys.magicItemVersion();
    private static final NamespacedKey SOULBOUND_OWNER_KEY = MagicItemBehaviorKeys.soulboundOwner();
    /** Above 0 while a rebuilt container item's own contents are processed: their items are counted, not timed. */
    private static int containerDepth = 0;

    // Active updating of items on login/inventory open
    public static class PersistentDataUpdater implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onSneakyItemsLoaded(SneakyItemsLoadedEvent event) {
            if (!SneakyItemsPlatform.enableUpdateItemData())
                return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                joinOrLoadCharacter(player);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onJoin(PlayerJoinEvent event) {
            joinOrLoadCharacter(event.getPlayer());
        }

        public void joinOrLoadCharacter(Player player) {
            if (!shouldProcessItems())
                return;
            long t = PerformanceDiagnostics.start();
            try {
                processInventory(player.getInventory(), player);
                PlayerInventory inv = player.getInventory();
                ItemStack[] armor = inv.getArmorContents();
                processItemStacks(armor, player);
                inv.setArmorContents(armor);
                processInventory(player.getEnderChest(), player);
            } finally {
                PerformanceDiagnostics.end("player.load-inventories", t);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInvOpen(InventoryOpenEvent event) {
            if (!shouldProcessItems())
                return;
            long t = PerformanceDiagnostics.start();
            try {
                if (isBagOfHoldingGui(event.getInventory()))
                    return;
                Player player = event.getPlayer() instanceof Player p ? p : null;
                processInventory(event.getInventory(), player);
            } finally {
                PerformanceDiagnostics.end("event.inventory-open", t);
            }
        }
    }

    public static class ChunkMagicItemListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onSneakyItemsLoaded(SneakyItemsLoadedEvent event) {
            if (!shouldProcessItems())
                return;

            final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
            for (World world : Bukkit.getWorlds()) {
                Collections.addAll(chunks, world.getLoadedChunks());
            }

            if (chunks.isEmpty())
                return;

            final int chunksPerTick = 5;
            new BukkitRunnable() {
                @Override
                public void run() {
                    long t = PerformanceDiagnostics.start();
                    try {
                        int processed = 0;
                        while (processed++ < chunksPerTick && !chunks.isEmpty()) {
                            Chunk chunk = chunks.poll();
                            if (chunk == null || !chunk.isLoaded())
                                continue;

                            for (Entity entity : chunk.getEntities()) {
                                processGroundEntityIfNeeded(entity);
                            }
                        }

                        if (chunks.isEmpty())
                            cancel();
                    } finally {
                        PerformanceDiagnostics.end("task.loaded-chunk-scan", t);
                    }
                }
            }.runTaskTimer(SneakyItemsPlatform.getInstance(), 1L, 1L);
        }

        @EventHandler(ignoreCancelled = true)
        public void onChunkLoad(ChunkLoadEvent event) {
            if (!shouldProcessItems())
                return;

            long t = PerformanceDiagnostics.start();
            try {
                for (Entity entity : event.getChunk().getEntities()) {
                    processGroundEntityIfNeeded(entity);
                }
            } finally {
                PerformanceDiagnostics.end("event.chunk-load", t);
            }
        }

        /**
         * Tags then updates magic items that appear in the world (chest spills, explosions, etc.).
         * Skips player inventory drops: those already ran through inventory paths,
         * and Item.getThrower() is set for Q/death drops from a player.
         */
        @EventHandler(ignoreCancelled = true)
        public void onItemSpawn(ItemSpawnEvent event) {
            if (!shouldProcessItems())
                return;

            Item item = event.getEntity();
            if (item.getThrower() != null)
                return;

            long t = PerformanceDiagnostics.start();
            try {
                processItemEntityIfNeeded(item);
            } finally {
                PerformanceDiagnostics.end("event.item-spawn", t);
            }
        }

        private static void processGroundEntityIfNeeded(Entity entity) {
            if (entity instanceof Item item) {
                processItemEntityIfNeeded(item);
            } else if (entity instanceof ItemDisplay itemDisplay) {
                ItemStack stack = itemDisplay.getItemStack();
                ItemStack updated = processItemStack(stack, null);
                if (updated != stack)
                    itemDisplay.setItemStack(updated);
            }
        }

        private static void processItemEntityIfNeeded(Item item) {
            ItemStack stack = item.getItemStack();
            ItemStack updated = processItemStack(stack, null);
            if (updated != stack)
                item.setItemStack(updated);
        }
    }

    public static class CharacterPersistentDataUpdater implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        private void onCharacterLoad(LoadCharacterEvent event) {
            if (!event.isCancelled()) {
                SneakyItemsPlatform.scheduleDelayedTask(() -> {
                    persistentDataUpdater.joinOrLoadCharacter(event.getPlayer());
                }, 1);
            }
        }
    }

    private static boolean shouldProcessItems() {
        return SneakyItemsPlatform.enableUpdateItemData() || MagicItemTagging.hasMappings();
    }

    private static boolean isBagOfHoldingGui(Inventory inventory) {
        if (inventory == null)
            return false;
        var holder = inventory.getHolder();
        if (holder == null)
            return false;
        return holder.getClass().getName().startsWith("com.sneakybagofholding.gui.BagInventoryHolder");
    }

    /** Tag (if mappings exist) then update a player's inventories. Used by ItemTagSpell cast. */
    public static void processPlayerInventories(Player player) {
        if (player == null)
            return;
        long t = PerformanceDiagnostics.start();
        try {
            processInventory(player.getInventory(), player);
            PlayerInventory inv = player.getInventory();
            ItemStack[] armor = inv.getArmorContents();
            processItemStacks(armor, player);
            inv.setArmorContents(armor);
            processInventory(player.getEnderChest(), player);
        } finally {
            PerformanceDiagnostics.end("player.process-inventories", t);
        }
    }

    public static void processInventory(Inventory inv, @Nullable Player player) {
        if (inv == null || SneakyItemsHooks.isMenuInventory(inv) || isBagOfHoldingGui(inv))
            return;
        sampleLevels();
        long t = PerformanceDiagnostics.start();
        try {
            long phase = PerformanceDiagnostics.start();
            ItemStack[] contents = inv.getContents();
            PerformanceDiagnostics.end("inventory.read-contents", phase);
            PerformanceDiagnostics.count("inventory.slots", contents.length);
            long items = PerformanceDiagnostics.start();
            processItemStacks(contents, player);
            PerformanceDiagnostics.end("inventory.items", items);
            long write = PerformanceDiagnostics.start();
            inv.setContents(contents);
            PerformanceDiagnostics.end("inventory.write-contents", write);
        } finally {
            PerformanceDiagnostics.end("inventory.process", t);
        }
    }

    /** The plugin's single sampleDue site: about once a second while inventories are being processed. */
    private static void sampleLevels() {
        if (!PerformanceDiagnostics.sampleDue())
            return;
        PerformanceDiagnostics.level("item-data.cache-size", MagicItems.itemStackCacheSize());
        PerformanceDiagnostics.level("tag.mappings", MagicItemTagging.mappingCount());
        PerformanceDiagnostics.level("magic-items.registered", magicItems.size());
    }

    public static void processItemStacks(ItemStack[] items, @Nullable Player player) {
        if (items == null)
            return;
        for (int i = 0; i < items.length; i++) {
            ItemStack itemStack = items[i];
            if (itemStack == null)
                continue;
            items[i] = processItemStack(itemStack, player);
        }
    }

    /**
     * Applies ItemTagSpell mappings first (PDC name), then rebuilds from the magic-item definition
     * when updates are enabled or a tag was just applied.
     */
    public static ItemStack processItemStack(ItemStack itemStack, @Nullable Player player) {
        if (itemStack == null)
            return null;

        PerformanceDiagnostics.count("items.processed");
        boolean timed = containerDepth == 0;
        // One section per item: each item is checked against every legacy mapping (about 1.5k), so the item, not a
        // single lookup, is the unit of work (a section costs ~40 ns while recording). Ended on every path: this is
        // public API that can run without an enclosing section.
        long tag = timed ? PerformanceDiagnostics.start() : 0;
        MagicItemTagging.TagResult tagResult;
        try {
            tagResult = MagicItemTagging.applyTagIfNeeded(itemStack);
        } finally {
            PerformanceDiagnostics.end("item.tag", tag);
        }
        ItemStack stack = tagResult.stack();
        boolean tagged = tagResult.tagged();

        if (tagged || SneakyItemsPlatform.enableUpdateItemData()) {
            long update = timed ? PerformanceDiagnostics.start() : 0;
            try {
                ItemStack updated = updateMagicItemItemStackIfNeeded(stack);
                if (updated != stack)
                    stack = updated;
            } finally {
                PerformanceDiagnostics.end("item.update", update);
            }
        }

        if (tagged) {
            String internalName = tagResult.internalName();
            MagicItem updateSource = MagicItems.getMagicItemByInternalName(internalName);
            if (updateSource == null)
                updateSource = tagResult.magicItem();

            ItemMeta meta = stack.getItemMeta();
            if (meta != null && internalName != null) {
                meta.getPersistentDataContainer().set(NAME_KEY, PersistentDataType.STRING, internalName);
                stack.setItemMeta(meta);
            }

            if (updateSource != null) {
                MagicItemBehaviors.applyFromData(stack, updateSource.getMagicItemData(), player);
                if (player != null)
                    MagicItemExpirationScheduler.scheduleFromItem(player, stack);
            }
        } else if (player != null && SneakyItemsPlatform.enableUpdateItemData()) {
            ExpirationResult result = MagicItemBehaviors.updateExpiresLineIfNeeded(stack, player);
            if (result == ExpirationResult.EXPIRED)
                return null;
        }

        return stack;
    }

    /**
     * If the stack is a magic item whose stored data differs from the current definition, returns an updated
     * stack from {@link #updateItem}; otherwise returns the same reference.
     */
    private static ItemStack updateMagicItemItemStackIfNeeded(ItemStack itemStack) {
        if (itemStack == null)
            return null;

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null)
            return itemStack;

        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (container.has(new NamespacedKey("sneakybagofholding", "gui_display"), PersistentDataType.BYTE)) {
            return itemStack;
        }

        if (container.has(MagicItemBehaviorKeys.key("transmogrifyoption"),
                PersistentDataType.STRING)) {
            return itemStack;
        }

        String magicitemName = null;

        if (container.has(NAME_KEY, PersistentDataType.STRING)) {
            magicitemName = container.get(NAME_KEY, PersistentDataType.STRING);
        }

        if (magicitemName == null || !magicItems.containsKey(magicitemName))
            return itemStack;

        MagicItem magicItem = magicItems.get(magicitemName);
        if (magicItem == null)
            return itemStack;

        PerformanceDiagnostics.count("items.magic");
        long definitionVersion = magicItem.getDefinitionVersion();
        Long stampedVersion = container.get(VERSION_KEY, PersistentDataType.LONG);
        if (stampedVersion != null && stampedVersion == definitionVersion) {
            PerformanceDiagnostics.count("items.version-current");
            return itemStack;
        }

        // Ignore transient soulbound ownership when determining whether a magic item needs updating.
        ItemStack compareStack = itemStack;
        if (container.has(SOULBOUND_OWNER_KEY, PersistentDataType.STRING)) {
            compareStack = itemStack.clone();
            ItemMeta compareMeta = compareStack.getItemMeta();
            if (compareMeta != null) {
                compareMeta.getPersistentDataContainer().remove(SOULBOUND_OWNER_KEY);
                compareStack.setItemMeta(compareMeta);
            }
        }

        MagicItemData stackData = MagicItems.getMagicItemDataFromItemStack(compareStack);
        MagicItemData magicItemData = magicItem.getMagicItemData();

        if (magicItemData == null || stackData == null)
            return itemStack;
        if (MagicItems.matches(magicItemData, compareStack, false)) {
            PerformanceDiagnostics.count("items.stamped");
            return MagicItemDefinitionVersions.ensureStamped(itemStack, definitionVersion);
        }

        long t = containerDepth == 0 ? PerformanceDiagnostics.start() : 0;
        try {
            ItemStack rebuilt = updateItem(itemStack, magicItem);
            if (rebuilt != itemStack)
                PerformanceDiagnostics.count("items.rebuilt");
            return rebuilt;
        } finally {
            PerformanceDiagnostics.end("item.rebuild", t);
        }
    }

    public static ItemStack updateItem(ItemStack itemStack, MagicItem magicItem) {
        Material material = itemStack.getType();

        if (material.equals(Material.WRITTEN_BOOK)) {
            return itemStack;
        }

        Integer durability = null;
        String creatorName = null;
        int amount = itemStack.getAmount();
        BookMeta bookMeta = null;
        Inventory blockInventory = null;

        ItemMeta sourceMeta = itemStack.getItemMeta();

        PersistentDataContainer sourceContainer = sourceMeta.getPersistentDataContainer();

        if (sourceContainer.has(MagicItemBehaviorKeys.key("menuoption"),
                PersistentDataType.STRING)) {
            return itemStack;
        }

        if (sourceContainer.has(MagicItemBehaviorKeys.key("transmogrifyoption"),
                PersistentDataType.STRING)) {
            return itemStack;
        }

        MagicItemBehaviorState behaviorState = MagicItemBehaviorState.capture(sourceContainer);

        if (sourceContainer.has(MagicItemBehaviorKeys.key("creator_name"),
                PersistentDataType.STRING)) {
            creatorName = sourceContainer.get(MagicItemBehaviorKeys.key("creator_name"),
                    PersistentDataType.STRING);
        }

        if (sourceMeta instanceof Damageable damageable) {
            durability = damageable.getDamage();
        }

        if (sourceMeta instanceof BookMeta sourceBookMeta) {
            bookMeta = sourceBookMeta.clone();
        }

        if (sourceMeta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof Container container) {
            blockInventory = container.getInventory();
        }

        ItemStack updatedItem = magicItem.getItemStack().clone();
        updatedItem.setAmount(amount);

        ItemMeta meta = updatedItem.getItemMeta();
        MagicItemData definition = magicItem.getMagicItemData();
        EnumSet<MagicItemAttribute> effectiveIgnored = MagicItemIgnoredAttributes.getEffectiveIgnored(definition, itemStack);
        boolean durabilityIgnored = effectiveIgnored.contains(MagicItemAttribute.DURABILITY);

        if (durability != null && durability != 0 && meta instanceof Damageable updatedDamageable && !durabilityIgnored) {
            updatedDamageable.setDamage(durability);
        }
        behaviorState.restore(meta.getPersistentDataContainer());

        if (creatorName != null) {
            meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.key("creator_name"),
                    PersistentDataType.STRING, creatorName);
        }

        if (meta instanceof BookMeta updatedBookMeta && bookMeta != null) {
            updatedBookMeta.setTitle(bookMeta.getTitle());
            updatedBookMeta.setAuthor(bookMeta.getAuthor());
            updatedBookMeta.pages(bookMeta.pages());
            updatedBookMeta.setGeneration(bookMeta.getGeneration());
        }

        if (meta instanceof BlockStateMeta updatedBlockStateMeta && blockInventory != null) {
            ItemStack[] contents = blockInventory.getContents();
            long t = containerDepth == 0 ? PerformanceDiagnostics.start() : 0;
            containerDepth++;
            try {
                processItemStacks(contents, null);
            } finally {
                containerDepth--;
                PerformanceDiagnostics.end("item.rebuild.container-contents", t);
            }
            Container updatedContainer = (Container) updatedBlockStateMeta.getBlockState();
            updatedContainer.getInventory().setContents(contents);
            updatedBlockStateMeta.setBlockState(updatedContainer);
        }

        MagicItemPdcMigration.copy(sourceContainer, meta.getPersistentDataContainer(), NAME_KEY.getNamespace());

        MagicItemIgnoredAttributes.copyPdc(sourceMeta, meta);

        MagicItemData stackData = MagicItems.getMagicItemDataFromItemStack(itemStack);
        EnumSet<MagicItemAttribute> ignored = effectiveIgnored;
        EnumSet<MagicItemAttribute> blacklisted = definition.getBlacklistedAttributes();

        for (MagicItemAttribute attr : ignored) {
            if (blacklisted.contains(attr)) continue;
            if (attr == MagicItemAttribute.AMOUNT || attr == MagicItemAttribute.TYPE
                    || attr == MagicItemAttribute.MAGIC_ITEM_NAME || attr == MagicItemAttribute.PERSISTENT_DATA
                    || attr == MagicItemAttribute.PERMANENT_DATA || attr == MagicItemAttribute.TAGS) {
                continue;
            }
            boolean has = stackData != null && stackData.hasAttribute(attr);
            MagicItems.applyMagicItemAttribute(updatedItem, meta, stackData, attr, !has);
        }

        for (MagicItemAttribute attr : blacklisted) {
            if (attr == MagicItemAttribute.AMOUNT || attr == MagicItemAttribute.TYPE
                    || attr == MagicItemAttribute.MAGIC_ITEM_NAME || attr == MagicItemAttribute.PERSISTENT_DATA
                    || attr == MagicItemAttribute.PERMANENT_DATA || attr == MagicItemAttribute.TAGS) {
                continue;
            }
            MagicItems.applyMagicItemAttribute(updatedItem, meta, null, attr, true);
        }

        meta.getPersistentDataContainer().set(VERSION_KEY, PersistentDataType.LONG, magicItem.getDefinitionVersion());
        updatedItem.setItemMeta(meta);

        if (countItemUpdates)
            itemUpdateCount += amount;

        return updatedItem;
    }

    // Add PDC to all items for worlds used prior to PDC
    public static void addMagicItemPDC(boolean updatePlayers, boolean updateWorlds,
            boolean updateVaults, boolean updateCharacters) {

        SneakyItemsPlatform.log("Adding PDC to Magic Items.");
        cacheItemData();
        setCheckItemPersistentData(false);
        countItemUpdates = true;

        // Update Player Inventories
        if (updatePlayers)
            updatePlayers();

        // Update Characters
        if (updateCharacters && CompatBasics.pluginEnabled("SneakyCharacterManager"))
            updateCharacters();

        // Update Vaults
        if (updateVaults && CompatBasics.pluginEnabled("SneakyVaults"))
            updateVaults();

        // Update Chunks
        if (updateWorlds)
            scanWorlds();
    }

    private static void cacheItemData() {
        // Cache data for performance purposes when adding PDC
        for (Map.Entry<String, MagicItem> entry : magicItems.entrySet()) {
            String key = entry.getKey();
            MagicItem value = entry.getValue();
            magicItemsCache.put(key, value.getMagicItemData());
        }
    }

    private static void addItemNames(ItemStack[] items) {
        if (items == null)
            return;

        for (int i = 0; i < items.length; i++) {
            ItemStack itemStack = items[i];
            if (itemStack == null)
                continue;

            MagicItemData item = MagicItems.getMagicItemDataFromItemStack(itemStack);
            if (item == null)
                continue;

            for (Map.Entry<String, MagicItemData> entry : magicItemsCache.entrySet()) {
                if (MagicItems.matches(entry.getValue(), itemStack, false)) {

                    items[i] = updateItem(itemStack, magicItems.get(entry.getKey()));
                    break;
                }
            }
        }
    }

    private static void updatePlayers() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            SneakyItemsPlatform.log("No online players to update.");
            return;
        }
        int batchSize = 5;
        Iterator<Player> iterator = players.iterator();
        SneakyItemsPlatform.log("Starting online player inventory update (" + players.size() + " players)...");

        Bukkit.getScheduler().runTaskTimer(SneakyItemsPlatform.getInstance(), new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < batchSize && iterator.hasNext(); i++) {
                    Player player = iterator.next();
                    if (!player.isOnline()) continue;
                    Inventory inventory = player.getInventory();
                    ItemStack[] items = inventory.getContents();
                    ItemStack[] enderChestContents = player.getEnderChest().getContents();
                    addItemNames(items);
                    addItemNames(enderChestContents);
                    inventory.setContents(items);
                    player.getEnderChest().setContents(enderChestContents);
                    player.updateInventory();
                }
                if (!iterator.hasNext()) {
                    SneakyItemsPlatform.log("Finished updating online players.");
                    Bukkit.getScheduler().cancelTasks(SneakyItemsPlatform.getInstance());
                }
            }
        }, 0L, 1L);
    }

    private static void updateCharacters() {
        SneakyItemsPlatform.log(
                "Offline SneakyCharacterManager data migration is not available in this build; "
                        + "use in-game load paths or add SCM inventory utilities to compileOnly.");
    }

    private static void updateVaults() {
        SneakyItemsPlatform.log(
                "Offline SneakyVaults data migration is not available in this build; "
                        + "runtime inventory updates still run when SneakyVaults is enabled.");
    }

    private static void scanWorlds() {
        Bukkit.getScheduler().runTaskAsynchronously(SneakyItemsPlatform.getInstance(), () -> {
            CountDownLatch latch = new CountDownLatch(Bukkit.getWorlds().size());

            for (World world : Bukkit.getWorlds()) {
                Bukkit.getScheduler().runTaskAsynchronously(SneakyItemsPlatform.getInstance(), () -> {
                    SneakyItemsPlatform.error("Scanning world: " + world.getName());

                    File worldFolder = world.getWorldFolder();
                    File regionFolder;
                    switch (world.getEnvironment()) {
                        case NORMAL:
                            regionFolder = new File(worldFolder, "region");
                            break;
                        case NETHER:
                            regionFolder = new File(worldFolder, "DIM-1/region");
                            break;
                        case THE_END:
                            regionFolder = new File(worldFolder, "DIM1/region");
                            break;
                        default:
                            SneakyItemsPlatform.error("Unknown world environment for world: " + world.getName());
                            latch.countDown();
                            return;
                    }

                    if (!regionFolder.exists() || !regionFolder.isDirectory()) {
                        SneakyItemsPlatform.error("Region folder not found for world: " + world.getName());
                        latch.countDown();
                        return;
                    }

                    File[] regionFiles = regionFolder.listFiles((dir, name) -> name.endsWith(".mca"));

                    if (regionFiles == null || regionFiles.length == 0) {
                        SneakyItemsPlatform.error("No region files found for world: " + world.getName());
                        latch.countDown();
                        return;
                    }

                    for (File regionFile : regionFiles) {
                        String fileName = regionFile.getName();
                        String[] parts = fileName.split("\\.");
                        if (parts.length != 4) {
                            SneakyItemsPlatform.error("Invalid region file name: " + fileName);
                            continue;
                        }

                        try {
                            int regionX = Integer.parseInt(parts[1]);
                            int regionZ = Integer.parseInt(parts[2]);

                            SneakyItemsPlatform.error("Scanning region: r." + regionX + "." + regionZ + ".mca in world "
                                    + world.getName());

                            for (int chunkX = regionX * 32; chunkX < (regionX + 1) * 32; chunkX++) {
                                for (int chunkZ = regionZ * 32; chunkZ < (regionZ + 1) * 32; chunkZ++) {
                                    if (world.isChunkGenerated(chunkX, chunkZ)) {
                                        final int finalChunkX = chunkX;
                                        final int finalChunkZ = chunkZ;

                                        world.getChunkAtAsync(finalChunkX, finalChunkZ).thenAccept(chunk -> {
                                            Bukkit.getScheduler().runTask(SneakyItemsPlatform.getInstance(), () -> {
                                                if (chunk.isLoaded() && chunk.getTileEntities() != null) {
                                                    processChunk(chunk);
                                                } else {
                                                    SneakyItemsPlatform.error("Chunk not fully loaded: " + finalChunkX + ", "
                                                            + finalChunkZ);
                                                }
                                            });
                                        }).exceptionally(ex -> {
                                            SneakyItemsPlatform
                                                    .error("Error loading chunk: " + finalChunkX + ", " + finalChunkZ);
                                            return null;
                                        });
                                    }
                                }
                            }
                        } catch (NumberFormatException e) {
                            SneakyItemsPlatform.error("Error parsing region coordinates from file name: " + fileName);
                        }
                    }
                    latch.countDown();
                });
            }

            Bukkit.getScheduler().runTaskAsynchronously(SneakyItemsPlatform.getInstance(), () -> {
                try {
                    latch.await();
                    Bukkit.getScheduler().runTaskLater(SneakyItemsPlatform.getInstance(),
                            () -> SneakyItemsPlatform.log("All chunk data has been updated."),
                            1L);
                    Bukkit.getScheduler().runTaskLater(SneakyItemsPlatform.getInstance(),
                            () -> SneakyItemsPlatform.log("All tasks complete. " + itemUpdateCount + " items have been updated."
                                    + " Please restart to proceed."),
                            1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    SneakyItemsPlatform.error("Task interrupted while waiting for world scans to complete.");
                }
            });
        });
    }

    private static void processChunk(Chunk chunk) {
        synchronized (chunk) {
            for (BlockState blockState : chunk.getTileEntities()) {
                if (blockState instanceof Container container) {
                    Inventory inventory = container.getInventory();
                    ItemStack[] items = inventory.getContents();
                    addItemNames(items);

                    inventory.setContents(items);
                }
            }
        }
    }
}
