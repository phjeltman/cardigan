#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$ROOT_DIR"
source "$ROOT_DIR/dev/tools/lib/runtime.sh"

SERVER_PID=""
DRIP_PID=""
cleanup() {
    trap - EXIT INT TERM
    if [ -n "$DRIP_PID" ]; then
        kill "$DRIP_PID" 2>/dev/null || true
        wait "$DRIP_PID" 2>/dev/null || true
    fi
    cardigan_stop_process "Cardigan server" "$SERVER_PID"
}
trap cleanup EXIT INT TERM

ulimit -n 65536 2>/dev/null || true

echo "Compiling Cardigan server..."
dev/tools/compile.sh >/dev/null

echo "Starting Cardigan server on port 8088..."
cardigan_require_ports_available 8088
java -Xms64m -Xmx256m \
    --enable-native-access=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-modules jdk.incubator.vector \
    -cp "target/classes:dev/example-server/target/classes" \
    dev.cardigan.Main 8088 2 &
SERVER_PID=$!

echo "Waiting for server to bind port 8088..."
READY=false
for _ in {1..100}; do
    if curl -fsS -o /dev/null http://localhost:8088/users/0 2>/dev/null; then
        READY=true
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Cardigan exited before becoming ready" >&2
        exit 1
    fi
    sleep 0.05
done
if [ "$READY" != true ]; then
    echo "Cardigan did not become ready" >&2
    exit 1
fi

HOST=127.0.0.1
PORT=8088
NUM_SLOW_CONNECTIONS=500
SLOW_FDS=()
SLOW_REQUEST=$'POST /users HTTP/1.1\r\nHost: localhost\r\n'
SLOW_REQUEST+=$'User-Agent: SlowlorisTest\r\nContent-Length: 1000\r\n'

echo "================================================="
echo "       CARDIGAN SLOWLORIS RESILIENCE TEST"
echo "================================================="
echo "Target: http://$HOST:$PORT"
echo "Opening $NUM_SLOW_CONNECTIONS partial-request sockets..."

for ((i = 1; i <= NUM_SLOW_CONNECTIONS; i++)); do
    if exec {fd}<>"/dev/tcp/$HOST/$PORT"; then
        printf '%s' "$SLOW_REQUEST" >&"$fd"
        SLOW_FDS+=("$fd")
    fi
    if ((i % 100 == 0 || i == NUM_SLOW_CONNECTIONS)); then
        echo "Established ${#SLOW_FDS[@]} / $i partial requests..."
    fi
done

drip_headers() {
    for _ in {1..5}; do
        sleep 1
        alive=0
        for fd in "${SLOW_FDS[@]}"; do
            if printf '%s' $'X-Slow-Header: drip\r\n' >&"$fd" 2>/dev/null; then
                alive=$((alive + 1))
            fi
        done
        echo "Dripped another partial header to $alive sockets..."
    done
}
drip_headers &
DRIP_PID=$!

echo "Sending legitimate requests while partial requests remain active..."
LATENCIES=()
FAILURES=0
for request in {1..10}; do
    if latency=$(curl --max-time 2 -fsS -o /dev/null \
            -w '%{time_total}' "http://$HOST:$PORT/users/427"); then
        LATENCIES+=("$latency")
    else
        echo "Legitimate request $request failed" >&2
        FAILURES=$((FAILURES + 1))
    fi
done

wait "$DRIP_PID"
DRIP_PID=""

for fd in "${SLOW_FDS[@]}"; do
    exec {fd}>&-
done

if ((FAILURES != 0)); then
    echo "Slowloris resilience test failed: $FAILURES legitimate request(s) failed" >&2
    exit 1
fi

printf '%s\n' "${LATENCIES[@]}" | awk '
    NR == 1 { min = $1; max = $1 }
    { sum += $1; if ($1 < min) min = $1; if ($1 > max) max = $1 }
    END {
        printf "Legitimate requests: PASS; mean %.3f ms, min %.3f ms, max %.3f ms\n", \
            (sum / NR) * 1000, min * 1000, max * 1000
    }
'

echo "Done!"
