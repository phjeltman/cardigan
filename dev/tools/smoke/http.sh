#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$ROOT_DIR"
source "$ROOT_DIR/dev/tools/lib/runtime.sh"

SERVER_PID=""
cleanup() {
    trap - EXIT INT TERM
    cardigan_stop_process "Cardigan server" "$SERVER_PID"
    echo "Stopped."
}
trap cleanup EXIT INT TERM

cardigan_require_ports_available 8080

echo "Compiling Cardigan..."
mvn -q -f dev/pom.xml -pl :cardigan-example-server -am -DskipTests package

echo "Starting Cardigan server on port 8080..."
java --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-modules jdk.incubator.vector -XX:MaxInlineSize=150 -XX:FreqInlineSize=600 -cp "target/classes:dev/example-server/target/classes" dev.cardigan.Main 8080 4 &
SERVER_PID=$!

echo "Waiting for server to initialize..."
READY=false
for _ in {1..100}; do
    if curl -fsS -o /dev/null http://localhost:8080/users/0 2>/dev/null; then
        READY=true
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        break
    fi
    sleep 0.05
done
if [ "$READY" != true ]; then
    echo "Cardigan did not become ready" >&2
    exit 1
fi

echo "Sending HTTP GET request via curl..."
curl -s http://localhost:8080/users/427
printf '\n\n'

echo "Sending HTTP POST JSON request via curl..."
curl -v -H "Content-Type: application/json" -d '{"name":"Charlie Brown","id":999,"active":true}' http://localhost:8080/users

printf '\nServer test complete!\n'
