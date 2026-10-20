#!/usr/bin/env bash
# Headless smoke for the C2ME compat bridge, one loader at a time:
#
#   A  make a .cso world without C2ME (convert cso)
#   B  serve that same world WITH C2ME installed — the bridge must keep it on the vanilla
#      worker: bridge log line present, stats [active] with reads, no .mca appearing, and a
#      conversion back to .mca still runs (conversion under C2ME)
#   C  the opted-out .mca world under C2ME — CSO stands aside, the world loads, and converts
#      back to .cso in a fresh session
#   D  the refusal: config switched off over a world that still holds .cso files — the
#      server must refuse to open it instead of silently regenerating
#
# Every phase is its own server process, stopped before the next starts (rcon stop, then the
# hard fallback — no server is left behind on any exit path). Each phase runs WITHOUT the Gradle
# daemon on purpose: the hard fallback kills the process tree, and with a daemon the build would
# be a child of a shared JVM that the kill takes down with it — measured, it left Gradle's script
# cache in a state where every later build died with a Groovy compiler "BUG!". The fabric and neoforge halves
# differ in two places this script has to know: the compat jar must be dropped into
# fabric/run/mods (loom's include is packaging-only, the dev classpath carries none of the
# comps), while the neoforge dev run gets it from the jarJar wiring already; and each side
# keeps its config in its own format.
#
# Usage: tools/c2me-smoke.sh [loader] [row]        defaults: fabric 26.3.0
#
# Run it on both shapes: a modern row (26.3.0, or 1.21.11) exercises the single-class bridge,
# and a legacy row (1.21.1, 1.21.10) exercises the two-class one — chunks on ChunkStorage,
# entities and POI on SimpleRegionStorage. Phase B is where a half-applied bridge shows itself:
# it asserts that the *chunk* store served reads, which the entity store alone cannot satisfy.
#
# Caveat on Fabric legacy rows: the dev runtime uses named mappings, so the compat jar handed to
# it must be loom's named build (build/devlibs/*-dev.jar). build/libs holds the intermediary jar
# the release ships; dropping that into a dev run leaves every mixin target unresolved and looks
# like a bridge failure. The selection below prefers the named jar and falls back to libs for a
# 26.x row, where no remapping happens at all. A Fabric 1.21 run therefore verifies the bridge
# logic; the shipped jar's packaging is checked separately (see docs/DEVELOPMENT.md).
# Needs: the dev runtime dirs (fabric/run, neoforge/run) that `gradlew :*:runServer` uses,
# and a JDK on PATH (or JAVA_HOME) for the embedded RCON client.
set -euo pipefail

LOADER="${1:-fabric}"
ROW="${2:-26.3.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$LOADER" in
  fabric)   RUN="$ROOT/fabric/run";   GRADLE_TASK=":fabric:runServer"
            # Newest build, whatever the compat's version is: the jar name carries it, and a
            # pinned one silently goes stale the moment the compat bumps.
            COMPAT_JAR="$(cd "$ROOT" && { ls -t compat/c2me/fabric/build/devlibs/chunkstorageoptimizer_c2mecompat-*-dev.jar 2>/dev/null || ls -t compat/c2me/fabric/build/libs/chunkstorageoptimizer_c2mecompat-*.jar 2>/dev/null; } | head -1)";;
  neoforge) RUN="$ROOT/neoforge/run"; GRADLE_TASK=":neoforge:runServer"; COMPAT_JAR="";;
  *) echo "usage: $0 [fabric|neoforge] [row]" >&2; exit 2;;
esac
PROPS="$RUN/server.properties"
WORLD="$RUN/c2mesmoke"
MODS="$RUN/mods"
LOG_DIR="$RUN/logs"
RCON_PORT=25575
RCON_PASSWORD=c2mesmoke
CACHE=/tmp/c2me-smoke-cache
# Whatever else lives in mods/ is set aside for the run and put back on exit. A dev run
# directory accumulates mods from unrelated experiments (a world-map jar for a different
# Minecraft, most memorably), and a mandatory dependency of one of them fails the load before
# the bridge is ever reached — which reads as a bridge failure. The smoke must only ever load
# the jars it chose.
MODS_BACKUP="$(mktemp -d)"
mkdir -p "$CACHE" "$MODS"

die() { echo "C2ME SMOKE FAILED [$LOADER]: $*" >&2; exit 1; }
# 26.x nests every dimension's stores under dimensions/<ns>/<dim>/region, and 1.21 keeps the
# legacy per-dimension folders — the assertions walk the whole world either way.
cso_count() { find "$WORLD" -name "*.cso" 2>/dev/null | wc -l; }
mca_count() { find "$WORLD" -name "*.mca" 2>/dev/null | wc -l; }
# The conversions are synchronous on the server thread, but their RCON response can come home
# before the last byte lands (measured under C2ME, where the run takes minutes) — so the
# assertions after a conversion poll the filesystem, the ground truth, instead of racing it.
poll_gt() { # <die-message> <count-fn> <minimum>
    for _ in $(seq 1 60); do
        n="$("$2")"
        echo "   poll: $2 = $n (want > $3)"
        [ "$n" -gt "$3" ] && return 0
        sleep 5
    done
    die "$1"
}
poll_eq() { # <die-message> <count-fn> <expected>
    for _ in $(seq 1 60); do
        [ "$("$2")" -eq "$3" ] && return 0
        sleep 5
    done
    die "$1"
}
[ -f "$PROPS" ] || die "no $PROPS — run the dev server once so the runtime exists"
[ -d "$WORLD" ] && die "$WORLD already exists — remove it before a clean run"

# --- the C2ME release under test, downloaded once ---------------------------------------------
# Resolved from Modrinth for the row under test rather than pinned: the bridge has two shapes,
# and the 1.21-through-1.21.10 one needs a C2ME built for that row (its chunk rewrite targets
# ChunkStorage, which 1.21.11 deleted). The newest release of the row is the one a player gets.
C2ME_PROJECT=$([ "$LOADER" = fabric ] && echo c2me-fabric || echo c2me-neoforge)
# Modrinth names a two-part game version the way the game does ("26.3"); the csv row keeps the
# loader-line spelling ("26.3.0"). Querying the raw row finds no release on every 26.x row.
MODRINTH_ROW="$ROW"
case "$ROW" in
  26.*.0) MODRINTH_ROW="${ROW%.0}" ;;
esac
C2ME_VERSION_ID="$(curl -s "https://api.modrinth.com/v2/project/$C2ME_PROJECT/version?game_versions=%5B%22$MODRINTH_ROW%22%5D&loaders=%5B%22$LOADER%22%5D" \
    | python -c 'import json, sys
releases = json.load(sys.stdin)
print(releases[0]["id"] if releases else "")')"
[ -n "$C2ME_VERSION_ID" ] || die "no C2ME release for Minecraft $ROW on $LOADER"
C2ME_JAR="$CACHE/c2me-$LOADER-$C2ME_VERSION_ID.jar"
if [ ! -f "$C2ME_JAR" ]; then
    url="$(curl -s "https://api.modrinth.com/v2/version/$C2ME_VERSION_ID" \
        | python -c 'import json,sys; print(json.load(sys.stdin)["files"][0]["url"])')"
    curl -s -o "$C2ME_JAR" -L "$url" || die "could not download C2ME $C2ME_VERSION_ID"
fi
[ -s "$C2ME_JAR" ] || die "empty C2ME jar at $C2ME_JAR"
[ -n "$COMPAT_JAR" ] && [ -f "$ROOT/$COMPAT_JAR" ] || {
    [ -n "$COMPAT_JAR" ] && die "compat jar missing at $COMPAT_JAR — build :compat:c2me:fabric first"
    COMPAT_JAR=""
}

# --- embedded Source RCON client (the proven one: little-endian, exact packet reads) ---------
WORK="$(mktemp -d)"
cat > "$WORK/RconTool.java" <<'EOF'
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal Source RCON client: send one command, print the streamed response. */
public class RconTool {
    public static void main(String[] args) throws Exception {
        int requestId = 42;
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(args[0]))) {
            long deadline = System.currentTimeMillis() + Long.parseLong(args[3]);
            socket.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            send(out, requestId, 3, args[1]);
            read(in); // auth response
            send(out, requestId, 2, args[2]);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                while (System.currentTimeMillis() < deadline) {
                    socket.setSoTimeout((int) Math.max(500, deadline - System.currentTimeMillis()));
                    buffer.write(read(in));
                }
            } catch (SocketTimeoutException quiet) {
                // RCON streams response fragments; the server going quiet means it is done.
            }
            System.out.println(new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void send(DataOutputStream out, int id, int type, String payload) throws Exception {
        byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(4 + 4 + body.length + 2).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        out.write(packet.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in) throws Exception {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] rest = new byte[length];
        in.readFully(rest);
        byte[] payload = new byte[length - 10];
        System.arraycopy(rest, 8, payload, 0, payload.length);
        return payload;
    }
}
EOF
"${JAVA_HOME:+$JAVA_HOME/bin/}"javac -d "$WORK" "$WORK/RconTool.java" 2>/dev/null \
    || javac -d "$WORK" "$WORK/RconTool.java" \
    || die "no javac on PATH or JAVA_HOME"
rcon() { "${JAVA_HOME:+$JAVA_HOME/bin/}"java -cp "$WORK" RconTool "$RCON_PORT" "$RCON_PASSWORD" "$1" "${2:-20000}" 2>/dev/null; }

# --- server configuration: our values now, the dev ones back on exit -----------------------
set_prop() {
    if grep -q "^$1=" "$PROPS"; then
        sed -i "s|^$1=.*|$1=$2|" "$PROPS"
    else
        echo "$1=$2" >> "$PROPS"
    fi
}
cp "$PROPS" "$PROPS.smoke-backup"
[ -f "$RUN/eula.txt" ] || echo "eula=true" > "$RUN/eula.txt"

SERVER_PID=""
DROPPED=""
FOREIGN=""
set_aside_foreign_mods() {
    for jar in "$MODS"/*.jar; do
        [ -e "$jar" ] || continue
        mv "$jar" "$MODS_BACKUP/" || die "could not set aside $jar"
        FOREIGN="$FOREIGN $jar"
        echo "   set aside $(basename "$jar") — not part of this run"
    done
}
cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "${JAVA_HOME:+$JAVA_HOME/bin/}"java -cp "$WORK" RconTool "$RCON_PORT" "$RCON_PASSWORD" stop 8000 >/dev/null 2>&1 || true
        for _ in $(seq 1 12); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 5
        done
        if kill -0 "$SERVER_PID" 2>/dev/null; then
            taskkill //F //T //PID "$SERVER_PID" >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
        fi
    fi
    for jar in $FOREIGN; do
        [ -e "$jar" ] && continue
        mv "$MODS_BACKUP/$(basename "$jar")" "$jar" 2>/dev/null || true
    done
    rmdir "$MODS_BACKUP" 2>/dev/null || true
    for jar in $DROPPED; do
        rm -f "$jar"
    done
    [ -f "$PROPS.smoke-backup" ] && mv "$PROPS.smoke-backup" "$PROPS"
    config_restore
    rm -rf "$WORK"
}
trap cleanup EXIT

# The config side of phase D, per loader: where the mod keeps its enabled switch, and how
# to flip it back. The file exists once phase A has run the server once.
config_disable() {
    case "$LOADER" in
      fabric)   CFG="$RUN/config/chunkstorageoptimizer.properties";;
      neoforge) CFG="$RUN/config/chunkstorageoptimizer-common.toml";;
    esac
    [ -f "$CFG" ] || die "no $CFG — phase A must run first"
    cp "$CFG" "$CFG.smoke-backup"
    sed -i 's/^enabled=true/enabled=false/; s/^enabled = true/enabled = false/' "$CFG"
    grep -q "^enabled *=* *false" "$CFG" || die "could not switch the config off in $CFG"
}
config_restore() {
    [ -n "${CFG:-}" ] && [ -f "$CFG.smoke-backup" ] && mv "$CFG.smoke-backup" "$CFG"
}

set_prop level-name c2mesmoke
set_prop level-type 'minecraft\:flat'
set_prop enable-rcon true
set_prop rcon.port "$RCON_PORT"
set_prop rcon.password "$RCON_PASSWORD"
set_prop online-mode false
set_prop max-tick-time -1
set_prop view-distance 4
set_prop simulation-distance 4

drop_mods() {
    cp "$C2ME_JAR" "$MODS/$(basename "$C2ME_JAR")"
    DROPPED="$MODS/$(basename "$C2ME_JAR")"
    if [ -n "$COMPAT_JAR" ]; then
        cp "$ROOT/$COMPAT_JAR" "$MODS/chunkstorageoptimizer_c2mecompat.jar"
        DROPPED="$DROPPED $MODS/chunkstorageoptimizer_c2mecompat.jar"
    fi
}
clear_mods() {
    for jar in $DROPPED; do rm -f "$jar"; done
    DROPPED=""
}

# start <log> — launches the dev server in the background, waits for Done, dies on early exit.
start() {
    LOG="$1"
    ( cd "$ROOT" && ./gradlew "$GRADLE_TASK" --no-daemon --console=plain -Pminecraft_version="$ROW" > "$LOG" 2>&1 ) &
    SERVER_PID=$!
    for _ in $(seq 1 90); do
        grep -q "Done (" "$LOG" 2>/dev/null && return 0
        kill -0 "$SERVER_PID" 2>/dev/null || die "server exited before Done — log: $LOG"
        sleep 5
    done
    die "server did not come up in 450 s — log: $LOG"
}
# stop — rcon stop, wait it out, hard fallback, forget the pid.
stop() {
    rcon stop 15000 >/dev/null || true
    for _ in $(seq 1 24); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 5
    done
    kill -0 "$SERVER_PID" 2>/dev/null && die "server would not stop — log: $LOG"
    SERVER_PID=""
}

# --- phase A: a .cso world without C2ME ------------------------------------------------------
echo "== [$LOADER] A: build a .cso world without C2ME"
set_aside_foreign_mods
clear_mods
start "$LOG_DIR/c2me-smoke-a.log"
out="$(rcon 'forceload add -8 -8 8 8' 30000)"
echo "$out" | grep -q "Marked" || die "A forceload: $out"
sleep 8
out="$(rcon 'save-all flush' 30000)"
echo "$out" | grep -q "Saved the game" || die "A save-all: $out"
out="$(rcon 'cso convert cso' 120000)"
echo "$out" | grep -q "converted" || die "A convert cso: $out"
rcon 'forceload remove all' 30000 >/dev/null || true
[ "$(cso_count)" -gt 0 ] || die "A: no .cso files under $WORLD"
[ "$(mca_count)" -eq 0 ] || die "A: .mca survived the conversion"
stop

# --- phase B: the same world under C2ME, through the bridge ----------------------------------
echo "== [$LOADER] B: serve the .cso world with C2ME installed"
drop_mods
start "$LOG_DIR/c2me-smoke-b.log"
grep -q "the compat bridge is in place" "$LOG" \
    || die "B: no bridge log line — the compat did not come up (log: $LOG)"
grep -q "Chunk Storage Optimizer disabled: C2ME" "$LOG" \
    && die "B: the guard disabled the format despite the bridge (log: $LOG)"
out="$(rcon 'forceload add -8 -8 8 8' 30000)"
echo "$out" | grep -q "Marked" || die "B forceload: $out"
sleep 8
stats="$(rcon 'cso stats' 15000)"
echo "$stats" | grep -q "CSO \[active\]" || die "B: stats not active under C2ME: $stats"
# The chunk store specifically, not "some store": a half-applied bridge serves entities and POI
# while chunks stay on C2ME's worker, and a plain `read=[1-9]` anywhere in the report would call
# that a success. The label's prefix depends on the save layout (26.x nests it under
# dimensions/minecraft/<dim>/, 1.21 keeps it at the world root), so match the trailing store name.
echo "$stats" | grep -E "/region[[:space:]]+read=" | grep -Eq "read=[1-9]" \
    || die "B: the chunk store served no reads — chunks are still on C2ME's worker: $stats"
[ "$(mca_count)" -eq 0 ] \
    || die "B: .mca appeared beside the .cso — C2ME is bypassing the bridge"
# Conversion under C2ME: the pause machinery runs against the bridged vanilla worker.
out="$(rcon 'cso convert mca prune' 300000)"
echo "$out" | grep -q "converted" || die "B convert mca under C2ME: $out"
poll_gt "B: no .mca after convert-back" mca_count 0
poll_eq "B: .cso survived the convert-back under C2ME" cso_count 0
[ -f "$WORLD/cso.disabled" ] || die "B: opt-out marker missing after convert-back"
rcon 'forceload remove all' 30000 >/dev/null || true
stop

# --- phase C: the opted-out .mca world keeps C2ME --------------------------------------------
echo "== [$LOADER] C: the opted-out world under C2ME"
start "$LOG_DIR/c2me-smoke-c.log"
stats="$(rcon 'cso stats' 15000)"
echo "$stats" | grep -q "this world opted out" || die "C: stats must show the opt-out: $stats"
out="$(rcon 'forceload add -8 -8 8 8' 30000)"
echo "$out" | grep -q "Marked" || die "C forceload: $out"
sleep 8
probe="$(rcon 'setblock 0 200 0 minecraft:air keep' 400)"
case "$probe" in *"not loaded"*) die "C: chunk not loaded in the opted-out world under C2ME: $probe";; esac
# The opted-out world's conversion is a two-step by design: the first call clears the opt-out
# marker and refuses (the session still holds the .mca files open through vanilla), and only a
# re-entered session — started with the marker gone, so this mod serves the folders — may
# convert. The refusal is part of the contract this phase pins.
out="$(rcon 'cso convert cso' 300000)"
echo "$out" | grep -q "leave and re-enter" || die "C first convert cso must refuse with the re-enter guidance: $out"
[ ! -f "$WORLD/cso.disabled" ] || die "C: the refusal must have cleared the opt-out marker"
rcon 'forceload remove all' 30000 >/dev/null || true
stop
start "$LOG_DIR/c2me-smoke-c2.log"
# prune deletes the .mca originals after the verify — the assert below counts on it.
out="$(rcon 'cso convert cso prune' 300000)"
echo "$out" | grep -q "converted" || die "C second convert cso after re-enter: $out"
echo "$out" | grep -q "Deleted" || die "C second convert cso should report the prune: $out"
poll_gt "C: no .cso after re-conversion" cso_count 0
poll_eq "C: .mca survived the re-conversion" mca_count 0
rcon 'forceload remove all' 30000 >/dev/null || true
stop

# --- phase D: the refusal — unserved .cso must not open --------------------------------------
echo "== [$LOADER] D: the refusal over a .cso world with the format switched off"
config_disable
LOG="$LOG_DIR/c2me-smoke-d.log"
( cd "$ROOT" && ./gradlew "$GRADLE_TASK" --no-daemon --console=plain -Pminecraft_version="$ROW" > "$LOG" 2>&1 ) &
SERVER_PID=$!
refused=0
for _ in $(seq 1 60); do
    if grep -q "Refusing to continue" "$LOG" 2>/dev/null; then refused=1; break; fi
    kill -0 "$SERVER_PID" 2>/dev/null || { refused=2; break; }
    sleep 5
done
if [ "$refused" = 0 ]; then
    grep -q "Done (" "$LOG" 2>/dev/null && die "D: the server OPENED the .cso world it cannot serve (log: $LOG)"
    die "D: neither refusal nor open within 300 s — log: $LOG"
fi
[ "$refused" = 2 ] || {
    for _ in $(seq 1 24); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 5
    done
}
grep -q "contains .cso region files" "$LOG" || die "D: refusal message incomplete (log: $LOG)"
grep -q "silently regenerate" "$LOG" || die "D: refusal message incomplete (log: $LOG)"
SERVER_PID=""

echo
echo "C2ME SMOKE OK — [$LOADER] bridge, opt-out and refusal all held on row $ROW (logs kept in $LOG_DIR/c2me-smoke-*.log)"
rm -rf "$WORLD"
