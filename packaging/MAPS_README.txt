Place each prepared world in maps/<map-id>/.

Expected files:
  maps/<map-id>/dimensions/minecraft/overworld/region/r.0.0.mca
  maps/<map-id>/cf/lanes-v2.properties

The world is external to the compiled game code. No .mca files ship in core/.

Required cf/lanes-v2.properties keys:
  schema=2.2
  dot.max-gap=12.0
  blue.x=<your-blue-castle-x>
  blue.y=<your-blue-castle-y>
  blue.z=<your-blue-castle-z>
  red.x=<your-red-castle-x>
  red.y=<your-red-castle-y>
  red.z=<your-red-castle-z>
Optional lobby point (all 3 keys):
  spawn.x=<your-lobby-x>
  spawn.y=<your-lobby-y>
  spawn.z=<your-lobby-z>

Use actual coordinates from your selected map; placeholders are not valid properties.
Road carpets are BROWN/RED/ORANGE for lanes 1/2/3.
Blue build masks: CYAN/LIGHT_BLUE/BLUE.
Red build masks: PURPLE/MAGENTA/PINK.
The core validates 3 lanes in both directions at runtime.

Run ./start.sh when exactly one map exists, or ./start.sh <map-id>.
Do not copy a live Minecraft world while it is being saved.
