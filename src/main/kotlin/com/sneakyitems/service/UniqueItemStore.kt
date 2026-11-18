package com.sneakyitems.service

import com.sneakyitems.api.UniqueItemState
import com.sneakyitems.api.UniqueWhere
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

/**
 * Live serial and last known place of each item in a unique rule group, kept in [file]. Each item has one live
 * serial: copies with any other serial are stale. Main thread; writes go off it ([plugin]'s async scheduler) as
 * snapshots, newest wins.
 */
class UniqueItemStore(private val plugin: Plugin, private val file: File, itemIds: Collection<String>) {

  internal class Entry(val id: String) {
    var serial: String? = null
    var where = UniqueWhere.NONE
    var holder: UUID? = null
    var entity: UUID? = null
    var display: UUID? = null
    var world: String? = null
    var x = 0.0
    var y = 0.0
    var z = 0.0

    fun at(loc: Location) {
      world = loc.world?.name
      x = loc.x
      y = loc.y
      z = loc.z
    }
  }

  private val entries: Map<String, Entry> = itemIds.associateWith { Entry(it) }
  @Volatile private var pending = ""

  internal val all: Collection<Entry> get() = entries.values

  private fun entry(id: String): Entry = requireNotNull(entries[id]) { "unknown item $id" }

  fun serial(id: String): String? = entries[id]?.serial

  /** A fresh serial for [id]: every older copy becomes stale. */
  fun newSerial(id: String): String {
    val s = UUID.randomUUID().toString()
    entry(id).serial = s
    save()
    return s
  }

  fun adopt(id: String, serial: String, where: UniqueWhere, location: Location?): Boolean {
    val e = entry(id)
    if (e.serial != null) return false
    e.serial = serial
    e.where = if (location == null) UniqueWhere.NONE else where
    location?.let(e::at)
    save()
    return true
  }

  fun held(id: String, p: Player) = update(id) {
    where = UniqueWhere.HELD; holder = p.uniqueId; entity = null; display = null; at(p.location)
  }

  fun ground(id: String, entityId: UUID, loc: Location) = update(id) {
    where = UniqueWhere.GROUND; holder = null; entity = entityId; display = null; at(loc)
  }

  fun placed(id: String, interaction: UUID, displayId: UUID, loc: Location) = update(id) {
    where = UniqueWhere.PLACED; holder = null; entity = interaction; display = displayId; at(loc)
  }

  /** Moves the last known spot without saving (the periodic check; saved with the next real change). */
  internal fun seen(id: String, loc: Location) = entry(id).at(loc)

  private inline fun update(id: String, change: Entry.() -> Unit) {
    entries[id]?.let { it.change(); save() }
  }

  fun state(id: String): UniqueItemState {
    val e = entry(id)
    val loc = e.world?.let { Bukkit.getWorld(it) }?.let { Location(it, e.x, e.y, e.z) }
    return UniqueItemState(e.id, e.serial, e.where, e.holder, e.entity, e.display, loc)
  }

  fun locate(id: String): Location? {
    val e = entry(id)
    if (e.where == UniqueWhere.NONE) return null
    e.holder?.let { Bukkit.getPlayer(it) }?.let { return it.location }
    e.entity?.let { Bukkit.getEntity(it) }?.takeIf { it.isValid }?.let { return it.location }
    val w = e.world?.let { Bukkit.getWorld(it) } ?: return null
    return Location(w, e.x, e.y, e.z)
  }

  fun load() {
    if (!file.isFile) return
    val y = YamlConfiguration.loadConfiguration(file)
    for (e in entries.values) {
      val s = y.getConfigurationSection("items.${e.id}") ?: continue
      e.serial = s.getString("serial")
      e.where = runCatching { UniqueWhere.valueOf(s.getString("where", "NONE")!!) }.getOrDefault(UniqueWhere.NONE)
      e.holder = s.getString("holder")?.let(::uuidOrNull)
      e.entity = s.getString("entity")?.let(::uuidOrNull)
      e.display = s.getString("display")?.let(::uuidOrNull)
      e.world = s.getString("world")
      e.x = s.getDouble("x"); e.y = s.getDouble("y"); e.z = s.getDouble("z")
    }
  }

  private fun uuidOrNull(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()

  /** Snapshot on the server thread, write off it (synchronously while [plugin] is disabling). */
  fun save() {
    pending = snapshot()
    val write = Runnable { synchronized(file) { val t = pending; file.parentFile?.mkdirs(); file.writeText(t) } }
    if (plugin.isEnabled) Bukkit.getScheduler().runTaskAsynchronously(plugin, write) else write.run()
  }

  /** Writes now, on this thread. */
  fun saveNow() {
    pending = snapshot()
    synchronized(file) { file.parentFile?.mkdirs(); file.writeText(pending) }
  }

  private fun snapshot(): String {
    val y = YamlConfiguration()
    for (e in entries.values) {
      val p = "items.${e.id}"
      y.set("$p.serial", e.serial); y.set("$p.where", e.where.name)
      y.set("$p.holder", e.holder?.toString()); y.set("$p.entity", e.entity?.toString())
      y.set("$p.display", e.display?.toString()); y.set("$p.world", e.world)
      y.set("$p.x", e.x); y.set("$p.y", e.y); y.set("$p.z", e.z)
    }
    return y.saveToString()
  }
}
