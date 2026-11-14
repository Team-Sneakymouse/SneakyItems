# SneakyItems

Paper plugin that owns the LoM **magic item** platform: definition registry, YAML/stack parsing, PDC stamping,
inventory updater, legacy ItemTag mappings, and soulbound/expiration enforcement. Other plugins (notably
**MagicSpells**) register definitions via the public API without embedding item parsers.

**Legendary items:** documented design only (see [AGENTS.md](./AGENTS.md)); not implemented in this plugin.

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
| `SneakyItemsAPI.keyOf` / `isItem` / `inMainHand` / `inOffHand` / `inEitherHand` / `onHotbar` / `inInventory` / `wearing` / `findSlots` / `count` | Find a magic item by internal name on a stack or player |

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

### Magic item clicks

`MagicItemClickEvent` fires when a player clicks with a stack whose `magicitem` PDC key is set. Listen to it instead of
`PlayerInteractEvent` so every plugin handles clicks the same way. It carries the item's internal name (`itemKey`),
the live stack, the hand, `click` (`LEFT`/`RIGHT`) and `trigger` (`AIR`, `BLOCK`, `ENTITY`, `ARM_SWING`) with the
clicked block/face or entity.

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

Every push to `main` publishes `io.github.team-sneakymouse:sneakyitems:<version>` to `https://maven.sneakyrp.com/releases`
through the shared [`Team-Sneakymouse/ci`](https://github.com/Team-Sneakymouse/ci) workflow
(`.github/workflows/publish.yml`). The repository must be allowlisted for Infisical OIDC; private repositories
also need the `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` secrets.
