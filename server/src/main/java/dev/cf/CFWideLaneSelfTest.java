package dev.cf;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Engine-independent geometry regression. This is NOT a live 40/1000-unit benchmark. */
public final class CFWideLaneSelfTest {
    static int checks;
    static void check(boolean ok,String why) {checks++;if(!ok)throw new AssertionError(why);}
    static void close(double a,double b,String why){check(Math.abs(a-b)<1.e-8,why+": "+a+" != "+b);}
    static CFWideLaneGeometry.Node p(double x,double z){return new CFWideLaneGeometry.Node(x,8,z);}
    static CFWideLaneGeometry road(CFWideLaneGeometry.Node... p){return new CFWideLaneGeometry(List.of(p));}
    public static void main(String[] args) {
        var east=road(p(0,0),p(100,0));
        for(double side:new double[]{-3.5,-2,-.7,0,.7,2,3.5}) {
            var q=east.nearest(p(20,side));var aim=east.aim(q,4,3.65);
            close(aim.z(),side,"straight road does not attract to centre");
            close(aim.x(),24,"forward look-ahead");
        }
        var west=road(p(100,0),p(0,0));
        close(west.aim(west.nearest(p(20,2)),4,3.65).z(),2,"reverse keeps offset");
        close(west.aim(west.nearest(p(20,2)),4,3.65).x(),16,"reverse moves west");
        var pushed=east.aim(east.nearest(p(20,2.5)),4,3.65);
        close(pushed.z(),2.5,"push changes current offset, no original-slot restoration");
        close(east.aim(east.nearest(p(20,5)),4,3.65).z(),3.65,"only boundary pulls inward");
        check(east.contains(p(20,3.99),0,100,4),"inside four-block corridor");
        check(!east.contains(p(20,4.01),0,100,4),"outside corridor rejected");
        check(!east.contains(new CFWideLaneGeometry.Node(20,11,0),0,100,4),"separate floor rejected");
        var diagonal=road(p(0,0),p(100,100));
        double rt=Math.sqrt(2),side=2.4;
        var dp=p(20-side/rt,20+side/rt);
        var dq=diagonal.nearest(dp);var dg=diagonal.aim(dq,4,3.65);
        close(dq.lateral(),side,"diagonal lateral projection");
        close(dg.z()-dg.x(),dp.z()-dp.x(),"diagonal no centre attraction");
        var hairpin=road(p(0,0),p(100,0),p(100,2),p(0,2));
        var nearOtherLeg=hairpin.nearest(p(20,1.8),15,30,2);
        check(nearOtherLeg.progress()<31,"parallel future leg cannot steal progress");
        var crossing=road(p(0,0),p(20,20),p(0,20),p(20,0));
        check(crossing.nearest(p(10,10),0,20,2).progress()<20,"crossing cannot jump to later segment");
        check(east.finished(east.nearest(p(99.5,3.3)),p(99.5,3.3)),"finish across width, not centre pixel");
        check(!east.finished(east.nearest(p(95,0)),p(95,0)),"no premature arrival");
        check(!east.finished(east.nearest(new CFWideLaneGeometry.Node(100,9,0)),new CFWideLaneGeometry.Node(100,9,0)),"no arrival on wrong level");
        var dense=new ArrayList<CFWideLaneGeometry.Node>();
        for(int i=0;i<=100;i++)dense.add(p(i,0));
        var denseRoad=new CFWideLaneGeometry(dense);
        close(denseRoad.aim(denseRoad.nearest(p(10.3,2.7)),4,3.65).z(),2.7,"dense nodes do not force recentering");
        var repeated=road(p(0,0),p(0,0),p(10,0));
        close(repeated.length(),10,"duplicate nodes");
        boolean rejected=false;try {road(p(0,0),p(0,0));}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"reject route without horizontal motion");
        // 40 independent kinematic walkers: 8 columns, 5 rows, 500 steps.
        // No artificial expansion is expected; the existing six-block front is retained.
        for(int row=0;row<5;row++)for(int col=0;col<8;col++) {
            double initialZ=-3.2+col*(6.4/7), x=2+row*.9,z=initialZ,progress=x;
            for(int step=0;step<500;step++) {
                var q=denseRoad.nearest(p(x,z),Math.max(0,progress-3),progress+12,2);
                progress=q.progress();var goal=denseRoad.steeringTarget(p(x,z),q,4,3.65);
                double dx=goal.x()-x,dz=goal.z()-z,len=Math.hypot(dx,dz);
                x+=dx/len*.1035;z+=dz/len*.1035;
                close(z,initialZ,"40-walker straight width regression");
            }
            check(x>50,"walker progresses, not a frozen formation");
        }
        Random random=new Random(266);
        for(int i=0;i<1000;i++) {
            double angle=random.nextDouble()*Math.PI*2,tx=Math.cos(angle),tz=Math.sin(angle);
            var r=road(p(-17,5),p(-17+100*tx,5+100*tz));
            double lateral=(random.nextDouble()*2-1)*3.5,s=10+random.nextDouble()*70;
            var at=p(-17+s*tx-tz*lateral,5+s*tz+tx*lateral);
            var q=r.nearest(at,s-2,s+12,2);var to=r.steeringTarget(at,q,4,3.65);
            close((to.x()-at.x())*(-tz)+(to.z()-at.z())*tx,0,"random direction lateral velocity zero");
        }
        var bend=road(p(0,0),p(30,0),p(30,80));
        double minFinishX=100,maxFinishX=-100;
        for(double lateral:new double[]{-3.2,-2,-1,0,1,2,3.2}) {
            double x=10,z=lateral,progress=10;
            for(int step=0;step<650;step++) {
                var at=p(x,z);var q=bend.nearest(at,Math.max(0,progress-4),progress+12,2);
                progress=q.progress();var to=bend.steeringTarget(at,q,4,3.65);
                double dx=to.x()-x,dz=to.z()-z,len=Math.hypot(dx,dz);
                x+=dx/len*.1;z+=dz/len*.1;
                check(bend.contains(p(x,z),Math.max(0,progress-4),progress+12,4),"corner stays in corridor");
            }
            check(z>35,"corner makes progress");
            if(Math.abs(lateral)>1)check(Math.abs(x-30)>Math.abs(lateral)-.25,"corner does not funnel to vertex");
            minFinishX=Math.min(minFinishX,x);maxFinishX=Math.max(maxFinishX,x);
        }
        check(maxFinishX-minFinishX>6,"wide front survives a right-angle bend");
        var inward=east.steeringTarget(p(20,5),east.nearest(p(20,5)),4,3.65);
        check(inward.z()<5 && inward.x()>20,"boundary correction guides inward and forward");
        System.out.println("[CF266 WIDE LANE] PASS "+checks+" assertions; geometry only, not live performance");
    }
}
