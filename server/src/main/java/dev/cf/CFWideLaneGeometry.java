package dev.cf;

import java.util.ArrayList;
import java.util.List;

/** Pure corridor geometry. A road is a direction, not a set of compulsory centres.
 * No random slots, population-dependent force, teleport or entity separation here.
 */
final class CFWideLaneGeometry {
    static final double RADIUS = 4.0;
    record Node(double x, double y, double z) {
        Node {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Non-finite lane coordinate");
        }
    }
    record Projection(double progress, double lateral, double distanceSq, double height,
                      int nextIndex, double x, double z) { }
    private record Segment(Node a, Node b, double start, double length,
                           double tx, double tz, int nextIndex) { }
    private final Segment[] segments;
    private final double length;
    private final int nodeCount;

    CFWideLaneGeometry(List<Node> nodes) {
        if (nodes == null || nodes.size() < 2) throw new IllegalArgumentException("Need 2+ nodes");
        var built = new ArrayList<Segment>();
        double arc = 0;
        for (int i=1; i<nodes.size(); i++) {
            Node a=nodes.get(i-1), b=nodes.get(i);
            double len=Math.hypot(b.x-a.x,b.z-a.z);
            if (len < 1.e-8) continue;
            built.add(new Segment(a,b,arc,len,(b.x-a.x)/len,(b.z-a.z)/len,i));
            arc+=len;
        }
        if (built.isEmpty()) throw new IllegalArgumentException("No horizontal route");
        segments=built.toArray(Segment[]::new); length=arc; nodeCount=nodes.size();
    }
    double length() { return length; }
    int nodeCount() { return nodeCount; }

    /** Bounded by arc progress, not globally nearest: a hairpin cannot skip half the lane. */
    Projection nearest(Node p, double low, double high, double heightTolerance) {
        long _cfMegaCost = CFMegaPerf.begin();
        try {

        low=clamp(low,0,length); high=clamp(high,low,length);
        boolean found=false;
        double bestScore=Double.POSITIVE_INFINITY,bestProgress=0,bestSide=0,bestDistance=0,
                bestHeight=0,bestX=0,bestZ=0;
        int bestIndex=0;
        int first=segmentAt(low);
        for(int i=first; i<segments.length; i++) {
            Segment s=segments[i];
            if(s.start>high+1.e-8) break;
            double lo=clamp((low-s.start)/s.length,0,1);
            double hi=clamp((high-s.start)/s.length,lo,1);
            double t=clamp(((p.x-s.a.x)*s.tx+(p.z-s.a.z)*s.tz)/s.length,lo,hi);
            double x=s.a.x+s.tx*s.length*t, z=s.a.z+s.tz*s.length*t;
            double y=s.a.y+(s.b.y-s.a.y)*t;
            if(Math.abs(p.y-y)>heightTolerance) continue;
            double dx=p.x-x, dz=p.z-z, distanceSq=dx*dx+dz*dz;
            double score=distanceSq+4*(p.y-y)*(p.y-y);
            // Prefer earlier progress on exact ties, including self crossings.
            if(score < bestScore-1.e-10) {
                bestScore=score;
                found=true;bestProgress=s.start+s.length*t;
                bestSide=-s.tz*dx+s.tx*dz;bestDistance=distanceSq;
                bestHeight=y;bestIndex=s.nextIndex;bestX=x;bestZ=z;
            }
        }
        return found?new Projection(bestProgress,bestSide,bestDistance,bestHeight,bestIndex,bestX,bestZ):null;
    
        } finally { CFMegaPerf.end(CFMegaPerf.Metric.WIDE_GEOMETRY_NEAREST, _cfMegaCost); }
    }
    Projection nearest(Node p) { return nearest(p,0,length,CFVerticalTravel.ROUTE_HEIGHT_TOLERANCE); }
    boolean contains(Node p,double low,double high,double radius) {
        Projection q=nearest(p,low,high,CFVerticalTravel.ROUTE_HEIGHT_TOLERANCE);
        return q!=null && q.distanceSq<=radius*radius+1.e-8;
    }

    /** Local flow tangent to the distance contours of the road. On straight ground
     * it has ZERO lateral component. Around an outer corner it follows an arc,
     * rather than aiming every member of the crowd at the vertex of the bend.
     * The target is a direction handle: only the actual small step is walked.
     */
    Node steeringTarget(Node here,Projection q,double ahead,double usableRadius) {
        int index=segmentAt(q.progress);Segment active=segments[index];
        double orientX=active.tx,orientZ=active.tz;
        if(index>0 && Math.abs(q.progress-active.start)<1.e-6) {
            orientX+=segments[index-1].tx;orientZ+=segments[index-1].tz;
            if(Math.hypot(orientX,orientZ)<1.e-6){orientX=active.tx;orientZ=active.tz;}
        }
        double dx=here.x-q.x,dz=here.z-q.z,distance=Math.hypot(dx,dz);
        double vx=active.tx,vz=active.tz;
        if(distance>1.e-6) {
            double nx=dx/distance,nz=dz/distance;
            double candidateX=-nz,candidateZ=nx;
            double alignment=candidateX*orientX+candidateZ*orientZ;
            if(Math.abs(alignment)>1.e-8) {
                double sign=alignment<0?-1:1;
                vx=candidateX*sign;vz=candidateZ*sign;
            }
            // No correction at all inside the usable strip. Outside it, guide
            // inward only enough to return to the edge, never to the centreline.
            double correction=clamp((distance-usableRadius)/.40,0,1.5);
            vx-=nx*correction;vz-=nz*correction;
        }
        double norm=Math.hypot(vx,vz);
        if(norm<1.e-8){vx=active.tx;vz=active.tz;norm=1;}
        return new Node(here.x+vx/norm*ahead,q.height,here.z+vz/norm*ahead);
    }

    /** Use the CURRENT sideways position, never a stored spawn slot. */
    Node aim(Projection q,double ahead,double usableRadius) {
        return offsetAt(Math.min(length,q.progress+Math.max(0,ahead)),
                clamp(q.lateral,-usableRadius,usableRadius));
    }
    Node offsetAt(double progress,double lateral) {
        progress=clamp(progress,0,length);
        Segment s=segments[segmentAt(progress)];
        double t=clamp((progress-s.start)/s.length,0,1);
        return new Node(s.a.x+s.tx*s.length*t-s.tz*lateral,
                s.a.y+(s.b.y-s.a.y)*t,
                s.a.z+s.tz*s.length*t+s.tx*lateral);
    }
    boolean finished(Projection q,Node here) {
        return q!=null && length-q.progress<=.70 && q.distanceSq<=RADIUS*RADIUS
                && Math.abs(here.y-q.height)<.60;
    }
    private int segmentAt(double progress) {
        int lo=0,hi=segments.length-1;
        while(lo<hi) {
            int mid=(lo+hi+1)>>>1;
            if(segments[mid].start<=progress)lo=mid;else hi=mid-1;
        }
        return lo;
    }
    static double clamp(double value,double low,double high) {
        return Math.max(low,Math.min(high,value));
    }
}
