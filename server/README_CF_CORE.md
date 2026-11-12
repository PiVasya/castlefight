# Castle Fight — чистое игровое ядро

**Только игровая логика. Никакого WorldEdit, BUILD, старых карт, дорожных ковров или миграционных скриптов.**

## Сборка

Нужны JDK 25 и Maven. В корне общего Git-репозитория:

```bash
bash build.sh server
```

После сборки:

```text
dist/CastleFight/
├── start.sh
├── core/server.jar
├── core/content/
├── maps/
└── data/
```

`build.sh` компилирует, запускает регрессионные тесты и публикует сервер.
**Исходники не удаляет и не редактирует; карты и данные сохраняет.**

### ВАЖНО: готовый сервер выносят из Git-проекта

После `bash build.sh server` **перемести целиком** папку `dist/CastleFight/`
в любое удобное место (например, `~/Игры/CastleFight/`). Это самостоятельный
сервер: `start.sh` использует только соседние `core/`, `maps/` и `data/`.
Никаких ссылок на исходный репозиторий в нём не должно быть.

Карты клади **только в `CastleFight/maps/<map-id>/` у готового сервера**,
а не в Git. Каталог `maps/` исключён из Git, как и `dist/` и `target/`.
Переносить, переименовывать и хранить сборку отдельно можно после сборки;
если снова вызвать `bash build.sh server` в Git-проекте, он создаст новую сборку в
`dist/`, а ранее вынесенная папка останется без изменений.

```bash
# Пример после того, как готовая папка перенесена:
cd "$HOME/Игры/CastleFight"
bash start.sh --list
bash start.sh имя-карты
```

**При сборке** дополнительно обязательны `CF22SelfTest` и
`CF23FourLaneRouteSmoke` (проверяют Map 3.0, четыре дорожки и маяки).
При отмене публикации `core/` старая версия восстанавливается; при
аварийном выключении остаётся резервная `.core-previous` для восстановления
при следующей сборке.


## Единственный формат карт: CF Map 3.0

В папке каждой карты требуется файл **`cf/map.properties`** с `schema=3.0`.
Схемы `2.2`, `2.3`, `lanes-v2.properties` и прежние ковровые маршруты
**не поддерживаются**. Старые карты не конвертируются и не запускаются.

Карта размещается отдельно:

```text
maps/<map-id>/
├── dimensions/minecraft/overworld/region/r.0.0.mca
└── cf/
    ├── map.properties
    └── compiled-routes-v3.bin  # создаётся при первой успешной проверке
```

### Маршрутные маяки — строго НА дороге

```text
[цветное стекло]  <- 1 коричневое; 2 красное; 3 оранжевое; 4 жёлтое
[магнетит]        <- стоит непосредственно НА дороге
[поверхность дороги]
```

Маяки оставляют свободное место над собой. Два служебных блока скрываются
только в **памяти Minestom** во время игры. Сам мир на диске остаётся нетронутым.
Минимум два маяка для каждого пути. 1–4 дорожки, без пропусков в нумерации.

### Ковры — только строительство баз

| Лайн | Синие ковры | Красные ковры |
|---:|---|---|
| 1 | CYAN / WHITE | PURPLE / BROWN |
| 2 | LIGHT_BLUE / LIGHT_GRAY | MAGENTA / RED |
| 3 | BLUE / GRAY | PINK / ORANGE |
| 4 | GREEN / BLACK | LIME / YELLOW |

Для каждой площадки башни 3×3 нужны **9 ковров одного цвета**. Альтернативные
цвета той же команды/дорожки в пределах одной площадки смешивать нельзя.
Дорожка определяется цветом, а не расстоянием до пути.

### Castle compiler and two kings

Castles are discovered by the CF Map 3.0 COMPILER, in the same scanning pass
as route markers and construction zones. They are not configured by coordinates.
Build EXACTLY one flat square of 9 blue glazed terracotta blocks (3x3) and one
flat square of 9 red glazed terracotta blocks (3x3). Keep two air blocks above
each center and connect each throne area to the road network. The compiler
validates the squares and stores BOTH centers together with the compiled routes
in `maps/<map-id>/cf/compiled-routes-v3.bin`. Recompilation happens when the
source world or map.properties changes. No source `.mca` files are edited.

Minimal `cf/map.properties`:

```properties
schema=3.0
dot.max-gap=12
# spawn.x=4.5
# spawn.y=12
# spawn.z=12.5
```

The `spawn.*` triplet is optional; no `blue.*` or `red.*` castle coordinates.
A king appears at the CENTER of each ceramic square, with 4000 HP and
10 melee damage. King AI is deliberately basic: protect the throne, attack
nearby enemies, return to the center. The castle floor has NO HP. King deaths
are reported but full match-win logic is a separate feature.

## Запуск

```bash
bash dist/CastleFight/start.sh <map-id>
```

Если карта одна, можно запустить `start.sh` без имени.

## Проверки карты и компилятора

```bash
java -cp server/target/cfengine-0.1.0.jar dev.cf.CF22SelfTest
mkdir -p /tmp/cf-map-qa && cd /tmp/cf-map-qa
java -cp "$OLDPWD/server/target/cfengine-0.1.0.jar" dev.cf.CF23FourLaneRouteSmoke new-map-smoke
```

Вторая команда проверяет четыре дорожки на временной синтетической карте.
Это не заменяет полноценную проверку настоящего игрового мира.

Более подробный контракт — `server/docs/MAP_CONTRACT.md`.
Большой запрещающий комментарий — в `server/src/main/java/dev/cf/CFMarkerPalette.java`.

## Клиентская разметка (Overlay V1)

Ядро теперь включает `CFOverlaySync` и `CFOverlayWire`. После готовности
выбранной Map 3.0 сервер **один раз за соединение** передаёт Fabric-моду
строительные зоны и рассчитанные дорожки. Мод держит их только в RAM, а
клавиша **G** лишь переключает отрисовку — без запросов к серверу.

Исходники клиентского мода находятся в `client/` ЭТОГО ЖЕ Git-репозитория. Сборка: `bash build.sh client`; артефакт: `dist/client/castlefight-client.jar`.
Подробности: `server/docs/OVERLAY_CLIENT_PROTOCOL.md`. Карты в Git не добавляем.


## Ограничение карты (V10.2)

Большие Anvil-миры не нужно обрезать вручную. Загрузчик использует только
существующие чанки с X/Z в `[-50,50]` (максимум 10 201). Вне квадрата
исходные чанки не читаются, файлы `.mca` не меняются. Сканирование керамики
замков, ковров, маяков и кеш маршрутов учитывает эти границы. Для большой
арены может понадобиться больше RAM: `CF_MAX_RAM=6G bash start.sh my-arena`.
