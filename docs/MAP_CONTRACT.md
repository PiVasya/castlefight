# Map contract v2.2

The game JAR has no bundled .mca map data. Each world must be installed under
`dist/CastleFight/maps/<map-id>/` using a file layout that Minestom's Anvil
loader supports.

```
<map-id>/
  dimensions/minecraft/overworld/region/r.X.Z.mca
  cf/lanes-v2.properties
```

Example metadata (replace all placeholder positions with YOUR map coordinates):

```
schema=2.2
dot.max-gap=12.0
blue.x=<blue-castle-x>
blue.y=<blue-castle-y>
blue.z=<blue-castle-z>
red.x=<red-castle-x>
red.y=<red-castle-y>
red.z=<red-castle-z>
# Optional: player lobby spawn; omit all three to use the castle midpoint.
spawn.x=<lobby-x>
spawn.y=<lobby-y>
spawn.z=<lobby-z>
```

Route carpet colors: BROWN (lane 1), RED (2), ORANGE (3).
Blue construction zone colors: CYAN (1), LIGHT_BLUE (2), BLUE (3).
Red construction zone colors: PURPLE (1), MAGENTA (2), PINK (3).

The engine validates 3 connected routes between the two castle positions in
both directions at startup and requires contiguous 3x3 construction masks for
each team and lane. The runtime hides carpet markers in memory without saving any changes to the
original world. Map editing takes place outside Castle Fight.

Diagnostic duels use the selected map's castle markers and compute safe spawn
positions dynamically. No precomputed spawn-position asset is needed.
They require suitable open ground near both castles.
