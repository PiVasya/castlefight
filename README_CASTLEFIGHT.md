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
* `client` requires Java 25, Gradle 9.5.1+ and an internet connection for
  Fabric dependencies. If you later add the standard `client/gradlew`, the
  build script uses it first. Otherwise, Gradle must be installed on PATH.
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
