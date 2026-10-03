#!/usr/bin/env bash
# Check that the loader versions pinned in supported-versions.csv are still the ones the tooling
# would pick, so a stale row fails here instead of shipping a jar a player cannot load.
#
#   NeoForge  every row's floor must equal what tools/neoforge-floor.sh prints for that Minecraft —
#             the oldest stable build of the line, or the newest beta when the line has none. One
#             row is a deliberate exception, listed below with its reason.
#   Fabric    every row's Fabric API must exist and carry that Minecraft as its "+<mc>" suffix, its
#             Mod Menu must exist, and the Fabric loader the build pins must be compatible with
#             every row. None of the three has a "the tool would pick this" rule — they are the
#             versions the mod is compiled and tested against — so the check is that they are real
#             and belong to that Minecraft.
#
# Exit 0 when everything lines up, 1 when something does not, 2 when upstream data could not be
# fetched. A network failure must not read as "the pins are wrong" — the two need different
# reactions.
#
#   tools/check-loader-versions.sh [csv]
#
# The csv argument exists so a deliberately broken copy can be run through it.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
csv="${1:-$repo_root/supported-versions.csv}"
floor_tool="$repo_root/tools/neoforge-floor.sh"

# Rows whose NeoForge floor is deliberately NOT what the rule picks, because that build cannot be
# compiled against. See the note at the top of supported-versions.csv.
declare -A NEOFORGE_EXCEPTIONS=(
  [1.21.10]=21.10.64
)

work=$(mktemp -d)
trap 'rm -rf "$work" || true' EXIT

failures=0
mismatch() {
  printf 'MISMATCH  %s\n' "$1" >&2
  failures=$((failures + 1))
}

fetch() {
  if ! curl -fs --retry 3 --retry-all-errors --max-time 120 "$1" -o "$2"; then
    echo "could not fetch $1" >&2
    exit 2
  fi
}

rows=$(grep -v '^#' "$csv" | tr -d ' \t\r' | grep -v '^$')
if [ -z "$rows" ]; then
  echo "no data rows in $csv" >&2
  exit 1
fi

# ------------------------------------------------------------------ NeoForge

# Same derivation as tools/neoforge-floor.sh: the loader drops the "1." prefix, and a two-part
# version means patch 0.
neo_line() {
  case "$1" in
    1.*.*) printf '%s' "${1#1.}" ;;
    1.*)   printf '%s' "${1#1.}.0" ;;
    *)     printf '%s' "$1" ;;
  esac
}

# The csv is ordered oldest first, so its last row is the newest Minecraft — and therefore the only
# line NeoForge may still be publishing builds for. That distinction is what makes this check stable:
# on a closed line the newest beta is frozen, so the rule's answer can be compared exactly, while on
# the open line it moves every time upstream publishes, and a floor that has fallen behind is still
# a perfectly good lower bound. Reporting that as a failure would leave CI red more often than green.
newest_mc=${rows##*$'\n'}
newest_mc=${newest_mc%%,*}
newest_line=$(neo_line "$newest_mc")

# One call for every row: the tool fetches the version list once and answers for all of them.
neo_mcs=()
while IFS=, read -r mc _java _family floor _api _modmenu; do
  [ "$floor" = '-' ] && continue
  neo_mcs+=("$mc")
done <<<"$rows"

neo_out=$("$floor_tool" "${neo_mcs[@]}" 2>&1) || {
  status=$?
  if [ "$status" = 2 ]; then
    echo "$neo_out" >&2
    exit 2
  fi
  # Status 1 means some line had no builds at all; the per-row comparison below reports which.
}

declare -A expected_by_mc=()
while read -r mc floor; do
  expected_by_mc[$mc]=$floor
done < <(printf '%s\n' "$neo_out" | awk '
  /^mc      / { mc = substr($0, 9) }
  /^floor   / { print mc " " substr($0, 9) }
')

neo_rows=0
for mc in "${neo_mcs[@]}"; do
  neo_rows=$((neo_rows + 1))
  floor=$(grep -m1 "^$mc," <<<"$rows" | cut -d, -f4 | tr -d ' ')
  expected=${NEOFORGE_EXCEPTIONS[$mc]:-${expected_by_mc[$mc]:-}}

  if [ -z "$expected" ]; then
    mismatch "NeoForge $mc: no build of this loader line exists"
    continue
  fi
  [ "$floor" = "$expected" ] && continue

  if [ "$(neo_line "$mc")" = "$newest_line" ]; then
    printf 'warning   NeoForge %s: the csv pins %s but %s is now the newest build of the line.\n' \
      "$mc" "$floor" "$expected" >&2
    printf '          The pin still works as a lower bound; move it when the line settles.\n' >&2
  else
    mismatch "NeoForge $mc: csv says $floor, the rule says $expected"
  fi
done

# ------------------------------------------------------------------ Fabric

# The two pins in the csv come from maven-metadata.xml, which both repositories serve properly.
fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml" "$work/fabric-api.xml"
fetch "https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml" "$work/modmenu.xml"

metadata_versions() {
  grep -o '<version>[^<]*</version>' "$1" | sed -e 's/<[^>]*>//g'
}
fabric_api_versions=$(metadata_versions "$work/fabric-api.xml")
modmenu_versions=$(metadata_versions "$work/modmenu.xml")

# The loader is pinned in fabric/build.gradle rather than in the csv, because nothing about it
# reaches the jar — but a pin the loader does not offer for a row's Minecraft breaks that build.
# The default is the last quoted string on that line; `|| true` because a pipeline that finds
# nothing must reach the check below rather than ending the script under `set -e`.
loader_version=$(grep -m1 "fabric_loader_version" "$repo_root/fabric/build.gradle" \
  | grep -oE "'[^']*'" | tail -1 | tr -d "'" || true)
if [ -z "$loader_version" ]; then
  echo "could not read the pinned Fabric loader version out of fabric/build.gradle" >&2
  exit 1
fi

fabric_mcs=()
while IFS=, read -r mc _java _family _floor api _modmenu; do
  [ "$api" = '-' ] && continue
  fabric_mcs+=("$mc")
done <<<"$rows"

# The compatibility list is per Minecraft, so this needs one request per row — in parallel, because
# the endpoint takes a few seconds each and twelve of them in series was most of this check's
# runtime. `set -e` does not see a failing background job, so they are waited on explicitly.
loader_pids=()
for mc in "${fabric_mcs[@]}"; do
  curl -fs --retry 3 --retry-all-errors --max-time 120 \
    "https://meta.fabricmc.net/v2/versions/loader/${mc%.0}" -o "$work/loader-$mc.json" &
  loader_pids+=($!)
done
loader_failed=0
for pid in "${loader_pids[@]}"; do
  wait "$pid" || loader_failed=1
done
if [ "$loader_failed" != 0 ]; then
  echo "could not fetch Fabric loader compatibility from meta.fabricmc.net" >&2
  exit 2
fi

fabric_rows=0
for mc in "${fabric_mcs[@]}"; do
  fabric_rows=$((fabric_rows + 1))
  api=$(grep -m1 "^$mc," <<<"$rows" | cut -d, -f5 | tr -d ' ')
  modmenu=$(grep -m1 "^$mc," <<<"$rows" | cut -d, -f6 | tr -d ' ')

  # The suffix is the game's spelling of the Minecraft, which for a two-part version drops the
  # loader's trailing .0 — so accept both.
  game_mc=${mc%.0}
  case "$api" in
    *"+$mc" | *"+$game_mc") ;;
    *) mismatch "Fabric $mc: Fabric API $api does not name this Minecraft (expected a +$game_mc suffix)" ;;
  esac
  if ! grep -qxF "$api" <<<"$fabric_api_versions"; then
    mismatch "Fabric $mc: Fabric API $api is not published"
  fi
  if ! grep -qxF "$modmenu" <<<"$modmenu_versions"; then
    mismatch "Fabric $mc: Mod Menu $modmenu is not published"
  fi

  if ! grep -q "\"version\":\"$loader_version\"" "$work/loader-$mc.json" \
     && ! grep -q "\"version\": \"$loader_version\"" "$work/loader-$mc.json"; then
    mismatch "Fabric $mc: Fabric loader $loader_version is not offered for this Minecraft"
  fi
done

# ------------------------------------------------------------------ verdict

if [ "$failures" -gt 0 ]; then
  echo "$failures pin(s) are stale" >&2
  exit 1
fi
echo "loader pins are current: $neo_rows NeoForge row(s), $fabric_rows Fabric row(s)"
