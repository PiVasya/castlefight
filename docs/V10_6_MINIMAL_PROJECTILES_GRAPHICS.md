# V10.6 - Tiny Fabric projectile events, graphics menu and smooth walking heading

## Immutable architecture

- The server sends NO shape, color, effect, texture, start or destination coordinates.
- Only one compact 9-byte payload per visually observable attack: `int shooterEntityId`, `int targetEntityId`, `unsigned byte projectileStyleId`.
- The wire version is part of the channel name `castlefight:shot_visual_v2`.
- Compatible Fabric clients opt in once via a 1-byte `shot_hello_v2` payload. The server only sends to compatible viewers within its existing visual radius. No per-tick motion updates.
- Fabric uses its already streamed Minecraft entities to determine projectile endpoints, lifetime, geometry and animation. This may cause minor cosmetic latency differences; no client trajectory affects damage.
- If shooter or target is absent from the client world, the visual effect is skipped. The authoritative server hit and damage still apply.
- No vanilla-arrow fallback and no vanilla projectile spawn packets.

## Stable numeric style IDs (the *entire* style table lives on the Fabric client)

| ID | Client appearance | Example ranged units |
| --- | --- | --- |
| 0 | Regular arrow | Unknown/default style |
| 1 | Bone arrow | Risen, skeleton units |
| 2 | Flaming arrow | Arsonist and upgraded skeleton |
| 3 | Arcane bolt | Acolyte and altar |
| 4 | Dark/void beam | Upgraded altar |
| 5 | Spirit orb | Banshee and cottage |
| 6 | Hex orb | Upgraded cottage |
| 7 | Frost shard | Ancient snow burial |
| 8 | Snowball | Snowman house |
| 9 | Icy comet | Upgraded snowman house |

Server content files contain only `visual.projectile-id=NUMBER` for ranged mobs, never appearance definitions.
No colors, animation frames, model ids, render settings or asset data are stored on the server.

## Client graphics settings

- Rebindable **O** opens the local Castle Fight graphics menu.
- **G** still toggles building areas and route ribbons without contacting the server.
- Path ribbons are narrower; their height is raised by precisely 0.25 block over v10.5.
- Menu: route width, area opacity, projectile quality (off/basic/detailed), optional trails, visual distance.
- Preferences are saved to `config/castlefight-graphics.properties`; this is not a map disk cache.
- New cosmetic projectile types can be added client-side using more numeric IDs, without adding more packet fields or extra packets.

## Server navigation

- `CFSmoothGroundFollower` smooths **walking yaw only**, max 8 degrees per tick.
- Hit checking, attack aiming, physics, collision, position, max step/drop, 3-block combat height and lane selection are unchanged.
- Pure-Java regression `CFViewSmoothingSelfTest` prevents wraparound and left/right stair jitter.

## Validation / deployment

- Build both sides, `bash build.sh all`, because the visual packet channel changed from v1 to v2.
- Server and client copies of CFShotWire are byte-identical and verified in root build.sh.
- `CFShotWireSelfTest` tests the 9-byte packet; client arrow and settings tests are required JavaExec tasks.
- Install new server `core/` and client JAR together, while preserving world `maps/` and `data/`.
- On real hardware: check at least three cosmetic styles, menus O/G, stair mobs, HP hit authority and missing/mismatched client version.
