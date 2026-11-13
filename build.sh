#!/usr/bin/env bash
# Build exactly the selected part of the Castle Fight mono-repository.
# Only release artifacts are written under dist/.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

usage() {
  cat <<'EOF'
Castle Fight build commands:
  bash build.sh server   # Minestom server -> dist/CastleFight/
  bash build.sh client   # Fabric client  -> dist/client/castlefight-client.jar
  bash build.sh all      # Server followed by client
EOF
}
if [ "$#" -ne 1 ]; then usage; exit 2; fi

# The payload format is shared between client and server: building drifted
# implementations would produce incompatible JARs.
SERVER_WIRE="$ROOT/server/src/main/java/dev/cf/protocol/CFOverlayWire.java"
CLIENT_WIRE="$ROOT/client/src/main/java/dev/cf/protocol/CFOverlayWire.java"
check_protocol() {
  if [ ! -f "$SERVER_WIRE" ] || [ ! -f "$CLIENT_WIRE" ] || ! cmp -s -- "$SERVER_WIRE" "$CLIENT_WIRE"; then
    echo '[CF BUILD] Client/server protocol sources differ. Sync CFOverlayWire.java before building.' >&2
    exit 1
  fi
  local stats_server="$ROOT/server/src/main/java/dev/cf/protocol/CFMobStatsWire.java"
  local stats_client="$ROOT/client/src/main/java/dev/cf/protocol/CFMobStatsWire.java"
  if [ ! -f "$stats_server" ] || [ ! -f "$stats_client" ] || ! cmp -s -- "$stats_server" "$stats_client"; then
    echo '[CF BUILD] Hover-stats protocol sources differ. Sync CFMobStatsWire.java.' >&2
    exit 1
  fi
  local build_server="$ROOT/server/src/main/java/dev/cf/protocol/CFBuildWire.java"
  local build_client="$ROOT/client/src/main/java/dev/cf/protocol/CFBuildWire.java"
  if [ ! -f "$build_server" ] || [ ! -f "$build_client" ] || ! cmp -s -- "$build_server" "$build_client"; then
    echo '[CF BUILD] Tower-menu protocol sources differ. Sync CFBuildWire.java.' >&2
    exit 1
  fi
  local shot_server="$ROOT/server/src/main/java/dev/cf/protocol/CFShotWire.java"
  local shot_client="$ROOT/client/src/main/java/dev/cf/protocol/CFShotWire.java"
  if [ ! -f "$shot_server" ] || [ ! -f "$shot_client" ] || ! cmp -s -- "$shot_server" "$shot_client"; then
    echo '[CF BUILD] Arrow protocol sources differ. Sync CFShotWire.java.' >&2
    exit 1
  fi
}
case "$1" in
  server) check_protocol; bash "$ROOT/server/build.sh" ;;
  client) check_protocol; bash "$ROOT/client/build.sh" ;;
  all) check_protocol; bash "$ROOT/server/build.sh"; bash "$ROOT/client/build.sh" ;;
  -h|--help|help) usage ;;
  *) usage >&2; exit 2 ;;
esac
