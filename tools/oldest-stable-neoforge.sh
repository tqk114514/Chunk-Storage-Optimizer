#!/usr/bin/env bash
# Print the oldest STABLE NeoForge build for a Minecraft version — the value that belongs in
# supported-versions.csv.
#
# NeoForge marks pre-releases with a suffix (26.1.2.70-beta, 26.1.0.0-alpha.1+snapshot-6).
# A mod's lower bound should never be one of those: a player cannot install a build that was
# never released as stable, so pinning a beta makes the floor unreachable in practice.
#
# The oldest stable build is only a candidate floor: it has to be assemblable, i.e. its published
# binpatches must apply to the Minecraft jar Mojang serves today. When one does not, take the next
# stable build in the same line instead (see the note at the top of supported-versions.csv).
#
#   tools/oldest-stable-neoforge.sh 26.2.0
#   tools/oldest-stable-neoforge.sh 1.21.11
#
# The loader drops the old "1." prefix, so Minecraft 1.21.11 is served by the 21.11 line and
# Minecraft 26.2.0 by the 26.2.0 line. Pass the Minecraft version exactly as NeoForge spells it.
set -euo pipefail

mc="${1:?usage: oldest-stable-neoforge.sh <minecraft version, e.g. 26.2.0 or 1.21.11>}"
url="https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"

case "$mc" in
  # A two-part old version means patch 0: Minecraft 1.21 is served by the 21.0 line.
  1.*.*) line="${mc#1.}" ;;
  1.*)   line="${mc#1.}.0" ;;
  *)     line="$mc" ;;
esac

# A failed fetch must not look like "no stable build" — the two need different reactions, so the
# exit codes differ (2 = could not fetch, 1 = fetched and found nothing).
metadata=$(mktemp)
trap 'rm -f "$metadata"' EXIT
if ! curl -fs --retry 3 --retry-all-errors --max-time 90 "$url" -o "$metadata"; then
  echo "could not fetch $url" >&2
  exit 2
fi

escaped=$(printf '%s' "$line" | sed 's/\./\\./g')
matches=$(grep -o '<version>[^<]*</version>' "$metadata" \
  | sed -e 's/<[^>]*>//g' \
  | grep -v -- '-' \
  | grep -E "^${escaped}\.[0-9]+$" \
  | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n || true)

if [ -z "$matches" ]; then
  echo "no stable NeoForge build for Minecraft $mc (loader line $line)" >&2
  echo "  (a '-beta'/'-alpha' build does not count; see the full list at $url)" >&2
  exit 1
fi

oldest=$(printf '%s\n' "$matches" | head -1)
printf 'oldest  %s\n' "$oldest"
printf 'newest  %s\n' "$(printf '%s\n' "$matches" | tail -1)"
printf 'builds  %s\n' "$(printf '%s\n' "$matches" | wc -l | tr -d ' ')"
# The dependency range's upper bound is derived in build.gradle from the loader line, so the floor
# is the only value that has to be pasted. The java level comes from Mojang's own version manifest,
# and the family from which command-permission API that Minecraft has — neither is decided here.
printf 'row     %s, %s, <java>, <legacy|modern>\n' "$mc" "$oldest"
