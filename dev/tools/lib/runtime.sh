#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0

# Shared process and host helpers for Cardigan's developer scripts. This file
# is sourced; callers retain responsibility for their own shell options.

cardigan_stop_process() {
    local label="$1"
    local pid="${2:-}"
    local attempts="${3:-30}"

    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        return
    fi

    echo "Stopping $label (PID: $pid)..."
    kill "$pid" 2>/dev/null || true
    for ((attempt = 0; attempt < attempts; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.1
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "Force killing $label (PID: $pid)..."
        kill -9 "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
}

cardigan_require_ports_available() {
    if ! command -v ss >/dev/null 2>&1; then
        echo "The iproute2 'ss' command is required to verify benchmark ports" >&2
        return 2
    fi

    local port listeners
    for port in "$@"; do
        listeners=$(ss -H -ltn "sport = :$port" 2>/dev/null || true)
        if [ -n "$listeners" ]; then
            echo "Port $port is already in use; stop the owning process first" >&2
            return 1
        fi
    done
}

cardigan_wait_for_log() {
    local pid="$1"
    local log="$2"
    local pattern="$3"
    local attempts="${4:-100}"
    local delay="${5:-0.05}"

    for ((attempt = 0; attempt < attempts; attempt++)); do
        if rg -q "$pattern" "$log" 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            return 1
        fi
        sleep "$delay"
    done
    return 1
}

cardigan_find_perf_jvmti() {
    if [ -n "${PERF_JVMTI_AGENT:-}" ]; then
        if [ ! -f "$PERF_JVMTI_AGENT" ]; then
            echo "PERF_JVMTI_AGENT does not exist: $PERF_JVMTI_AGENT" >&2
            return 1
        fi
        printf '%s\n' "$PERF_JVMTI_AGENT"
        return
    fi

    # perf 7.0's Ubuntu JVMTI agent can omit Java source annotations. Prefer
    # the compatible 6.8 agent when installed; an explicit override wins.
    local known_good=/usr/lib/linux-tools/6.8.0-136-generic/libperf-jvmti.so
    if [ -f "$known_good" ]; then
        printf '%s\n' "$known_good"
        return
    fi

    local current="/usr/lib/linux-tools/$(uname -r)/libperf-jvmti.so"
    if [ -f "$current" ]; then
        printf '%s\n' "$current"
        return
    fi

    echo "Could not find libperf-jvmti.so; set PERF_JVMTI_AGENT" >&2
    return 1
}
