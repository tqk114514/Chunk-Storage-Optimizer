#!/usr/bin/env bash
#
# Publishes the built jars to Modrinth, one version per (loader, Minecraft) pair.
#
# The grouping is not cosmetic. A Modrinth version carries a list of loaders and a list of game
# versions, and the launcher resolves an instance by matching those and then taking that version's
# primary file. Putting both loaders' jars in one version would therefore hand a Fabric player the
# NeoForge jar, so every jar gets its own version — the same way JEI and AppleSkin publish.
#
# Required environment:
#   MODRINTH_TOKEN    personal access token with the VERSION_CREATE scope
#   MODRINTH_PROJECT  the project's id or slug
#   VERSION           the mod version, e.g. 1.0.7
# Optional:
#   JARS_DIR          where the built jars are (default: dist)
#   CHANGELOG_FILE    where this version's notes are lifted from (default: CHANGELOG.md)
#   VERSION_TYPE      release | beta | alpha (default: release)
#   VERSION_STATUS    listed | draft | unlisted | archived (default: listed)
#   FORCE             "true" uploads even where this version already exists
#   DRY_RUN           "true" prints the plan and calls nothing
#
# API: https://docs.modrinth.com/api/operations/createversion/

set -euo pipefail

# No apostrophes inside the ${VAR:?message} words below: bash processes quotes there, and an
# unmatched one desynchronises the rest of the file — it surfaces as a syntax error far away.
: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required (a personal access token with VERSION_CREATE)}"
: "${MODRINTH_PROJECT:?MODRINTH_PROJECT is required (set the MODRINTH_PROJECT repository variable to the project id)}"
: "${VERSION:?VERSION is required}"

JARS_DIR="${JARS_DIR:-dist}"
CHANGELOG_FILE="${CHANGELOG_FILE:-CHANGELOG.md}"
VERSION_TYPE="${VERSION_TYPE:-release}"
VERSION_STATUS="${VERSION_STATUS:-listed}"
FORCE="${FORCE:-false}"
DRY_RUN="${DRY_RUN:-false}"

API="https://api.modrinth.com/v2"
# A uniquely identifying User-Agent is mandatory, and one that only names the HTTP client gets
# traffic blocked. The version is in it so a release stays traceable from Modrinth's side.
USER_AGENT="tqk114514/chunkstorageoptimizer/${VERSION} (https://github.com/tqk114514/Chunk-Storage-Optimizer)"

# Answers "yes", "no", or "unknown" when the question could not be asked at all.
#
# This is what makes a run repeatable: a release that died half way can simply be re-run, and the
# jars already up are skipped while the rest go up. Without it a re-run would double every version.
already_published() {
    local minecraft="$1" loader="$2" response code body
    response=$(curl -sS --max-time 60 -w $'\n%{http_code}' \
        -H "Authorization: $MODRINTH_TOKEN" \
        -H "User-Agent: $USER_AGENT" \
        --get \
        --data-urlencode "loaders=[\"$loader\"]" \
        --data-urlencode "game_versions=[\"$minecraft\"]" \
        "$API/project/$MODRINTH_PROJECT/version") || { echo unknown; return 0; }
    code=$(tail -n1 <<<"$response")
    body=$(sed '$d' <<<"$response")
    if [ "$code" != "200" ]; then
        echo unknown
        return 0
    fi
    if [ "$(jq --arg v "$VERSION" '[.[] | select(.version_number == $v)] | length' <<<"$body")" = "0" ]; then
        echo no
    else
        echo yes
    fi
}

# This version's notes: from its own heading up to the next one. The field is nullable, so a miss
# is not fatal, but a release that arrives with no notes is worse than one that says why.
changelog=$(awk -v v="$VERSION" '
    index($0, "## [" v "]") == 1 { inside = 1; next }
    inside && index($0, "## [") == 1 { exit }
    inside { print }
' "$CHANGELOG_FILE")

if [ -z "${changelog//[[:space:]]/}" ]; then
    echo "warning: no '## [$VERSION]' section in $CHANGELOG_FILE, publishing without notes" >&2
fi

shopt -s nullglob
jars=("$JARS_DIR"/*.jar)
if [ ${#jars[@]} -eq 0 ]; then
    echo "no jars in $JARS_DIR — did the build step run?" >&2
    exit 1
fi

echo "publishing ${#jars[@]} jar(s) of $VERSION to project $MODRINTH_PROJECT"
if [ "$DRY_RUN" = "true" ]; then
    echo "(dry run: nothing will be uploaded)"
fi

failures=0
for jar in "${jars[@]}"; do
    name=$(basename "$jar")
    # chunkstorageoptimizer-<loader>-<minecraft>-<version>.jar
    rest=${name#chunkstorageoptimizer-}
    loader=${rest%%-*}
    rest=${rest#*-}
    minecraft=${rest%-"$VERSION".jar}

    if [ "$loader" != "neoforge" ] && [ "$loader" != "fabric" ]; then
        echo "FAILED $name: no loader in the name" >&2
        failures=$((failures + 1))
        continue
    fi

    # Fabric needs Fabric API. Nothing on the NeoForge side is a Modrinth project, so the loader
    # itself is not listed — the version's `loaders` field is what says that.
    dependencies='[]'
    if [ "$loader" = "fabric" ]; then
        dependencies='[{"project_id":"fabric-api","dependency_type":"required"}]'
    fi

    data=$(jq -nc \
        --arg name "Chunk Storage Optimizer ${VERSION} for ${minecraft} (${loader})" \
        --arg number "$VERSION" \
        --arg changelog "$changelog" \
        --arg minecraft "$minecraft" \
        --arg loader "$loader" \
        --arg project "$MODRINTH_PROJECT" \
        --arg type "$VERSION_TYPE" \
        --arg status "$VERSION_STATUS" \
        --argjson dependencies "$dependencies" \
        '{name: $name,
          version_number: $number,
          changelog: $changelog,
          game_versions: [$minecraft],
          loaders: [$loader],
          dependencies: $dependencies,
          version_type: $type,
          status: $status,
          project_id: $project,
          file_parts: ["file"],
          primary_file: "file"}')

    if [ "$DRY_RUN" = "true" ]; then
        echo "would publish $name  ->  $minecraft / $loader"
        continue
    fi

    if [ "$FORCE" != "true" ]; then
        case "$(already_published "$minecraft" "$loader")" in
            yes)
                echo "skipped $name  ->  $minecraft / $loader already has $VERSION"
                continue
                ;;
            no) ;;
            *)
                # Not being able to ask is not a licence to guess: creating blind is what would
                # produce a duplicate, so this jar is counted as a failure instead.
                echo "FAILED $name: could not list the project's versions, not guessing" >&2
                failures=$((failures + 1))
                continue
                ;;
        esac
    fi

    ok=false
    for attempt in 1 2 3; do
        # --form-string, not -F: curl's -F splits its argument on ',' and ';' and JSON is full of
        # commas, so -F would tear the body apart into extra form parts.
        response=$(curl -sS --max-time 300 -w $'\n%{http_code}' -X POST "$API/version" \
            -H "Authorization: $MODRINTH_TOKEN" \
            -H "User-Agent: $USER_AGENT" \
            --form-string "data=$data" \
            -F "file=@${jar};type=application/java-archive")
        code=$(tail -n1 <<<"$response")
        body=$(sed '$d' <<<"$response")

        if [ "$code" = "200" ]; then
            ok=true
            break
        fi
        case "$code" in
            429|500|502|503)
                echo "  $name: HTTP $code, retrying (attempt $attempt)" >&2
                sleep 10
                ;;
            *)
                echo "FAILED $name: HTTP $code" >&2
                echo "$body" >&2
                break
                ;;
        esac
    done

    if [ "$ok" = "true" ]; then
        echo "published $name  ->  $minecraft / $loader"
    else
        failures=$((failures + 1))
    fi
done

if [ "$failures" -gt 0 ]; then
    echo "$failures upload(s) failed" >&2
    exit 1
fi
echo "all ${#jars[@]} uploads done"
