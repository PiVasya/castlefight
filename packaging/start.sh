#!/usr/bin/env bash
# Castle Fight game launcher. Lives beside core/, maps/, data/.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
JAR="$ROOT/core/server.jar"
[ -s "$JAR" ] || { echo '[CF] Missing core/server.jar; run build.sh in the source repository' >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo '[CF] Java 25 is required' >&2; exit 1; }

map_list() {
  find "$ROOT/maps" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | LC_ALL=C sort
}
if [ "${1:-}" = '--list' ]; then map_list; exit 0; fi
MAP_ID="${1:-${CF_MAP_ID:-}}"
if [ "$#" -gt 0 ]; then shift; fi
if [ -z "$MAP_ID" ]; then
  mapfile -t choices < <(map_list)
  if [ "${#choices[@]}" -eq 1 ]; then
    MAP_ID="${choices[0]}"
  else
    echo '[CF] Choose a map: bash start.sh <map-id>' >&2
    echo '[CF] Available maps:' >&2
    printf '  %s\n' "${choices[@]:-none}" >&2
    exit 2
  fi
fi
[[ "$MAP_ID" =~ ^[A-Za-z0-9_-]+$ ]] || { echo '[CF] Invalid map ID' >&2; exit 2; }
MAP="$ROOT/maps/$MAP_ID"
MAP_CONFIG="$MAP/cf/map.properties"
REGIONS="$MAP/dimensions/minecraft/overworld/region"
[ -d "$REGIONS" ] || { echo "[КФ] Отсутствует папка регионов мира: $REGIONS" >&2; exit 1; }
find "$REGIONS" -maxdepth 1 -type f -name 'r.*.*.mca' -print -quit | grep -q . || {
  echo '[КФ] У выбранной карты нет файлов регионов .mca' >&2; exit 1;
}
[ -f "$MAP_CONFIG" ] || {
  echo "[КФ] Отсутствует файл карты: $MAP_CONFIG" >&2
  echo '[КФ] Старые карты не поддерживаются. Создай файл cf/map.properties с schema=3.0.' >&2
  exit 1
}

# Read the exact key; do not source .properties as executable shell code.
prop() {
  awk -v k="$1" '
    index($0, "=") && substr($0,1,index($0,"=")-1)==k {
      v=substr($0,index($0,"=")+1);
      sub(/^[[:space:]]+/, "", v); sub(/[[:space:]]+$/, "", v);
      print v; exit
    }' "$MAP_CONFIG"
}
case "$(prop schema)" in
  3.0) ;;
  *) echo '[КФ] Поддерживается ТОЛЬКО schema=3.0. Старые карты запускать запрещено.' >&2; exit 1 ;;
esac
for k in blue.x blue.y blue.z red.x red.y red.z; do
  [ -n "$(prop "$k")" ] || { echo "[КФ] В настройках карты отсутствует $k" >&2; exit 1; }
done

SX="$(prop spawn.x)"; SY="$(prop spawn.y)"; SZ="$(prop spawn.z)"
if [ -z "$SX$SY$SZ" ]; then
  # A neutral initial spawn location; map author may override all three axes.
  SX="$(awk -v a="$(prop blue.x)" -v b="$(prop red.x)" 'BEGIN{printf "%.3f",(a+b)/2}')"
  SY="$(awk -v a="$(prop blue.y)" -v b="$(prop red.y)" 'BEGIN{printf "%.3f",(a+b)/2+2}')"
  SZ="$(awk -v a="$(prop blue.z)" -v b="$(prop red.z)" 'BEGIN{printf "%.3f",(a+b)/2}')"
elif [ -z "$SX" ] || [ -z "$SY" ] || [ -z "$SZ" ]; then
  echo '[КФ] Точка spawn должна иметь все три координаты либо ни одной' >&2
  exit 1
fi

RUN="$ROOT/data/runs/$MAP_ID"
mkdir -p "$RUN/diagnostics"
if [ -e "$RUN/content" ] && [ ! -L "$RUN/content" ]; then
  echo "[CF] Refusing to replace non-symlink content directory at $RUN/content" >&2; exit 1
fi
if [ -L "$RUN/content" ]; then
  [ "$(realpath -e -- "$RUN/content" 2>/dev/null || true)" = "$ROOT/core/content" ] || {
    echo '[CF] Unexpected content symlink; refusing to replace it' >&2; exit 1;
  }
else
  ln -s ../../../core/content "$RUN/content"
fi
cp -- "$ROOT/core/CF_BUILD_INFO.txt" "$RUN/CF_BUILD_INFO.txt"
if [ -f "$ROOT/data/ops.txt" ] && [ ! -e "$RUN/ops.txt" ]; then
  ln -s ../../ops.txt "$RUN/ops.txt"
fi

printf '[КФ] Карта: %s\n[КФ] Точка появления: %s %s %s\n' "$MAP_ID" "$SX" "$SY" "$SZ"
echo '[КФ] Игровой сервер. Исходная карта открыта только для чтения.'
cd "$RUN"
exec java -Xms512M -Xmx"${CF_MAX_RAM:-2G}" \
  -Dcf.map.path="$MAP" -Dcf.map.id="$MAP_ID" \
  -Dcf.spawn.x="$SX" -Dcf.spawn.y="$SY" -Dcf.spawn.z="$SZ" \
  -jar "$JAR" "$@"
