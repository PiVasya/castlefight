package dev.cf;
import java.nio.file.*;
import java.util.Properties;
public final class CF263LogSelfTest {
    public static void main(String[] args)throws Exception {
        CFSoakTest.selfTestInterruptedRound();
        Path dir=Path.of("capture-selftest");Files.createDirectory(dir);
        CFSkeletonMindLog.beginCapture(dir);
        for(int i=0;i<24;i++)CFSkeletonMindLog.systemEvent("ROTATION_TEST","number",i,"data","x".repeat(1600));
        CFSkeletonMindLog.finishCapture(dir);
        Properties p=new Properties();
        long end=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<end) {
            p.clear();try(var in=Files.newBufferedReader(dir.resolve("ai-capture.properties"))){p.load(in);}
            if(p.getProperty("state").equals("FLUSHED"))break;
            Thread.sleep(50);
        }
        if(!"FLUSHED".equals(p.getProperty("state")))throw new AssertionError("AI capture did not flush");
        int first=Integer.parseInt(p.getProperty("firstPart")),last=Integer.parseInt(p.getProperty("lastPart"));
        if(last-first<8)throw new AssertionError("Use -Dcf.ai.rotateBytes=1024 for this test");
        for(int n=first;n<=last;n++)
            if(!Files.isRegularFile(Path.of("diagnostics/skeleton-ai",p.getProperty("prefix")+n+".jsonl")))
                throw new AssertionError("Pinned AI part missing "+n);
        if(!"0".equals(p.getProperty("dropped")))throw new AssertionError("AI log drops");
        System.out.println("[CF263 LOG] PASS: "+(last-first+1)+" AI parts retained (old retention limit was 8)");
    }
}
