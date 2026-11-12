package dev.cf;

import dev.cf.protocol.CFShotWire;
import java.io.IOException;
import java.util.Arrays;

/** The same pure Java wire is tested without Fabric or a running server. */
public final class CFShotWireSelfTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static void reject(byte[] bytes, String reason) throws Exception {
        boolean failed = false;
        try { CFShotWire.read(bytes); }
        catch (IOException ignored) { failed = true; }
        check(failed, reason);
    }
    public static void main(String[] args) throws Exception {
        CFShotWire.Shot shot = new CFShotWire.Shot(81, true, 1.5f, 4.6f, -3.5f,
                13.5f, 4.7f, -3.5f, 8);
        byte[] frame = CFShotWire.write(shot);
        check(frame.length == CFShotWire.PACKET_BYTES, "fixed packet bounds");
        check(shot.equals(CFShotWire.read(frame)), "round trip");
        check(CFShotWire.validHello(CFShotWire.hello()), "hello handshake");
        check(!CFShotWire.validHello(new byte[]{0x43,2}), "reject unknown handshake");
        reject(Arrays.copyOf(frame,frame.length-1), "truncated frame");
        byte[] wrong = frame.clone(); wrong[0] = 0;
        reject(wrong, "bad magic");
        wrong=frame.clone(); wrong[4] = 2;
        reject(wrong, "unsupported protocol version");
        wrong=frame.clone(); wrong[9] = 3;
        reject(wrong, "unknown team");
        wrong=frame.clone(); wrong[34] = 0;
        reject(wrong, "zero flight time");
        try { CFShotWire.write(new CFShotWire.Shot(4,false,Float.NaN,0,0, 2,0,0,8));
              throw new AssertionError("NaN accepted"); }
        catch(IOException expected) { checks++; }
        System.out.println("[КФ СТРЕЛЫ] ПРОЙДЕНО " + checks + " проверок протокола V10.5");
    }
}
