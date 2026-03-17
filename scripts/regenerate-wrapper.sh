#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS_FILE="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"

if [[ ! -f "$PROPS_FILE" ]]; then
  echo "Missing $PROPS_FILE" >&2
  exit 1
fi

DIST_URL="$(awk -F= '/^distributionUrl=/{print $2}' "$PROPS_FILE" | sed 's#\\:#:#g')"
VERSION="$(sed -n 's#.*gradle-\([0-9.]*\)-bin.zip#\1#p' <<<"$DIST_URL")"

if [[ -z "$VERSION" ]]; then
  echo "Unable to parse Gradle version from distributionUrl in $PROPS_FILE" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

GRADLE_CMD=(gradle)
if command -v mise >/dev/null 2>&1; then
  if JAVA17_HOME="$(mise where java@17 2>/dev/null)"; then
    GRADLE_CMD=(env "JAVA_HOME=$JAVA17_HOME" "PATH=$JAVA17_HOME/bin:$PATH" gradle)
  fi
fi

cat > "$TMP_DIR/settings.gradle" <<'SETTINGS'
rootProject.name = 'wrapper-bootstrap'
SETTINGS
: > "$TMP_DIR/build.gradle"

(
  cd "$TMP_DIR"
  "${GRADLE_CMD[@]}" wrapper --gradle-version "$VERSION" --no-validate-url >/dev/null
)

cp "$TMP_DIR/gradle/wrapper/gradle-wrapper.jar" "$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"

echo "Generated gradle/wrapper/gradle-wrapper.jar for Gradle $VERSION"
