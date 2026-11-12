# V10.5.3 -- Fabric 26.2 key mapping compatibility

Fixed `CFOverlayClient.java` after the successful Gradle 9.7.1 bootstrap.
Minecraft 26.2 / Fabric API uses:

- `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper`
- `KeyMappingHelper.registerKeyMapping(...)`

The pre-26.1 `KeyBindingHelper` and `registerKeyBinding` identifiers do not exist
in the 26.2 Fabric API classpath. See the Fabric 26.1 API migration list and
Fabric 26.2 KeyMappingHelper documentation.

Key `G` still toggles client-side overlay using the geometry stored in RAM.
No packets are sent when pressing G; existing on/off behavior is unchanged.

Only the client source file was modified. Server behavior, generated maps,
protocols, and Gradle bootstrap remain untouched.

Build on Fedora with JDK 25:

    bash build.sh client

A passing `BUILD SUCCESS` from Gradle followed by `[CF BUILD] CLIENT SUCCESS`
will confirm client packaging. The full Minecraft 26.2 visual run remains a
separate integration check.
