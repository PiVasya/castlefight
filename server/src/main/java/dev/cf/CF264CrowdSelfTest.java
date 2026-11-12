package dev.cf;

import java.util.*;

/** Independent regression of near-body detection across grid boundaries and time. */
public final class CF264CrowdSelfTest {
    private static void check(boolean success,String note){if(!success)throw new AssertionError(note);}
    public static void main(String[] args){
        CFSoakCrowdAudit audit=new CFSoakCrowdAudit();
        var near=List.of(new CFSoakCrowdAudit.Snapshot(1,"blue",1,.98,11,.10),
                new CFSoakCrowdAudit.Snapshot(2,"red",1,1.04,11,.11));
        List<String> events=new ArrayList<>();
        for(int i=1;i<=5;i++){
            var result=audit.observe(near,(e,v)->events.add(e));
            check(result.nearPairs()==1,"one overlapping pair across grid boundary");
            check(result.longestSeconds()==i,"streak increments once per sample");
        }
        check(events.size()==1 && events.getFirst().equals("CROWD_OVERLAP_PERSIST"),"alert at fifth sample");
        check(audit.observe(List.of(),(e,v)->events.add(e)).nearPairs()==0,"no pairs after leaving");
        check(audit.observe(near,(e,v)->events.add(e)).longestSeconds()==5,"historical longest retained");
        audit.reset();
        check(audit.longestSeconds()==0,"reset between rounds");
        var far=List.of(new CFSoakCrowdAudit.Snapshot(3,"blue",1,0,11,0),
                new CFSoakCrowdAudit.Snapshot(4,"blue",1,0,13,0));
        check(audit.observe(far,(e,v)->events.add(e)).nearPairs()==0,"different height not collision");
        System.out.println("[CF264 CROWD] PASS: time-streak, cross-cell, reset, separated-height");
    }
}
