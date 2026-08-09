#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
cd "$ROOT_DIR"
source "$ROOT_DIR/dev/tools/lib/runtime.sh"

RUN_MICROBENCHMARKS=false
MICRO_ONLY=false
REQUEST_PARSERS_ONLY=false
HPACK_HUFFMAN_ONLY=false
HTTP2_RESPONSE_ONLY=false
HTTP1_CHUNKED_ONLY=false
CHUNKED_UPLOAD=false
CHUNK_SIZES="64,1024,16384"
CHUNK_SIZE=""
PIPELINED=false
PIPELINE_DEPTH=16
HTTP2=false
HTTP2_STREAMS=1
TLS=false
TLS12=false
TLS_DIRECT_RX=false
TLS_CERTIFICATE=""
TLS_PRIVATE_KEY=""
TLS_STATS=false
HTTP2_RESOURCE_STATS=false
SCHEDULER_STATS=false
FIXED_FILE_STATS=false
FIXED_FILES_MODE="auto"
FIXED_FILES_CAPACITY=8192
URING_MAX_TASKS=""
SCHEDULER_BOUNDED="true"
SCHEDULER_CQES=256
SCHEDULER_COMPLETIONS=256
SCHEDULER_PROTOCOL_TASKS=128
SCHEDULER_HANDLER_CONTINUATIONS=32
SCHEDULER_EGRESS_TASKS=256
SCHEDULER_EXTERNAL_TASKS=64
SCHEDULER_QUANTUM_MICROS=50
HTTP2_MAX_PARKED_SENDERS=1024
HTTP2_FLOW_CONTROL=false
FLOW_CONNECTIONS=4
FLOW_STALLED_STREAMS=64
FLOW_CYCLES=5
FLOW_TIMEOUT_MILLIS=5000
CPUS=1
ISOLATED_CARRIERS=""
ISOLATED_CPUS=""
ISOLATED_MAX_TASKS=4096
DURATION="60s"
WARMUP_DURATION="5s"
WRK_THREADS=4
CONNECTIONS=200
PAYLOAD_SIZE=65536
SLEEP_MILLIS=2000
HEAVY_ITERATIONS=5000000
ENDPOINT_CHOICE=""
JVMTI=false

usage() {
    cat <<'EOF'
Usage: ./dev/benchmarks/benchmark.sh [options] [1|2|3|4|5|6|7|8]

Endpoints:
  1  GET /users/423
  2  POST /users
  3  GET /some/response/large
  4  GET /heavy (@Isolated)
  5  GET /stream/{bytes}
  6  POST /stream/upload
  7  GET /stream-unknown/{bytes}
  8  POST /stream/upload-heavy (@Isolated)

With no endpoint, the regular benchmark runs endpoints 1 and 2. In HTTP/1
pipelined mode, endpoint 1 is the default.

Options:
  --pipeline                 Send HTTP/1.1 requests in pipelines
  --pipeline-depth=N         Pipeline N requests at a time (default: 16)
  --http2                    Use HTTP/2 (prior knowledge, or ALPN with TLS)
  --http2-streams=N          Streams per HTTP/2 connection (default: 1)
                             Use 16 or more for a saturation-throughput run
  --tls                      Use Cardigan's TLS 1.3 OpenSSL/kTLS transport
  --tls12                    Force TLS 1.2 (implies --tls)
  --tls-direct-rx            Use multishot io_uring for TLS 1.2 kTLS RX
  --tls-certificate=PATH     PEM certificate chain (default: generated test cert)
  --tls-private-key=PATH     PEM private key (default: generated test key)
  --tls-stats                Print opt-in TLS transport counters at shutdown
  --http2-resource-stats     Print opt-in HTTP/2 resource high-water marks
  --scheduler-stats          Print per-loop scheduler turn/lane counters
  --fixed-file-stats         Print fixed-file capacity and admission counters
  --fixed-files=MODE         auto, legacy, async-explicit, async-alloc, direct
  --fixed-files-capacity=N   Registered socket slots per event loop (default: 8192)
  --uring-max-tasks=N        Pin io_uring task slots per event loop
                             (default: derived from fixed-file capacity)
  --scheduler-bounded=BOOL   Enable bounded reactor turns (default: true)
  --scheduler-cqes=N         CQEs reaped per turn (default: 256)
  --scheduler-completions=N  CQ-unblocked continuations per turn (default: 256)
  --scheduler-protocol=N     Protocol continuations per turn (default: 128)
  --scheduler-handlers=N     Handler continuations per turn (default: 32)
  --scheduler-egress=N       Egress-ready connections per turn (default: 256)
  --scheduler-external=N     External inbox tasks per turn (default: 64)
  --scheduler-quantum-us=N   Cooperative protocol quantum (default: 50)
  --http2-max-parked=N       Maximum parked response senders per event loop
                             (default: 1024; excess streams are reset)
  --http2-flow-control       Stall zero-window HTTP/2 responses, then cancel
                             them and verify same/cross-connection recovery
  --flow-connections=N       Zero-window connections in that probe (default: 4)
  --flow-stalled-streams=N   Stalled streams per connection (default: 64)
  --flow-cycles=N            Stall/cancel/recovery cycles (default: 5)
  --flow-timeout-ms=N        Per-frame probe timeout (default: 5000)
  --endpoint=N|all           Select an endpoint, or the regular GET/POST suite
  --duration=TIME            Client measurement duration (default: 60s)
  --warmup=TIME              Client warm-up duration (default: 5s)
  --threads=N                Client worker thread count (default: 4)
  --connections=N            Client connection count (default: 200)
  --cpus=N                   Server event-loop count
  --isolated-carriers=N      Carrier count for Cardigan @Isolated routes
                             (default: same as --cpus)
  --isolated-cpus=LIST       CPU list for @Isolated carriers, e.g. 2 or 2-3
  --isolated-max-tasks=N     Process-wide live @Isolated task limit (default: 4096)
  --payload-size=N           Bytes transferred by endpoints 3, 5, 6, 7 and 8
                             (default: 65536)
  --chunked-upload           Benchmark endpoint 6 or 8 with HTTP/1 chunk framing
  --chunk-sizes=LIST         Comma-separated chunk sizes to sweep
                             (default: 64,1024,16384; implies --chunked-upload)
  --sleep-millis=N           Work duration for /sleepy (default: 2000)
  --heavy-iterations=N       CPU iterations for /heavy (default: 5000000)
  --microbenchmarks          Run production-path microbenchmarks before live load
  --micro-only               Run production-path microbenchmarks without live load
  --request-parsers          Benchmark HTTP/1 and HTTP/2 request paths only
  --hpack-huffman            Benchmark production HPACK Huffman paths only
  --http2-response           Benchmark small HTTP/2 response framing only
  --http1-chunked            Benchmark Pico HTTP/1 chunk decoding only
  --jvmti                    Build with debug symbols and load perf-jvmti
  -h, --help                 Show this help

Examples:
  ./dev/benchmarks/benchmark.sh --cpus=4
  ./dev/benchmarks/benchmark.sh --pipeline --cpus=4 --duration=10s 1
  ./dev/benchmarks/benchmark.sh --pipeline-depth=4 --endpoint=2
  ./dev/benchmarks/benchmark.sh --cpus=1 --isolated-carriers=1 --duration=10s 4
  ./dev/benchmarks/benchmark.sh --http2 --cpus=4 --duration=10s 1
  ./dev/benchmarks/benchmark.sh --http2 --http2-streams=16 --cpus=4 --duration=10s 1
  ./dev/benchmarks/benchmark.sh --tls --http2 --cpus=2 --duration=10s 1
  ./dev/benchmarks/benchmark.sh --tls12 --tls-direct-rx --http2 --cpus=2 --duration=10s 1
  ./dev/benchmarks/benchmark.sh --http2-flow-control --cpus=2 --flow-cycles=10
  ./dev/benchmarks/benchmark.sh --http2 --payload-size=16384 --cpus=2 --duration=10s 3
  ./dev/benchmarks/benchmark.sh --http2 --payload-size=1048576 --cpus=2 --duration=10s 5
  ./dev/benchmarks/benchmark.sh --http2 --payload-size=1048576 --cpus=2 --duration=10s 6
  ./dev/benchmarks/benchmark.sh --chunked-upload --payload-size=65536 --cpus=2 --duration=10s
  ./dev/benchmarks/benchmark.sh --endpoint=8 --payload-size=65536 --cpus=2 --duration=10s
  ./dev/benchmarks/benchmark.sh --micro-only
  ./dev/benchmarks/benchmark.sh --request-parsers
  ./dev/benchmarks/benchmark.sh --hpack-huffman
  ./dev/benchmarks/benchmark.sh --http2-response
  ./dev/benchmarks/benchmark.sh --http1-chunked
EOF
}

require_value() {
    if [ "$#" -lt 2 ] || [ -z "$2" ]; then
        echo "Missing value for $1" >&2
        usage >&2
        exit 2
    fi
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --microbenchmarks)
            RUN_MICROBENCHMARKS=true
            shift
            ;;
        --micro-only)
            RUN_MICROBENCHMARKS=true
            MICRO_ONLY=true
            shift
            ;;
        --request-parsers)
            RUN_MICROBENCHMARKS=true
            MICRO_ONLY=true
            REQUEST_PARSERS_ONLY=true
            shift
            ;;
        --hpack-huffman)
            RUN_MICROBENCHMARKS=true
            MICRO_ONLY=true
            HPACK_HUFFMAN_ONLY=true
            shift
            ;;
        --http2-response)
            RUN_MICROBENCHMARKS=true
            MICRO_ONLY=true
            HTTP2_RESPONSE_ONLY=true
            shift
            ;;
        --http1-chunked)
            RUN_MICROBENCHMARKS=true
            MICRO_ONLY=true
            HTTP1_CHUNKED_ONLY=true
            shift
            ;;
        --scheduler-stats)
            SCHEDULER_STATS=true
            shift
            ;;
        --fixed-file-stats)
            FIXED_FILE_STATS=true
            shift
            ;;
        --fixed-files=*)
            FIXED_FILES_MODE="${1#*=}"
            shift
            ;;
        --fixed-files)
            require_value "$@"
            FIXED_FILES_MODE="$2"
            shift 2
            ;;
        --fixed-files-capacity=*)
            FIXED_FILES_CAPACITY="${1#*=}"
            shift
            ;;
        --fixed-files-capacity)
            require_value "$@"
            FIXED_FILES_CAPACITY="$2"
            shift 2
            ;;
        --uring-max-tasks=*)
            URING_MAX_TASKS="${1#*=}"
            shift
            ;;
        --uring-max-tasks)
            require_value "$@"
            URING_MAX_TASKS="$2"
            shift 2
            ;;
        --scheduler-bounded=*)
            SCHEDULER_BOUNDED="${1#*=}"
            shift
            ;;
        --scheduler-bounded)
            require_value "$@"
            SCHEDULER_BOUNDED="$2"
            shift 2
            ;;
        --scheduler-cqes=*)
            SCHEDULER_CQES="${1#*=}"
            shift
            ;;
        --scheduler-cqes)
            require_value "$@"
            SCHEDULER_CQES="$2"
            shift 2
            ;;
        --scheduler-completions=*)
            SCHEDULER_COMPLETIONS="${1#*=}"
            shift
            ;;
        --scheduler-completions)
            require_value "$@"
            SCHEDULER_COMPLETIONS="$2"
            shift 2
            ;;
        --scheduler-protocol=*)
            SCHEDULER_PROTOCOL_TASKS="${1#*=}"
            shift
            ;;
        --scheduler-protocol)
            require_value "$@"
            SCHEDULER_PROTOCOL_TASKS="$2"
            shift 2
            ;;
        --scheduler-handlers=*)
            SCHEDULER_HANDLER_CONTINUATIONS="${1#*=}"
            shift
            ;;
        --scheduler-handlers)
            require_value "$@"
            SCHEDULER_HANDLER_CONTINUATIONS="$2"
            shift 2
            ;;
        --scheduler-egress=*)
            SCHEDULER_EGRESS_TASKS="${1#*=}"
            shift
            ;;
        --scheduler-egress)
            require_value "$@"
            SCHEDULER_EGRESS_TASKS="$2"
            shift 2
            ;;
        --scheduler-external=*)
            SCHEDULER_EXTERNAL_TASKS="${1#*=}"
            shift
            ;;
        --scheduler-external)
            require_value "$@"
            SCHEDULER_EXTERNAL_TASKS="$2"
            shift 2
            ;;
        --scheduler-quantum-us=*)
            SCHEDULER_QUANTUM_MICROS="${1#*=}"
            shift
            ;;
        --scheduler-quantum-us)
            require_value "$@"
            SCHEDULER_QUANTUM_MICROS="$2"
            shift 2
            ;;
        --chunked-upload)
            CHUNKED_UPLOAD=true
            shift
            ;;
        --chunk-sizes=*)
            CHUNKED_UPLOAD=true
            CHUNK_SIZES="${1#*=}"
            shift
            ;;
        --chunk-sizes)
            require_value "$@"
            CHUNKED_UPLOAD=true
            CHUNK_SIZES="$2"
            shift 2
            ;;
        --pipeline)
            PIPELINED=true
            shift
            ;;
        --pipeline-depth=*)
            PIPELINED=true
            PIPELINE_DEPTH="${1#*=}"
            shift
            ;;
        --pipeline-depth)
            require_value "$@"
            PIPELINED=true
            PIPELINE_DEPTH="$2"
            shift 2
            ;;
        --http2)
            HTTP2=true
            shift
            ;;
        --tls)
            TLS=true
            shift
            ;;
        --tls12)
            TLS=true
            TLS12=true
            shift
            ;;
        --tls-direct-rx)
            TLS=true
            TLS_DIRECT_RX=true
            shift
            ;;
        --tls-stats)
            TLS=true
            TLS_STATS=true
            shift
            ;;
        --tls-certificate=*)
            TLS=true
            TLS_CERTIFICATE="${1#*=}"
            shift
            ;;
        --tls-certificate)
            require_value "$@"
            TLS=true
            TLS_CERTIFICATE="$2"
            shift 2
            ;;
        --tls-private-key=*)
            TLS=true
            TLS_PRIVATE_KEY="${1#*=}"
            shift
            ;;
        --tls-private-key)
            require_value "$@"
            TLS=true
            TLS_PRIVATE_KEY="$2"
            shift 2
            ;;
        --http2-streams=*)
            HTTP2=true
            HTTP2_STREAMS="${1#*=}"
            shift
            ;;
        --http2-streams)
            require_value "$@"
            HTTP2=true
            HTTP2_STREAMS="$2"
            shift 2
            ;;
        --http2-resource-stats)
            HTTP2_RESOURCE_STATS=true
            shift
            ;;
        --http2-max-parked=*)
            HTTP2_MAX_PARKED_SENDERS="${1#*=}"
            shift
            ;;
        --http2-max-parked)
            require_value "$@"
            HTTP2_MAX_PARKED_SENDERS="$2"
            shift 2
            ;;
        --http2-flow-control)
            HTTP2=true
            HTTP2_RESOURCE_STATS=true
            HTTP2_FLOW_CONTROL=true
            shift
            ;;
        --flow-connections=*)
            FLOW_CONNECTIONS="${1#*=}"
            shift
            ;;
        --flow-connections)
            require_value "$@"
            FLOW_CONNECTIONS="$2"
            shift 2
            ;;
        --flow-stalled-streams=*)
            FLOW_STALLED_STREAMS="${1#*=}"
            shift
            ;;
        --flow-stalled-streams)
            require_value "$@"
            FLOW_STALLED_STREAMS="$2"
            shift 2
            ;;
        --flow-cycles=*)
            FLOW_CYCLES="${1#*=}"
            shift
            ;;
        --flow-cycles)
            require_value "$@"
            FLOW_CYCLES="$2"
            shift 2
            ;;
        --flow-timeout-ms=*)
            FLOW_TIMEOUT_MILLIS="${1#*=}"
            shift
            ;;
        --flow-timeout-ms)
            require_value "$@"
            FLOW_TIMEOUT_MILLIS="$2"
            shift 2
            ;;
        --cpus=*)
            CPUS="${1#*=}"
            shift
            ;;
        --cpus)
            require_value "$@"
            CPUS="$2"
            shift 2
            ;;
        --isolated-carriers=*)
            ISOLATED_CARRIERS="${1#*=}"
            shift
            ;;
        --isolated-carriers)
            require_value "$@"
            ISOLATED_CARRIERS="$2"
            shift 2
            ;;
        --isolated-cpus=*)
            ISOLATED_CPUS="${1#*=}"
            shift
            ;;
        --isolated-cpus)
            require_value "$@"
            ISOLATED_CPUS="$2"
            shift 2
            ;;
        --isolated-max-tasks=*)
            ISOLATED_MAX_TASKS="${1#*=}"
            shift
            ;;
        --isolated-max-tasks)
            require_value "$@"
            ISOLATED_MAX_TASKS="$2"
            shift 2
            ;;
        --duration=*)
            DURATION="${1#*=}"
            shift
            ;;
        --duration)
            require_value "$@"
            DURATION="$2"
            shift 2
            ;;
        --warmup=*)
            WARMUP_DURATION="${1#*=}"
            shift
            ;;
        --warmup)
            require_value "$@"
            WARMUP_DURATION="$2"
            shift 2
            ;;
        --threads=*)
            WRK_THREADS="${1#*=}"
            shift
            ;;
        --threads)
            require_value "$@"
            WRK_THREADS="$2"
            shift 2
            ;;
        --connections=*)
            CONNECTIONS="${1#*=}"
            shift
            ;;
        --connections)
            require_value "$@"
            CONNECTIONS="$2"
            shift 2
            ;;
        --payload-size=*)
            PAYLOAD_SIZE="${1#*=}"
            shift
            ;;
        --payload-size)
            require_value "$@"
            PAYLOAD_SIZE="$2"
            shift 2
            ;;
        --sleep-millis=*)
            SLEEP_MILLIS="${1#*=}"
            shift
            ;;
        --sleep-millis)
            require_value "$@"
            SLEEP_MILLIS="$2"
            shift 2
            ;;
        --heavy-iterations=*)
            HEAVY_ITERATIONS="${1#*=}"
            shift
            ;;
        --heavy-iterations)
            require_value "$@"
            HEAVY_ITERATIONS="$2"
            shift 2
            ;;
        --endpoint=*)
            ENDPOINT_CHOICE="${1#*=}"
            shift
            ;;
        --endpoint)
            require_value "$@"
            ENDPOINT_CHOICE="$2"
            shift 2
            ;;
        --jvmti)
            JVMTI=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        1|2|3|4|5|6|7|8)
            ENDPOINT_CHOICE="$1"
            shift
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$ISOLATED_CARRIERS" ]; then
    ISOLATED_CARRIERS="$CPUS"
fi

for numeric_option in \
    "pipeline depth:$PIPELINE_DEPTH" \
    "HTTP/2 stream count:$HTTP2_STREAMS" \
    "HTTP/2 parked sender limit:$HTTP2_MAX_PARKED_SENDERS" \
    "flow-control connection count:$FLOW_CONNECTIONS" \
    "flow-control stalled stream count:$FLOW_STALLED_STREAMS" \
    "flow-control cycle count:$FLOW_CYCLES" \
    "flow-control timeout:$FLOW_TIMEOUT_MILLIS" \
    "CPU count:$CPUS" \
    "isolated carrier count:$ISOLATED_CARRIERS" \
    "isolated task limit:$ISOLATED_MAX_TASKS" \
    "fixed-file capacity:$FIXED_FILES_CAPACITY" \
    "scheduler CQE turn budget:$SCHEDULER_CQES" \
    "scheduler completion turn budget:$SCHEDULER_COMPLETIONS" \
    "scheduler protocol turn budget:$SCHEDULER_PROTOCOL_TASKS" \
    "scheduler handler turn budget:$SCHEDULER_HANDLER_CONTINUATIONS" \
    "scheduler egress turn budget:$SCHEDULER_EGRESS_TASKS" \
    "scheduler external turn budget:$SCHEDULER_EXTERNAL_TASKS" \
    "scheduler protocol quantum:$SCHEDULER_QUANTUM_MICROS" \
    "client thread count:$WRK_THREADS" \
    "connection count:$CONNECTIONS" \
    "payload size:$PAYLOAD_SIZE" \
    "heavy iteration count:$HEAVY_ITERATIONS"
do
    option_name="${numeric_option%%:*}"
    option_value="${numeric_option#*:}"
    if ! [[ "$option_value" =~ ^[1-9][0-9]*$ ]]; then
        echo "Invalid $option_name: $option_value" >&2
        exit 2
    fi
done

if [ -n "$URING_MAX_TASKS" ] &&
   ! [[ "$URING_MAX_TASKS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid io_uring task limit: $URING_MAX_TASKS" >&2
    exit 2
fi

case "$FIXED_FILES_MODE" in
    auto|legacy|async-explicit|async-alloc|direct) ;;
    *)
        echo "Invalid fixed-file mode: $FIXED_FILES_MODE" >&2
        exit 2
        ;;
esac

case "$SCHEDULER_BOUNDED" in
    true|false) ;;
    *)
        echo "--scheduler-bounded must be true or false" >&2
        exit 2
        ;;
esac

if [ "$TLS" = true ] && [ "$FIXED_FILES_MODE" = direct ]; then
    echo "--fixed-files=direct cannot be used with TLS" >&2
    exit 2
fi

if ! [[ "$SLEEP_MILLIS" =~ ^[0-9]+$ ]]; then
    echo "Invalid sleep duration: $SLEEP_MILLIS" >&2
    exit 2
fi

for cpu_list_option in \
    "isolated CPU list:$ISOLATED_CPUS"
do
    option_name="${cpu_list_option%%:*}"
    option_value="${cpu_list_option#*:}"
    if [ -n "$option_value" ] &&
       ! [[ "$option_value" =~ ^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$ ]]; then
        echo "Invalid $option_name: $option_value" >&2
        exit 2
    fi
done

if [ "$HTTP2" = true ] && [ "$PIPELINED" = true ]; then
    echo "--http2 and --pipeline are mutually exclusive" >&2
    exit 2
fi

if [ "$HTTP2" = true ] && [ "$CHUNKED_UPLOAD" = true ]; then
    echo "--chunked-upload is an HTTP/1-only mode" >&2
    exit 2
fi

if [ "$CHUNKED_UPLOAD" = true ] &&
   ! [[ "$CHUNK_SIZES" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]]; then
    echo "Invalid chunk-size list: $CHUNK_SIZES" >&2
    exit 2
fi

if { [ -n "$TLS_CERTIFICATE" ] && [ -z "$TLS_PRIVATE_KEY" ]; } ||
   { [ -z "$TLS_CERTIFICATE" ] && [ -n "$TLS_PRIVATE_KEY" ]; }; then
    echo "--tls-certificate and --tls-private-key must be supplied together" >&2
    exit 2
fi

if [ "$TLS_DIRECT_RX" = true ] && [ "$TLS12" = false ]; then
    echo "--tls-direct-rx currently requires --tls12" >&2
    exit 2
fi

if [ "$TLS" = true ] && [ "$HTTP2_FLOW_CONTROL" = true ]; then
    echo "TLS mode does not yet support the flow-control probe" >&2
    exit 2
fi

if [ "$FLOW_STALLED_STREAMS" -gt 127 ]; then
    echo "--flow-stalled-streams must be at most 127" >&2
    exit 2
fi

if [ "$HTTP2_FLOW_CONTROL" = true ]; then
    if [ -n "$ENDPOINT_CHOICE" ]; then
        echo "--http2-flow-control cannot be combined with an endpoint selection" >&2
        exit 2
    fi
fi

MICRO_ONLY_COUNT=0
for micro_only in \
    "$REQUEST_PARSERS_ONLY" "$HPACK_HUFFMAN_ONLY" "$HTTP2_RESPONSE_ONLY" \
    "$HTTP1_CHUNKED_ONLY"
do
    if [ "$micro_only" = true ]; then
        MICRO_ONLY_COUNT=$((MICRO_ONLY_COUNT + 1))
    fi
done
if [ "$MICRO_ONLY_COUNT" -gt 1 ]; then
    echo "Microbenchmark-only modes are mutually exclusive" >&2
    exit 2
fi

if [ "$PIPELINED" = false ] && [ "$CHUNKED_UPLOAD" = false ] &&
   [ "$HTTP2_FLOW_CONTROL" = false ] && [ "$MICRO_ONLY" = false ] &&
   ! command -v h2load >/dev/null 2>&1; then
    echo "Regular HTTP/1 and HTTP/2 benchmarks require h2load" >&2
    exit 2
fi

if { [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; } &&
   [ "$MICRO_ONLY" = false ] &&
   ! command -v wrk >/dev/null 2>&1; then
    echo "HTTP/1 pipeline and chunked-upload modes require wrk" >&2
    exit 2
fi

if [ -z "$ENDPOINT_CHOICE" ]; then
    if [ "$CHUNKED_UPLOAD" = true ]; then
        ENDPOINT_CHOICE="6"
    elif [ "$PIPELINED" = true ]; then
        ENDPOINT_CHOICE="1"
    else
        ENDPOINT_CHOICE="all"
    fi
fi

case "$ENDPOINT_CHOICE" in
    all)
        ENDPOINTS=(1 2)
        ;;
    1|2|3|4|5|6|7|8)
        ENDPOINTS=("$ENDPOINT_CHOICE")
        ;;
    *)
        echo "Unknown endpoint: $ENDPOINT_CHOICE" >&2
        exit 2
        ;;
esac

if [ "$CHUNKED_UPLOAD" = true ] &&
   [ "$ENDPOINT_CHOICE" != "6" ] &&
   [ "$ENDPOINT_CHOICE" != "8" ]; then
    echo "--chunked-upload requires endpoint 6 or 8" >&2
    exit 2
fi

CARDIGAN_PID=""
CLIENT_PID=""
TEMP_DIR=""

stop_process() {
    local label="$1"
    local pid="$2"
    local attempts=30
    if [ "$label" = "Cardigan server" ] && [ "$HTTP2_RESOURCE_STATS" = true ]; then
        attempts=50
    fi
    cardigan_stop_process "$label" "$pid" "$attempts"
}

cleanup() {
    trap - EXIT INT TERM
    stop_process "load client" "$CLIENT_PID"
    stop_process "Cardigan server" "$CARDIGAN_PID"
    if [ -n "$TEMP_DIR" ] && [ -d "$TEMP_DIR" ]; then
        rm -f "$TEMP_DIR"/*.lua "$TEMP_DIR"/*.log "$TEMP_DIR"/*.out \
            "$TEMP_DIR"/*.json "$TEMP_DIR"/*.bin \
            "$TEMP_DIR"/*.pem 2>/dev/null || true
        rmdir "$TEMP_DIR" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/cardigan-benchmark.XXXXXX")
POST_BODY_FILE="$TEMP_DIR/post-body.json"
printf '%s' '{"name":"Alice Smith","id":427,"active":true}' > "$POST_BODY_FILE"
STREAM_BODY_FILE="$TEMP_DIR/stream-body.bin"
head -c "$PAYLOAD_SIZE" /dev/zero > "$STREAM_BODY_FILE"

if [ "$TLS" = true ]; then
    if [ -z "$TLS_CERTIFICATE" ]; then
        if ! command -v openssl >/dev/null 2>&1; then
            echo "TLS mode requires openssl to generate a test certificate" >&2
            exit 2
        fi
        TLS_CERTIFICATE="$TEMP_DIR/cardigan-cert.pem"
        TLS_PRIVATE_KEY="$TEMP_DIR/cardigan-key.pem"
        openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
            -subj /CN=localhost \
            -addext subjectAltName=DNS:localhost,IP:127.0.0.1 \
            -keyout "$TLS_PRIVATE_KEY" \
            -out "$TLS_CERTIFICATE" >/dev/null 2>&1
    elif [ ! -f "$TLS_CERTIFICATE" ] || [ ! -f "$TLS_PRIVATE_KEY" ]; then
        echo "TLS certificate or private key does not exist" >&2
        exit 2
    fi
fi

# Do not kill unrelated processes merely because their command resembles a
# benchmark server. Refuse occupied ports and let the owner decide what to do.
if [ "$MICRO_ONLY" = false ]; then
    cardigan_require_ports_available 8080
fi

COMPILE_ARGS=()
JVMTI_ARGS=()
if [ "$JVMTI" = true ]; then
    COMPILE_ARGS+=(-g)
    PERF_JVMTI_PATH=$(cardigan_find_perf_jvmti)
    JVMTI_ARGS=(
        "-agentpath:$PERF_JVMTI_PATH"
        -XX:+PreserveFramePointer
    )
fi

# Measured on JDK 26: 150 retains the small stage-two JSON helpers, while 600
# keeps the 699-byte HPACK request decoder out of processFrame's compiled graph.
# The Vector API intrinsic path uses @ForceInline and does not require larger
# global node budgets.
JIT_ARGS=(
    -XX:+UseCompactObjectHeaders
    -XX:MaxInlineSize=150
    -XX:FreqInlineSize=600
)
if [ "${JIT_DIAGNOSTICS:-0}" = "1" ]; then
    JIT_ARGS+=(
        -XX:+UnlockDiagnosticVMOptions
        -XX:+PrintCompilation
        -XX:+PrintInlining
    )
fi

JAVA_ARGS=(
    "${JVMTI_ARGS[@]}"
    -Xms64m
    -Xmx512m
    --enable-native-access=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-modules jdk.incubator.vector
    "${JIT_ARGS[@]}"
    -cp "target/classes:dev/example-server/target/classes:dev/benchmarks/target/classes"
)

echo "Compiling Cardigan..."
dev/tools/compile.sh "${COMPILE_ARGS[@]}" >/dev/null

if [ "$RUN_MICROBENCHMARKS" = true ]; then
    echo
    echo "Running Cardigan microbenchmarks..."
    MICROBENCHMARK_ARGS=()
    if [ "$REQUEST_PARSERS_ONLY" = true ]; then
        MICROBENCHMARK_ARGS+=(--request-parsers)
    elif [ "$HPACK_HUFFMAN_ONLY" = true ]; then
        MICROBENCHMARK_ARGS+=(--hpack-huffman)
    elif [ "$HTTP2_RESPONSE_ONLY" = true ]; then
        MICROBENCHMARK_ARGS+=(--http2-response)
    elif [ "$HTTP1_CHUNKED_ONLY" = true ]; then
        MICROBENCHMARK_ARGS+=(--http1-chunked)
    fi
    java "${JAVA_ARGS[@]}" dev.cardigan.benchmark.BenchmarkSuite \
        "${MICROBENCHMARK_ARGS[@]}"
    if [ "$MICRO_ONLY" = true ]; then
        exit 0
    fi
fi

endpoint_name() {
    case "$1" in
        1) echo "GET /users/423" ;;
        2) echo "POST /users" ;;
        3) echo "GET /some/response/large" ;;
        4) echo "GET /heavy (@Isolated)" ;;
        5) echo "GET /stream/$PAYLOAD_SIZE" ;;
        6)
            if [ "$CHUNKED_UPLOAD" = true ]; then
                echo "POST /stream/upload (chunked, $CHUNK_SIZE-byte chunks)"
            else
                echo "POST /stream/upload"
            fi
            ;;
        7) echo "GET /stream-unknown/$PAYLOAD_SIZE" ;;
        8)
            if [ "$CHUNKED_UPLOAD" = true ]; then
                echo "POST /stream/upload-heavy (@Isolated, chunked, $CHUNK_SIZE-byte chunks)"
            else
                echo "POST /stream/upload-heavy (@Isolated)"
            fi
            ;;
    esac
}

endpoint_url() {
    local port="$1"
    local scheme="http"
    if [ "$TLS" = true ]; then
        scheme="https"
    fi
    case "$2" in
        1) echo "$scheme://localhost:$port/users/423" ;;
        2) echo "$scheme://localhost:$port/users" ;;
        3) echo "$scheme://localhost:$port/some/response/large" ;;
        4) echo "$scheme://localhost:$port/heavy" ;;
        5) echo "$scheme://localhost:$port/stream/$PAYLOAD_SIZE" ;;
        6) echo "$scheme://localhost:$port/stream/upload" ;;
        7) echo "$scheme://localhost:$port/stream-unknown/$PAYLOAD_SIZE" ;;
        8) echo "$scheme://localhost:$port/stream/upload-heavy" ;;
    esac
}

create_request_script() {
    local endpoint="$1"
    local script_path="$2"
    local upload_path="/stream/upload"
    if [ "$endpoint" = "8" ]; then
        upload_path="/stream/upload-heavy"
    fi

    if [ "$CHUNKED_UPLOAD" = true ]; then
        local request_count=1
        if [ "$PIPELINED" = true ]; then
            request_count="$PIPELINE_DEPTH"
        fi
        cat > "$script_path" <<EOF
init = function(args)
  local framed = {}
  local remaining = $PAYLOAD_SIZE
  while remaining > 0 do
    local length = math.min($CHUNK_SIZE, remaining)
    framed[#framed + 1] = string.format("%x\\r\\n", length)
    framed[#framed + 1] = string.rep("A", length)
    framed[#framed + 1] = "\\r\\n"
    remaining = remaining - length
  end
  framed[#framed + 1] = "0\\r\\n\\r\\n"

  local request = "POST $upload_path HTTP/1.1\\r\\n" ..
      "Host: localhost\\r\\n" ..
      "Transfer-Encoding: chunked\\r\\n" ..
      "Connection: keep-alive\\r\\n\\r\\n" ..
      table.concat(framed)
  local requests = {}
  for i = 1, $request_count do
    requests[i] = request
  end
  payload = table.concat(requests)
end

request = function()
  return payload
end
EOF
    elif [ "$PIPELINED" = true ]; then
        case "$endpoint" in
            1)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("GET", "/users/423")
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            2)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  local headers = {}
  headers["Content-Type"] = "application/json"
  local body = '{"name":"Alice Smith","id":427,"active":true}'
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("POST", "/users", headers, body)
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            3)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("GET", "/some/response/large")
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            4)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("GET", "/heavy")
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            5)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("GET", "/stream/$PAYLOAD_SIZE")
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            7)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("GET", "/stream-unknown/$PAYLOAD_SIZE")
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            6)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  local body = string.rep("A", $PAYLOAD_SIZE)
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("POST", "/stream/upload", nil, body)
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
            8)
                cat > "$script_path" <<EOF
init = function(args)
  local requests = {}
  local body = string.rep("A", $PAYLOAD_SIZE)
  for i = 1, $PIPELINE_DEPTH do
    requests[i] = wrk.format("POST", "/stream/upload-heavy", nil, body)
  end
  pipeline = table.concat(requests)
end

request = function()
  return pipeline
end
EOF
                ;;
        esac
    elif [ "$endpoint" = "2" ]; then
        cat > "$script_path" <<'EOF'
wrk.method = "POST"
wrk.body = '{"name":"Alice Smith","id":427,"active":true}'
wrk.headers["Content-Type"] = "application/json"
EOF
    elif [ "$endpoint" = "6" ] || [ "$endpoint" = "8" ]; then
        cat > "$script_path" <<EOF
wrk.method = "POST"
wrk.body = string.rep("A", $PAYLOAD_SIZE)
EOF
    else
        : > "$script_path"
    fi
}

run_wrk() {
    local duration="$1"
    local url="$2"
    local script_path="$3"
    local args=(-t"$WRK_THREADS" -c"$CONNECTIONS" -d"$duration")
    if [ -s "$script_path" ]; then
        args=(-s "$script_path" "${args[@]}")
    fi
    wrk "${args[@]}" "$url"
}

run_h2load() {
    local duration="$1"
    local warmup_duration="$2"
    local url="$3"
    local endpoint="$4"
    local streams=1
    if [ "$HTTP2" = true ]; then
        streams="$HTTP2_STREAMS"
    fi
    local args=(
        -t "$WRK_THREADS"
        -c "$CONNECTIONS"
        -m "$streams"
        -D "$duration"
        --warm-up-time "$warmup_duration"
    )
    if [ "$HTTP2" = false ]; then
        args+=(--h1)
    fi
    if [ "$endpoint" = "2" ]; then
        args+=(
            -d "$POST_BODY_FILE"
            -H "content-type: application/json"
        )
    elif [ "$endpoint" = "6" ] || [ "$endpoint" = "8" ]; then
        args+=(-d "$STREAM_BODY_FILE")
    fi
    h2load "${args[@]}" "$url"
}

print_runtime_configuration() {
    local effective_fixed_files="$FIXED_FILES_MODE"
    if [ "$FIXED_FILES_MODE" = auto ]; then
        if [ "$TLS" = true ]; then
            effective_fixed_files="async-alloc"
        else
            effective_fixed_files="direct"
        fi
    fi

    echo "Fixed files: requested=$FIXED_FILES_MODE, effective=$effective_fixed_files, capacity=$FIXED_FILES_CAPACITY per event loop"
    if [ -n "$URING_MAX_TASKS" ]; then
        echo "io_uring task pool: $URING_MAX_TASKS slots per event loop (pinned)"
    else
        echo "io_uring task pool: runtime-derived from fixed-file capacity and SQ entries"
    fi
    if [ "$SCHEDULER_BOUNDED" = true ]; then
        echo "Scheduler: bounded=true, CQEs=$SCHEDULER_CQES, completions=$SCHEDULER_COMPLETIONS, protocol=$SCHEDULER_PROTOCOL_TASKS, handlers=$SCHEDULER_HANDLER_CONTINUATIONS, egress=$SCHEDULER_EGRESS_TASKS, external=$SCHEDULER_EXTERNAL_TASKS, quantum=${SCHEDULER_QUANTUM_MICROS}us"
    else
        echo "Scheduler: bounded=false (lane budgets disabled), quantum=${SCHEDULER_QUANTUM_MICROS}us"
    fi
}

run_http2_flow_control() {
    local server_pid="$1"
    local port="$2"

    echo
    echo "================================================="
    echo "  Cardigan: HTTP/2 flow-control isolation"
    echo "================================================="
    echo "CPUs: $CPUS"
    echo "Zero-window connections: $FLOW_CONNECTIONS"
    echo "Stalled streams per connection: $FLOW_STALLED_STREAMS"
    echo "Parked sender limit per event loop: $HTTP2_MAX_PARKED_SENDERS"
    echo "Cycles: $FLOW_CYCLES"
    print_runtime_configuration

    java \
        --enable-preview \
        -cp "target/classes:dev/benchmarks/target/classes" \
        dev.cardigan.benchmark.Http2FlowControlProbe \
        --port "$port" \
        --connections "$FLOW_CONNECTIONS" \
        --stalled-streams "$FLOW_STALLED_STREAMS" \
        --cycles "$FLOW_CYCLES" \
        --timeout-millis "$FLOW_TIMEOUT_MILLIS" \
        --server-pid "$server_pid"
}

run_case() {
    local server_name="$1"
    local server_pid="$2"
    local port="$3"
    local endpoint="$4"
    local name
    local url
    local slug
    local request_script
    local cpu_log
    local client_log
    local client_output
    local client_status=0
    local early_status=0
    local inflight_per_connection
    local max_inflight

    name=$(endpoint_name "$endpoint")
    url=$(endpoint_url "$port" "$endpoint")
    if [ "$HTTP2" = true ]; then
        slug=$(echo "$server_name-http2-$endpoint" | tr '[:upper:] ' '[:lower:]-')
    else
        slug=$(echo "$server_name-$endpoint" | tr '[:upper:] ' '[:lower:]-')
    fi
    request_script="$TEMP_DIR/$slug.lua"
    cpu_log="$TEMP_DIR/$slug.log"
    client_log="$TEMP_DIR/$slug.out"
    if [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; then
        create_request_script "$endpoint" "$request_script"
    fi

    echo
    echo "================================================="
    echo "  $server_name: $name"
    echo "================================================="
    if [ "$HTTP2" = true ]; then
        if [ "$TLS" = true ]; then
            echo "Mode: HTTP/2 over TLS (ALPN), $HTTP2_STREAMS concurrent stream(s) per connection"
        else
            echo "Mode: HTTP/2, $HTTP2_STREAMS concurrent stream(s) per connection"
        fi
        inflight_per_connection="$HTTP2_STREAMS"
    elif [ "$PIPELINED" = true ]; then
        echo "Mode: pipeline depth $PIPELINE_DEPTH"
        inflight_per_connection="$PIPELINE_DEPTH"
    elif [ "$CHUNKED_UPLOAD" = true ]; then
        echo "Mode: HTTP/1.1 chunked upload"
        inflight_per_connection=1
    else
        echo "Mode: HTTP/1.1, one concurrent request per connection"
        inflight_per_connection=1
    fi
    max_inflight=$((CONNECTIONS * inflight_per_connection))
    echo "CPUs: $CPUS, client threads: $WRK_THREADS, connections: $CONNECTIONS"
    echo "Maximum in-flight requests: $max_inflight"
    print_runtime_configuration
    if [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; then
        echo "Client: wrk"
    else
        echo "Client: h2load (steady-state warm-up on the same connections)"
    fi
    if [ "$TLS" = true ]; then
        if [ "$TLS12" = true ]; then
            echo "TLS: OpenSSL TLS 1.2 with directional kTLS when supported"
        else
            echo "TLS: OpenSSL TLS 1.3 with directional kTLS when supported"
        fi
        if [ "$TLS_DIRECT_RX" = true ]; then
            echo "TLS receive: direct multishot io_uring"
        fi
    fi
    if [ "$endpoint" = "3" ] || [ "$endpoint" = "5" ] ||
       [ "$endpoint" = "6" ] || [ "$endpoint" = "7" ] ||
       [ "$endpoint" = "8" ]; then
        echo "Payload size: $PAYLOAD_SIZE bytes"
    fi
    if [ "$endpoint" = "4" ] || [ "$endpoint" = "8" ]; then
        echo "Isolated carriers: $ISOLATED_CARRIERS"
    fi
    if [ "$CHUNKED_UPLOAD" = true ]; then
        echo "Chunk size: $CHUNK_SIZE bytes"
    fi

    if [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; then
        echo "Warming up for $WARMUP_DURATION..."
        run_wrk "$WARMUP_DURATION" "$url" "$request_script" >/dev/null
        echo "Running for $DURATION with CPU recording..."
    else
        echo "Starting h2load: warm-up $WARMUP_DURATION, measurement $DURATION..."
        run_h2load \
            "$DURATION" "$WARMUP_DURATION" "$url" "$endpoint" \
            >"$client_log" 2>&1 &
        CLIENT_PID=$!
        sleep "$WARMUP_DURATION"
        if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
            wait "$CLIENT_PID" || early_status=$?
            CLIENT_PID=""
            cat "$client_log" >&2
            echo "h2load exited during warm-up" >&2
            if [ "$early_status" -eq 0 ]; then
                early_status=1
            fi
            return "$early_status"
        fi
        echo "Warm-up complete; recording the measurement interval..."
    fi

    if command -v pidstat >/dev/null 2>&1; then
        pidstat -p "$server_pid" 1 > "$cpu_log" &
        PIDSTAT_PID=$!
    else
        PIDSTAT_PID=""
        echo "Warning: pidstat is unavailable; continuing without CPU samples." >&2
    fi

    if [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; then
        client_output=$(run_wrk "$DURATION" "$url" "$request_script")
    else
        wait "$CLIENT_PID" || client_status=$?
        CLIENT_PID=""
        client_output=$(<"$client_log")
    fi

    if [ -n "$PIDSTAT_PID" ]; then
        kill "$PIDSTAT_PID" 2>/dev/null || true
        wait "$PIDSTAT_PID" 2>/dev/null || true
        PIDSTAT_PID=""
    fi

    if [ "$client_status" -ne 0 ]; then
        echo "$client_output" >&2
        return "$client_status"
    fi

    if [ -s "$cpu_log" ]; then
        echo
        echo "--- CPU measurements ---"
        cat "$cpu_log"
    fi
    echo
    if [ "$PIPELINED" = true ] || [ "$CHUNKED_UPLOAD" = true ]; then
        echo "--- wrk output ---"
    else
        echo "--- h2load output ---"
    fi
    echo "$client_output"
}

echo
echo "Starting Cardigan on port 8080 with $CPUS event loop(s)..."
CARDIGAN_TLS_ARGS=()
CARDIGAN_DIAGNOSTIC_ARGS=()
CARDIGAN_FIXED_FILE_ARGS=(
    -Dcardigan.fixed.files.mode="$FIXED_FILES_MODE"
    -Dcardigan.fixed.files.capacity="$FIXED_FILES_CAPACITY"
    -Dcardigan.fixed.files.stats="$FIXED_FILE_STATS"
)
if [ -n "$URING_MAX_TASKS" ]; then
    CARDIGAN_FIXED_FILE_ARGS+=(
        -Dcardigan.max.tasks="$URING_MAX_TASKS"
    )
fi
if [ "$TLS" = true ]; then
    CARDIGAN_TLS_ARGS=(
        -Dcardigan.tls.certificate="$TLS_CERTIFICATE"
        -Dcardigan.tls.privateKey="$TLS_PRIVATE_KEY"
    )
    if [ "$TLS12" = true ]; then
        CARDIGAN_TLS_ARGS+=( -Dcardigan.tls.version=1.2 )
    fi
    if [ "$TLS_DIRECT_RX" = true ]; then
        CARDIGAN_TLS_ARGS+=( -Dcardigan.tls.directKtlsReceive=true )
    fi
    if [ "$TLS_STATS" = true ]; then
        CARDIGAN_TLS_ARGS+=( -Dcardigan.tls.stats=true )
    fi
fi
if [ "$HTTP2_RESOURCE_STATS" = true ]; then
    CARDIGAN_DIAGNOSTIC_ARGS=(
        -Dcardigan.shutdown.grace.millis=2000
        -Dcardigan.shutdown.force.millis=1000
    )
fi
java "${JAVA_ARGS[@]}" \
        "${CARDIGAN_TLS_ARGS[@]}" \
        "${CARDIGAN_DIAGNOSTIC_ARGS[@]}" \
        "${CARDIGAN_FIXED_FILE_ARGS[@]}" \
        -Dcardigan.benchmark.payloadSize="$PAYLOAD_SIZE" \
        -Dcardigan.benchmark.sleepMillis="$SLEEP_MILLIS" \
        -Dcardigan.benchmark.heavyIterations="$HEAVY_ITERATIONS" \
        -Dcardigan.http2.resource.stats="$HTTP2_RESOURCE_STATS" \
        -Dcardigan.http2.max.parked.senders.per.loop="$HTTP2_MAX_PARKED_SENDERS" \
        -Dcardigan.scheduler.boundedTurns="$SCHEDULER_BOUNDED" \
        -Dcardigan.scheduler.cqesPerTurn="$SCHEDULER_CQES" \
        -Dcardigan.scheduler.completionsPerTurn="$SCHEDULER_COMPLETIONS" \
        -Dcardigan.scheduler.protocolTasksPerTurn="$SCHEDULER_PROTOCOL_TASKS" \
        -Dcardigan.scheduler.handlerContinuationsPerTurn="$SCHEDULER_HANDLER_CONTINUATIONS" \
        -Dcardigan.scheduler.egressTasksPerTurn="$SCHEDULER_EGRESS_TASKS" \
        -Dcardigan.scheduler.externalTasksPerTurn="$SCHEDULER_EXTERNAL_TASKS" \
        -Dcardigan.scheduler.protocolQuantumMicros="$SCHEDULER_QUANTUM_MICROS" \
        -Dcardigan.scheduler.stats="$SCHEDULER_STATS" \
        -Dcardigan.isolated.carriers="$ISOLATED_CARRIERS" \
        -Dcardigan.isolated.cpus="$ISOLATED_CPUS" \
        -Dcardigan.isolated.max.tasks="$ISOLATED_MAX_TASKS" \
        dev.cardigan.Main 8080 "$CPUS" &
CARDIGAN_PID=$!
sleep 2

if [ "$HTTP2_FLOW_CONTROL" = true ]; then
    run_http2_flow_control "$CARDIGAN_PID" 8080
else
    for endpoint in "${ENDPOINTS[@]}"; do
        if [ "$CHUNKED_UPLOAD" = true ]; then
            IFS=',' read -r -a chunk_sizes <<< "$CHUNK_SIZES"
            for CHUNK_SIZE in "${chunk_sizes[@]}"; do
                run_case "Cardigan" "$CARDIGAN_PID" 8080 "$endpoint"
            done
        else
            run_case "Cardigan" "$CARDIGAN_PID" 8080 "$endpoint"
        fi
    done
fi

stop_process "Cardigan server" "$CARDIGAN_PID"
CARDIGAN_PID=""

if [ "$HTTP2_FLOW_CONTROL" = true ]; then
    exit 0
fi

echo
echo "Benchmark execution finished."
