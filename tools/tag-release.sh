#!/usr/bin/env bash
# Tag the current commit once per supported Minecraft version: mc-<minecraft>-<mod version>.
#
# One release produces one jar per Minecraft, so the tags say which game a given tag was built for.
# If a later fix ships for only some versions, tagging stays per-version and the untouched rows keep
# pointing at the commit that actually shipped their jar.
#
#   tools/tag-release.sh 1.0.3          # checks, then creates and pushes every tag
set -euo pipefail

ver="${1:?usage: tag-release.sh <mod version, e.g. 1.0.3>}"
root=$(cd "$(dirname "$0")/.." && pwd)

declared=$(grep -m1 '^mod_version=' "$root/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
if [ "$ver" != "$declared" ]; then
  echo "gradle.properties declares mod_version=$declared, not $ver" >&2
  exit 1
fi
[ -z "$(git -C "$root" status --porcelain)" ] || { echo "working tree is not clean" >&2; exit 1; }

# A tag nobody can fetch is worse than no tag: require the commit to be on the remote already.
git -C "$root" fetch --quiet origin
if [ "$(git -C "$root" rev-list --count '@{upstream}..HEAD')" != "0" ]; then
  echo "HEAD is not pushed; push the branch before tagging" >&2
  exit 1
fi

mcs=()
while IFS=, read -r mc _rest; do
  case "$mc" in ''|'#'*) continue ;; esac
  mcs+=("$(printf '%s' "$mc" | tr -d '[:space:]')")
done < "$root/supported-versions.csv"
[ "${#mcs[@]}" -gt 0 ] || { echo "supported-versions.csv lists no Minecraft versions" >&2; exit 1; }

# Check every name first: creating half a release's tags and then failing would be a mess to undo.
tags=()
for mc in "${mcs[@]}"; do
  tag="mc-${mc}-${ver}"
  if git -C "$root" rev-parse -q --verify "refs/tags/$tag" >/dev/null \
     || git -C "$root" ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    echo "tag $tag already exists; refusing to move it" >&2
    exit 1
  fi
  tags+=("$tag")
done

for i in "${!mcs[@]}"; do
  git -C "$root" tag -a "${tags[$i]}" -m "Chunk Storage Optimizer ${ver} for Minecraft ${mcs[$i]}"
  printf 'tagged  %s\n' "${tags[$i]}"
done
git -C "$root" push origin "${tags[@]}"
printf 'pushed  %d tags\n' "${#tags[@]}"
