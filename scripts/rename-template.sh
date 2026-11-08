#!/usr/bin/env bash
# Turn a fresh copy of the EmptyPlugin template into a new plugin, then delete this script.
#
# Usage:
#   bash scripts/rename-template.sh <PluginName> [plugin-id]
#   bash scripts/rename-template.sh --self-test    # template CI: rename a scratch copy and verify it
#
# <PluginName>  class, jar and Gradle project name, e.g. LootTables.
# [plugin-id]   lowercase id for the package (com.<id>), command, permissions and Maven artifactId.
#               Defaults to <PluginName> in lowercase.
#
# The GitHub URLs and the publish.yml repository check use the origin remote's GitHub repository,
# or Team-Sneakymouse/<PluginName> if origin is missing or still points at the template.
set -euo pipefail

TEMPLATE_OWNER="Team-Sneakymouse"
TEMPLATE_NAME="EmptyPlugin"
TEMPLATE_ID="emptyplugin"
TEMPLATE_DESCRIPTION="Standard Paper plugin template for Team-Sneakymouse / Lords of Minecraft."
BLOCK_BEGIN='<!-- EMPTYPLUGIN-ONLY LOCAL EXCEPTION'
BLOCK_END='<!-- END EMPTYPLUGIN-ONLY LOCAL EXCEPTION -->'
TEMPLATE_GRADLE="gradle/template.gradle.kts"
SELF="scripts/rename-template.sh"

die() {
  printf 'rename-template: %s\n' "$*" >&2
  exit 1
}

# Replace every occurrence of $2 with $3 in file $1 (literal strings; dies if $2 is missing).
replace_literal() {
  local file="$1"
  FROM="$2" TO="$3" perl -0pi -e '$n = s/\Q$ENV{FROM}\E/$ENV{TO}/g; END { exit($n ? 0 : 3) }' "$file" ||
    die "expected text not found in $file: $2"
}

validate() {
  local name="$1" id="$2"
  [[ "$name" =~ ^[A-Z][A-Za-z0-9]*$ ]] ||
    die "plugin name must start with an uppercase letter and contain only letters and digits (e.g. LootTables): '$name'"
  [[ "$id" =~ ^[a-z][a-z0-9_]*$ ]] ||
    die "plugin id must be lowercase letters, digits or _ and start with a letter (e.g. loottables): '$id'"
  [[ "$name" != "$TEMPLATE_NAME" && "$id" != "$TEMPLATE_ID" ]] ||
    die "pick a name other than $TEMPLATE_NAME / $TEMPLATE_ID"
  case "$id" in
    as | break | class | continue | do | else | false | for | fun | if | in | interface | is | null | object | \
      package | return | super | this | throw | true | try | typealias | typeof | val | var | when | while)
      die "plugin id '$id' is a Kotlin keyword and cannot be a package name; pass a different [plugin-id]" ;;
  esac
}

rename() {
  local name="$1" id="$2"
  validate "$name" "$id"

  grep -q "rootProject.name = \"$TEMPLATE_NAME\"" settings.gradle.kts && [[ -f "$TEMPLATE_GRADLE" ]] ||
    die "this checkout is not an unrenamed $TEMPLATE_NAME template (settings.gradle.kts or $TEMPLATE_GRADLE changed)"
  [[ -z "$(git status --porcelain)" ]] ||
    die "commit or stash your changes first, so the rename shows up as one reviewable diff"

  # 1. Packages and files named after the template.
  local set
  for set in main test; do
    git mv "src/$set/kotlin/com/$TEMPLATE_ID" "src/$set/kotlin/com/$id"
  done
  git mv "src/main/kotlin/com/$id/$TEMPLATE_NAME.kt" "src/main/kotlin/com/$id/$name.kt"
  git mv "src/test/kotlin/com/$id/${TEMPLATE_NAME}Test.kt" "src/test/kotlin/com/$id/${name}Test.kt"

  # 2. Template-only files. Drop this script from the index now so step 5 skips it; the file goes at the end.
  git rm -q "$TEMPLATE_GRADLE"
  git rm -q --cached "$SELF"

  # 3. Template-only instruction blocks, then collapse the blank lines they leave behind.
  local file
  while IFS= read -r -d '' file; do
    BEGIN="$BLOCK_BEGIN" END_="$BLOCK_END" perl -0pi -e '
      s/^[ \t]*\Q$ENV{BEGIN}\E.*?\Q$ENV{END_}\E[ \t]*\r?\n//gms;
      s/(\r?\n)(?:[ \t]*\r?\n){2,}/$1$1/g;
    ' "$file"
    if grep -qF -e "$BLOCK_BEGIN" -e "$BLOCK_END" "$file"; then
      die "$file has an unmatched template block marker; fix it and start over from a clean checkout"
    fi
  done < <(git ls-files -z -- '*.md' '*.mdc' | xargs -0 grep -lF --null -e "$BLOCK_BEGIN" -e "$BLOCK_END" || true)

  # 4. Wording that only makes sense in the template.
  replace_literal AGENTS.md "This repo is a **Paper plugin template**" "This repo is a **Paper plugin**"
  replace_literal AGENTS.md "| $TEMPLATE_NAME (Paper plugin template) |" "| $name (Paper plugin) |"
  replace_literal build.gradle.kts "$TEMPLATE_DESCRIPTION" "$name for Team-Sneakymouse / Lords of Minecraft."
  replace_literal src/main/resources/paper-plugin.yml "$TEMPLATE_DESCRIPTION" "$name for Team-Sneakymouse / Lords of Minecraft."
  write_readme "$name" "$id"

  # 5. Every remaining EmptyPlugin / emptyplugin (case-sensitive) in tracked text files.
  local files=()
  while IFS= read -r -d '' file; do files+=("$file"); done < <(
    git ls-files -z | xargs -0 grep -lI --null -e "$TEMPLATE_NAME" -e "$TEMPLATE_ID" || true
  )
  if ((${#files[@]})); then
    NAME="$name" ID="$id" perl -pi -e "s/\Q$TEMPLATE_NAME\E/\$ENV{NAME}/g; s/\Q$TEMPLATE_ID\E/\$ENV{ID}/g" "${files[@]}"
  fi

  # 6. Step 5 made the GitHub URLs and the publish.yml check Team-Sneakymouse/<name>; use the real repository.
  local repo
  repo="$(github_repo "$name")"
  if [[ "$repo" != "$TEMPLATE_OWNER/$name" ]]; then
    files=()
    while IFS= read -r -d '' file; do files+=("$file"); done < <(
      git ls-files -z | xargs -0 grep -lIF --null -e "$TEMPLATE_OWNER/$name" || true
    )
    if ((${#files[@]})); then
      FROM="$TEMPLATE_OWNER/$name" TO="$repo" perl -pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/g' "${files[@]}"
    fi
  fi

  rm -f "$SELF"
  git add -A

  cat <<EOF
Renamed $TEMPLATE_NAME -> $name (package com.$id, command /$id, artifact io.github.team-sneakymouse:$id).
GitHub repository: $repo (publish.yml only publishes from there; build.gradle.kts and paper-plugin.yml link to it).
All changes are staged; review them with: git diff --cached

Still to do by hand:
  1. Write the plugin description in README.md, build.gradle.kts and paper-plugin.yml.
  2. Make sure Infisical OIDC allowlists the repository (private repos also need the INFISICAL_* secrets).
  3. Run ./gradlew check, then commit. Pushing to main publishes the plugin.
EOF
}

# owner/repo of the GitHub origin remote; Team-Sneakymouse/<name> if there is none or it is still the template.
github_repo() {
  local name="$1" url repo
  url="$(git remote get-url origin 2>/dev/null || true)"
  url="${url%/}"
  url="${url%.git}"
  if [[ "$url" =~ github\.com[:/]([^/]+/[^/]+)$ ]]; then
    repo="${BASH_REMATCH[1]}"
    # GitHub repository names are case-insensitive.
    if [[ "${repo,,}" != "${TEMPLATE_OWNER,,}/${TEMPLATE_NAME,,}" ]]; then
      printf '%s\n' "$repo"
      return
    fi
  fi
  printf '%s\n' "$TEMPLATE_OWNER/$name"
}

write_readme() {
  local name="$1" id="$2"
  cat >README.md <<EOF
# $name

TODO: describe what $name does.

## Usage

Run \`/$id reload\` in-game or \`$id reload\` from the console to re-read \`plugins/$name/config.yml\`.
The permission is \`$id.command.reload\` (default: operators).

## Development

| Task | Command |
|------|---------|
| Build, unit tests and publish preflight | \`./gradlew check\` |
| Local Paper server | \`./gradlew runServer\` |

Builds need a git checkout with at least one commit: the version is \`yyyy.MM.dd-<12-char-git-hash>\` of \`HEAD\`.
Build output goes to \`~/.cache/$name-gradle-build\`, which keeps Gradle off slow FUSE/NTFS checkouts.
Coding agents follow [AGENTS.md](./AGENTS.md).

## Publishing

Every push to \`main\` publishes \`io.github.team-sneakymouse:$id:<version>\` to \`https://maven.sneakyrp.com/releases\`
through the shared [\`Team-Sneakymouse/ci\`](https://github.com/Team-Sneakymouse/ci) workflow
(\`.github/workflows/publish.yml\`). The repository must be allowlisted for Infisical OIDC; private repositories
also need the \`INFISICAL_IDENTITY_ID\` and \`INFISICAL_DOMAIN\` secrets.
EOF
}

SCRATCH=""
self_test() {
  SCRATCH="$(mktemp -d)"
  trap 'rm -rf "$SCRATCH"' EXIT

  # Copy the working tree as it is now (tracked + new files, uncommitted edits included) into a fresh repo.
  local file existing=()
  while IFS= read -r -d '' file; do
    [[ -f "$file" ]] && existing+=("$file")
  done < <(git ls-files -z --cached --others --exclude-standard)
  tar -cf - "${existing[@]}" | tar -xf - -C "$SCRATCH"
  (
    cd "$SCRATCH"
    git init -q && git config core.safecrlf false
    git add -A
    git -c user.name=rename-smoke -c user.email=rename-smoke@invalid -c commit.gpgsign=false commit -qm template
  )

  local script="$SCRATCH/$SELF"
  expect_failure() {
    if bash "$script" "$@" >/dev/null 2>&1; then die "self-test: expected '$*' to be rejected"; fi
  }
  expect_failure bad-name
  expect_failure "$TEMPLATE_NAME"
  expect_failure Ok Bad-Id
  expect_failure Ok when
  touch "$SCRATCH/untracked-file"
  expect_failure SmokeTestPlugin
  rm "$SCRATCH/untracked-file"

  # A repository named differently from the plugin; the URLs and publish check must follow origin.
  local repo="Smoke-Owner/smoke-test-repo"
  git -C "$SCRATCH" remote add origin "https://github.com/$repo.git"
  bash "$script" SmokeTestPlugin >/dev/null

  cd "$SCRATCH"
  grep -qF "if: github.repository == '$repo'" .github/workflows/publish.yml ||
    die "self-test: publish.yml does not check $repo"
  grep -qF "website: https://github.com/$repo" src/main/resources/paper-plugin.yml ||
    die "self-test: paper-plugin.yml does not link $repo"
  [[ "$(grep -cF "github.com/$repo" build.gradle.kts)" == 3 ]] || die "self-test: build.gradle.kts does not link $repo"
  ! git grep -q -e "$TEMPLATE_OWNER/SmokeTestPlugin" || die "self-test: Team-Sneakymouse/SmokeTestPlugin left after rename"
  [[ "$(github_repo X)" == "$repo" ]] || die "self-test: github_repo misread origin"
  local url
  for url in git@github.com:Team-Sneakymouse/EmptyPlugin.git https://github.com/team-sneakymouse/emptyplugin; do
    git remote set-url origin "$url"
    [[ "$(github_repo X)" == "$TEMPLATE_OWNER/X" ]] || die "self-test: github_repo did not fall back for $url"
  done
  git remote set-url origin git@github.com:Other/Repo.git/
  [[ "$(github_repo X)" == "Other/Repo" ]] || die "self-test: github_repo misread ssh origin"
  git remote remove origin
  [[ "$(github_repo X)" == "$TEMPLATE_OWNER/X" ]] || die "self-test: github_repo did not fall back without origin"
  local leftovers
  leftovers="$(git grep -n -I -e "$TEMPLATE_NAME" -e "$TEMPLATE_ID" || true)"
  # templatePolicyPreflight stays in renamed plugins on purpose (it keeps template blocks from coming back).
  leftovers+="$(git grep -n -I -i -e 'EMPTYPLUGIN-ONLY' -e template -- '*.md' '*.mdc' | grep -v 'templatePolicyPreflight' || true)"
  [[ -z "$leftovers" ]] || die "self-test: template text left after rename:"$'\n'"$leftovers"
  local expected
  for expected in src/main/kotlin/com/smoketestplugin/SmokeTestPlugin.kt \
    src/test/kotlin/com/smoketestplugin/SmokeTestPluginTest.kt; do
    [[ -f "$expected" ]] || die "self-test: missing $expected"
  done
  for expected in "$TEMPLATE_GRADLE" "$SELF" "src/main/kotlin/com/$TEMPLATE_ID"; do
    [[ ! -e "$expected" ]] || die "self-test: $expected should be gone"
  done
  grep -q 'rootProject.name = "SmokeTestPlugin"' settings.gradle.kts || die "self-test: settings.gradle.kts not renamed"
  grep -q 'const val IDENTIFIER = "smoketestplugin"' src/main/kotlin/com/smoketestplugin/SmokeTestPlugin.kt ||
    die "self-test: IDENTIFIER not renamed"
  [[ -z "$(git status --porcelain --untracked-files=all | grep -v '^[MADR] ' || true)" ]] ||
    die "self-test: rename left unstaged changes"
  printf 'rename-template: self-test ok\n'
}

main() {
  cd "$(dirname "${BASH_SOURCE[0]}")/.."
  case "${1:-}" in
    --self-test) self_test ;;
    "" | -h | --help) sed -n '2,13p' "$SELF" | sed 's/^# \{0,1\}//'; [[ -n "${1:-}" ]] ;;
    *) rename "$1" "${2:-$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')}" ;;
  esac
}

# Everything above is parsed before main runs, so the script can safely delete itself.
main "$@"
