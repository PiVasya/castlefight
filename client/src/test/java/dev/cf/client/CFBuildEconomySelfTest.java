package dev.cf.client;

import dev.cf.protocol.CFBuildWire;
import java.util.List;

/** Economy is not implemented: do not fabricate a price or a balance check. */
public final class CFBuildEconomySelfTest {
    public static void main(String[] args) {
        var tower=new CFBuildWire.Tower("dark_graveyard_l1","ТЬМА | Кладбище",
                "dark","","",12,List.of());
        if(CFBuildEconomy.check(tower)!=CFBuildEconomy.Result.NO_ECONOMY)
            throw new AssertionError("Missing economy must be reported as NOT IMPLEMENTED");
        if(CFBuildEconomy.Result.NOT_AFFORDABLE == CFBuildEconomy.Result.NO_ECONOMY)
            throw new AssertionError("No economy must not be confused with insufficient funds");
        System.out.println("[КФ КЛИЕНТ] Экономика не введена: корректный явный статус; выдуманного баланса нет.");
    }
}
