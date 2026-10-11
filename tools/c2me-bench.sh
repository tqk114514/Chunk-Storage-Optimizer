#!/usr/bin/env bash
# One benchmark boot: start the dev server on convtest, sweep four 225-chunk areas with
# cold forceloads, poll each until its chunks are loaded, and record per-batch milliseconds.
# Usage: tools/c2me-bench.sh <label> [--skip-bench]
# The server log goes to /tmp/c2bench/<label>.log; results append to /tmp/c2bench/results.txt
set -euo pipefail

LABEL="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/fabric/run"
LOG="/tmp/c2bench/${LABEL}.log"
RESULTS=/tmp/c2bench/results.txt

rcon() { java -cp /tmp/c2bench RconTool 25575 csobench "$1" "${2:-20000}" 2>/dev/null; }

die() { echo "BENCH FAILED [$LABEL]: $*" >&2; exit 1; }

# --- launch
( cd "$ROOT" && ./gradlew :fabric:runServer --console=plain -Pminecraft_version=26.3.0 > "$LOG" 2>&1 ) &
PID=$!
for _ in $(seq 1 60); do
    grep -q "Done (" "$LOG" 2>/dev/null && break
    kill -0 "$PID" 2>/dev/null || die "server exited before Done"
    sleep 5
done
grep -q "Done (" "$LOG" || die "no Done in 300s"
BOOT=$(grep -o "Done ([0-9.]*s)" "$LOG" | tail -1)
START_MS=$(date +%s%3N)

# --- sweep: four spread areas, 225 chunks each, cold-loaded
# Positions are centers of real, generated regions of this world. RCON has no end-of-response
# marker, so every call streams until its deadline — batch timing therefore starts only after
# the forceload call returns, and the poll loop uses a short deadline so one iteration costs
# ~0.5 s. All arms pay the same overhead; absolute times are slightly under-measured.
AREAS=("-13056 -7424" "-3840 -2304" "5376 -4352" "13056 7424")
for area in "${AREAS[@]}"; do
    set -- $area
    X=$1; Z=$2
    rcon "forceload add $((X-120)) $((Z-120)) $((X+120)) $((Z+120))" 800 >/dev/null
    t0=$(date +%s%3N)
    # Poll center and all four corners until every one's chunk is loaded — the whole batch,
    # not whichever chunk the scheduler happens to finish first (C2ME rewrites the order, so
    # a single-position probe would compare schedulers rather than IO). setblock ... keep
    # never mutates anything.
    for _ in $(seq 1 240); do
        pending=0
        for probe in "0 0" "-120 -120" "120 -120" "-120 120" "120 120"; do
            set -- $probe
            out="$(rcon "setblock $((X+$1)) 200 $((Z+$2)) minecraft:air keep" 400)"
            case "$out" in *"not loaded"*) pending=1 ;; esac
        done
        [ "$pending" = 0 ] && break
        sleep 0.15
    done
    t1=$(date +%s%3N)
    echo "$LABEL batch $X $Z: $((t1 - t0)) ms" | tee -a "$RESULTS"
    rcon "forceload remove $((X-120)) $((Z-120)) $((X+120)) $((Z+120))" 800 >/dev/null
    rcon "save-all flush" 20000 >/dev/null
    sleep 3
done

# --- read latency histogram, when CSO serves this world
STATS="$(rcon "cso stats" 15000 || true)"
echo "$LABEL stats: $(echo "$STATS" | tr -d '\n' | cut -c1-220)" >> "$RESULTS"

rcon stop 15000 >/dev/null || true
for _ in $(seq 1 24); do kill -0 "$PID" 2>/dev/null || break; sleep 5; done
kill -0 "$PID" 2>/dev/null && taskkill //F //T //PID "$PID" >/dev/null 2>&1 || true

WALL=$(( ($(date +%s%3N) - START_MS) ))
echo "$LABEL boot: $BOOT (${WALL} ms incl. sweep)" | tee -a "$RESULTS"
echo "BENCH DONE [$LABEL]"
