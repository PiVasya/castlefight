# Castle Fight: game-only core (2.6.8.3 + Necromancy Hotfix V3)

This archive contains **only source code and release packaging**. Unpack the ZIP
**inside the root of your existing Git repository**, beside its `.git/` folder.
No Git history, world, logs, player data, secrets or precompiled server JAR is included.
Your existing `README.md` will not be overwritten.

## Build

Requirements: Fedora (or a compatible Linux environment), JDK **25**, Maven.
Stop any old Castle Fight server before starting a new instance.

```bash
bash build.sh
```

The script compiles a standalone jar, runs regression checks and creates:

```text
dist/CastleFight/
  start.sh                  <-- launch from here
  core/
    server.jar
    CF_BUILD_INFO.txt
    content/
      mobs/
      towers/
      settings.properties
  maps/                     <-- place your ready Minecraft worlds here
    README.txt
  data/                     <-- per-map runtime state and diagnostics
```

Only `core/` and `start.sh` are updated on rebuild; existing `maps/` and `data/`
are **not deleted or replaced**. `build.sh` never deletes or edits source files,
and never runs Git commands. Maven generates files under `target/` as usual.
The archive also does not touch your older
`~/castlefight`, `~/castlefight-dev` or `~/castlefight-data` installations.


**When replacing an earlier ZIP overlay:** extraction only adds/replaces files; it cannot
remove obsolete files already in your repository. Before committing, manually remove
`src/main/resources/cf/arena-spawn-cells.csv` if it still exists in your old checkout.
The new Java code does not load it, and Maven excludes CSV resources from the JAR.
This is a one-time Git-worktree cleanup, not a build step.

## Add a map

Copy a **stopped** compatible Minecraft world into `dist/CastleFight/maps/`:

```bash
cp -a "/path/to/your-prepared-world" dist/CastleFight/maps/my-arena
mkdir -p dist/CastleFight/maps/my-arena/cf
cp "/path/to/your-map-lanes-v2.properties" \
   dist/CastleFight/maps/my-arena/cf/lanes-v2.properties
```

For a new map, supply its **own** `cf/lanes-v2.properties` with positions for
blue and red castles. See `dist/CastleFight/maps/README.txt`.
The world has to contain the 3 carpet-marked paths and the six build masks;
the engine calculates and validates the routes at startup. The route compiler
**does not create or modify the Minecraft terrain**.

The map format must contain
`dimensions/minecraft/overworld/region/*.mca` (Minecraft 26.1+ format).
Old world layouts must be converted using an appropriate Minecraft version
before use; no automatic conversion is included.

## Start

With exactly one map, run:

```bash
bash dist/CastleFight/start.sh
```

With multiple maps, choose one:

```bash
bash dist/CastleFight/start.sh my-arena
```

List maps: `bash dist/CastleFight/start.sh --list`.
The release starts in **PLAY only** with read-only world storage, does not
register WorldEdit commands and prevents switching into BUILD mode. In-game
tower construction is still available. The older Java editor classes are
retained only as build-time compatibility; no editor server is shipped.

A map may set `spawn.x`, `spawn.y`, `spawn.z` in its `cf/lanes-v2.properties`.
If omitted, the initial spawn is estimated near the midpoint between the two
castles, two blocks above their average Y. For nonstandard maps, set an
explicit, safe spawn point in the map file.

Runtime logs and state are stored under `dist/CastleFight/data/runs/<map-id>/`,
not in the world directory. Do not put the release's `dist/` in Git; `.gitignore`
already excludes it.

## Notes and limitations

- The source is adapted from the **installed** 2.6.8.3 + Necromancy V3 snapshot.
- This is a **single Maven project**; not yet a deep multi-module engine refactor.
- Diagnostic duels now derive their safe spawn cells and spectator camera from
  the selected map's red/blue castle positions; no bundled arena CSV is used.
- Large stress tests may reuse safe positions near a castle if there are more
  mobs than clear ground cells. Their performance results depend on map layout.
- Carpet markers define road paths and tower build zones; castle coordinates
  still need per-map metadata. The core cannot infer the castles from carpets.
- The game server uses port `25565`; do not start it alongside another server
  on that port.
- Java 25 build and Minestom runtime tests need to be run on your Fedora box.

## Git

The ZIP contains no `.git` directory and does not run `git add`, `commit` or
`push`. Review the code and commit changes yourself.

```bash
git status --short
git add pom.xml src content packaging docs build.sh .gitignore README_CF_CORE.md
git commit -m "Add game-only Castle Fight core and separate-map release build"
# Push through your preferred GitHub workflow.
```
