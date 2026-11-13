# Castle Fight V10.7.2 - compilation fixes

This is a focused follow-up to V10.7.1. No gameplay, map, balance, or wire-format changes.

## Server

- `CFTowerSystem.register()`: renamed the `String race` variable in the
  tower-build packet handler to `selectedRace` because the method already has
  an `Argument<String> race` for the `/cf race` command. Java forbids shadowing
  that enclosing local name inside the listener's lambda.
- The race and item checks themselves are unchanged.

## Fabric client

- `CFBuildClient.tick()`: reads the open screen with `mc.gui.screen()`, as
  required by Minecraft 26.2, instead of the old `mc.screen` field.

## Validation

- The four shared server/client protocol source pairs match byte for byte.
- Shell scripts pass `bash -n`.
- Pure Java tests for build requests, placement geometry, economy placeholder,
  and projectile metadata run successfully on the available Java 21 runtime.
- Full Minecraft 26.2 build with Java 25, Minestom and Fabric dependencies
  still needs to be executed on the author's Fedora machine.

## Build

Run `bash build.sh all` from the **Git repository** root. Only after
`[CF BUILD] SUCCESS` and `[CF BUILD] CLIENT SUCCESS` should the server core
be copied into the separate installed `CastleFight` folder and the mod jar
be replaced in Prism Launcher. Never delete the user's map or runtime data.
