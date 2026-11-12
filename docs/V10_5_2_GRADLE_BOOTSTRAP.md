# V10.5.2 — исправление сборки Fabric: Gradle 9.4.1 против Loom 1.18.2

## Симптом

`bash build.sh all`: серверные Maven/self-tests успешны, далее Gradle 9.4.1
останавливается на настройке Fabric Loom 1.18.2 с сообщением:

```text
No matching variant of net.fabricmc:fabric-loom:1.18.2
org.gradle.plugin.api-version: 9.7.0 (plugin) vs 9.4.1 (consumer)
```

Это несовместимость версий Gradle, а не ошибка карты, java-файлов мода
или серверной боевой системы.

## Исправление

`client/build.sh` перед сборкой проверяет подходящую версию Gradle.
Приоритет: (1) совместимый `client/gradlew`, если имеется; (2) совместимый
системный `gradle`; (3) Gradle 9.7.1 из пользовательского кеша. Если кеш
пуст, один раз скачивает дистрибутив с:

https://services.gradle.org/distributions/gradle-9.7.1-bin.zip

SHA-256 бинарного ZIP (официальная контрольная сумма Gradle):

```text
acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
```

Кеш: `${XDG_CACHE_HOME:-$HOME/.cache}/castlefight/gradle/`.
Сборщик не редактирует исходники и не трогает исходные `.mca`, `maps/`,
`data/`, Git, сервер в отдельной папке. В Git не добавляется бинарный
Gradle, JAR-обёртка или скомпилированные артефакты.

## Проверка на Fedora

В корне репозитория:

```bash
bash build.sh all
```

После `[CF BUILD] SUCCESS` серверной стороны должен начаться Fabric-клиент.
Если версия системного Gradle слишком старая, скрипт сообщит о ней и
использует Gradle 9.7.1. В конце нужен `[CF BUILD] CLIENT SUCCESS`.

При ошибке загрузки Gradle проверь подключение к интернету, `curl` или
`wget`, `unzip`, `sha256sum`. Для первой загрузки требуется около 145 МБ.

Если появится **другая** ошибка компиляции Fabric/Minecraft API, это уже
отдельный следующий этап: Loom раньше не мог даже настроить проект.
