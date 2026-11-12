package dev.cf;

import dev.cf.protocol.CFOverlayWire;
import java.io.IOException;
import java.util.*;

/** Pure protocol checks: independent of Minestom's world and Java 25 APIs. */
public final class CFOverlayWireSelfTest {
    public static void main(String[] args) throws Exception {
        var rng = new Random(72);
        List<CFOverlayWire.Cell> zones = new ArrayList<>();
        for (int i=0;i<3000;i++) zones.add(new CFOverlayWire.Cell(rng.nextInt(300)-150, 9,
                rng.nextInt(300)-150, i%16));
        List<CFOverlayWire.Route> routes = new ArrayList<>();
        for (int id=1;id<=4;id++) {
            List<CFOverlayWire.Point> points = new ArrayList<>();
            for (int i=0;i<1000;i++) points.add(new CFOverlayWire.Point(i*.5f, id+9, (float)Math.sin(i*.08f)*20+id*20));
            routes.add(new CFOverlayWire.Route(id,points));
        }
        var original = new CFOverlayWire.Geometry("four-lanes",zones,routes);
        byte[] packed = CFOverlayWire.pack(original);
        var frames = new ArrayList<>(CFOverlayWire.fragment(packed, 735));
        if (frames.size() < 2) throw new AssertionError("Test didn't fragment payload");
        Collections.shuffle(frames, rng);
        var receiver = new CFOverlayWire.Receiver();
        CFOverlayWire.Geometry decoded = null;
        for (byte[] frame : frames) { var next = receiver.accept(frame); if (next != null) decoded=next; }
        if (!original.equals(decoded)) throw new AssertionError("Overlay roundtrip mismatch");
        if (!CFOverlayWire.CHANNEL.equals("castlefight:overlay_v1")) throw new AssertionError();
        byte[] modified = frames.get(0).clone(); modified[modified.length-1]^=1;
        var badReceiver = new CFOverlayWire.Receiver();
        try {
            for (int i=0;i<frames.size();i++) badReceiver.accept(i==0?modified:frames.get(i));
            throw new AssertionError("Expected checksum failure");
        } catch (IOException expected) { /* corruption rejected */ }
        System.out.println("[КФ ОВЕРЛЕЙ] PASS: "+zones.size()+" клеток, 4 маршрута, "+frames.size()+
                " пакетов, перестановка частей, контрольная сумма, RAM-only протокол");
    }
}
