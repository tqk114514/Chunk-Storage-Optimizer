#!/usr/bin/env bash
# Tag the current commit as a release: exactly one annotated tag, `v<mod version>`.
#
# What gets published does not come from the tag: the workflow reads the matrix from
# supported-versions.csv, so one tag builds and ships every jar the mod has a row for. That is why
# tagging per Minecraft version was dropped — fourteen tags meant fourteen runs, thirteen of which
# had nothing to do (see the header of .github/workflows/build.yml). This script used to cut
# `mc-<minecraft>-<version>` tags anyway, and none of them matched the workflow's `v*` trigger: a
# release run through it published nothing.
#
# A narrowed release is said so in the annotation, one `scope:` line per Minecraft or per loader
# pair, which is the syntax the workflow's discover job parses:
#
#   tools/tag-release.sh 1.1.10                         # the whole matrix
#   tools/tag-release.sh 1.1.10 26.3.0 1.21.11/fabric   # just those two jars' rows
#
# A scope the csv has no jar for fails here rather than in the runner: the workflow refuses the same
# input, and a release that dies after the tag exists leaves a tag nobody can reuse.
#
#   tools/tag-release.sh <mod version> [scope ...]
set -euo pipefail

ver="${1:?usage: tag-release.sh <mod version, e.g. 1.0.3> [scope ...]}"
shift
scopes=("$@")
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

# Every entry the matrix would build for: each row's Minecraft, and its pairs where a loader ships.
# Column 4 is the NeoForge pin and column 5 the Fabric API one; a '-' means that loader never
# shipped for that Minecraft, exactly as the discover job reads it.
allowed=()
while IFS=, read -r -a cols; do
  mc=$(printf '%s' "${cols[0]:-}" | tr -d ' \t\r')
  case "$mc" in ''|'#'*) continue ;; esac
  allowed+=("$mc")
  loader=${cols[3]:-}
  [ "$loader" = "-" ] || allowed+=("$mc/neoforge")
  loader=${cols[4]:-}
  [ "$loader" = "-" ] || allowed+=("$mc/fabric")
done < "$root/supported-versions.csv"
[ "${#allowed[@]}" -gt 0 ] || { echo "supported-versions.csv lists no Minecraft versions" >&2; exit 1; }

tag="v$ver"
if git -C "$root" rev-parse -q --verify "refs/tags/$tag" >/dev/null \
   || git -C "$root" ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
  echo "tag $tag already exists; refusing to move it" >&2
  echo "a narrowed fix on an already-tagged version is a new version number, not a second tag" >&2
  exit 1
fi

for scope in ${scopes+"${scopes[@]}"}; do
  known=0
  for entry in "${allowed[@]}"; do
    [ "$entry" = "$scope" ] && { known=1; break; }
  done
  if [ "$known" != "1" ]; then
    echo "scope $scope is not something the csv has a jar for. Valid entries:" >&2
    printf '  %s\n' "${allowed[@]}" >&2
    exit 1
  fi
done

message="Chunk Storage Optimizer $ver"
if [ "${#scopes[@]}" -gt 0 ]; then
  message="$message"$'\n'
  for scope in "${scopes[@]}"; do
    message="$message"$'\n'"scope: $scope"
  done
fi

# Annotated, because the discover job reads the scope out of the tag object; a lightweight tag here
# would silently mean "the whole matrix".
git -C "$root" tag -a "$tag" -m "$message"
git -C "$root" push origin "$tag"
if [ "${#scopes[@]}" -gt 0 ]; then
  printf 'tagged and pushed %s, narrowed to: %s\n' "$tag" "${scopes[*]}"
else
  printf 'tagged and pushed %s — the whole matrix\n' "$tag"
fi
