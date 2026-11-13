package dev.cf.client;

import dev.cf.client.render.CFArrowMotion;
import dev.cf.client.render.CFProjectilePalette;
import dev.cf.protocol.CFShotWire;

/** Protocol and visuals use only a one-byte style type, never serialized graphics. */
public final class CFArrowMotionSelfTest {
    public static void main(String[] args) throws Exception {
        if (CFArrowMotion.progress(-1,10)!=0 || CFArrowMotion.progress(0,10)!=0
                || Math.abs(CFArrowMotion.progress(250_000_000L,10)-.5)>1e-9
                || CFArrowMotion.progress(500_000_000L,10)!=1
                || CFArrowMotion.progress(900_000_000L,10)!=1)
            throw new AssertionError("Projectile interpolation bounds");
        var shot = new CFShotWire.Shot(31, 78, 9);
        if (CFShotWire.write(shot).length != 9 ||
                !CFShotWire.read(CFShotWire.write(shot)).equals(shot))
            throw new AssertionError("Compact shot packet roundtrip");
        if (CFProjectilePalette.forId(1).shape() == CFProjectilePalette.forId(5).shape())
            throw new AssertionError("Bow and spirit attacks should look different");
        if (CFProjectilePalette.forId(255).shape() != CFProjectilePalette.Shape.ARROW)
            throw new AssertionError("Unknown types should use a safe generic arrow");
        System.out.println("[CF CLIENT] PASS: 9-byte shot ID, client styles, local interpolation");
    }
}
