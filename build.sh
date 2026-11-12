#!/usr/bin/env bash
# Build the Castle Fight game-only distribution from the Git worktree.
# Does not change any existing ~/castlefight* installation.
set -Eeuo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DIST="$REPO/dist/CastleFight"
JAR="$REPO/target/cfengine-0.1.0.jar"

command -v java >/dev/null 2>&1 || { echo '[CF BUILD] Java 25 is required' >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo '[CF BUILD] Maven is required' >&2; exit 1; }
[ -f "$REPO/pom.xml" ] && [ -d "$REPO/content/mobs" ] || {
  echo '[CF BUILD] Incomplete source tree; unpack ZIP in Git repository root' >&2; exit 1;
}
mkdir -p "$DIST/maps" "$DIST/data"
TMP="$(mktemp -d -p "$DIST" .cf-build.XXXXXXXX)"
trap 'rm -rf -- "$TMP"' EXIT

printf '\n[CF BUILD] Compiling with Maven...\n'
(cd "$REPO" && mvn -B -DskipTests clean package)
[ -s "$JAR" ] || { echo '[CF BUILD] Missing assembled server JAR' >&2; exit 1; }

if [ "${CF_SKIP_SMOKE:-0}" != 1 ]; then
  echo '[CF BUILD] Running source regression checks in isolated temporary data...'
  mkdir -p "$TMP/tests"
  (
    cd "$TMP/tests"
    java -Xmx1G -cp "$JAR" dev.cf.CF271DamageSelfTest
    for test in CFCastleSpawnsSelfTest CFNecromancySelfTest CF271DamageRuntimeSelfTest CF268FactionContentSelfTest CFAbilitiesSelfTest; do
      echo "[CF BUILD] Self-test: $test"
      java -Xmx1G -Dcf.target.meleeReviewTicks=20 -cp "$JAR" "dev.cf.$test" "$REPO/content"
    done
  )
fi

# Publish only the generated core. The user's maps and runtime data are never
# removed or overwritten. All tests finish before publishing any release files.
mkdir -p "$TMP/core"
cp -- "$JAR" "$TMP/core/server.jar"
cp -a -- "$REPO/content" "$TMP/core/content"
VERSION="$(sed -n 's/^version=//p' "$REPO/docs/BASELINE_BUILD_INFO.txt" | head -1)"
JAR_HASH="$(sha256sum "$TMP/core/server.jar" | cut -d ' ' -f 1)"
COMMIT="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo uncommitted)"
{
  echo 'publicVersion=2.6.8.3'
  echo 'flavor=game-only'
  echo "sourceVersion=$VERSION"
  echo "gitCommit=$COMMIT"
  echo "jarSha256=$JAR_HASH"
  echo "built=$(date -Is)"
} > "$TMP/core/CF_BUILD_INFO.txt"
cp -- "$REPO/packaging/start.sh" "$TMP/start.sh"
chmod +x "$TMP/start.sh"

BACKUP_CORE="$TMP/previous-core"
if [ -e "$DIST/core" ]; then mv -- "$DIST/core" "$BACKUP_CORE"; fi
if ! mv -- "$TMP/core" "$DIST/core"; then
  if [ -d "$BACKUP_CORE" ]; then mv -- "$BACKUP_CORE" "$DIST/core"; fi
  echo '[CF BUILD] Failed to publish core; previous core restored' >&2
  exit 1
fi
mv -f -- "$TMP/start.sh" "$DIST/start.sh"
if [ ! -e "$DIST/maps/README.txt" ]; then
  cp -- "$REPO/packaging/MAPS_README.txt" "$DIST/maps/README.txt"
fi

cat <<EOF

[CF BUILD] SUCCESS
Release: $DIST
  $DIST/start.sh
  $DIST/core/server.jar
  $DIST/core/content/
  $DIST/maps/       (preserved across builds)
  $DIST/data/       (preserved across builds)

Add a CF Map 3.0 world under maps/<map-id>/ with cf/map.properties.
Then run: bash '$DIST/start.sh'
EOF
