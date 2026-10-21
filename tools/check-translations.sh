#!/usr/bin/env bash
# Proofreads every language file against its en_us: JSON validity, key-set equality, no empty
# values, and technical tokens (URLs, file extensions, numbers-with-units) carried over intact.
# A translation that drops a URL or turns "889 MB" into "889" still parses and still matches
# keys — these are the mistakes a machine CAN catch, so it catches them.
# Usage: bash tools/check-translations.sh  (run from the repo root, or any dir under it)
set -euo pipefail

JQ="${JQ:-jq}"
# A missing jq used to look like broken translations: every check below runs through jq, so with
# no jq on PATH every language file reported "not valid JSON" and the script exited 1 — measured
# on a machine without it: 90 false FAIL lines over 472 files that all parse fine. A missing
# dependency has to say so and stop; the fallback to a hardcoded /tmp/jq.exe was a leftover of one
# person's machine and could only ever fail this way.
command -v "$JQ" > /dev/null 2>&1 || {
    echo "needs jq — install it, or point the script at it: JQ=/path/to/jq bash $0" >&2
    exit 2
}

# Every lang tree the mod ships. The c2mecompat tree (31 languages) was not in this list from the
# day it landed, while crowdin.yml uploads and downloads it — the count printed at the end is what
# makes that kind of gap visible instead of silent.
#
# Key lists go to a private temp dir: they used to be /tmp/e_keys and /tmp/x_keys, which two
# checkouts (a Crowdin sync PR and a local run, say) would write over each other mid-comparison,
# and each then reported the other's files as drifted.
work=$(mktemp -d) || exit 2
trap 'rm -rf "$work"' EXIT
en_keys="$work/en_us_keys"
this_keys="$work/this_keys"

fail=0
proofread=0
trees=0
for tree in \
    core/src/main/resources/assets/chunkstorageoptimizer \
    compat/xaero/fabric/src/main/resources/assets/chunkstorageoptimizer_xaerocompat \
    compat/voxy/fabric/src/main/resources/assets/chunkstorageoptimizer_voxycompat \
    compat/c2me/fabric/src/main/resources/assets/chunkstorageoptimizer_c2mecompat; do
    lang_dir="$tree/lang"
    [ -f "$lang_dir/en_us.json" ] || continue
    trees=$((trees + 1))
    "$JQ" -r 'keys' "$lang_dir/en_us.json" | sort > "$en_keys"
    for f in "$lang_dir"/*.json; do
        code=$(basename "$f" .json)
        [ "$code" = "en_us" ] && continue
        proofread=$((proofread + 1))

        problems=""

        # 1. Valid JSON.
        if ! "$JQ" -e . "$f" > /dev/null 2>&1; then
            echo "FAIL $tree/$code: not valid JSON"
            fail=1
            continue
        fi

        # 2. Key set identical to en_us: a missing key falls back to English in-game (fine but
        #    worth knowing), an extra key is dead weight — either means the file drifted.
        "$JQ" -r 'keys' "$f" | sort > "$this_keys"
        missing=$(comm -23 "$en_keys" "$this_keys" | tr '\n' ' ')
        extra=$(comm -13 "$en_keys" "$this_keys" | tr '\n' ' ')
        if [ -n "$missing$extra" ]; then
            problems="keys differ (missing: ${missing:-none}, extra: ${extra:-none})"
        fi

        # 3. No empty translations: an empty string renders as nothing where the source would
        #    have shown text — worse than falling back to English.
        empty=$("$JQ" -r 'to_entries | map(select((.value | type == "string") and (.value | length == 0))) | length' "$f")
        [ "$empty" != "0" ] && problems="${problems:+$problems; }$empty empty value(s)"

        # 4. Technical tokens survive translation: every URL and file extension in the source
        #    must appear in the translation. Numbers with units are the other classic casualty.
        #
        #    One token per LINE, not one per word. The shipped source strings really do carry
        #    "8 MB" and "889 MB" (measured against core's en_us), and the unquoted for-loop split
        #    those in two — a translation that kept the number and dropped the unit, or mentioned
        #    "MB" in some unrelated sentence, passed. It also re-ran jq once per token per file,
        #    which is why the translated values are extracted once here.
        "$JQ" -r 'to_entries[].value' "$f" > "$work/this_values"
        while IFS= read -r token; do
            [ -n "$token" ] || continue
            if ! grep -qF -- "$token" "$work/this_values"; then
                problems="${problems:+$problems; }missing token '$token'"
            fi
        done < <("$JQ" -r 'to_entries[].value' "$lang_dir/en_us.json" \
            | grep -oE 'https?://[a-zA-Z0-9./_-]+|\.(mca|cso|toml)\b|[0-9]+ ?(MB|GB|x32|32x32)' | sort -u)

        if [ -n "$problems" ]; then
            echo "WARN $tree/$code: $problems"
        fi
    done
done

[ "$fail" = "0" ] || exit 1
echo "proofread $proofread language file(s) across $trees lang tree(s)"
