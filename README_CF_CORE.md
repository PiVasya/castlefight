# Castle Fight - game core only

This Git project contains the Castle Fight 2.6.8.3 + Necromancy V3 server engine,
its combat content and its test suite. **Map editing tools are not part of the
server**. Prepare maps separately in Minecraft and copy them into the release.
There is no world-editing mode, no WorldEdit, no map-autosave, and no command for
changing the castle positions while a match is running.

## Build

Requires JDK 25 and Maven. From the repository root:

```bash
bash build.sh
```

The build compiles Java, runs selected regression tests, and publishes
`dist/CastleFight/`:

```text
dist/CastleFight/
  start.sh
  core/
    server.jar
    content/mobs/
    content/towers/
    content/settings.properties
    CF_BUILD_INFO.txt
  maps/
    <map-id>/...
  data/
```

`build.sh` **never deletes or edits source files**. It updates only the generated
`core/` and `start.sh`. Existing `maps/` and `data/` remain in place. This
archive has no `.git`, worlds, player records, logs, prebuilt JAR or secrets.

## Maps

Put a ready Minecraft world in `dist/CastleFight/maps/<map-id>/`:

```text
<map-id>/
  dimensions/minecraft/overworld/region/r.0.0.mca
  cf/lanes-v2.properties
```

The map properties contain castle positions, optional spawn position and schema
`2.2`. See `docs/MAP_CONTRACT.md` and the release `maps/README.txt`.
The game core calculates all three paths from saved carpet markers at startup.
It hides those markers in memory. **Map files are always read-only to the game**.

## Run

For a single map: `bash dist/CastleFight/start.sh`.
With multiple maps: `bash dist/CastleFight/start.sh my-arena`.
To list maps: `bash dist/CastleFight/start.sh --list`.

Match tower construction, upgrades, races, necromancy, revivals, AI and combat
remain intact. Operator diagnostics and battle stress tests are retained. The
server does not register editor commands or creative-mode switching commands.
Runtime logs are written under `dist/CastleFight/data/runs/<map-id>/`.

## Repository updates

This is a full **clean source tree**, not an installer. Unzipping onto an old
checkout does not remove older files from that checkout. Delete the obsolete
files from the old checkout **once, outside `build.sh`**, when upgrading:

```text
src/main/java/dev/cf/CFWorldEdit.java
src/main/java/dev/cf/CFWorldMode.java
src/main/java/dev/cf/CFSingleLaneTool.java
src/main/java/dev/cf/CF23SelfTest.java
src/main/java/dev/cf/CF234JournalSelfTest.java
src/main/resources/cf/arena-spawn-cells.csv    (if present in an older checkout)
```

The new game-only replacements are `CFGameArena.java` and
`CFReadOnlyMapLoader.java`. Do not put `dist/` or `target/` in Git.

The complete Maven/Minestom build is intended to run on Fedora with Java 25.
