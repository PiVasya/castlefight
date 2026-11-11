package dev.cf;

import java.util.*;

/** Per-second spatial-hash collision audit; O(N + local contacts), never moves a unit. */
final class CFSoakCrowdAudit {
    record Snapshot(int id,String team,int lane,double x,double y,double z) { }
    record Cell(int x,int y,int z) { }
    @FunctionalInterface interface Sink { void report(String event,Object... attrs); }
    record Result(int nearPairs,int persistentPairs,int longestSeconds,boolean truncated) { }
    private Map<Long,Integer> previous=Map.of();
    private int longest;
    private static final int TRACK_LIMIT=3000;

    void reset(){previous=Map.of();longest=0;}
    int longestSeconds(){return longest;}
    private static long pair(int left,int right){
        int a=Math.min(left,right),b=Math.max(left,right);
        return ((long)a<<32)|(b&0xffffffffL);
    }
    Result observe(List<Snapshot> snapshots,Sink sink) {
        Map<Cell,List<Snapshot>> occupied=new HashMap<>();
        Map<Long,Integer> now=new HashMap<>();
        int pairs=0,persistent=0;
        boolean truncated=false;
        for(Snapshot mob:snapshots) {
            int bx=(int)Math.floor(mob.x),by=(int)Math.floor(mob.y),bz=(int)Math.floor(mob.z);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                List<Snapshot> nearby=occupied.get(new Cell(bx+dx,by+dy,bz+dz));
                if(nearby==null)continue;
                for(Snapshot other:nearby) {
                    double horizontal=Math.hypot(mob.x-other.x,mob.z-other.z);
                    if(horizontal>=.70 || Math.abs(mob.y-other.y)>.85)continue;
                    pairs++;
                    if(now.size()>=TRACK_LIMIT){truncated=true;continue;}
                    long key=pair(mob.id,other.id);
                    int streak=previous.getOrDefault(key,0)+1;
                    now.put(key,streak);
                    longest=Math.max(longest,streak);
                    if(streak>=5) {
                        persistent++;
                        if(streak==5 || streak%15==0)
                            sink.report("CROWD_OVERLAP_PERSIST","unitA",other.id,"unitB",mob.id,
                                    "teamA",other.team,"teamB",mob.team,"laneA",other.lane,
                                    "laneB",mob.lane,"distance",horizontal,"seconds",streak,
                                    "a",other.x+","+other.y+","+other.z,
                                    "b",mob.x+","+mob.y+","+mob.z);
                    }
                }
            }
            occupied.computeIfAbsent(new Cell(bx,by,bz),ignored->new ArrayList<>()).add(mob);
        }
        previous=now;
        return new Result(pairs,persistent,longest,truncated);
    }
}
