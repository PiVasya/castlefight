CASTLE FIGHT — КАРТА НОВОГО ФОРМАТА 3.0

Поддерживается ТОЛЬКО CF Map 3.0. Старые карты/схемы не читаются.

Этот CastleFight/ — САМОСТОЯТЕЛЬНАЯ ПАПКА СЕРВЕРА.
Её можно переносить или переименовывать целиком. Git и папка исходников
для запуска НЕ нужны. Карты не нужно (и не следует) хранить в Git.

Положи подготовленный Minecraft-мир сюда:
  maps/<map-id>/dimensions/minecraft/overworld/region/r.x.z.mca
  maps/<map-id>/cf/map.properties

Минимальный файл cf/map.properties:
  schema=3.0
  dot.max-gap=12
  # spawn.x=4.5
  # spawn.y=12
  # spawn.z=12.5

CASTLE MARKERS (built into the CF Map 3.0 compiler):
  BLUE_GLAZED_TERRACOTTA: 3x3 same-level floor of 9 blocks
  RED_GLAZED_TERRACOTTA: 3x3 same-level floor of 9 blocks
  Exactly one complete square for EACH side. Leave 2 air blocks over centers.
  Castle coordinates are NOT specified in map.properties.
  They and all lane routes are saved in cf/compiled-routes-v3.bin.
  A weak, very slow melee king spawns at the center of each throne:
  4000 HP, 10 damage; castle blocks have NO HP.

МАЯКИ ПУТЕЙ — ТОЛЬКО НА ДОРОГЕ:
  [цветное стекло] <- лайн 1 BROWN; 2 RED; 3 ORANGE; 4 YELLOW
  [магнетит]       <- стоит НА дороге
  [пол дороги]

Минимум два маяка на каждый путь. Путей от 1 до 4, без пропусков.
В игровом мире маяки скрываются в памяти. Исходные .mca не изменяются.

ВСЕ 16 КОВРОВ — ТОЛЬКО ДЛЯ СТРОИТЕЛЬСТВА:
  лайн 1: синие CYAN/WHITE; красные PURPLE/BROWN
  лайн 2: синие LIGHT_BLUE/LIGHT_GRAY; красные MAGENTA/RED
  лайн 3: синие BLUE/GRAY; красные PINK/ORANGE
  лайн 4: синие GREEN/BLACK; красные LIME/YELLOW

Под башню нужны 9 ковров ОДНОГО цвета в квадрате 3x3.
Другие цвета, даже той же команды и лайна, смешивать нельзя.

Успешно проверенные маршруты автоматически сохраняются РЯДОМ с миром:
  cf/compiled-routes-v3.bin
Это не часть сборки, build.sh не изменяет карты и исходники.

Как запускать из готовой папки сервера:
  bash start.sh --list
  bash start.sh <map-id>
Если карта одна, достаточно: bash start.sh

Кеш не заменяет загрузку чанков: ядро загружает Anvil-мир при каждом запуске,
но повторно использует вычисленные маршруты, если исходная карта не менялась.

Подробное описание — docs/MAP_CONTRACT.md в Git-проекте.
