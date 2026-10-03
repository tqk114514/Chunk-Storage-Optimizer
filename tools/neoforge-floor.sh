#!/usr/bin/env bash
# Print the NeoForge floor for one or more Minecraft versions — the values that belong in
# supported-versions.csv.
#
# The floor is the oldest STABLE build of that loader line, because that is the earliest loader a
# player can be asked to install.
#
# Some lines never got one. NeoForge closes a line as soon as the next Minecraft is out, so a
# Minecraft that lived for a week leaves a line whose last build is a -beta: 1.21.2, 1.21.6, 1.21.7,
# 1.21.9, 26.1 and 26.1.1 all ended that way. Demanding a stable build there would mean supporting
# nothing at all, so the fallback is the NEWEST beta of the line. A beta is not ideal, but it is what
# exists, and the newest is the least likely to carry the kind of broken build input that got
# NeoForge 21.10.63 withdrawn — its binpatches were generated from a modified base jar, so nothing
# could be compiled against it.
#
#   tools/neoforge-floor.sh 26.2.0
#   tools/neoforge-floor.sh 1.21 1.21.1 1.21.9      # one fetch for all of them
#
# The loader drops the old "1." prefix, so Minecraft 1.21.11 is served by the 21.11 line and
# Minecraft 26.2.0 by the 26.2.0 line. Pass the Minecraft version exactly as NeoForge spells it.
set -euo pipefail

if [ $# -eq 0 ]; then
  echo "usage: neoforge-floor.sh <minecraft version>..." >&2
  exit 1
fi

api="https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"

# The version list comes from the repository's own API, which is the only source here that was not
# caught serving stale content: maven-metadata.xml answered 404 from cache, maven-metadata-2.xml
# lagged by a dozen builds, and the directory listing came back nine builds short — it reported
# 26.3.0.34-beta as the newest when 26.3.0.43-beta already existed. The cache-buster is what defeats
# that; without it the CDN is free to answer from its copy.
work=$(mktemp -d)
# `|| true`: failing to delete a temporary directory must not change the exit status, or a caller
# reading that status to decide "did the fetch work" gets the wrong answer.
trap 'rm -rf "$work" || true' EXIT
if ! curl -fs --retry 3 --retry-all-errors --max-time 90 "$api?cb=$RANDOM$RANDOM" -o "$work/nf.json"; then
  echo "could not fetch $api" >&2
  exit 2
fi

# The payload is {"isSnapshot":false,"versions":["26.3.0.43-beta",...]} and nothing else in it is a
# quoted string, so the version list comes out with one grep and no JSON parser.
all=$(grep -oE '"[0-9][^"]*"' "$work/nf.json" | tr -d '"' || true)
if [ -z "$all" ]; then
  echo "$api returned no versions" >&2
  exit 2
fi

# Sorted once rather than per line. Sorting a line's builds after grepping them out is what the
# obvious version does, but every one of those pipelines is a process, and a shell on Windows pays
# something like 300 ms for each — seventeen lines turned a two-second fetch into a minute. A global
# sort orders each line correctly on its own, because the prefix is constant within a line.
all=$(printf '%s\n' "$all" | sort -u | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n)

missing=0
for mc in "$@"; do
  case "$mc" in
    # A two-part old version means patch 0: Minecraft 1.21 is served by the 21.0 line.
    1.*.*) line="${mc#1.}" ;;
    1.*)   line="${mc#1.}.0" ;;
    *)     line="$mc" ;;
  esac

  escaped=$(printf '%s' "$line" | sed 's/\./\\./g')
  builds=$(printf '%s\n' "$all" | grep -E "^${escaped}\.[0-9]+" || true)

  printf 'mc      %s\n' "$mc"
  printf 'line    %s\n' "$line"
  if [ -z "$builds" ]; then
    printf 'floor   -\n'
    printf 'note    no NeoForge build at all for this loader line\n\n'
    missing=$((missing + 1))
    continue
  fi

  stable=$(printf '%s\n' "$builds" | grep -v -- '-' || true)
  beta=$(printf '%s\n' "$builds" | grep -- '-' || true)
  if [ -n "$stable" ]; then
    floor=$(printf '%s\n' "$stable" | head -1)
    kind=stable
  else
    floor=$(printf '%s\n' "$beta" | tail -1)
    kind=beta
  fi

  printf 'floor   %s\n' "$floor"
  printf 'kind    %s\n' "$kind"
  printf 'newest  %s\n' "$(printf '%s\n' "$builds" | tail -1)"
  printf 'builds  %s (%s stable)\n' \
    "$(printf '%s\n' "$builds" | grep -c . || true)" \
    "$(printf '%s\n' "$stable" | grep -c . || true)"
  if [ "$kind" = beta ]; then
    printf 'note    this line has no stable build; the floor is the newest beta\n'
  fi
  # The dependency range's upper bound is derived in build.gradle from the loader line, so the floor
  # is the only value that has to be pasted. The java level comes from Mojang's own version manifest,
  # and the family from which command-permission API that Minecraft has — neither is decided here.
  printf 'row     %s, %s, <java>, <legacy|modern>\n\n' "$mc" "$floor"
done

[ "$missing" -eq 0 ]
