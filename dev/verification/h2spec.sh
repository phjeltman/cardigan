#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
cd "$ROOT_DIR"
source "$ROOT_DIR/dev/tools/lib/runtime.sh"

H2SPEC_BIN="${H2SPEC_BIN:-/usr/local/bin/h2spec}"
PORT=8080
CPUS=1
TIMEOUT=2
REQUEST_PATH=/users/423
REPORT=target/h2spec-report.xml
BUILD=true
STRICT=true
VERBOSE=false
SPECS=()

usage() {
    echo "Usage: ./dev/verification/h2spec.sh [options] [spec ...]"
    echo
    echo "Starts Cardigan in cleartext HTTP/2-only mode and runs h2spec."
    echo
    echo "Options:"
    echo "  --port=N             Server port (default: 8080)"
    echo "  --cpus=N             Cardigan event loops (default: 1)"
    echo "  --timeout=N          Per-test timeout in seconds (default: 2)"
    echo "  --path=PATH          Request target (default: /users/423)"
    echo "  --report=FILE        JUnit report (default: target/h2spec-report.xml)"
    echo "  --no-build           Reuse existing target/classes"
    echo "  --no-strict          Omit h2spec strict tests"
    echo "  --verbose            Print frame-level h2spec diagnostics"
    echo "  -h, --help           Show this help"
    echo
    echo "Examples:"
    echo "  ./dev/verification/h2spec.sh"
    echo "  ./dev/verification/h2spec.sh --no-build --verbose http2/5.1"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --port=*) PORT="${1#*=}" ;;
        --cpus=*) CPUS="${1#*=}" ;;
        --timeout=*) TIMEOUT="${1#*=}" ;;
        --path=*) REQUEST_PATH="${1#*=}" ;;
        --report=*) REPORT="${1#*=}" ;;
        --no-build) BUILD=false ;;
        --no-strict) STRICT=false ;;
        --verbose) VERBOSE=true ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            SPECS+=("$@")
            break
            ;;
        *) SPECS+=("$1") ;;
    esac
    shift
done

if [ ! -x "$H2SPEC_BIN" ]; then
    echo "h2spec is not executable: $H2SPEC_BIN" >&2
    exit 1
fi
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "Invalid port: $PORT" >&2
    exit 1
fi
if ! [[ "$CPUS" =~ ^[0-9]+$ ]] || [ "$CPUS" -lt 1 ]; then
    echo "Invalid CPU count: $CPUS" >&2
    exit 1
fi
if ! [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || [ "$TIMEOUT" -lt 1 ]; then
    echo "Invalid timeout: $TIMEOUT" >&2
    exit 1
fi

if [ "$BUILD" = true ]; then
    echo "Compiling Cardigan..."
    mvn -q -f dev/pom.xml -pl :cardigan-example-server -am -DskipTests package
elif [ ! -d target/classes ] || [ ! -d dev/example-server/target/classes ]; then
    echo "Core or example-server classes do not exist; omit --no-build" >&2
    exit 1
fi

mkdir -p "$(dirname "$REPORT")"
CONFORMANCE_TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/cardigan-h2spec.XXXXXX")
SERVER_LOG="$CONFORMANCE_TEMP_DIR/server.log"
SERVER_PID=""

cleanup() {
    trap - EXIT INT TERM
    cardigan_stop_process "Cardigan server" "$SERVER_PID"
    rm -f "$SERVER_LOG"
    rmdir "$CONFORMANCE_TEMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Starting HTTP/2-only Cardigan on port $PORT with $CPUS event loop(s)..."
cardigan_require_ports_available "$PORT"
java \
        -Xms64m \
        -Xmx512m \
        --enable-native-access=ALL-UNNAMED \
        --add-opens java.base/java.lang=ALL-UNNAMED \
        --add-modules jdk.incubator.vector \
        -XX:+UseCompactObjectHeaders \
        -XX:MaxInlineSize=150 \
        -XX:FreqInlineSize=600 \
        -cp "target/classes:dev/example-server/target/classes" \
        dev.cardigan.Main "$PORT" "$CPUS" HTTP2_ONLY >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

if ! cardigan_wait_for_log \
        "$SERVER_PID" "$SERVER_LOG" "listening on socket FD" 100 0.05; then
    echo "Cardigan did not become ready:" >&2
    tail -n 80 "$SERVER_LOG" >&2
    exit 1
fi

H2SPEC_ARGS=(
    --host 127.0.0.1
    --port "$PORT"
    --path "$REQUEST_PATH"
    --timeout "$TIMEOUT"
    --junit-report "$REPORT"
)
if [ "$STRICT" = true ]; then
    H2SPEC_ARGS+=(--strict)
fi
if [ "$VERBOSE" = true ]; then
    H2SPEC_ARGS+=(--verbose)
fi

echo "Running h2spec $($H2SPEC_BIN --version)..."
set +e
"$H2SPEC_BIN" "${SPECS[@]}" "${H2SPEC_ARGS[@]}"
H2SPEC_STATUS=$?
set -e

if [ "$H2SPEC_STATUS" -ne 0 ]; then
    echo
    echo "Cardigan server log:"
    tail -n 80 "$SERVER_LOG"
fi
exit "$H2SPEC_STATUS"
