# SneakyItems

Paper plugin that owns the LoM **magic item** platform: definition registry, YAML/stack parsing, PDC stamping,
inventory updater, legacy ItemTag mappings, and soulbound/expiration enforcement. Other plugins (notably
**MagicSpells**) register definitions via the public API without embedding item parsers.

**Legendary items:** the generic behaviours they need (one per player, no chests, never lost, unique copies, set
down on blocks) are [item rules](#item-rules); the items themselves live in their own plugins (SneakyLegendarys).

## API (for consumers)

| Entry | Purpose |
|-------|---------|
| `com.sneakyitems.api.SneakyItemsAPI` | Register/clear defs, resolve items, match stacks, menu-inventory skip hook |
| `SneakyItemsAPI.registerMagicItemsFile` | MagicsSpells-style YAML (`magic-items:` wrapper or flat keys) |
| `com.sneakyitems.api.MagicItemRegistry` | Owner-scoped registration with global internal-name uniqueness |
| `com.sneakyitems.util.magicitems.MagicItems` | Parser/match/create (MagicSpells-compatible behavior) |
| `com.sneakyitems.util.items.ItemBehaviors` | Soulbound + expiration apply/update (PDC keys stay `magicspells:*`) |
| `com.sneakyitems.events.ItemExpireEvent` | Fired before inventory removal of an expired stack |
| `com.sneakyitems.events.SneakyItemsLoadedEvent` | Fired after SneakyItems reload (MagicSpells can refresh stacks) |
| `com.sneakyitems.events.SneakyItemsDefinitionsChangedEvent` | Fired once a burst of registrations settles, with the added/changed/removed names |
| `com.sneakyitems.events.MagicItemClickEvent` | Left/right click with a magic item; see [Magic item clicks](#magic-item-clicks) |
| `com.sneakyitems.events.MagicItemSwingEvent` | Raw main-hand arm swing with a magic item |
| `SneakyItemsAPI.registerLegacyKey` / `registerLegacyPattern` | Migrate copies made before an item was a magic item; see [Migrating existing items](#migrating-existing-items) |
| `SneakyItemsAPI.keyOf` / `isItem` / `inMainHand` / `inOffHand` / `inEitherHand` / `onHotbar` / `inInventory` / `wearing` / `findSlots` / `count` | Find a magic item by internal name on a stack or player |
| `SneakyItemsAPI.carriedCount` / `carries` / `carriedIds` / `refreshCarried`, `com.sneakyitems.events.CarriedItemChangeEvent` | Live index of the magic items a player carries; see [Carried items](#carried-items) |
| `SneakyItemsAPI.whileCarrying` / `whileHolding`, `com.sneakyitems.api.ItemWatch` / `HeldSlot` | SneakyUtils per-player timer for just the players carrying or holding an item; see [Timers while carrying or holding](#timers-while-carrying-or-holding) |
| `WatchMembership` (the `whileCarrying` / `whileHolding` overloads that take one) | `entered` / `left` callbacks when a player joins or leaves a watch's timer |
| `SneakyItemsAPI.itemRules`, `ItemRules` / `ItemRuleGroup` / `ItemRuleHooks` | Carry limit, no containers, protected on the ground, drop on leave, unique serials, set down on blocks; see [Item rules](#item-rules) |

Creative soulbound bypass: `sneakyitems.nosoulbound` **and** Creative mode.

## Usage

Run `/sneakyitems reload` in-game or `sneakyitems reload` from the console to re-read `plugins/SneakyItems/config.yml`.
The permission is `sneakyitems.command.reload` (default: operators).

### Item re-checks

SneakyItems re-checks loaded item entities, item displays and online players itself. Owner plugins don't
need to trigger it. The work goes through one queue, drained a few entries per tick (`item-recheck` in
`config.yml`, default 10 entities and 1 player per tick):

- **Entities load** (`EntitiesLoadEvent`): their item entities and item displays are queued.
- **Definitions change** (any register/clear, for example `/ms reload`): once no change has arrived for
  `definition-settle-ticks`, the definition versions are compared with the previous settle. Only stacks
  tagged with a changed name are rebuilt. The first full startup re-check includes legacy matching;
  subsequent definition-only re-checks skip it, including inside rebuilt container items. An unchanged
  definition reload queues nothing.
- **Legacy mappings change** (registration/replacement/removal): queue a full legacy-aware re-check.
- **`/sneakyitems reload`** queues everything, including legacy matching.

Coalesced requests retain legacy matching if **any** request requires it. Normal entity loads, item
spawns, joins, character loads, inventory opens and explicit player refreshes retain legacy matching;
this optimization never disables the mapping registry globally.

The queue doesn't drain while definitions are still changing, or before the server starts ticking, so
entities loaded at boot wait until all plugins have registered their items. Item spawns, joins, character
loads and inventory opens are still handled right away.

Legacy tagging skips stacks already tagged with a registered name, and only compares a stack against
mappings with the same item type and model (custom model data or item model).

### Migrating existing items

Every custom item a player can hold is a magic item. When a plugin turns an item it used to build itself into a
registered definition, copies already in inventories, chests and on the ground still carry only the old identity.
Register a legacy mapping next to the definition and the updater stamps those copies (`magicitem`) the next time it
sees them, then rebuilds them from the definition:

- **Own PDC key** (`registerLegacyKey(owner, key, internalName)`): any stack carrying that key, whatever its value.
  The rebuild keeps the stack's other PDC keys, so per-copy data (an award's winner, a polaroid's photo) survives; add
  `ignored-attributes` to the definition for per-copy name, lore or texture. Checked before pattern mappings, and
  only once the definition is registered.
- **Look only** (`registerLegacyPattern(owner, internalName, pattern)`): stacks matching a magic-item data string,
  for example `paper{"item-model":"lom:coach_whistle"}`; only the attributes it names are compared. The same
  mechanism as `legacy-item-tag-mappings.yml`, shipped by the owning plugin instead of edited on the server.

`clearOwner` drops an owner's mappings with its definitions (so register both again on reload); the YAML file's
mappings and the `legacy-tags on|off` switch are separate and unaffected.

### Magic item clicks

`MagicItemClickEvent` fires when a player clicks with a stack whose `magicitem` PDC key is set. Listen to it instead of
`PlayerInteractEvent` so every plugin handles clicks the same way. It carries the item's internal name (`itemKey`),
the live stack, the hand, `click` (`LEFT`/`RIGHT`) and `trigger` (`AIR`, `BLOCK`, `ENTITY`, `ARM_SWING`) with the
clicked block/face or entity, and `getInteractionPoint()` when the client sent an exact block hit (same as
`PlayerInteractEvent.getInteractionPoint()`).

Listeners choose their own priority on the event. SneakyItems fires it while handling the vanilla event at `NORMAL`,
after protection plugins (`LOWEST`/`LOW`). `isAlreadyDenied()` says whether one of them already denied the action: item
use set to `DENY` for interacts (an air click arriving "cancelled" doesn't count), or the entity event cancelled.

The timing builds on MagicSpells' `CastListener` (left) and `RightClickListener` (right):

- **Right:** fired at once, per hand holding a magic item, for block/air and entity interacts. One physical click is
  reported once: the interact-at/interact pair Paper fires for an entity, and the client's air/block click right after
  an entity click, are the same click (and the follow-up is cancelled too when the entity click was).
- **Left:** main hand only.
  - Block hits (`BLOCK`) and attacks on any entity (`ENTITY`, from Paper's `PrePlayerAttackEntityEvent`, so hitboxes
    and item frames count) fire at once and can be cancelled.
  - Clicks only known from the arm swing fire a tick later: left-click air (`AIR`, default) or, with
    `click-events.cast-on-animate: true`, the main-hand arm swing itself (`ARM_SWING`, only with no container open).
    They're ignored within 150 ms of dropping an item or of a right-click on a container/door/button/bed/etc. or with a
    block item, and when a block/entity hit happened within a tick (that swing belonged to the hit).

Cancelling the event cancels the vanilla action for every trigger fired at once. A late `AIR`/`ARM_SWING` left click
was already handled by vanilla; cancelling it only marks it as consumed for listeners with `ignoreCancelled = true`.
Interact triggers (`hasInteractResults()`) also take `setUseItemInHand` / `setUseInteractedBlock` to deny one side only.

`MagicItemSwingEvent` is the raw alternative: every main-hand arm swing with a magic item, whatever `cast-on-animate`
says, except the swing of a drop. Fired a tick late and not cancellable. A swing isn't always a left click
(right-clicking a block swings too), so use it only when "any swing" is what you want.

### Carried items

SneakyItems keeps a per-player count of the magic items in the player's own inventory (storage, hotbar, armor,
offhand; not the cursor, open containers or bags) so plugins don't scan inventories themselves. An item counts when
`keyOf` says it is that id.

- `carriedCount(player, id)`, `carries(player, id)`, `carriedIds(player)` (an unmodifiable snapshot that never changes
  after it is returned). Main thread only: other threads get an `IllegalStateException`. Reads are cached.
- `CarriedItemChangeEvent(player, itemId, oldCount, newCount)` fires, synchronously, for each id whose count changed.
  It is only built when something listens.
- Counts follow Paper's `PlayerInventorySlotChangeEvent`, which fires when Paper syncs the player's menu (the same tick
  or the next), not when a plugin calls `setItem`. A slot change involving a magic item marks the player stale and
  one 41-slot recount runs at the next query, or on the next tick when the change event has listeners. Call
  `refreshCarried(player)` to see a change made earlier in the same tick. Joining scans once; quitting frees the state.
- Events are delivered one batch at a time: a listener that changes inventories and calls `refreshCarried` has that
  recount deferred until the batch has reached every listener, so for each player and id the events chain
  (each old count is the previous new count). Changing the active PDC namespace (any owner registering under a new one)
  recounts every tracked player, so event-only consumers get the transitions.
- Cost (estimate, not measured on a live server): with a listener on `PlayerInventorySlotChangeEvent` Paper clones the
  old and new stack and builds the event for every slot change of every player, roughly 1-3 microseconds per change
  including our two PDC reads (a plain stack allocates one empty tag; a magic one adds a 41-slot recount, about the
  same again, when it is next read). Slot changes in normal play are about 0-3 per player per second (placing or
  using stack items, pickups, drops), in bursts of a few dozen when a container opens (up to 36 stacks reported at
  once). Nothing here runs per tick or per movement. Hooking `InventoryClick`/pickup/drop instead would be cheaper but
  misses plugin `setItem`/`addItem`, consumption and crafting, so it is not equally reliable.

### Timers while carrying or holding

Don't poll every online player's hand or inventory every few ticks to find the few holding a magic item. Ask
SneakyItems for a [SneakyUtils per-player timer](https://github.com/Team-Sneakymouse/SneakyUtils/blob/main/docs/triggers.md#timers)
that contains exactly the players who have it:

```java
// every 5 ticks for each player with a lantern anywhere in their own inventory
ItemWatch glow = SneakyItemsAPI.whileCarrying(plugin, "item-lantern", 5, p -> lantern.glow(p));
// every 2 ticks for each player with the wand in the selected hotbar slot
ItemWatch aim = SneakyItemsAPI.whileHolding(plugin, "item-wand", HeldSlot.MAIN_HAND, 2, p -> wand.showAim(p),
    PlayerTimerOptions.builder().label("myplugin:wand-aim").build());
// later, when the feature stops
glow.close();
```

- `whileCarrying(owner, itemId, intervalTicks, handler[, options])`: players with `carries(player, itemId)` (storage,
  hotbar, armor, offhand). `whileHolding(owner, itemId, where, intervalTicks, handler[, options])`: players with the
  item in `where`: `MAIN_HAND` (selected hotbar slot), `OFF_HAND`, `EITHER_HAND`, `ARMOR` (any armor slot) or `ANY`
  (hands or armor). The handler is SneakyUtils' `PlayerTick`; `options` are its `PlayerTimerOptions` (`label`,
  `skipDead`), scope `ADDED` only. Interval 1..72,000 ticks.
- The result is an `ItemWatch` (`AutoCloseable`) around a SneakyUtils per-player timer owned by `owner`: `close()` it
  to stop (idempotent, main thread); it also ends when `owner` disables, and when SneakyItems disables. `isActive()`,
  `contains(player)`, `getSize()`, and read-only diagnostics `getTimerId()` (the `/triggers` id) and `getLabel()`.
  The timer itself is never handed out; SneakyItems alone adds and removes the players. Watches are independent of
  each other. (It is not a SneakyUtils `Subscription` itself: only SneakyUtils may implement that interface.)
- Subscribing reads every online player's inventory fresh (a carried recount, a held re-read), so a `setItem` made just
  before counts; differences found that way also update the existing watches. Subscribing from a
  `CarriedItemChangeEvent` listener works too: the new watch starts from a live read, and once the current batch of
  events (and the recounts it deferred) has been delivered, its players are set to the recounted state. If a
  `CarriedItemChangeEvent` listener changes the inventory in response to those recounts, SneakyItems recounts again
  (comparing with the live inventory) before setting the players, so every watch ends up matching the inventory. Afterwards a player enters when the item
  arrives and leaves when it goes or they quit; rejoining with it adds them again. A player whose quit has started is
  never enrolled, even by a watch made from a `PlayerQuitEvent` handler (Paper still lists them as online then).
  - Carrying follows the [carried index](#carried-items): the change is seen on the next scheduler pass after Paper
    reports the slot change (pickup, drop, crafting, a plugin's `setItem`, ...).
  - Holding re-reads the six places (selected slot, offhand, four armor slots) from the live inventory: hotbar
    scrolls and hand swaps apply in the event itself (after other plugins could cancel it); inventory moves, pickups
    into the held slot and armor equips when Paper reports the slot change or equipment change (right-click equip
    included); item breaks, dispenser equips, deaths, respawns and world changes are checked on the next tick.
    Paper's spurious slot events (unchanged slots re-reported when a container opens) change nothing.
- Dead players: SneakyUtils pauses dead players in per-player timers by default. A death that keeps the inventory
  leaves the player in the timer, not called until the respawn; a death that drops the item removes them. Pass
  `PlayerTimerOptions.builder().skipDead(false)` to keep calling dead holders.
- Membership follows SneakyItems' own indexes, not the public `CarriedItemChangeEvent` (anyone can fire one; a forged
  event changes nothing). That event is still fired for listeners as before.
- Cost: nothing per tick for players without the item (they are in no timer), and nothing at all while no watch
  exists. Held tracking runs from the first `whileHolding` until the last one ends, with handlers for slot, equipment,
  held-slot and swap events that read at most six slots when an item id changes (durability loss on a held tool costs
  two PDC reads). While a `whileCarrying` exists, the carried index recounts a player on the tick after a magic-item
  slot change (as when the change event has listeners).

- Enter / leave callbacks: pass a `WatchMembership` to the overloads that take one. `entered(player)` runs when a player
  joins the timer (the item arrived, or they had it when you subscribed), before their first tick from it; `left(player)`
  when they leave (the item went, they quit, or you closed the watch), after their last. No `left` runs when the owner
  disables. Keep them short; one that throws is logged and ignored.

### Item rules

Generic rules for special items, so a plugin keeps only what is special about its items (the Dragon Orbs in
SneakyLegendarys use all of them). Items are matched by magic item id; register the definitions first.

```kotlin
val orbs = SneakyItemsAPI.itemRules(plugin, "dragon_orbs", (1..9).map { "legendary_dragon_orb_$it" },
    ItemRules.builder()
        .carryLimit(1).bypassPermission("myplugin.admin").bypass { p -> hasTag(p, "monica") }
        .noContainers(true).protectOnGround(true).dropOnLeave(true)
        .unique(true).placement(Placement())
        .hooks(object : ItemRuleHooks {
          override fun refused(player: Player, itemId: String, reason: RuleRefusal) = player.sendActionBar(...)
        })
        .build())
orbs.give("legendary_dragon_orb_3", player)   // a new live copy; older copies go stale
orbs.locate("legendary_dragon_orb_3")         // holder, entity, or last saved spot
```

| Rule | Effect |
|------|--------|
| `carryLimit(n)` | At most `n` items of the group per player (inventory, armor, offhand, cursor and crafting grid together). Pickups and taking a placed one back past it are refused. `bypassPermission` / `bypass` exempt players (check characters, not accounts, in `bypass`). |
| `noContainers` | Never in a container (ender chest and the 2x2 crafting grid included), a bundle, an item frame or other entity, an armour stand, a hopper, or an insert block (decorated pot, shelves, chiseled bookshelf, lectern, jukebox, flower pot, campfire, composter, vault). The creative tab is exempt. |
| `protectOnGround` | Dropped copies never despawn, can't be hurt, and no mob or hopper picks them up. Copies get `magicspells:magicspellpermanentdata_grounditem_placeable = "false"` so SneakyGroundItems leaves them vanilla drops (its display pickup would skip the carry limit). |
| `dropOnLeave` | Death (even with keepInventory) and quit drop them where the player is. |
| `unique` | One live copy per item id. Copies made by the group carry a serial (`serialKey`, default `sneakyitems:serial`); any copy whose serial isn't the newest vanishes when seen (inventory click, join, pickup, chunk load, character switch). The group keeps where each item is in `plugins/SneakyItems/unique/<owner>-<group>.yml` and checks every 5 s: an item whose holder no longer has it, or whose ground entity is gone from a loaded chunk, comes back on the ground where it was last (lifted out of the void). With `dropOnLeave`, a SneakyCharacterManager character switch puts the held one back on the ground too. A stack without a serial (a plain give of the definition) is never live: make copies with `create` / `give` / `respawn`. `adopt(id, serial)` keeps copies from before the group existed live. |
| `placement(Placement(scale, lift, hitbox))` | Right-click the top of a block to set it down as an `ItemDisplay` with an `Interaction` hitbox (tag `sneakyitems_placed`); sneak to place on blocks with their own use. Click it (either button) to take it back. `place(id, location)` places from code. |

`ItemRuleHooks` (all optional) hear `refused` (at most once per 2 s per player, so it can message as is), `pickedUp`,
`dropped` (thrown, dropped on leave, respawned), `placed` and `taken`. A group ends with `close()`, when its owner
disables, and when SneakyItems disables; registering the same owner + group id again replaces it. Main thread only.

`SneakyItemsAPI` lookups take the internal name. "Inventory" (`inInventory`, `findSlots`, `count`) means the main
inventory plus the offhand; armor slots (`wearing`, optionally one slot) and the cursor don't count.

## Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional): `/diagnostics plugins start 60 SneakyItems`.
Setup and rules: [plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Sections: `event.inventory-open`, `event.item-spawn`, `task.recheck`, `player.load-inventories`, `player.process-inventories`, `inventory.process` (with `inventory.read-contents`, `inventory.items`, `inventory.write-contents`), per item `item.tag`, `item.update`, `item.rebuild`, `item.rebuild.container-contents`, and `item-data.extract` (reading the meta on an item-data cache miss); counts: `items.processed`, `items.magic`, `items.version-current`, `items.stamped`, `items.rebuilt`, `items.tagged`, `inventory.slots`, `match.calls`, `tag.mapping-checks`, `tag.known-name-skips`, `tag.type-skips`, `recheck.entities`, `recheck.entities-filtered`, `recheck.entities-gone`, `recheck.players`, `item-data.cache-hits`, `item-data.cache-misses`; levels: `item-data.cache-size`, `tag.mappings`, `magic-items.registered` (sampled while inventories are processed, so idle seconds have no sample).
Items inside a rebuilt container item are counted but not timed per item (their time is in `item.rebuild.container-contents`).
Cache lookups (each hashes the stack and compares equal keys) are counted (`item-data.cache-hits` + `item-data.cache-misses`), not timed one by one: legacy tagging can check an untagged item against many mappings (about 1.5k in total, fewer after the type and model index), and a timer per lookup (~40 ns while recording) would inflate the path it measures. Their cost is in `item.tag` self time: divide by `tag.mapping-checks` for the cost per mapping check (lookup and comparison together). `match.calls` and the cache counts include calls from other plugins (MagicSpells matching) on the server thread; calls from other threads are not recorded.

## Development

| Task | Command |
|------|---------|
| Build, unit tests and publish preflight | `./gradlew check` |
| Local Paper server | `./gradlew runServer` |

Builds need a git checkout with at least one commit: the version is `yyyy.MM.dd-<12-char-git-hash>` of `HEAD`.
Build output goes to `~/.cache/SneakyItems-gradle-build`, which keeps Gradle off slow FUSE/NTFS checkouts.
Coding agents follow [AGENTS.md](./AGENTS.md).

## Publishing

Every push to `main` publishes `io.github.team-sneakymouse:sneakyitems:<version>` to `https://maven.sneakyrp.com/sneakymouse`
through the shared [`Team-Sneakymouse/ci`](https://github.com/Team-Sneakymouse/ci) workflow
(`.github/workflows/publish.yml`). The repository must be allowlisted for Infisical OIDC; private repositories
also need the `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` secrets.
