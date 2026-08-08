#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
cd "$ROOT_DIR"

echo "Building Cardigan's unsigned Maven Central candidate..."
mvn -Ppublication -Dgpg.skip=true clean verify

VERSION=$(mvn -q -DforceStdout help:evaluate \
    -Dexpression=project.version)
SCM_TAG=$(mvn -q -DforceStdout help:evaluate \
    -Dexpression=project.scm.tag)

if [[ "$VERSION" == *-SNAPSHOT ]]; then
    echo "Refusing to verify a snapshot as a release candidate: $VERSION" >&2
    exit 1
fi
if [ "$SCM_TAG" != "v$VERSION" ]; then
    echo "SCM tag $SCM_TAG does not match release version $VERSION" >&2
    exit 1
fi

MAIN_JAR="target/cardigan-$VERSION.jar"
SOURCES_JAR="target/cardigan-$VERSION-sources.jar"
JAVADOC_JAR="target/cardigan-$VERSION-javadoc.jar"

for artifact in "$MAIN_JAR" "$SOURCES_JAR" "$JAVADOC_JAR"; do
    if [ ! -s "$artifact" ]; then
        echo "Missing publication artifact: $artifact" >&2
        exit 1
    fi
done

FORBIDDEN='(^|/)(Main|ExampleController)\.(class|java)$|dev/cardigan/(example|benchmark|httparena)/|\.(so|sh)$'
for artifact in "$MAIN_JAR" "$SOURCES_JAR"; do
    if jar tf "$artifact" | grep -E "$FORBIDDEN"; then
        echo "Development-only content leaked into $artifact" >&2
        exit 1
    fi
done

for required in \
        META-INF/LICENSE \
        META-INF/THIRD_PARTY_NOTICES.md \
        META-INF/LICENSES/Apache-2.0.txt \
        META-INF/LICENSES/picohttpparser-MIT.txt; do
    if ! jar tf "$MAIN_JAR" | grep -Fxq "$required"; then
        echo "Missing required notice from $MAIN_JAR: $required" >&2
        exit 1
    fi
done

echo "Publication candidate is structurally clean:"
printf '  %s\n' "$MAIN_JAR" "$SOURCES_JAR" "$JAVADOC_JAR"
echo "No files were uploaded."
