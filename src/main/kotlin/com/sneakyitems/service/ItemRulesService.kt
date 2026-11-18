package com.sneakyitems.service

import com.sneakyitems.api.ItemRuleGroup
import com.sneakyitems.api.ItemRuleHooks
import com.sneakyitems.api.ItemRules
import com.sneakyitems.api.RuleRefusal
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.api.UniqueWhere
import com.sneakyutils.triggers.ClickOptions
import com.sneakyutils.triggers.ClickSide
import com.sneakyutils.triggers.EntityMatch
import com.sneakyutils.triggers.Subscription
import com.sneakyutils.triggers.Triggers
import net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID
import java.util.logging.Level

/**
 * Applies [ItemRules] groups ([SneakyItemsAPI.itemRules]). One listener for every group: each handler looks the stack
 * up by magic item id and returns at once when no group is registered. The placed-item click and the 5-second unique
 * check run on SneakyUtils triggers, started with the first group. Main thread only.
 */
class ItemRulesService(private val plugin: Plugin, private val triggers: () -> Triggers?) : Listener {

  private val groups = LinkedHashMap<String, ItemRuleGroup>()
  private val byItem = HashMap<String, ItemRuleGroup>()
  private val lastRefusal = HashMap<UUID, Long>()
  private var subscriptions: List<Subscription> = emptyList()
  private var characterHook: Listener? = null

  private val placedItemKey = NamespacedKey(plugin, "placed_item")
  private val placedSerialKey = NamespacedKey(plugin, "placed_serial")

  /** Registered groups (a level for tests). */
  val size: Int get() = groups.size

  fun register(owner: Plugin, groupId: String, itemIds: Collection<String>, rules: ItemRules): ItemRuleGroup {
    check(Bukkit.isPrimaryThread()) { "itemRules must be called on the main server thread" }
    check(plugin.isEnabled) { "SneakyItems is not enabled" }
    require(owner.isEnabled) { "owner ${owner.name} is not enabled" }
    require(groupId.matches(Regex("[a-z0-9_.-]+"))) { "groupId must be [a-z0-9_.-]+: $groupId" }
    val ids = itemIds.toSet()
    require(ids.isNotEmpty() && ids.none { it.isBlank() }) { "itemIds empty or blank" }
    val key = "${owner.name.lowercase()}:$groupId"
    groups[key]?.let { close(it) } // registering again (owner reload) replaces the group
    for (id in ids) {
      val other = byItem[id]
      require(other == null) { "$id is already in group ${other!!.owner.name}:${other.groupId}" }
    }
    val file = File(plugin.dataFolder, "unique/${owner.name.lowercase()}-$groupId.yml")
    val store = UniqueItemStore(plugin, file, ids)
    if (rules.unique) store.load()
    val group = ItemRuleGroup(this, owner, groupId, ids, rules, store)
    groups[key] = group
    for (id in ids) byItem[id] = group
    start()
    return group
  }

  fun groupOf(stack: ItemStack?): ItemRuleGroup? {
    if (byItem.isEmpty()) return null
    return SneakyItemsAPI.keyOf(stack)?.let(byItem::get)
  }

  fun close(group: ItemRuleGroup) {
    if (!group.isActive) return
    group.isActive = false
    groups.values.remove(group)
    for (id in group.itemIds) if (byItem[id] === group) byItem.remove(id)
    if (group.rules.unique) runCatching { group.store.saveNow() }
        .onFailure { plugin.logger.log(Level.WARNING, "Saving unique items of ${group.groupId} failed", it) }
    if (groups.isEmpty()) stop()
  }

  /** SneakyItems disables: close every group. */
  fun clear() {
    for (g in groups.values.toList()) close(g)
    lastRefusal.clear()
    stop()
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onPluginDisable(event: PluginDisableEvent) {
    if (event.plugin === plugin) return
    for (g in groups.values.toList()) if (g.owner === event.plugin) close(g)
    if (event.plugin.name == SCM && characterHook != null) {
      HandlerList.unregisterAll(characterHook!!)
      characterHook = null
    }
  }

  @EventHandler
  fun onPluginEnable(event: PluginEnableEvent) {
    if (groups.isNotEmpty() && event.plugin.name == SCM) hookCharacters()
  }

  private fun start() {
    hookCharacters()
    if (subscriptions.isNotEmpty()) return
    val t = triggers() ?: return
    subscriptions = listOf(
        t.onEntityClick(plugin, EntityMatch.scoreboardTag(PLACED_TAG).withType(EntityType.INTERACTION),
            { p, e, _, _ -> takePlaced(p, e as Interaction); true },
            ClickOptions.builder().sides(ClickSide.BOTH).cancelEvent(true).build()),
        t.every(plugin, SWEEP_TICKS) { sweep() },
    )
  }

  private fun stop() {
    for (s in subscriptions) runCatching { s.close() }
    subscriptions = emptyList()
  }

  /** Character switches (SneakyCharacterManager, optional): typed import, registered while SCM is enabled. */
  private fun hookCharacters() {
    if (characterHook != null) return
    val scm = Bukkit.getPluginManager().getPlugin(SCM)
    if (scm == null || !scm.isEnabled) return
    val listener = object : Listener {
      @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
      fun onCharacterLoad(event: LoadCharacterEvent) {
        val p = event.player
        // SCM swaps the inventory with no pre-switch event: look once the new character is in
        Bukkit.getScheduler().runTask(plugin, Runnable { if (plugin.isEnabled && p.isOnline) afterSwitch(p) })
      }
    }
    Bukkit.getPluginManager().registerEvents(listener, plugin)
    characterHook = listener
  }

  // ------------------------------------------------------------------ helpers

  internal inline fun hook(group: ItemRuleGroup, call: (ItemRuleHooks) -> Unit) {
    try {
      call(group.rules.hooks)
    } catch (e: Exception) {
      group.owner.logger.log(Level.WARNING, "Item rule hook of ${group.groupId} failed", e)
    }
  }

  private fun refuse(group: ItemRuleGroup, p: Player, itemId: String, reason: RuleRefusal) {
    val now = System.currentTimeMillis()
    if ((lastRefusal[p.uniqueId] ?: 0) > now - REFUSAL_COOLDOWN_MS) return
    lastRefusal[p.uniqueId] = now
    hook(group) { it.refused(p, itemId, reason) }
  }

  /** True (and the copy is gone) when [item] is a unique group item that isn't live. */
  private fun purgeIfStale(item: ItemStack?): Boolean {
    val g = groupOf(item) ?: return false
    if (!g.rules.unique || g.isLive(item)) return false
    item!!.amount = 0
    return true
  }

  private fun purgeInventory(p: Player) {
    if (byItem.isEmpty()) return
    for (it in p.inventory.contents) purgeIfStale(it)
    purgeIfStale(p.itemOnCursor)
  }

  internal fun tagPlaced(e: Entity, itemId: String, serial: String?) {
    e.addScoreboardTag(PLACED_TAG)
    e.persistentDataContainer.set(placedItemKey, PersistentDataType.STRING, itemId)
    if (serial != null) e.persistentDataContainer.set(placedSerialKey, PersistentDataType.STRING, serial)
  }

  private fun placedId(e: Entity): String? = e.persistentDataContainer.get(placedItemKey, PersistentDataType.STRING)
  private fun placedSerial(e: Entity): String? = e.persistentDataContainer.get(placedSerialKey, PersistentDataType.STRING)

  private fun placedIsLive(group: ItemRuleGroup, e: Entity): Boolean {
    val id = placedId(e) ?: return false
    return !group.rules.unique || placedSerial(e) == group.store.serial(id)
  }

  internal fun removePlacedNear(group: ItemRuleGroup, itemId: String, at: Location) {
    if (group.rules.placement == null) return
    for (e in at.world.getNearbyEntities(at, 1.0, 1.0, 1.0)) {
      if (PLACED_TAG in e.scoreboardTags && placedId(e) == itemId && (e is ItemDisplay || e is Interaction)) e.remove()
    }
  }

  internal fun onGround(group: ItemRuleGroup, itemId: String, item: Item) {
    if (group.rules.protectOnGround) protect(item)
    if (group.rules.unique) group.store.ground(itemId, item.uniqueId, item.location)
    hook(group) { it.dropped(itemId, item) }
  }

  private fun protect(item: Item) {
    item.setUnlimitedLifetime(true)
    item.isInvulnerable = true
    item.setCanMobPickup(false)
  }

  // ------------------------------------------------------------------ picking up

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  fun onPickup(e: EntityPickupItemEvent) {
    val stack = e.item.itemStack
    val g = groupOf(stack) ?: return
    if (g.rules.unique && !g.isLive(stack)) { e.isCancelled = true; e.item.remove(); return }
    val p = e.entity as? Player
    if (p == null) { if (g.rules.protectOnGround) e.isCancelled = true; return }
    if (!g.mayTakeAnother(p)) {
      e.isCancelled = true
      refuse(g, p, g.idOf(stack)!!, RuleRefusal.CARRY_LIMIT)
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onPickedUp(e: EntityPickupItemEvent) {
    val p = e.entity as? Player ?: return
    val g = groupOf(e.item.itemStack) ?: return
    val id = g.idOf(e.item.itemStack)!!
    if (g.rules.unique) g.store.held(id, p)
    hook(g) { it.pickedUp(p, id) }
  }

  @EventHandler(ignoreCancelled = true)
  fun onHopperPickup(e: InventoryPickupItemEvent) {
    val g = groupOf(e.item.itemStack) ?: return
    if (g.rules.protectOnGround || g.rules.noContainers) e.isCancelled = true
  }

  // ------------------------------------------------------------------ on the ground

  /** Before SneakyGroundItems (NORMAL) sees the drop: keep protected items plain vanilla drops. */
  @EventHandler(priority = EventPriority.LOWEST)
  fun onDropEarly(e: PlayerDropItemEvent) {
    val stack = e.itemDrop.itemStack
    val g = groupOf(stack) ?: return
    if (!g.rules.protectOnGround) return
    if (markNotGroundPlaceable(stack)) e.itemDrop.itemStack = stack
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onDrop(e: PlayerDropItemEvent) {
    val g = groupOf(e.itemDrop.itemStack) ?: return
    onGround(g, g.idOf(e.itemDrop.itemStack)!!, e.itemDrop)
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  fun onItemSpawn(e: ItemSpawnEvent) {
    val stack = e.entity.itemStack
    val g = groupOf(stack) ?: return
    if (g.rules.unique && !g.isLive(stack)) { e.isCancelled = true; return }
    if (g.rules.protectOnGround) protect(e.entity)
    if (g.rules.unique) g.store.ground(g.idOf(stack)!!, e.entity.uniqueId, e.entity.location)
  }

  @EventHandler(ignoreCancelled = true)
  fun onDespawn(e: ItemDespawnEvent) {
    if (groupOf(e.entity.itemStack)?.rules?.protectOnGround == true) e.isCancelled = true
  }

  @EventHandler
  fun onEntitiesLoad(e: EntitiesLoadEvent) {
    if (byItem.isEmpty()) return
    for (ent in e.entities) {
      when (ent) {
        is Item -> if (purgeIfStaleEntity(ent)) ent.remove()
        is Interaction, is ItemDisplay -> if (PLACED_TAG in ent.scoreboardTags) {
          val g = placedId(ent)?.let(byItem::get) ?: continue
          if (!placedIsLive(g, ent)) ent.remove()
        }
        else -> {}
      }
    }
  }

  private fun purgeIfStaleEntity(item: Item): Boolean {
    val g = groupOf(item.itemStack) ?: return false
    return g.rules.unique && !g.isLive(item.itemStack)
  }

  // ------------------------------------------------------------------ no containers

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  fun onClick(e: InventoryClickEvent) {
    if (byItem.isEmpty()) return
    if (purgeIfStale(e.currentItem) || purgeIfStale(e.cursor)) { e.isCancelled = true; return }
    val p = e.whoClicked as? Player ?: return
    val cursor = e.cursor
    val current = e.currentItem
    val cursorG = groupOf(cursor)?.takeIf { it.rules.noContainers }
    val currentG = groupOf(current)?.takeIf { it.rules.noContainers }
    if ((cursorG != null && isBundle(current)) || (currentG != null && isBundle(cursor))) {
      e.isCancelled = true
      return
    }
    val top = e.view.topInventory
    if (top.type == InventoryType.CREATIVE) return
    val clickedTop = e.clickedInventory != null && e.clickedInventory == top
    val hotbar = if (e.click == ClickType.NUMBER_KEY && e.hotbarButton >= 0) p.inventory.getItem(e.hotbarButton) else null
    val offhand = if (e.click == ClickType.SWAP_OFFHAND) p.inventory.itemInOffHand else null
    val blocked = when {
      clickedTop -> listOf(cursor, hotbar, offhand).firstOrNull { groupOf(it)?.rules?.noContainers == true }
      e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY && currentG != null -> current
      else -> null
    } ?: return
    e.isCancelled = true
    val g = groupOf(blocked)!!
    refuse(g, p, g.idOf(blocked)!!, RuleRefusal.CONTAINER)
  }

  private fun isBundle(item: ItemStack?) = item != null && item.type.name.endsWith("BUNDLE")

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  fun onDrag(e: InventoryDragEvent) {
    if (groupOf(e.oldCursor)?.rules?.noContainers != true) return
    val top = e.view.topInventory
    if (top.type == InventoryType.CREATIVE) return
    if (e.rawSlots.any { it < top.size }) e.isCancelled = true
  }

  @EventHandler(ignoreCancelled = true)
  fun onMove(e: InventoryMoveItemEvent) {
    if (groupOf(e.item)?.rules?.noContainers == true) e.isCancelled = true
  }

  /** Item frames, allays and other entities: never take one in. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  fun onInteractEntity(e: PlayerInteractEntityEvent) {
    if (PLACED_TAG in e.rightClicked.scoreboardTags) return
    if (groupOf(e.player.inventory.getItem(e.hand))?.rules?.noContainers == true) e.isCancelled = true
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  fun onArmorStand(e: PlayerArmorStandManipulateEvent) {
    if (groupOf(e.playerItem)?.rules?.noContainers == true) e.isCancelled = true
  }

  // ------------------------------------------------------------------ placing

  @EventHandler(priority = EventPriority.HIGH)
  fun onUse(e: PlayerInteractEvent) {
    if (e.action != Action.RIGHT_CLICK_BLOCK) return
    val item = e.item ?: return
    val g = groupOf(item) ?: return
    val placement = g.rules.placement
    val block = e.clickedBlock ?: return
    if (g.rules.noContainers && isInsertBlock(block.type)) {
      e.setUseItemInHand(Event.Result.DENY)
      e.setUseInteractedBlock(Event.Result.DENY)
      return
    }
    if (placement == null) return
    e.setUseItemInHand(Event.Result.DENY)
    if (e.blockFace != BlockFace.UP || (!e.player.isSneaking && block.type.isInteractable)) return
    e.setUseInteractedBlock(Event.Result.DENY)
    val above = block.getRelative(BlockFace.UP)
    if (!above.isPassable || above.isLiquid) return
    if (g.rules.unique && !g.isLive(item)) { item.amount = 0; return }
    val id = g.idOf(item)!!
    val top = block.boundingBox.maxY.takeIf { block.type.isSolid } ?: (block.y.toDouble() + 1)
    val at = Location(block.world, block.x + 0.5, top, block.z + 0.5)
    val stack = item.clone()
    item.amount -= 1
    g.placeStack(id, stack, g.serial(stack), at, placement)
  }

  private fun takePlaced(p: Player, hit: Interaction) {
    val id = placedId(hit) ?: return
    val g = byItem[id]
    val display = hit.world.getNearbyEntities(hit.location, 1.0, 1.0, 1.0)
        .firstOrNull { it is ItemDisplay && PLACED_TAG in it.scoreboardTags && placedId(it) == id } as ItemDisplay?
    if (g == null) return // the owner isn't running: leave it be
    if (!placedIsLive(g, hit)) { hit.remove(); display?.remove(); return }
    if (!g.mayTakeAnother(p)) { refuse(g, p, id, RuleRefusal.CARRY_LIMIT); return }
    if (p.inventory.firstEmpty() < 0) { refuse(g, p, id, RuleRefusal.POCKETS_FULL); return }
    val stack = display?.itemStack?.takeIf { !it.type.isAir } ?: SneakyItemsAPI.getItemStack(id) ?: return
    placedSerial(hit)?.let { s -> stack.editPersistentDataContainer { it.set(g.rules.serialKey, PersistentDataType.STRING, s) } }
    hit.remove()
    display?.remove()
    removePlacedNear(g, id, hit.location)
    p.inventory.addItem(stack)
    if (g.rules.unique) g.store.held(id, p)
    hook(g) { it.taken(p, id) }
  }

  // ------------------------------------------------------------------ leaving: death, quit, character switch

  private fun dropAll(p: Player, at: Location) {
    for (g in groups.values.toList()) {
      if (!g.rules.dropOnLeave) continue
      val live = g.carried(p)
      if (live.isEmpty()) continue
      val copies = live.map { it.clone() }
      for (it in live) it.amount = 0
      for (c in copies) {
        if (g.rules.unique && !g.isLive(c)) continue
        val item = at.world.dropItemNaturally(at, c)
        onGround(g, g.idOf(c)!!, item)
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  fun onDeath(e: PlayerDeathEvent) {
    if (byItem.isEmpty()) return
    val leaving = { s: ItemStack? -> groupOf(s)?.rules?.dropOnLeave == true }
    e.drops.removeIf(leaving)
    e.itemsToKeep.removeIf(leaving)
    dropAll(e.player, e.player.location)
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onQuit(e: PlayerQuitEvent) {
    lastRefusal.remove(e.player.uniqueId)
    if (byItem.isEmpty()) return
    dropAll(e.player, e.player.location)
  }

  @EventHandler
  fun onJoin(e: PlayerJoinEvent) = purgeInventory(e.player)

  internal fun afterSwitch(p: Player) {
    purgeInventory(p)
    for (g in groups.values.toList()) {
      if (!g.rules.unique) continue
      for (entry in g.store.all) {
        if (entry.where != UniqueWhere.HELD || entry.holder != p.uniqueId) continue
        if (g.carried(p).any { g.idOf(it) == entry.id && g.isLive(it) }) continue
        g.respawn(entry.id, p.location)
      }
    }
  }

  // ------------------------------------------------------------------ the check (unique groups, every 5 s)

  fun sweep() {
    for (g in groups.values.toList()) {
      if (!g.rules.unique) continue
      for (entry in g.store.all) {
        when (entry.where) {
          UniqueWhere.HELD -> {
            val p = entry.holder?.let { Bukkit.getPlayer(it) }
            if (p != null && g.carried(p).any { g.idOf(it) == entry.id && g.isLive(it) }) {
              g.store.seen(entry.id, p.location)
              continue
            }
            // the holder lost it without a drop (cleared, deleted, offline without a quit drop)
            g.store.locate(entry.id)?.let { g.respawn(entry.id, p?.location ?: it) }
          }
          UniqueWhere.GROUND, UniqueWhere.PLACED -> {
            val ent = entry.entity?.let { Bukkit.getEntity(it) }
            if (ent != null && ent.isValid) { g.store.seen(entry.id, ent.location); continue }
            val w = entry.world?.let { Bukkit.getWorld(it) } ?: continue
            val cx = entry.x.toInt() shr 4
            val cz = entry.z.toInt() shr 4
            if (!w.isChunkLoaded(cx, cz) || !w.getChunkAt(cx, cz).isEntitiesLoaded) continue
            g.respawn(entry.id, Location(w, entry.x, entry.y, entry.z))
          }
          UniqueWhere.NONE -> {}
        }
      }
    }
  }

  companion object {
    /** Scoreboard tag on placed items' `ItemDisplay` and `Interaction`. */
    const val PLACED_TAG = "sneakyitems_placed"
    const val SWEEP_TICKS = 100
    const val REFUSAL_COOLDOWN_MS = 2000L
    private const val SCM = "SneakyCharacterManager"

    /** SneakyGroundItems leaves drops alone whose stack has this set to "false". */
    @JvmField val GROUND_PLACEABLE: NamespacedKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_placeable")

    /** Marks [stack] so SneakyGroundItems leaves its drops to vanilla. True when it changed. */
    @JvmStatic
    fun markNotGroundPlaceable(stack: ItemStack): Boolean {
      if (stack.persistentDataContainer.get(GROUND_PLACEABLE, PersistentDataType.STRING) == "false") return false
      stack.editPersistentDataContainer { it.set(GROUND_PLACEABLE, PersistentDataType.STRING, "false") }
      return true
    }

    private fun isInsertBlock(m: Material) = m == Material.DECORATED_POT || m == Material.CHISELED_BOOKSHELF ||
        m == Material.COMPOSTER || m == Material.LECTERN || m == Material.JUKEBOX || m == Material.FLOWER_POT ||
        m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.VAULT || m.name.endsWith("_SHELF")
  }
}
