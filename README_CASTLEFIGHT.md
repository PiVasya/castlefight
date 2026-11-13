# Castle Fight - one Git repository, two independent builds

This repository contains the Minestom server and the Fabric client mod for
Minecraft Java 26.2. Java 25 is required for either build.

## Layout

```text
castlefight/                 <- Git repository (contains .git/)
  build.sh                   <- choose server or client
  server/                    <- Maven, game engine, races, towers, mobs
    pom.xml
    src/
    content/
    packaging/
    build.sh                 <- private server build implementation
  client/                    <- Fabric, Gradle, G-key overlay
    build.gradle
    gradle.properties
    settings.gradle
    src/
    build.sh                 <- private client build implementation
  dist/                      <- IGNORED by Git, created during builds
    CastleFight/             <- MOVE this entire folder anywhere
      start.sh
      core/server.jar
      core/content/
      maps/                  <- your Minecraft worlds (outside Git)
      data/                  <- runtime state
    client/
      castlefight-client.jar <- copy into Minecraft mods/
```

## Build one target

From the repository root (even if the path contains spaces):

```bash
bash build.sh server
bash build.sh client
bash build.sh all
```

* `server` requires Java 25 and Maven. Tests run before the release core is
  replaced, and existing maps and data inside `dist/CastleFight/` survive.
* `client` requires Java 25 and Fabric dependencies from the internet. Fabric
  Loom 1.18.2 requires **Gradle 9.7.0+**. The build script uses a compatible
  local Gradle/Wrapper, or downloads the official, SHA-256-verified **9.7.1**
  distribution into `~/.cache/castlefight/gradle/` on its first run. Fedora's
  Gradle 9.4.1 can stay installed; no system upgrade is needed.
* `all` builds server first, then client. The two artifacts remain separate.
* The build scripts never delete or rewrite code, never perform a Git action,
  never transfer maps into the repository, and never modify your moved server.
* Server and client share the same `CFOverlayWire` protocol source. A read-only
  byte-for-byte check prevents building incompatible copies by mistake.

## Run the portable server

Move the entire `dist/CastleFight` directory OUTSIDE this repository, for
example into your games folder, then put prepared CF Map 3.0 worlds into
`CastleFight/maps/<map-id>/` and launch:

```bash
cd /path/to/CastleFight
bash start.sh --list
bash start.sh my-arena
```

The launcher resolves paths relative to its own directory. The compiled server
will never depend on Maven, Git, or the original source directory.

The Fabric mod is a different artifact. Install `dist/client/castlefight-client.jar`
into the Minecraft 26.2 Fabric instance's `mods/` folder, together with Fabric API.
On the first world join the server sends zone/route geometry once to client RAM;
G toggles the local overlay with no network traffic.


## Castle markers are compiled with paths

For CF Map 3.0 maps, make one complete 3x3 floor from
`BLUE_GLAZED_TERRACOTTA` and one from `RED_GLAZED_TERRACOTTA`.
The game compiler scans both alongside the carpets and lodestone/glass beacons,
then saves the discovered castle centers in `compiled-routes-v3.bin` with the
routes. Castle coordinates are no longer stored in map.properties.
Two defensive kings spawn at the detected centers: 4000 HP, 10 melee damage,
very slow, with throne-guard AI. Castles have no separate HP. This does NOT
introduce a complete king-death victory/match flow.

## Карта в квадрате ±50 чанков (V10.2)

Загрузчик читает из исходных регионов Anvil только чанки с координатами
`chunkX=-50..50`, `chunkZ=-50..50`, то есть до **10 201** чанка.
Это квадрат 1616 × 1616 блоков, центрированный около нулевых координат.
Избыточные чанки большого плоского мира игнорируются, но **не удаляются**:
исходные файлы `.mca` и внешняя копия карты остаются неизменными.
Компилятор замков 3×3, маяков и строительных ковров работает внутри этого окна.
Кеш пересчитывается при обновлении алгоритма. См. `server/docs/MAP_CONTRACT.md`.

При публикации новой версии уже вынесенный `CastleFight/` не обновляется сам.
После `bash build.sh server` следует перенести только готовые `core/` и
`start.sh` из `dist/CastleFight/`; собственные `maps/` и `data/` не заменять.

## One-time migration from the previous layout

Earlier versions stored Maven's `src/`, `content/`, `pom.xml`, `packaging/`
and `docs/` directly at the repository root. This ZIP replaces that layout
with `server/` and `client/`. ZIP extraction cannot delete your old root
files. Inspect your Git diff and remove/archive the **old root copies** once
before committing; **do not remove `.git/`, `server/`, `client/`, or `dist/`.**
This is a manual source-tree migration, NOT part of either build script.

See `server/README_CF_CORE.md`, `server/docs/MAP_CONTRACT.md`,
`server/docs/OVERLAY_CLIENT_PROTOCOL.md`, and `client/README.md`.

## Наведение на мобов (V9)

Прицел на CF-мобе открывает панель серверных характеристик. HP — только полоска без цифр. Сервер сам отправляет изменения HP выбранного моба. Подробнее: [docs/MOB_HOVER.md](docs/MOB_HOVER.md).

### V10.3 — поведение короля и лучников

См. `docs/V10_3_KING_COMBAT_AND_RETURN.md`. Король с 4000 HP возвращается
в центр своей глазурованной керамики 3x3 после боя, его успешный удар
наносит 10 реальных HP. Дальники больше не стоят навечно, удерживая цель
за стеной: они возвращаются к своему назначенному маршруту.


### V10.4 — ступени и небольшие перепады высоты

Серверные мобы могут безопасно сойти вниз на 1–2 блока, когда под ними
есть реальный сухой пол. Высотный допуск к своей дорожке учитывает короткий
спуск или прыжок, но не позволяет переходить на другую дорожку.
Вверх — обычные ступени или прыжок на один блок, не подъём сквозь стены.
Подробно: `docs/V10_4_TERRAIN_HEIGHT.md`.

### V10.5 — ближний бой по высоте и стрелы Fabric-only

Ближники выбирают и атакуют врагов с разницей Y не больше 3 блоков,
не меняя дистанцию. Дальники проверяют прямую видимость: при блокировке
выстрела возвращаются на свой лайн и атакуют при новом открытом простреле.

**Старые серверные стрелы удалены без запасного варианта:** никаких vanilla
arrow entity и пакетов создания/удаления. Стрелы рисует только обновлённый
Fabric-мод. Без мода стрелы не видны, но урон вычисляется сервером как раньше.
Для этой версии нужно собрать **и сервер, и клиент** (`bash build.sh all`).
Описание: `docs/V10_5_VERTICAL_COMBAT_FABRIC_ARROWS.md`.


### V10.5.1 — надёжная регрессия возврата дальника к маршу

`CFKingBehaviorSelfTest` теперь загружает конечную синтетическую область
из 49 чанков для штатного навигатора Minestom. Он проверяет возврат короля,
его точные HP/урон и освобождение дальником цели за стеной, не падая из-за
отсутствующего тестового чанка с отрицательным Z. Ни боевая логика, ни
пользовательская карта не менялись. См. `docs/V10_5_1_KING_TEST_CHUNKS.md`.

### V10.5.2 — исправление Gradle для Fabric клиента

В Fedora был установлен Gradle 9.4.1. Сборка сервера завершалась успешно,
но Loom 1.18.2 требовал API Gradle 9.7.0 и клиент падал на стадии
настройки плагинов. `client/build.sh` теперь выбирает совместимый Gradle
или самостоятельно получает официальную версию 9.7.1 в пользовательский
кеш с проверкой SHA-256. Никаких JAR, сборочных инструментов и карт в
Git-репозиторий не добавляется. См. `docs/V10_5_2_GRADLE_BOOTSTRAP.md`.

## V10.6
Fabric-only visual projectiles use 9 payload bytes per shot: shooter ID, target ID and one numeric type. Graphics are solely on the Fabric client. Local graphics menu O; G keeps client-only map overlays. Routes are thinner and raised +0.25 block. Walking yaw is smoothed on stairs. Build both sides with `bash build.sh all`. See `docs/V10_6_MINIMAL_PROJECTILES_GRAPHICS.md`.


## V10.7.1 — ПКМ в инвентаре, клиентская проверка перед постройкой

Стартовая раса — `dark`, переключение `/cf race dark|snow`.
Предметы башен без длинного lore. **ПКМ именно по предмету в открытом инвентаре**
открывает только информационное меню башни. Закрытие меню возвращает в инвентарь.
ПКМ башней в воздухе меню не открывает.

Для строительства возьми предмет в руку и нажми ПКМ сверху по центральной
клетке строительной площадки. Клиент без сетевого запроса проверяет наличие
каталога, текущую расу/команду, дальность 8 блоков, точный цвет 3×3 и лайн
из снимка зон, загруженность чанка, пол и свободный объём башни.
Будущая проверка бюджета предусмотрена, но денег/цен пока нет.
Только после этих проверок уходит пакет `tower_build_v1`.
Сервер **не строит по обычному ПКМ**, он принимает только запросы по протоколу
и самостоятельно повторяет все правила, включая лимиты и расстояния между башнями.
Ошибки игрок видит над хотбаром, не в чате.

Подробности: `docs/V10_7_1_INVENTORY_RIGHT_CLICK.md`.

## V10.7.2 - исправления компиляции

Исправлено затенение переменной `race` в `CFTowerSystem.register()` и обращение к текущему экрану `mc.gui.screen()` в клиенте 26.2. Подробности: `docs/V10_7_2_COMPILATION_FIX.md`.

## V10.7.3 inventory mouse coordinates

For Minecraft 26.2, the inventory RMB tower inspector uses a mixin accessor
for `AbstractContainerScreen.leftPos/topPos`. The client build now checks
inventory slot hit testing with `cfInventorySlotHitSelfTest`. No server changes;
run `bash build.sh client` and replace the Fabric mod JAR. See
`docs/V10_7_3_INVENTORY_SCREEN_COORDS.md`.

## V10.7.4 — мини-шаг ближника

При каждой ближней атаке обычный моб делает два коротких шага к цели
(до 0.30 блока всего), даже если обычный удар уже попал. Это использует
прежний collision-aware follower, не создаёт новые A* маршруты и не отправляет
сетевые пакеты. См. `docs/V10_7_4_MELEE_CONTACT_STEP.md`.
