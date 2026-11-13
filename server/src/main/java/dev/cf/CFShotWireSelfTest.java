package dev.cf;

import dev.cf.protocol.CFShotWire;
import java.io.IOException;
import java.util.Arrays;

public final class CFShotWireSelfTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void reject(byte[] bytes, String message) throws Exception {
        try { CFShotWire.read(bytes); }
        catch (IOException ok) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void rejectWrite(CFShotWire.Shot shot, String message) throws Exception {
        try { CFShotWire.write(shot); }
        catch (IOException ok) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var shot = new CFShotWire.Shot(1234, 5678, 9);
        byte[] frame = CFShotWire.write(shot);
        check(frame.length == 9, "wire must be exactly 9 bytes");
        check(CFShotWire.read(frame).equals(shot), "roundtrip");
        check(CFShotWire.validHello(CFShotWire.hello()), "single-byte hello");
        check(!CFShotWire.validHello(new byte[] {1}), "reject outdated hello");
        check(!CFShotWire.validHello(new byte[] {2,3}), "reject oversized hello");
        reject(Arrays.copyOf(frame,frame.length-1), "reject truncated shot");
        reject(Arrays.copyOf(frame,frame.length+1), "reject oversized shot");
        rejectWrite(new CFShotWire.Shot(0,2,0), "reject invalid shooter");
        rejectWrite(new CFShotWire.Shot(1,-2,0), "reject invalid target");
        rejectWrite(new CFShotWire.Shot(1,1,0), "reject self-target");
        rejectWrite(new CFShotWire.Shot(1,2,-1), "reject negative type");
        rejectWrite(new CFShotWire.Shot(1,2,256), "reject >255 type");
        check(CFShotWire.read(CFShotWire.write(new CFShotWire.Shot(1,2,255)))
                .styleId() == 255, "unsigned style id");
        System.out.println("[CF SHOTS] PASS " + checks + " checks: 9 bytes and numeric-only ID v2");
    }
}
