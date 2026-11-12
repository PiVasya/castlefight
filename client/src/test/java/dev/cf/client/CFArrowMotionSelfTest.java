package dev.cf.client;

import dev.cf.client.render.CFArrowMotion;
import dev.cf.protocol.CFShotWire;

/** No Fabric classes required: launch packet and frame interpolation. */
public final class CFArrowMotionSelfTest {
    public static void main(String[] args) throws Exception {
        if (CFArrowMotion.progress(-1,10)!=0 || CFArrowMotion.progress(0,10)!=0
                || Math.abs(CFArrowMotion.progress(250_000_000L,10)-.5)>1e-9
                || CFArrowMotion.progress(500_000_000L,10)!=1
                || CFArrowMotion.progress(900_000_000L,10)!=1)
            throw new AssertionError("Arrow interpolation bounds");
        var shot = new CFShotWire.Shot(7,false,1,8,1,14,8,1,10);
        if (!CFShotWire.read(CFShotWire.write(shot)).equals(shot))
            throw new AssertionError("Visual packet round-trip");
        System.out.println("[КФ КЛИЕНТ] Протокол и движение стрелы: ПРОЙДЕНО");
    }
}
