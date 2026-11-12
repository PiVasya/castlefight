package dev.cf;

import net.minestom.server.coordinate.Pos;
import java.util.*;

/** Compile unordered same-color dots into a single unbranched chain; never skip unused dots. */
final class CFMarkerChain {
    static List<Pos> order(List<Pos> input,Pos blue,Pos red,double maxGap) {
        if(input.size()<2||input.size()>512)throw new IllegalArgumentException("Need 2..512 road dots of each color; found "+input.size());
        var p=new ArrayList<>(input);p.sort(Comparator.comparingDouble(Pos::x).thenComparingDouble(Pos::z).thenComparingDouble(Pos::y));
        int n=p.size();boolean[] used=new boolean[n];double[] best=new double[n];int[] parent=new int[n];
        Arrays.fill(best,Double.POSITIVE_INFINITY);Arrays.fill(parent,-1);best[0]=0;
        List<List<Integer>> edges=new ArrayList<>();for(int i=0;i<n;i++)edges.add(new ArrayList<>());
        for(int k=0;k<n;k++) {
            int v=-1;for(int i=0;i<n;i++)if(!used[i]&&(v<0||best[i]<best[v]))v=i;
            if(best[v]>maxGap*maxGap)throw new IllegalArgumentException("Road gap > "+maxGap+" blocks near "+p.get(v));
            used[v]=true;if(parent[v]>=0){edges.get(v).add(parent[v]);edges.get(parent[v]).add(v);}
            for(int i=0;i<n;i++)if(!used[i]){double d=p.get(v).distanceSquared(p.get(i));if(d<best[i]){best[i]=d;parent[i]=v;}}
        }
        for(int i=0;i<n;i++)if(edges.get(i).size()>2)throw new IllegalArgumentException("Ambiguous road branch/painted patch near "+p.get(i)+". Use separated dots in ONE chain.");
        int a=nearest(p,blue),b=nearest(p,red);
        if(a==b||edges.get(a).size()!=1||edges.get(b).size()!=1)
            throw new IllegalArgumentException("Nearest dots to the two castles must be OPPOSITE chain ends. Check endpoints near castles.");
        var out=new ArrayList<Pos>();int prev=-1,cur=a;
        while(cur>=0){out.add(p.get(cur));int next=-1;for(int j:edges.get(cur))if(j!=prev)next=j;prev=cur;cur=next;}
        if(out.size()!=n||!out.getLast().equals(p.get(b)))throw new IllegalArgumentException("Not one connected chain");
        return List.copyOf(out);
    }
    private static int nearest(List<Pos> p,Pos at){int best=0;for(int i=1;i<p.size();i++)if(at.distanceSquared(p.get(i))<at.distanceSquared(p.get(best)))best=i;return best;}
    private CFMarkerChain() { }
}
