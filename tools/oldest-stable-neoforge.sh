#!/usr/bin/env bash
# Print the oldest STABLE NeoForge build for a Minecraft version — the value that belongs in
# build.gradle's supportedVersions table.
#
# NeoForge marks pre-releases with a suffix (26.1.2.70-beta, 26.1.0.0-alpha.1+snapshot-6).
# A mod's lower bound should never be one of those: a player cannot install a build that was
# never released as stable, so pinning a beta makes the floor unreachable in practice.
#
#   tools/oldest-stable-neoforge.sh 26.2.0
#
# Pass the Minecraft version exactly as NeoForge spells it (26.2.0, not 26.2) — the loader's
# version is <minecraft>.<build>, so a short prefix also matches unrelated lines.
set -euo pipefail

mc="${1:?usage: oldest-stable-neoforge.sh <minecraft version, e.g. 26.2.0>}"
url="https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"

# A failed fetch must not look like "no stable build" — the two need different reactions, so the
# exit codes differ (2 = could not fetch, 1 = fetched and found nothing).
metadata=$(mktemp)
trap 'rm -f "$metadata"' EXIT
if ! curl -fs --retry 3 --retry-all-errors --max-time 90 "$url" -o "$metadata"; then
  echo "could not fetch $url" >&2
  exit 2
fi

escaped=$(printf '%s' "$mc" | sed 's/\./\\./g')
matches=$(grep -o '<version>[^<]*</version>' "$metadata" \
  | sed -e 's/<[^>]*>//g' \
  | grep -v -- '-' \
  | grep -E "^${escaped}\.[0-9]+$" \
  | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n || true)

if [ -z "$matches" ]; then
  echo "no stable NeoForge build for Minecraft $mc" >&2
  echo "  (a '-beta'/'-alpha' build does not count; see the full list at $url)" >&2
  exit 1
fi

printf 'oldest  %s\n' "$(printf '%s\n' "$matches" | head -1)"
printf 'newest  %s\n' "$(printf '%s\n' "$matches" | tail -1)"
printf 'builds  %s\n' "$(printf '%s\n' "$matches" | wc -l | tr -d ' ')"
