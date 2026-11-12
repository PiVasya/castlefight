#!/usr/bin/env bash
# Fabric client builder. Does not access the server runtime maps or modify sources.
set -Eeuo pipefail
CLIENT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$CLIENT/.." && pwd -P)"

if [ -f "$CLIENT/gradlew" ]; then
  echo '[CF BUILD] Building client using its Gradle wrapper...'
  (cd "$CLIENT" && bash ./gradlew --no-daemon build)
elif command -v gradle >/dev/null 2>&1; then
  echo '[CF BUILD] Building client using Gradle...'
  (cd "$CLIENT" && gradle --no-daemon build)
else
  echo '[CF BUILD] Gradle 9.5.1+ is required for the Fabric client.' >&2
  echo '[CF BUILD] Install Gradle or generate the standard Gradle wrapper in client/.' >&2
  exit 1
fi

property() {
  sed -n "s/^$1=//p" "$CLIENT/gradle.properties" | head -n 1 | tr -d '\r'
}
MOD_NAME="$(property archives_base_name)"
MOD_VERSION="$(property mod_version)"
[ -n "$MOD_NAME" ] && [ -n "$MOD_VERSION" ] || {
  echo '[CF BUILD] Missing client archive name/version' >&2; exit 1;
}
JAR="$CLIENT/build/libs/$MOD_NAME-$MOD_VERSION.jar"
[ -s "$JAR" ] || {
  echo "[CF BUILD] Built client JAR not found: $JAR" >&2; exit 1;
}
OUT="$ROOT/dist/client"
mkdir -p "$OUT"
TMP="$(mktemp "$OUT/.client-jar.XXXXXXXX")"
cleanup() { rm -f -- "$TMP"; }
trap cleanup EXIT
cp -- "$JAR" "$TMP"
# Replacement is atomic on the same filesystem. The previous JAR remains if
# Gradle or the copy fails. Neither maps nor the server release is touched.
mv -f -- "$TMP" "$OUT/castlefight-client.jar"
echo '[CF BUILD] CLIENT SUCCESS'
echo "[CF BUILD] Client mod: $OUT/castlefight-client.jar"
