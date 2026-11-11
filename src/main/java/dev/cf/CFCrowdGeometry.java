package dev.cf;

import java.util.List;

/**
 * Framework-independent, horizontal body geometry for crowd recovery.
 * A body may leave an existing overlap if the TOTAL occupied area decreases.
 * A body may never enter a previously clear neighbour (including via a swept step).
 * Coordinates are in blocks; feet Y is retained to ignore other floors.
 */
final class CFCrowdGeometry {
    static final double GAP = 0.055;
    private static final double EPS = 1.0e-8;
    private static final double IMPROVEMENT = 1.0e-5;

    record Body(double x, double y, double z,
                double minX, double maxX, double minY, double maxY,
                double minZ, double maxZ) { }

    record Assessment(boolean allowed, double before, double after, int overlaps,
                      String reason) { }

    private CFCrowdGeometry() { }

    static double overlapArea(Body mover, double x, double y, double z, Body other) {
        if (y + mover.maxY <= other.y + other.minY + .035 ||
                other.y + other.maxY <= y + mover.minY + .035) return 0;
        // Add half the separation gap on each body, so two just-touching bodies
        // remain slightly separated, as in the original swept collision check.
        double mx0=x+mover.minX-GAP*.5, mx1=x+mover.maxX+GAP*.5;
        double mz0=z+mover.minZ-GAP*.5, mz1=z+mover.maxZ+GAP*.5;
        double ox0=other.x+other.minX-GAP*.5, ox1=other.x+other.maxX+GAP*.5;
        double oz0=other.z+other.minZ-GAP*.5, oz1=other.z+other.maxZ+GAP*.5;
        double w=Math.min(mx1,ox1)-Math.max(mx0,ox0);
        double d=Math.min(mz1,oz1)-Math.max(mz0,oz0);
        return Math.max(0,w)*Math.max(0,d);
    }

    /** A ray crossing a formerly free rectangle is forbidden, even if the end is clear. */
    private static boolean enters(Body mover, double x, double y, double z, Body other) {
        if (Math.max(mover.y+mover.maxY,y+mover.maxY)<=other.y+other.minY ||
                Math.min(mover.y+mover.minY,y+mover.minY)>=other.y+other.maxY) return false;
        double minX=other.x+other.minX-mover.maxX-GAP;
        double maxX=other.x+other.maxX-mover.minX+GAP;
        double minZ=other.z+other.minZ-mover.maxZ-GAP;
        double maxZ=other.z+other.maxZ-mover.minZ+GAP;
        double dx=x-mover.x,dz=z-mover.z,enter=0,exit=1;
        if(Math.abs(dx)<1e-10){if(mover.x<=minX||mover.x>=maxX)return false;}
        else{double a=(minX-mover.x)/dx,b=(maxX-mover.x)/dx;
            enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));}
        if(Math.abs(dz)<1e-10){if(mover.z<=minZ||mover.z>=maxZ)return false;}
        else{double a=(minZ-mover.z)/dz,b=(maxZ-mover.z)/dz;
            enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));}
        return enter<exit && enter<1 && exit>0;
    }

    static Assessment assess(Body mover, double x, double y, double z, List<Body> others) {
        double before=0,after=0;
        int overlapping=0;
        for(Body other:others) {
            double oldArea=overlapArea(mover,mover.x,mover.y,mover.z,other);
            double newArea=overlapArea(mover,x,y,z,other);
            before+=oldArea;after+=newArea;
            if(oldArea>EPS)overlapping++;
            // Do not permit entering a new body, or tunnelling right through it.
            if(oldArea<=EPS && (newArea>EPS || enters(mover,x,y,z,other)))
                return new Assessment(false,before,after,overlapping,"NEW_BODY_CONTACT");
        }
        if(overlapping>0) {
            // Never jump across an already-overlapping neighbour in a single step;
            // escape must be incremental and governed by block collision too.
            if(Math.hypot(x-mover.x,z-mover.z)>.18)
                return new Assessment(false,before,after,overlapping,"OVERLAP_STEP_TOO_LONG");
            if(!(after+IMPROVEMENT<before))
                return new Assessment(false,before,after,overlapping,"NO_NET_OVERLAP_REDUCTION");
        }
        return new Assessment(true,before,after,overlapping,"CLEAR");
    }
}
