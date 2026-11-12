#!/usr/bin/env bash
# Build the Fabric client. Fetches only a verified Gradle distribution into
# the user's cache if the installed Gradle is too old for the pinned Loom.
# Never edits sources, Git, maps, or the external CastleFight server.
set -Eeuo pipefail
CLIENT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$CLIENT/.." && pwd -P)"

# Fabric Loom 1.18.2 publishes a Gradle plugin variant for API 9.7.0.
# Gradle 9.4.1 (Fedora packages) cannot load that plugin.
MIN_GRADLE_MAJOR=9
MIN_GRADLE_MINOR=7
PINNED_GRADLE=9.7.1
GRADLE_BIN_SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a

get_gradle_version() {
  "$@" --version 2>/dev/null | awk '$1 == "Gradle" && $2 ~ /^[0-9]+(\.[0-9]+)+$/ { print $2; exit }'
}

is_gradle_compatible() {
  local version="${1:-}" major minor rest
  IFS=. read -r major minor rest <<< "$version"
  [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]] || return 1
  (( major > MIN_GRADLE_MAJOR || (major == MIN_GRADLE_MAJOR && minor >= MIN_GRADLE_MINOR) ))
}

cached_gradle() (
  local cache_base="${XDG_CACHE_HOME:-$HOME/.cache}/castlefight/gradle"
  local installed="$cache_base/gradle-$PINNED_GRADLE/bin/gradle"
  if [[ -x "$installed" ]]; then
    printf '%s\n' "$installed"
    exit 0
  fi

  command -v unzip >/dev/null 2>&1 || {
    echo '[КФ КЛИЕНТ] Для установки Gradle требуется unzip (sudo dnf install unzip).' >&2
    exit 1
  }
  command -v sha256sum >/dev/null 2>&1 || {
    echo '[КФ КЛИЕНТ] Для проверки Gradle требуется sha256sum.' >&2
    exit 1
  }

  mkdir -p -- "$cache_base"
  local temp_dir
  temp_dir="$(mktemp -d "$cache_base/.gradle-download.XXXXXXXX")"
  # Cleanup affects ONLY our temporary download inside the user cache.
  trap 'rm -rf -- "$temp_dir"' EXIT
  local url="https://services.gradle.org/distributions/gradle-$PINNED_GRADLE-bin.zip"
  echo "[КФ КЛИЕНТ] Скачиваю Gradle $PINNED_GRADLE в пользовательский кеш (один раз, ~145 МБ)..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --progress-bar --retry 2 --connect-timeout 20 \
      --output "$temp_dir/gradle.zip" "$url" || {
      echo '[КФ КЛИЕНТ] Не удалось скачать Gradle. Проверь соединение с интернетом.' >&2
      exit 1
    }
  elif command -v wget >/dev/null 2>&1; then
    wget --progress=bar:force:noscroll --output-document="$temp_dir/gradle.zip" "$url" || {
      echo '[КФ КЛИЕНТ] Не удалось скачать Gradle. Проверь соединение с интернетом.' >&2
      exit 1
    }
  else
    echo '[КФ КЛИЕНТ] Для загрузки Gradle установи curl или wget.' >&2
    exit 1
  fi

  if ! printf '%s  %s\n' "$GRADLE_BIN_SHA256" "$temp_dir/gradle.zip" | sha256sum --check --status; then
    echo '[КФ КЛИЕНТ] ОШИБКА: SHA-256 загруженного Gradle не совпадает с официальной контрольной суммой.' >&2
    exit 1
  fi
  unzip -q "$temp_dir/gradle.zip" -d "$temp_dir" || {
    echo '[КФ КЛИЕНТ] Не удалось распаковать Gradle.' >&2
    exit 1
  }
  [[ -x "$temp_dir/gradle-$PINNED_GRADLE/bin/gradle" ]] || {
    echo '[КФ КЛИЕНТ] В архиве нет исполняемого файла Gradle.' >&2
    exit 1
  }

  # Atomic installation in the cache; project files remain unchanged.
  if [[ ! -x "$installed" ]]; then
    mv -T -- "$temp_dir/gradle-$PINNED_GRADLE" "$cache_base/gradle-$PINNED_GRADLE"
  fi
  printf '%s\n' "$installed"
)

GRADLE_CMD=()
if [[ -f "$CLIENT/gradlew" ]]; then
  wrapper_version="$(get_gradle_version bash "$CLIENT/gradlew" || true)"
  if is_gradle_compatible "$wrapper_version"; then
    GRADLE_CMD=(bash "$CLIENT/gradlew")
    echo "[КФ КЛИЕНТ] Gradle Wrapper $wrapper_version подходит для Fabric Loom."
  else
    echo "[КФ КЛИЕНТ] Wrapper ${wrapper_version:-не отвечает} несовместим; нужен Gradle 9.7.0 или новее."
  fi
fi

if (( ${#GRADLE_CMD[@]} == 0 )) && command -v gradle >/dev/null 2>&1; then
  system_version="$(get_gradle_version gradle || true)"
  if is_gradle_compatible "$system_version"; then
    GRADLE_CMD=(gradle)
    echo "[КФ КЛИЕНТ] Использую установленный Gradle $system_version."
  else
    echo "[КФ КЛИЕНТ] Gradle ${system_version:-не определён} слишком старый для Loom 1.18.2 (нужен >= 9.7.0)."
  fi
fi

if (( ${#GRADLE_CMD[@]} == 0 )); then
  GRADLE_PATH="$(cached_gradle)"
  GRADLE_CMD=("$GRADLE_PATH")
  echo "[КФ КЛИЕНТ] Использую Gradle $PINNED_GRADLE из пользовательского кеша."
fi

# --continue lets Gradle report failures from independent tasks in one run.
# Do not publish an old mod if Gradle fails. Build result is copied atomically.
echo '[CF BUILD] Building Fabric client with compatible Gradle...'
(cd "$CLIENT" && "${GRADLE_CMD[@]}" --no-daemon --continue build)

property() {
  sed -n "s/^$1=//p" "$CLIENT/gradle.properties" | head -n 1 | tr -d '\r'
}
MOD_NAME="$(property archives_base_name)"
MOD_VERSION="$(property mod_version)"
[[ -n "$MOD_NAME" && -n "$MOD_VERSION" ]] || {
  echo '[CF BUILD] Missing client archive name/version' >&2
  exit 1
}
JAR="$CLIENT/build/libs/$MOD_NAME-$MOD_VERSION.jar"
[[ -s "$JAR" ]] || {
  echo "[CF BUILD] Built client JAR not found: $JAR" >&2
  exit 1
}
OUT="$ROOT/dist/client"
mkdir -p "$OUT"
TMP="$(mktemp "$OUT/.client-jar.XXXXXXXX")"
cleanup() { rm -f -- "$TMP"; }
trap cleanup EXIT
cp -- "$JAR" "$TMP"
mv -f -- "$TMP" "$OUT/castlefight-client.jar"
echo '[CF BUILD] CLIENT SUCCESS'
echo "[CF BUILD] Client mod: $OUT/castlefight-client.jar"
