#!/usr/bin/env bash
# Proofreads every language file against its en_us: JSON validity, key-set equality, no empty
# values, and technical tokens (URLs, file extensions, numbers-with-units) carried over intact.
# A translation that drops a URL or turns "889 MB" into "889" still parses and still matches
# keys — these are the mistakes a machine CAN catch, so it catches them.
# Usage: bash tools/check-translations.sh  (run from the repo root, or any dir under it)
set -euo pipefail

JQ="${JQ:-jq}"
command -v "$JQ" > /dev/null 2>&1 || JQ=/tmp/jq.exe

fail=0
for tree in \
    core/src/main/resources/assets/chunkstorageoptimizer \
    compat/xaero/fabric/src/main/resources/assets/chunkstorageoptimizer_xaerocompat \
    compat/voxy/fabric/src/main/resources/assets/chunkstorageoptimizer_voxycompat; do
    lang_dir="$tree/lang"
    [ -f "$lang_dir/en_us.json" ] || continue
    for f in "$lang_dir"/*.json; do
        code=$(basename "$f" .json)
        [ "$code" = "en_us" ] && continue

        problems=""

        # 1. Valid JSON.
        if ! "$JQ" -e . "$f" > /dev/null 2>&1; then
            echo "FAIL $tree/$code: not valid JSON"
            fail=1
            continue
        fi

        # 2. Key set identical to en_us: a missing key falls back to English in-game (fine but
        #    worth knowing), an extra key is dead weight — either means the file drifted.
        if [ "$("$JQ" -S 'keys' "$lang_dir/en_us.json")" != "$("$JQ" -S 'keys' "$f")" ]; then
            missing=$("$JQ" -r 'keys' "$lang_dir/en_us.json" > /tmp/e_keys; "$JQ" -r 'keys' "$f" > /tmp/x_keys; comm -23 <(sort /tmp/e_keys) <(sort /tmp/x_keys) | tr '\n' ' ')
            extra=$(comm -13 <(sort /tmp/e_keys) <(sort /tmp/x_keys) | tr '\n' ' ')
            problems="keys differ (missing: ${missing:-none}, extra: ${extra:-none})"
        fi

        # 3. No empty translations: an empty string renders as nothing where the source would
        #    have shown text — worse than falling back to English.
        empty=$("$JQ" -r 'to_entries | map(select((.value | type == "string") and (.value | length == 0))) | length' "$f")
        [ "$empty" != "0" ] && problems="${problems:+$problems; }$empty empty value(s)"

        # 4. Technical tokens survive translation: every URL and file extension in the source
        #    must appear in the translation. Numbers with units are the other classic casualty.
        for token in $("$JQ" -r 'to_entries[].value' "$lang_dir/en_us.json" | grep -oE 'https?://[a-zA-Z0-9./_-]+|\.(mca|cso|toml)\b|[0-9]+ ?(MB|GB|x32|32x32)' | sort -u); do
            if ! "$JQ" -r 'to_entries[].value' "$f" | grep -qF "$token"; then
                problems="${problems:+$problems; }missing token '$token'"
            fi
        done

        if [ -n "$problems" ]; then
            echo "WARN $tree/$code: $problems"
        fi
    done
done

[ "$fail" = "0" ] || exit 1
echo "all translations proofread"
