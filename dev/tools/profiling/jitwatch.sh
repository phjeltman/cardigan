#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$ROOT_DIR"
source "$ROOT_DIR/dev/tools/lib/runtime.sh"

DIAGNOSTIC_DIR="$ROOT_DIR/target/diagnostics/jitwatch"
mkdir -p "$DIAGNOSTIC_DIR"
JIT_LOG_FILE="$DIAGNOSTIC_DIR/cardigan_jit.log"
SERVER_LOG_FILE="$DIAGNOSTIC_DIR/server_stdout.log"
POST_SCRIPT="$DIAGNOSTIC_DIR/post.lua"

SERVER_PID=""
cleanup() {
    trap - EXIT INT TERM
    cardigan_stop_process "Cardigan server" "$SERVER_PID"
    echo "Server stopped and log flushed."
}
trap cleanup EXIT INT TERM

cardigan_require_ports_available 8080

echo "Recompiling project files..."
dev/tools/compile.sh

rm -f "$JIT_LOG_FILE"

# Measured cutoffs: retain JSON helper inlining without folding the 699-byte
# HPACK request decoder into the HTTP/2 frame dispatcher.
JIT_FLAGS=(
    -XX:MaxInlineSize=150
    -XX:FreqInlineSize=600
)

JITWATCH_FLAGS=(
    -XX:+UnlockDiagnosticVMOptions
    -XX:+LogCompilation
    "-XX:LogFile=$JIT_LOG_FILE"
    -XX:+DebugNonSafepoints
    -XX:+PrintAssembly
)

printf '\n=================================================\n'
echo "          STARTING CARDIGAN WITH JITWATCH LOGGING"
echo "================================================="
echo "Log file destination: $(pwd)/$JIT_LOG_FILE"

echo "Starting Cardigan server on port 8080..."
rm -f "$SERVER_LOG_FILE"

java --enable-native-access=ALL-UNNAMED \
  --add-modules jdk.incubator.vector \
  "${JIT_FLAGS[@]}" \
  "${JITWATCH_FLAGS[@]}" \
  -cp "target/classes:dev/example-server/target/classes" \
  dev.cardigan.Main 8080 2 > "$SERVER_LOG_FILE" 2>&1 &
SERVER_PID=$!

echo "Waiting for server to initialize (up to 30 seconds)..."
SERVER_READY=false
for i in {1..30}; do
  if curl -s -o /dev/null http://localhost:8080/users/427; then
    SERVER_READY=true
    echo "Server is ready!"
    break
  fi
  sleep 1
done

if [ "$SERVER_READY" = "false" ]; then
  echo "Error: Server failed to start or respond within 30 seconds."
  exit 1
fi

echo "Warming up GET requests (5 seconds)..."
wrk -t4 -c200 -d5s http://localhost:8080/users/427 >/dev/null

echo "Running GET Benchmark to trigger C1/C2 compilations..."
wrk -t4 -c200 -d10s http://localhost:8080/users/427

printf '%s\n' \
  'wrk.method = "POST"' \
  'wrk.body   = '\''{"name":"Charlie Brown","id":999,"active":true}'\''' \
  'wrk.headers["Content-Type"] = "application/json"' \
  > "$POST_SCRIPT"

echo "Warming up POST requests (5 seconds)..."
wrk -t4 -c200 -d5s -s "$POST_SCRIPT" http://localhost:8080/users >/dev/null

echo "Running POST Benchmark to trigger compilation on serialization/parsing..."
wrk -t4 -c200 -d10s -s "$POST_SCRIPT" http://localhost:8080/users

printf '\nLoad generation complete. Stopping server to flush the remaining log buffer.\n'
