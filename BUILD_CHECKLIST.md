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
