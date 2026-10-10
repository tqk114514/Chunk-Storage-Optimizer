#!/usr/bin/env bash
# Headless same-process smoke for the conversion command's lifecycle — the exact sequence
# that reproduced 1.1.5's ClassCastException in a player's game (2026-10-10):
#
#   convert mca -> forceload cold chunks through the RELEASED storage -> convert again
#
# A dedicated server hosts one world load per process, so on its own it can never meet the
# bug's real shape — released storages of a previous session still sitting in the process's
# registry, their vanilla cache repopulated since. This script reproduces that shape in one
# process instead: the first conversion releases the storages, the cold forceload makes the
# released storage serve vanilla reads (populating its cache), and the second conversion
# has to walk that cache. The unit suite (common/src/test, run per row per loader in CI)
# pins the shape and registry contracts against plain classes; only this script runs the
# MIXED game, because the mixins exist nowhere else.
#
# Usage: tools/smoke-lifecycle.sh [row]        default row: 26.3.0
# Needs: the fabric dev runtime (fabric/run) that `gradlew :fabric:runServer` uses, and a
# JDK on PATH (or JAVA_HOME) for the embedded RCON client.
set -euo pipefail

ROW="${1:-26.3.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/fabric/run"
PROPS="$RUN/server.properties"
WORLD="$RUN/smokeworld"
LOG="$RUN/logs/smoke-lifecycle.log"
RCON_PORT=25575
RCON_PASSWORD=smoke-lifecycle

die() { echo "SMOKE FAILED: $*" >&2; exit 1; }
[ -f "$PROPS" ] || die "no $PROPS — run the dev server once so the runtime exists"

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
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
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

    private static void send(DataOutputStream out, int id, int type, String payload) throws IOException {
        byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(4 + 4 + 4 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        packet.putInt(4 + 4 + body.length + 2).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        out.write(packet.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in) throws IOException {
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
rcon() { "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$WORK" RconTool "$RCON_PORT" "$RCON_PASSWORD" "$1" "${2:-20000}" 2>/dev/null; }

# --- server configuration: our values now, the dev ones back on exit -----------------------
set_prop() {
    if grep -q "^$1=" "$PROPS"; then
        sed -i "s|^$1=.*|$1=$2|" "$PROPS"
    else
        echo "$1=$2" >> "$PROPS"
    fi
}
cp "$PROPS" "$PROPS.smoke-backup"
SERVER_PID=""

# Every way out — success, a failed assert, a Ctrl-C — restores the dev properties and
# leaves no server behind: the trap is the only cleanup path, so nothing leaks past a die.
cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$WORK" RconTool "$RCON_PORT" "$RCON_PASSWORD" stop 8000 >/dev/null 2>&1 || true
        for _ in $(seq 1 12); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 5
        done
        if kill -0 "$SERVER_PID" 2>/dev/null; then
            taskkill //F //T //PID "$SERVER_PID" >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
        fi
    fi
    [ -f "$PROPS.smoke-backup" ] && mv "$PROPS.smoke-backup" "$PROPS"
    rm -rf "$WORK"
}
trap cleanup EXIT
set_prop level-name smokeworld
set_prop level-type 'minecraft\:flat'
set_prop enable-rcon true
set_prop rcon.port "$RCON_PORT"
set_prop rcon.password "$RCON_PASSWORD"
set_prop max-tick-time -1
set_prop view-distance 4
set_prop simulation-distance 4
rm -rf "$WORLD"

# --- launch ----------------------------------------------------------------------------------
echo "row: $ROW — building and starting the dev server (first run takes a while)"
( cd "$ROOT" && ./gradlew :fabric:runServer --console=plain -Pminecraft_version="$ROW" > "$LOG" 2>&1 ) &
SERVER_PID=$!

for _ in $(seq 1 90); do
    grep -q "Done (" "$LOG" 2>/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || die "server process exited before Done — log: $LOG"
    sleep 5
done
grep -q "Done (" "$LOG" || die "server did not come up in 450 s — log: $LOG"

# --- the sequence -----------------------------------------------------------------------------
step() { echo; echo "== $1"; }

step "flush the save so spawn chunks exist as .cso"
out="$(rcon 'save-all flush' 30000)"
echo "$out" | grep -q "Saved the game" || die "save-all flush: $out"

step "convert to mca — releases the storages and writes the opt-out marker"
out="$(rcon 'cso convert mca prune' 120000)"
echo "$out" | grep -q "converted" || die "first convert mca: $out"

step "cold-load far chunks through the RELEASED storage, populating its vanilla cache"
out="$(rcon 'forceload add 2000 2000 2240 2240' 30000)"
echo "$out" | grep -q "Marked" || die "forceload: $out"
sleep 12

step "convert mca AGAIN — walks the populated cache of a released storage (the 1.1.5 crash site)"
out="$(rcon 'cso convert mca prune' 120000)"
echo "$out" | grep -q "converted" || die "second convert mca: $out"

step "convert cso — the opt-out guidance branch, clears the marker"
out="$(rcon 'cso convert cso' 30000)"
echo "$out" | grep -q "The opt-out marker is cleared" || die "guidance run: $out"

step "convert cso AGAIN in the same session — must be refused until the world is re-entered"
out="$(rcon 'cso convert cso' 30000)"
echo "$out" | grep -q "Leave and re-enter the world" || die "second convert cso should refuse: $out"

step "shut down"
rcon stop 15000 >/dev/null || true
for _ in $(seq 1 24); do
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 5
done
kill -0 "$SERVER_PID" 2>/dev/null && die "server would not stop — log: $LOG"

# --- whole-process assertions ------------------------------------------------------------------
grep -q "ClassCastException" "$LOG" && die "ClassCastException in the server log: $LOG"
grep -q "CSO conversion failed" "$LOG" && die "a conversion failed in the server log: $LOG"

echo
echo "SMOKE OK — same-process lifecycle clean on row $ROW (log kept at $LOG)"
