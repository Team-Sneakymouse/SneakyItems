# EmptyPlugin

Standard **Paper** plugin template for Team-Sneakymouse / Lords of Minecraft.

Clone this repo (or use it as a GitHub template), run `bash scripts/rename-template.sh <PluginName>` (see [Rename checklist](#rename-checklist-new-plugin-from-this-template)), and start implementing.

## What’s included

- Kotlin + Java 25 toolchain, Gradle wrapper (9.3+)
- `run-paper` for a local Paper `26.2` server (`./gradlew runServer` stages jars for every `paper-plugin.yml` server dependency by matching `compileOnly` plugin jars)
- JUnit 5 + MockBukkit (`mockbukkit-v26.2`) unit test setup under `src/test/kotlin`
- Git-derived versions and `maven-publish` to `https://maven.sneakyrp.com/releases`
- GitHub Actions CI and publish workflows that call the shared [`Team-Sneakymouse/ci`](https://github.com/Team-Sneakymouse/ci) Gradle workflow (Infisical OIDC → Maven credentials)
- `scripts/rename-template.sh` — one-step rename of the template into a new plugin
- **[AGENTS.md](./AGENTS.md)** — shared instructions for LLM coding agents (Cursor, Claude Code, Codex, etc.)

## Plugin reload

Run `/emptyplugin reload` in-game or `emptyplugin reload` from the console to re-read `plugins/EmptyPlugin/config.yml`. The permission is `emptyplugin.command.reload` (default: operators). This reloads configuration only, not plugin code or the server.

Startup and the command share `EmptyPlugin.reloadPlugin()` as the entry point for applying configuration. The command uses Brigadier to simplify native target selector usage in future commands.

## Versioning

Builds **require a git checkout** with at least one commit. The version is:

```text
yyyy.MM.dd-<12-char-git-hash>
```

derived from `git show -s --format=%ct:%h --abbrev=12 HEAD` (UTC date + abbreviated hash).

Plugin JAR name: `EmptyPlugin-<version>.jar`.

Maven coordinates (after publish):

```text
io.github.team-sneakymouse:emptyplugin:<version>
```

## Publish flow

On every push to `main`, `.github/workflows/publish.yml` calls the shared
`Team-Sneakymouse/ci/.github/workflows/gradle-publish.yml@main` workflow, which:

1. Builds with Java 25
2. Fetches `MAVEN_USERNAME` / `MAVEN_PASSWORD` from Infisical via OIDC
3. Runs `./gradlew publishMavenPublicationToSneakyrpRepository`

The publish job only runs in `Team-Sneakymouse/EmptyPlugin`, so any copy of the template (from "Use this template", a fork, or a pushed clone) skips publishing instead of deploying a jar named `EmptyPlugin`. The rename script points that check (and the other GitHub URLs) at the new repository. Push the renamed result to `main` to publish the new plugin.

`.github/workflows/ci.yml` calls the same workflow with `publish: false` for pull requests (pushes to `main` are already built by the publish workflow).
Both run on the organization's self-hosted `alphaarcadeus` runner. The steps live in the shared repository, so
changes to them are made there; see its README for the inputs (`publish-task`, `project-directory`, `runs-on`).

### GitHub secrets (repository-level setup only for private repos)

Public Team-Sneakymouse repositories inherit the following organization secrets; no repository-level setup is needed. Private repositories must configure these secrets in the repository's GitHub Actions settings.

| Secret | Purpose |
|--------|---------|
| `INFISICAL_IDENTITY_ID` | Infisical machine identity for OIDC |
| `INFISICAL_DOMAIN` | Infisical domain (e.g. `https://…`) |

The Infisical identity must allow OIDC from this repository (`Team-Sneakymouse/EmptyPlugin`). Allowlist the new repo in Infisical if the first Actions run fails at the secrets step.

Credentials are passed to Gradle as `sneakyrpUsername` / `sneakyrpPassword` (`PasswordCredentials` for the `sneakyrp` Maven repository).

## Internal dependencies

The template enables `https://maven.sneakyrp.com/releases` and depends on **SneakyUtils**
(`compileOnly` + required `paper-plugin.yml` server dependency, load `BEFORE`) for shared
helpers such as `com.sneakyutils.util.TextUtility`. Do not vendor a local copy of those
utilities — bump the `sneakyUtilsVersion` in `build.gradle.kts` when you need a newer build.

Add further plugin dependencies the same way: `compileOnly("group:artifact:version")` plus a
`dependencies.server.<PluginName>` entry in `paper-plugin.yml`. `./gradlew runServer` stages
matching jars automatically (by reading each jar’s `paper-plugin.yml` / `plugin.yml` `name:`);
required server deps with no matching `compileOnly` jar fail the task.

## Rename checklist (new plugin from this template)

Run the rename script from a clean checkout, then review and commit the staged result:

```bash
bash scripts/rename-template.sh LootTables            # package com.loottables, command /loottables
bash scripts/rename-template.sh LootTables loot       # pick a different lowercase id
git diff --cached
./gradlew check
```

The script renames the Gradle project, Maven `artifactId`, POM and `paper-plugin.yml` metadata, the `com.emptyplugin`
package (main and test, via `git mv`), the main class, `IDENTIFIER`, the command, permissions and tests. It also removes
the template-only `AGENTS.md` rules (every block between the `EMPTYPLUGIN-ONLY LOCAL EXCEPTION` markers), replaces this
README with a short stub, and deletes `gradle/template.gradle.kts` (its self-test) and itself. The GitHub URLs and the
`publish.yml` repository check point at the `origin` remote's GitHub repository, or `Team-Sneakymouse/<PluginName>`
when `origin` is missing or still the template.

Still by hand:

1. Write the real plugin description in `README.md`, `build.gradle.kts` and `paper-plugin.yml`.
2. Ensure Infisical OIDC allowlists the new repo. Public repos inherit the organization secrets; only private repos need repository-level `INFISICAL_IDENTITY_ID` and `INFISICAL_DOMAIN` secrets.
3. Keep the publication named `maven`, or pass `publish-task: <task>` under `with:` in `publish.yml` (the shared workflow defaults to `publishMavenPublicationToSneakyrpRepository`).
4. Keep the `SneakyUtils` server dependency unless you deliberately remove that shared util usage.

Template-only rules stay out of the new repository: follow the shared policy unless it records a separately approved, accurately scoped exception. `./gradlew templatePolicyPreflight` (part of `check`) fails if a template-only block reappears in `AGENTS.md`.

### Changing the template

Text tied to the template identity must stay renameable: use `EmptyPlugin` / `emptyplugin` only where the plugin name
belongs, wrap template-only agent rules in the `EMPTYPLUGIN-ONLY LOCAL EXCEPTION` begin/end markers, and put template-only
Gradle logic in `gradle/template.gradle.kts`. `./gradlew check` runs `templateRenameSmoke`, which renames a scratch copy
and fails if template text is left behind.

### Publish preflight (agents / CI)

`scripts/publish-preflight.sh` is wired into `./gradlew check`. The same check also runs `templatePolicyPreflight`, which catches template-only agent rules copied into a renamed plugin. Run the publish preflight against any plugin checkout:

```bash
bash /path/to/EmptyPlugin/scripts/publish-preflight.sh /path/to/OtherPlugin
```

## Local build

```bash
./gradlew build
```

Gradle writes under `~/.cache/EmptyPlugin-gradle-build` so checkouts on NTFS/FUSE (e.g. `/mnt/files`) do not wedge `test-results`. A leftover repo `build/` directory is unused; delete it if present. If `./gradlew check` fails deleting `test-results`, run `./gradlew --stop` and retry.

For local `runServer`, prefer `run/` as a symlink to a native path (e.g. `~/.cache/emptyplugin-run`) — Paper world COW/file locks break on FUSE mounts.

## Testing

```bash
./gradlew test          # unit tests only
./gradlew check         # tests + other verification (same gate as CI)
```

Example tests: `TextUtilityTest` (SneakyUtils `TextUtility` smoke), `CommandBaseTest` (permissions), `EmptyPluginTest` (MockBukkit load/enable).

## Agent / LLM instructions

Point your tool’s project instructions at **[AGENTS.md](./AGENTS.md)**. Cursor also loads `.cursor/rules/`; Claude Code reads `CLAUDE.md`; GitHub Copilot can use `.github/copilot-instructions.md`.
