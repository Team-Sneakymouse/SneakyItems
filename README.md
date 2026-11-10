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
  tagged with a changed name are rebuilt (untagged or unknown-tag stacks are still checked when legacy
  mappings exist). An unchanged reload queues nothing.
- **`/sneakyitems reload`** queues everything.

The queue doesn't drain while definitions are still changing, or before the server starts ticking, so
entities loaded at boot wait until all plugins have registered their items. Item spawns, joins, character
loads and inventory opens are still handled right away.

Legacy tagging skips stacks already tagged with a registered name, and only compares a stack against
mappings with the same item type and model (custom model data or item model).

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
