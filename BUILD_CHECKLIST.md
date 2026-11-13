# Castle Fight: release checklist

1. Verify that the Git root has `server/`, `client/`, `build.sh`.
2. `bash build.sh server` -> `dist/CastleFight/start.sh` and `core/server.jar`.
3. Move the `CastleFight/` folder somewhere outside the repository.
4. Place CF Map 3.0 worlds inside its `maps/` folder. Run `bash start.sh <id>`.
5. `bash build.sh client` -> `dist/client/castlefight-client.jar`.
6. Copy the client jar into Fabric 26.2 `mods/` (Fabric API required).
7. Test G toggling zones and routes. Client RAM clears on disconnect.

No map, runtime state, client jar, or server jar belongs in Git.
Do not run `build.sh` to delete or migrate source files.


### V10.2 — большое окно карты

- [ ] `CFMapWindowSelfTest` проходит (10 201 чанков, отрицательные координаты).
- [ ] На огромном плоском мире читаются только чанки X/Z = -50..50.
- [ ] Мир за границами остаётся нетронутым; исходные `.mca` не изменились.
- [ ] Замки 3x3, ковры и маяки внутри квадрата компилируются как раньше.
- [ ] После новой сборки обновлён вынесенный `CastleFight/core`, а `maps/` и `data/` сохранены.

## V10.3 (король и дальники)

- После Java 25/Maven сборки пройти `CFKingBehaviorSelfTest`.
- На реальной карте проверить: король защищает трон, после ухода врага возвращается в центр 3x3.
- Дальник за стеной должен отпустить недоступную цель и продолжить свой лайн.
- Реальный успешный удар короля: 10 HP, собственное здоровье: 4000 HP.
- Проверить `/cf king status` и отсутствие бесконечных логов каждую игровую итерацию.

## V10.4 (перепады высоты)

- [ ] На Java 25 `bash build.sh server` проходит включая `CFVerticalTravelSelfTest`.
- [ ] Проверить спуск на 1–2 блока без застревания и скачков вверх по Y.
- [ ] Проверить ступени, плиты и подъём на 1 блок с прыжком.
- [ ] Обрыв глубже 2 блоков, вода и стены не становятся проходимыми.
- [ ] Оба направления дороги, laneId, AI, короли, зоны, карта и кеш сохранены.

## V10.5 (бой по высоте и стрелы только в Fabric)

- [ ] `bash build.sh server` проходит, включая `CFCombatHeightSelfTest`
  и `CFShotWireSelfTest`.
- [ ] `bash build.sh client` собирает обновлённый Fabric JAR.
- [ ] В CFClientShot нет `SpawnEntityPacket` и `DestroyEntitiesPacket`,
  старые серверные стрелы не порождаются.
- [ ] На vanilla клиенте стрела невидима, урон по врагу наносится сервером.
- [ ] На клиенте V10.5 виден ровно один визуальный полёт на каждый выстрел.
- [ ] Через блоки дальник не стреляет и продолжает движение по лайну.
- [ ] Через открытое место дальник снова находит цель и стреляет.
- [ ] Ближний бой допустим по Y при разнице 0..3, но не выше 3.

## V10.5.1 (синтетический тест навигатора)

- [ ] `CFKingBehaviorSelfTest` проходит после расширения набора загруженных
  чанков тестового мира: -3..3 по обеим осям.
- [ ] Проверка не отключена: тестирует 4000 HP, точные 10 HP за удар,
  возврат короля к трону и возобновление марша лучником за стеной.
- [ ] `bash build.sh all` доходит до **обоих** итогов: `[CF BUILD] SUCCESS`
  и `[CF BUILD] CLIENT SUCCESS`.
- [ ] `maps/`, `data/`, реальные `.mca`, контент и старые стрелы не затронуты.

## V10.5.2 — сборка Fabric с Gradle 9.7.1

- [ ] `bash build.sh server` — прежние тесты и `[CF BUILD] SUCCESS` не изменены.
- [ ] `bash build.sh client` на Fedora с Gradle 9.4.1 сам выбирает кешированный
      официальный Gradle 9.7.1 (скачивает только один раз с SHA-256).
- [ ] После компиляции Gradle: `[CF BUILD] CLIENT SUCCESS`,
      `dist/client/castlefight-client.jar` существует.
- [ ] `bash build.sh all` доходит до двух успешных итогов.
- [ ] Код клиента не должен переключаться обратно на старую визуализацию стрел.
- [ ] Вся папка `dist/CastleFight/maps/` и вынесенный сервер остаются без изменений.

## V10.5.4 — тесты клиента

- Убедиться, что `bash build.sh client` выполняет именно три JavaExec-задачи:
  `cfArrowMotionSelfTest`, `cfOverlayMeshSelfTest`, `cfOverlayWireClientSelfTest`.
- Не считать `Gradle :test` без обнаруженных JUnit-методов прохождением
  проверок: три программы должны реально запуститься и закончить с кодом 0.
- Затем проверить `BUILD SUCCESSFUL` и `[CF BUILD] CLIENT SUCCESS`.

## V10.6 compact graphics and smooth movement

- [ ] Root `build.sh` checks all four pairs of shared protocol classes.
- [ ] `CFShotWireSelfTest` confirms exactly 9 payload bytes, ID bounds and v2 handshake.
- [ ] `cfArrowMotionSelfTest` and `cfGraphicsSettingsSelfTest` pass as real client JavaExec tasks.
- [ ] On Minecraft 26.2 Fabric, O opens a client-only settings menu; G still works.
- [ ] Route ribbons are thinner and exactly 0.25 block higher than v10.5.
- [ ] Skeleton, mage and snow units have visibly distinct local projectiles.
- [ ] Packet carries only shooter ID, target ID, visual type ID; no positions or effect data.
- [ ] Walking yaw no longer oscillates +/-15 degrees on steps (check actual mobs).
- [ ] Server remains authoritative for hits and damage; no legacy vanilla arrows.


## V10.7 — новые интеграционные проверки

- [ ] `CFBuildWireSelfTest` проходит при `bash build.sh server`.
- [ ] `CFBuildPreviewSelfTest` проходит при `bash build.sh client`.
- [ ] В меню ПКМ показаны корректные данные и дерево улучшений.
- [ ] Для чужих/смешанных ковров нет запроса на сервер.
- [ ] При подтверждении корректной позиции сервер строит ровно одну башню.
- [ ] При `/cf race snow` палитра и сжатый каталог обновляются.
- [ ] Нет сообщений игрового КФ в чате; краткий actionbar работает.
- [ ] Карта и кеш маршрутов не затронуты.

### V10.7.1: инвентарь и клиентская проверка

- [ ] Полный `bash build.sh all` с Java 25 на Fedora, включая `cfBuildEconomySelfTest`.
- [ ] ПКМ **по ячейке** башни в инвентаре показывает меню, предмет не делится.
- [ ] ПКМ в мире не открывает меню. Неверная площадка = 0 сетевых запросов.
- [ ] Валидный ПКМ по центру отправляет один запрос, сервер повторно всё проверяет.
- [ ] Сервер по обычному `PlayerBlockInteractEvent` башню больше не строит.
- [ ] Денежная система не выдумана: ожидается будущая реализация экономики.

## V10.7.3 — ПКМ по слоту инвентаря

- [ ] `bash build.sh client` проходит, включая `cfInventorySlotHitSelfTest` (28 проверок).
- [ ] Fabric Mixin `CFContainerOriginAccessor` зарегистрирован и применяется к `AbstractContainerScreen`.
- [ ] ПКМ по предмету башни в инвентаре открывает меню без разделения стака.
- [ ] ПКМ по обычному предмету не перехватывается.
- [ ] После смещения инвентаря книгой рецептов слот определяется корректно.
- [ ] Сетевые пакеты строительства не отправляются при просмотре предметов.

## V10.7.4 — ближник коротко шагает при каждой атаке

- [ ] `bash build.sh server` проходит, включая `CFMeleeContactStepSelfTest`.
- [ ] На каждом взмахе обычный ближник пытается сделать два маленьких
      шага к своей цели; при нормальном контакте движения не больше 0.30 блока.
- [ ] За стену не проходит: движение использует существующий ground follower.
- [ ] Нет нового A*, обнаружения промаха, постоянных проверок дистанции
      сверх существующего AI или дополнительного сетевого трафика.
- [ ] Потеря цели останавливает оставшийся микро-подход.
- [ ] Король и его возврат, дальники и клиентская сборка не изменены.
