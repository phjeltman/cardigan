#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
if [ -f "$SCRIPT_DIR/pom.xml" ]; then
    ROOT_DIR="$SCRIPT_DIR"
else
    ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
fi
DOCKERFILE="$ROOT_DIR/dev/httparena/Dockerfile"
[ -f "$DOCKERFILE" ] || DOCKERFILE="$ROOT_DIR/httparena/Dockerfile"
[ -f "$DOCKERFILE" ] || DOCKERFILE="$ROOT_DIR/Dockerfile"
docker build -t httparena-cardigan \
    --build-arg CARDIGAN_HTTPARENA_MODE=h1 \
    -f "$DOCKERFILE" "$ROOT_DIR"
