#!/usr/bin/env bash
# Build the Castle Fight game-only distribution from the Git worktree.
# Does not change any existing ~/castlefight* installation.
set -Eeuo pipefail

SERVER="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SERVER/.." && pwd -P)"
REPO="$SERVER"
DIST="$ROOT/dist/CastleFight"
JAR="$REPO/target/cfengine-0.1.0.jar"

command -v java >/dev/null 2>&1 || { echo '[CF BUILD] Java 25 is required' >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo '[CF BUILD] Maven is required' >&2; exit 1; }
[ -f "$REPO/pom.xml" ] && [ -d "$REPO/content/mobs" ] || {
  echo '[CF BUILD] Incomplete source tree; unpack the full repository ZIP' >&2; exit 1;
}
# Build into a staging directory inside dist (same filesystem for rename).
# IMPORTANT: the finished CastleFight/ directory can be MOVED outside Git.
# Never depend on the repository's path from the generated launcher.
mkdir -p "$DIST/maps" "$DIST/data"
BACKUP_CORE="$DIST/.core-previous"
# If a build was killed after renaming core/ but before publishing its replacement,
# restore the last good release BEFORE compiling anything else.
if [ ! -d "$DIST/core" ] && [ -d "$BACKUP_CORE" ]; then
  mv -- "$BACKUP_CORE" "$DIST/core"
  echo '[CF BUILD] Восстановлено ядро после прерванной предыдущей сборки.'
fi
TMP="$(mktemp -d -p "$DIST" .cf-build.XXXXXXXX)"
PUBLISHING=0
PUBLISHED=0
cleanup() {
  code=$?
  trap - EXIT INT TERM HUP
  if [ "$PUBLISHING" -eq 1 ] && [ "$PUBLISHED" -eq 0 ] && [ -d "$BACKUP_CORE" ]; then
    # If an error or interrupt occurred during publication, restore old core.
    if [ -d "$DIST/core" ]; then
      mv -- "$DIST/core" "$TMP/failed-core" || true
    fi
    if [ ! -d "$DIST/core" ]; then
      mv -- "$BACKUP_CORE" "$DIST/core" || echo '[CF BUILD] ОШИБКА: восстанови core/ из .core-previous вручную!' >&2
    fi
  elif [ ! -d "$DIST/core" ] && [ -d "$BACKUP_CORE" ]; then
    mv -- "$BACKUP_CORE" "$DIST/core" || true
  fi
  rm -rf -- "$TMP"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

printf '\n[CF BUILD] Compiling with Maven...\n'
(cd "$REPO" && mvn -B -DskipTests clean package)
[ -s "$JAR" ] || { echo '[CF BUILD] Missing assembled server JAR' >&2; exit 1; }

# No legacy server-side arrow visuals may re-enter the production firing path.
# This only validates the source; it never edits or deletes source files.
if grep -Eq 'SpawnEntityPacket|DestroyEntitiesPacket|EntityType\.ARROW|VANILLA_FALLBACK' \
    "$REPO/src/main/java/dev/cf/CFClientShot.java"; then
  echo '[CF BUILD] ОШИБКА: в CFClientShot вернулась старая визуализация стрел!' >&2
  exit 1
fi

if [ "${CF_SKIP_SMOKE:-0}" != 1 ]; then
  echo '[CF BUILD] Running source regression checks in isolated temporary data...'
  mkdir -p "$TMP/tests"
  (
    cd "$TMP/tests"
    java -Xmx1G -cp "$JAR" dev.cf.CF271DamageSelfTest
    for test in CFMapWindowSelfTest CFCastleMarkersSelfTest CFCastleSpawnsSelfTest CFKingBehaviorSelfTest CFNecromancySelfTest CF271DamageRuntimeSelfTest CF268FactionContentSelfTest CFAbilitiesSelfTest CF22SelfTest CFVerticalTravelSelfTest CFOverlayWireSelfTest CFMobStatsWireSelfTest CFShotWireSelfTest CFCombatHeightSelfTest; do
      echo "[CF BUILD] Self-test: $test"
      java -Xmx1G -Dcf.target.meleeReviewTicks=20 -cp "$JAR" "dev.cf.$test" "$REPO/content"
    done
    # CF Map 3.0: actual synthetic four-lane beacon/route integration smoke.
    # Give it a fresh directory INSIDE the temporary test area every time.
    echo '[CF BUILD] Self-test: CF23FourLaneRouteSmoke'
    java -Xmx1G -cp "$JAR" dev.cf.CF23FourLaneRouteSmoke "$TMP/tests/four-lane-map"
  )
fi

# Publish only the generated core. The user's maps and runtime data are never
# removed or overwritten. All tests finish before publishing any release files.
mkdir -p "$TMP/core"
cp -- "$JAR" "$TMP/core/server.jar"
cp -a -- "$REPO/content" "$TMP/core/content"
VERSION="$(sed -n 's/^version=//p' "$REPO/docs/BASELINE_BUILD_INFO.txt" | head -1)"
JAR_HASH="$(sha256sum "$TMP/core/server.jar" | cut -d ' ' -f 1)"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo uncommitted)"
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

# Validate the launcher BEFORE publishing either file.
bash -n "$TMP/start.sh"
# Preserve a completed core if the previous invocation was interrupted late.
# Existing map files and data/ are NEVER touched by this transaction.
if [ -d "$DIST/core" ] && [ -d "$BACKUP_CORE" ]; then
  rm -rf -- "$BACKUP_CORE"
fi
# The launcher is compatible with the previous core, so publish it first.
# If a later step fails, the previous core remains recoverable.
if [ ! -e "$DIST/maps/README.txt" ]; then
  cp -- "$REPO/packaging/MAPS_README.txt" "$DIST/maps/README.txt"
fi
mv -f -- "$TMP/start.sh" "$DIST/start.sh"
PUBLISHING=1
if [ -d "$DIST/core" ]; then mv -- "$DIST/core" "$BACKUP_CORE"; fi
if ! mv -- "$TMP/core" "$DIST/core"; then
  echo '[CF BUILD] Не удалось опубликовать ядро; восстановление предыдущей версии.' >&2
  exit 1
fi
PUBLISHED=1
# Do not remove the backup until the new core is fully published.
rm -rf -- "$BACKUP_CORE"

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
