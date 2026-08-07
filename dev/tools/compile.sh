#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
cd "$ROOT_DIR"

MAVEN_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -g)
            MAVEN_ARGS+=(
                -Dmaven.compiler.debug=true
                -Dmaven.compiler.debuglevel=lines,vars,source
            )
            shift
            ;;
        -D*|-P*)
            MAVEN_ARGS+=("$1")
            shift
            ;;
        *)
            echo "Unknown compile option: $1" >&2
            echo "Usage: dev/tools/compile.sh [-g] [-Pprofile] [-Dproperty=value]" >&2
            exit 2
            ;;
    esac
done

echo "Building Cardigan and its benchmark application via Maven..."
mvn -f dev/pom.xml \
    -pl :cardigan-example-server,:cardigan-benchmarks \
    -am clean test "${MAVEN_ARGS[@]}"
echo "Build and tests completed successfully!"
