# Plan: SneakyItems-owned, rate-limited item updates

Status: SneakyItems part implemented on branch `feat/queued-item-updates` (uncommitted). MagicSpells follow-up waits for a SneakyItems release. Repos: SneakyItems (main work), MagicSpells (small follow-up).

## Problem

- Important chunks now load at boot and never unload. `ChunkLoadEvent` and the
  reload scan then process every item entity / `ItemDisplay` almost at once,
  causing a large CPU spike on start.
- `ChunkLoadEvent` is the wrong hook: entities load separately since 1.17, so
  `chunk.getEntities()` is often empty there.
- `/ms reload` only refreshes online player inventories (MagicSpells calls
  `MagicItemUpdater.processPlayerInventories` for each player, all in one tick).
  Ground items and displays in loaded chunks are never refreshed until restart.
- Each registering plugin would have to trigger refreshes itself; only
  MagicSpells does, and only for players.
- `updatePlayers()` ends with `cancelTasks(plugin)`, which kills every
  SneakyItems task, including the chunk scan.

## Goals

1. SneakyItems alone decides when stacks get re-checked: after definitions
   change, after `/sneakyitems reload`, when entities load, when players join.
2. One shared queue for all entity and online-player re-checks, with a per-tick
   budget (default about 200 entities per second).
3. A burst of registrations (1000 items in one reload) leads to one re-check,
   limited to the definitions that changed.
4. No entity processing before server startup finishes, or while definitions
   are still changing.

Out of scope: offline characters / vaults (unchanged), block containers
(still updated on open), the legacy `addMagicItemPDC` migration (except the
`cancelTasks` fix).

## Design

### 1. Change tracking (`service/DefinitionChangeTracker`, no Bukkit)

- `MagicItemRegistry.register`, `registerFromStack` and `clearOwner` call
  `tracker.markDirty()`.
- A debounce: re-check starts `definition-settle-ticks` (default 20) after the
  last change. A main-thread task polls, or reschedules on each mark.
- When it fires: compare current `name -> definitionVersion` against the
  snapshot taken after the previous settle. Result = added + changed + removed
  names. Store the new snapshot.
- Empty result: no re-check (for example `/ms reload` with no config edits).
- Version 0 (unstamped, see `registerFromStack`) always counts as changed.
- Fire `SneakyItemsDefinitionsChangedEvent(changedNames)` (sync, public API).

Unit tests: debounce collapses many marks into one, diff detects
add/change/remove, unchanged reload gives an empty set.

### 2. Work queue (`service/ItemRecheckQueue` + a thin Bukkit runner)

- Entries: `EntityRef(worldId, uuid)` and `PlayerRef(uuid)`, stored in a
  `LinkedHashSet` so the same entry isn't queued twice. Each entry holds a
  filter: either "all" or a set of changed names. Filters merge when an entry
  is queued again (all wins; sets union).
- Runner: one repeating task (1 tick). Each tick it processes up to
  `entities-per-tick` (default 10) entities and `players-per-tick` (default 1).
  An entity is looked up when its turn comes (`Bukkit.getEntity(uuid)`) and
  skipped if null, dead or invalid; it'll be queued again on its next
  `EntitiesLoadEvent`.
- Gate: the runner is a scheduled task, so it only starts once the server
  ticks (after startup). It also doesn't drain while the change tracker is
  pending, so boot-time entities wait until every plugin's registrations have
  settled. No `ServerLoadEvent` listener needed.
- The task cancels only itself (`BukkitRunnable.cancel()`), never
  `cancelTasks(plugin)`. It goes idle when empty and restarts when work arrives.
- Changed-name filter: read the entity stack's `NAME_KEY`. If it's tagged and
  the name isn't in the set, skip. Untagged stacks only go through the full
  path when legacy mappings are enabled (they could become tagged).

Unit tests: dedup, filter merge, budget per drain, order kept.

### 3. Routes into the queue

| Trigger | Entities | Online players |
|--|--|--|
| `EntitiesLoadEvent` (replaces `ChunkLoadEvent`) | entities of that chunk, filter all | - |
| Definitions settled (step 1) | all loaded item / `ItemDisplay` entities, filter = changed names | all, filter = changed names |
| `/sneakyitems reload` / plugin enable (`SneakyItemsLoadedEvent`) | all loaded, filter all | all, filter all |
| `ItemSpawnEvent` (non-player drops) | stays immediate | - |
| Join / character load / inventory open | - | stays immediate |

Loaded-entity enumeration uses `world.getEntitiesByClasses(Item, ItemDisplay)`
rather than per-chunk `getEntities()`.

### 4. Cleanup

- Remove `ChunkMagicItemListener.onChunkLoad` and the per-chunk reload scan.
- `PersistentDataUpdater.onSneakyItemsLoaded` uses the queue instead of a
  same-tick loop.
- Fix `updatePlayers()` to cancel only its own task.
- Probe counters: `queue.size`, `queue.entities-processed`,
  `queue.players-processed`, `queue.skipped-filtered`.

### 5. Config (`config.yml`)

```yaml
item-recheck:
  entities-per-tick: 10
  players-per-tick: 1
  definition-settle-ticks: 20
```

### 6. MagicSpells follow-up (after a SneakyItems release)

- Remove the `processPlayerInventories` loop after reload in
  `MagicSpells.java` (~line 743); SneakyItems now handles it from the
  registry changes.
- Until then both run. That's harmless, because stacks that are already up to
  date are skipped by the version stamp.

## Legacy tagging cost (same change)

`MagicItemTagging.applyTagIfNeeded` builds `MagicItemData` for every stack and
compares it against every legacy mapping (~1.5k), even for tagged stacks. Two
changes, no new data written to items:

1. **Early-out for tagged stacks.** Read `NAME_KEY` first (before building
   `MagicItemData`). If it names a currently registered definition, skip the
   legacy scan. Tags pointing to unknown or removed names still get scanned.
   Behavior change: today a stack tagged `X` that matches a legacy pattern for
   `Y` is re-tagged to `Y`; after this it stays `X`. Accepted (tags win).
2. **Index mappings by item type.** All legacy patterns carry a `TYPE`. Store
   mappings as `Material -> list` (plus a small list for patterns without a
   type). An untagged stack is only compared against mappings of its own type,
   so a plain vanilla stack of a type no mapping uses costs one map lookup.

Rejected: an in-memory set of already-checked stacks. `ItemStack`s are copied
constantly, so identity doesn't survive. Hashing by content costs about as
much as the check, and the set grows without bound.

Unit tests: tagged + registered skips the scan; tagged + unknown name still
scans; type index returns only same-type and untyped mappings.

## Verification

- `./gradlew check` (unit tests for tracker and queue).
- MockBukkit: queue gate before `ServerLoadEvent`; registry burst -> one event.
- `runServer` smoke test: boot with forced chunks and many displays and watch
  the probe; edit one MagicSpells item, `/ms reload`, confirm only that item's
  displays update, spread over time; `/sneakyitems reload` does a full pass.

## Risks

- Async config reloads can register definitions off the main thread:
  `markDirty` is thread-safe (atomic timestamp), the settle check and all queue
  work run on the main thread, and the fingerprint snapshot is only read
  there. Registration itself never happens during normal play.
- A definition changing several times within the settle window counts as one
  change (intended).
- Removed definitions: stacks keep their last look (current behavior).
