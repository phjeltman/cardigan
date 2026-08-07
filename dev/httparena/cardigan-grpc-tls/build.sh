#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
if [ -f "$SCRIPT_DIR/../cardigan/pom.xml" ]; then
    ROOT_DIR="$(cd "$SCRIPT_DIR/../cardigan" && pwd -P)"
else
    ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
fi
DOCKERFILE="$ROOT_DIR/dev/httparena/Dockerfile"
[ -f "$DOCKERFILE" ] || DOCKERFILE="$ROOT_DIR/httparena/Dockerfile"
[ -f "$DOCKERFILE" ] || DOCKERFILE="$ROOT_DIR/Dockerfile"
docker build -t httparena-cardigan-grpc-tls \
    --build-arg CARDIGAN_HTTPARENA_MODE=grpc-tls \
    -f "$DOCKERFILE" "$ROOT_DIR"
