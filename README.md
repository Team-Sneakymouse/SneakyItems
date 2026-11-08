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

Creative soulbound bypass: `sneakyitems.nosoulbound` **and** Creative mode.

## Usage

Run `/sneakyitems reload` in-game or `sneakyitems reload` from the console to re-read `plugins/SneakyItems/config.yml`.
The permission is `sneakyitems.command.reload` (default: operators).

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
