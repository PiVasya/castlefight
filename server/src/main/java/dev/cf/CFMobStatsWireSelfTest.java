package dev.cf;

import dev.cf.protocol.CFMobStatsWire;
import java.io.IOException;
import java.util.Arrays;

public final class CFMobStatsWireSelfTest {
    public static void main(String[] args) throws Exception {
        var kingStats = new CFMobStatsWire.Stats(42,"King","blue",0,40000,40000,
                100,"slashing","none",100,0,.025,2.2,2500);
        var kingPacket = CFMobStatsWire.write(new CFMobStatsWire.Reply(42,kingStats));
        if(CFMobStatsWire.readReply(kingPacket).stats().maxHp10()!=40000)
            throw new AssertionError("King hover stats must retain 4000 HP, lane 0");

        int checks = 0;
        for (int id : new int[]{-1,0,1,384,65536,Integer.MAX_VALUE}) {
            if (CFMobStatsWire.readQuery(CFMobStatsWire.query(id)) != id)
                throw new AssertionError("Request roundtrip " + id);
            checks++;
        }
        CFMobStatsWire.Stats stats = new CFMobStatsWire.Stats(42, "Зомби могилы", "blue", 4,
                157, 280, 23, "slashing", "light", 45, 15, 0.22, 1.75, 1200);
        var packet = CFMobStatsWire.write(new CFMobStatsWire.Reply(42, stats));
        if (!CFMobStatsWire.readReply(packet).stats().equals(stats)) throw new AssertionError("Stats roundtrip");
        checks++;
        if (Math.abs(stats.healthRatio() - (157.0/280)) > 1e-12) throw new AssertionError("HP ratio");
        checks++;
        if (CFMobStatsWire.readReply(CFMobStatsWire.clear(42)).stats() != null) throw new AssertionError("Clear");
        checks++;
        expectFailure(() -> CFMobStatsWire.readQuery(new byte[]{1,0,0})); checks++;
        expectFailure(() -> CFMobStatsWire.readQuery(new byte[]{2,0,0,0,1})); checks++;
        byte[] corrupt=packet.clone(); corrupt[1]=99;
        expectFailure(() -> CFMobStatsWire.readReply(corrupt)); checks++;
        byte[] trailing=Arrays.copyOf(packet,packet.length+1);
        expectFailure(() -> CFMobStatsWire.readReply(trailing)); checks++;
        expectFailure(() -> CFMobStatsWire.readReply(new byte[2048])); checks++;
        try { new CFMobStatsWire.Stats(42,"A","green",1,1,10,1,"slashing","none",1,0,.2,1,1000);
              throw new AssertionError("Invalid team passed"); }
        catch (IllegalArgumentException ok) { checks++; }
        System.out.println("[КФ СТАТЫ] PASS " + checks + " checks: request, full stats, HP ratio, clear, invalid payloads");
    }
    private interface Attempt { void run() throws Exception; }
    private static void expectFailure(Attempt body) throws Exception {
        try { body.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
}
