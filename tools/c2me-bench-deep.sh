#!/usr/bin/env bash
# Deep-load benchmark: burst-forceload twelve spread 225-chunk areas at once (2700 chunks,
# twelve region files in flight), then poll all twelve centers until every chunk is loaded.
# The single-batch benchmark saturates in chunk-system scheduling; this one queues enough
# region IO to tell the IO stacks apart.
# Usage: tools/c2me-bench-deep.sh <label>
set -euo pipefail

LABEL="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="/tmp/c2bench/${LABEL}.log"
RESULTS=/tmp/c2bench/results.txt

rcon() { java -cp /tmp/c2bench RconTool 25575 csobench "$1" "${2:-20000}" 2>/dev/null; }
die() { echo "DEEP FAILED [$LABEL]: $*" >&2; exit 1; }

( cd "$ROOT" && ./gradlew :fabric:runServer --console=plain -Pminecraft_version=26.3.0 > "$LOG" 2>&1 ) &
PID=$!
for _ in $(seq 1 60); do
    grep -q "Done (" "$LOG" 2>/dev/null && break
    kill -0 "$PID" 2>/dev/null || die "server exited before Done"
    sleep 5
done
grep -q "Done (" "$LOG" || die "no Done in 300s"

POSITIONS=("-13056 -7424" "-11008 -2304" "-8960 2816" "-6400 -7424" "-4352 -2304" "-2304 2816" "-256 6400" "2304 -6400" "4352 -3328" "6400 -256" "8448 2816" "10496 5888")

t0=$(date +%s%3N)
for area in "${POSITIONS[@]}"; do
    set -- $area
    rcon "forceload add $(( $1 - 120 )) $(( $2 - 120 )) $(( $1 + 120 )) $(( $2 + 120 ))" 800 >/dev/null
done

# Poll all twelve centers round-robin until every one's chunk is loaded.
for _ in $(seq 1 300); do
    pending=0
    for area in "${POSITIONS[@]}"; do
        set -- $area
        out="$(rcon "setblock $1 200 $2 minecraft:air keep" 400)"
        case "$out" in *"not loaded"*) pending=$((pending + 1)) ;; esac
    done
    [ "$pending" = 0 ] && break
    sleep 0.15
done
t1=$(date +%s%3N)

echo "$LABEL deep: $((t1 - t0)) ms for 12 areas (2700 chunks), pending=$pending" | tee -a "$RESULTS"

STATS="$(rcon "cso stats" 15000 || true)"
echo "$LABEL stats: $(echo "$STATS" | tr -d '\n' | cut -c1-220)" >> "$RESULTS"

for area in "${POSITIONS[@]}"; do
    set -- $area
    rcon "forceload remove $(( $1 - 120 )) $(( $2 - 120 )) $(( $1 + 120 )) $(( $2 + 120 ))" 800 >/dev/null
done
rcon "save-all flush" 30000 >/dev/null
rcon stop 15000 >/dev/null || true
for _ in $(seq 1 24); do kill -0 "$PID" 2>/dev/null || break; sleep 5; done
kill -0 "$PID" 2>/dev/null && taskkill //F //T //PID "$PID" >/dev/null 2>&1 || true
echo "DEEP DONE [$LABEL]"
