#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
cd "$ROOT_DIR"

echo "Compiling Cardigan..."
mvn -q -f dev/pom.xml -pl :cardigan-example-server -am -DskipTests package

echo "Starting Cardigan server on port 8080..."
java --enable-native-access=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-modules jdk.incubator.vector -XX:MaxInlineSize=150 -XX:FreqInlineSize=600 -cp "target/classes:dev/example-server/target/classes" dev.cardigan.Main 8080 1 &
SERVER_PID=$!
wait $SERVER_PID
