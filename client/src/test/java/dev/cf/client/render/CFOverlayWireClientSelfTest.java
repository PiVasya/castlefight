package dev.cf.client.render;

import dev.cf.protocol.CFOverlayWire;
import java.util.List;

/** Quick deterministic test that shares the exact wire class with the server. */
public final class CFOverlayWireClientSelfTest {
    public static void main(String[] args) throws Exception {
        var route = new CFOverlayWire.Route(1,List.of(
                new CFOverlayWire.Point(1,9,1),new CFOverlayWire.Point(11,9,11)));
        var geometry = new CFOverlayWire.Geometry("arena-1",List.of(
                new CFOverlayWire.Cell(10,8,10,0),new CFOverlayWire.Cell(11,8,10,2)),List.of(route));
        var frames = CFOverlayWire.fragment(CFOverlayWire.pack(geometry),0x12345678);
        var receiver = new CFOverlayWire.Receiver();
        CFOverlayWire.Geometry result = null;
        for (byte[] frame:frames) result=receiver.accept(frame);
        if (!geometry.equals(result)) throw new AssertionError("Client wire mismatch");
        var mesh=CFOverlayMesh.build(result);
        if (mesh.quads().isEmpty()) throw new AssertionError("Empty render mesh");
        System.out.println("[КФ МОД] PASS: серверный формат -> клиентский RAM -> цветные полигоны");
    }
}
