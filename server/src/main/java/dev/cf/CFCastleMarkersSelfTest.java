package dev.cf;

import net.minestom.server.coordinate.Pos;
import java.util.*;

/** Pure marker-contract checks, independent of Anvil files and world saves. */
public final class CFCastleMarkersSelfTest {
    private static int checks;
    private static void check(boolean good,String message) {
        checks++;if(!good)throw new AssertionError(message);
    }
    private static void square(Map<CFPlacementRules.Cell,String> blocks,int x,int y,int z,String team) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            blocks.put(new CFPlacementRules.Cell(x+dx,y,z+dz),team);
    }
    private static void rejected(Map<CFPlacementRules.Cell,String> blocks,String label) {
        try {CFCastleMarkers.locate(blocks);throw new AssertionError("Accepted "+label);}
        catch(IllegalArgumentException expected) {checks++;}
    }
    public static void main(String[] args) {
        Map<CFPlacementRules.Cell,String> blocks=new HashMap<>();
        square(blocks,15,7,15,"blue"); // crossing BOTH x/z chunk borders
        square(blocks,-16,11,-16,"red");
        var located=CFCastleMarkers.locate(blocks);
        check(located.get("blue").samePoint(new Pos(15.5,8,15.5)),"cross-chunk blue throne");
        check(located.get("red").samePoint(new Pos(-15.5,12,-15.5)),"negative cross-chunk red throne");
        blocks.put(new CFPlacementRules.Cell(50,7,50),"blue");
        check(CFCastleMarkers.locate(blocks).size()==2,"lone decorative ceramic ignored");
        var mixed=new HashMap<>(blocks);
        mixed.put(new CFPlacementRules.Cell(15,7,15),"red");
        rejected(mixed,"mixed colours in 3x3");
        var partial=new HashMap<>(blocks);
        partial.remove(new CFPlacementRules.Cell(14,7,15));
        rejected(partial,"missing ceramic tile");
        var duplicate=new HashMap<>(blocks);
        square(duplicate,100,8,100,"blue");
        rejected(duplicate,"multiple blue thrones");
        var broad=new HashMap<>(blocks);
        for(int x=14;x<=17;x++)for(int z=14;z<=17;z++)
            broad.put(new CFPlacementRules.Cell(x,7,z),"blue");
        rejected(broad,"oversized 4x4 marker");
        System.out.println("[КФ ЗАМКИ] ПРОЙДЕНО "+checks+" проверок: 3x3, границы чанков, смешение, пропуски и дубли.");
    }
}
