package dev.cf;

import java.util.ArrayList;
import java.util.List;

/** Offline, framework-free regression: javac CFCrowdGeometry.java CF265CrowdSelfTest.java */
public final class CF265CrowdSelfTest {
    private static int assertions;
    private static void check(boolean value,String why) {
        assertions++;
        if(!value)throw new AssertionError(why);
    }
    private static CFCrowdGeometry.Body body(int x,int z) {return body((double)x,(double)z);}
    private static CFCrowdGeometry.Body body(double x,double z) {
        return new CFCrowdGeometry.Body(x,8,z,-.3,.3,0,1.9,-.3,.3);
    }
    private static CFCrowdGeometry.Body bodyAtHeight(double x,double y,double z) {
        return new CFCrowdGeometry.Body(x,y,z,-.3,.3,0,1.9,-.3,.3);
    }
    public static void main(String[] args) {
        var self=body(0,0);
        var overlap=List.of(body(0,0));
        var outward=CFCrowdGeometry.assess(self,.08,8,0,overlap);
        check(outward.allowed(),"coincident pair must separate");
        check(outward.after()<outward.before(),"pair overlap area decreases");
        check(!CFCrowdGeometry.assess(body(.28,0),.20,8,0,List.of(self)).allowed(),
                "cannot move deeper into a single neighbour");
        // A pairwise-only blocker would reject this: left body becomes slightly deeper,
        // but releasing the much larger right overlap is a net improvement.
        var group=List.of(body(-.52,.4),body(.18,0));
        var intoSpace=CFCrowdGeometry.assess(self,-.06,8,0,group);
        check(intoSpace.allowed(),"group release despite one worse pair");
        check(intoSpace.after()<intoSpace.before(),"group objective improves");
        check(!CFCrowdGeometry.assess(body(-1,0),1,8,0,List.of(self)).allowed(),
                "body tunnelling through empty destination forbidden");
        check(!CFCrowdGeometry.assess(body(-.7,0),-.56,8,0,List.of(self)).allowed(),
                "new neighbour contact forbidden");
        check(CFCrowdGeometry.assess(body(-1,0),-1.08,8,0,List.of(self)).allowed(),
                "free body may move away");
        check(CFCrowdGeometry.assess(self,.1,8,0,List.of(bodyAtHeight(0,12,0))).allowed(),
                "different floors do not collide");
        check(!CFCrowdGeometry.assess(self,.25,8,0,overlap).allowed(),
                "overlapping unit cannot tunnel by a long leap");
        var four=new ArrayList<CFCrowdGeometry.Body>();
        four.add(body(0,0));four.add(body(.12,0));four.add(body(0,.12));four.add(body(.12,.12));
        check(CFCrowdGeometry.assess(self,-.06,8,-.06,four).allowed(),
                "four-body stack permits incremental outward release");
        // No fake free movement if every candidate only exchanges one overlap for another.
        var symmetric=List.of(body(-.2,0),body(.2,0));
        check(!CFCrowdGeometry.assess(self,.05,8,0,symmetric).allowed(),
                "balanced compression may not masquerade as progress");
        System.out.println("[CF265 CROWD] PASS "+assertions+" geometry assertions");
    }
}
