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
