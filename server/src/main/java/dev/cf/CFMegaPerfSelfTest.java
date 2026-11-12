package dev.cf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Pure-Java smoke test; runs in an isolated working directory. */
public final class CFMegaPerfSelfTest {
    private CFMegaPerfSelfTest() { }
    public static void main(String[] args) throws Exception {
        Path control = Path.of("diagnostics/mega-control.txt");
        Files.createDirectories(control.getParent());
        Files.writeString(control, "start 10 selftest-abc\n");
        for(int i=0;i<20;i++) CFMegaPerf.serverTick(2.5,40);
        Path run = Path.of("diagnostics/mega/selftest-abc");
        if(!Files.exists(run.resolve("ACTIVE.txt")))throw new AssertionError("No session start");
        for(int i=0;i<22;i++) {
            long begin=CFMegaPerf.begin();
            CFMegaPerf.count(CFMegaPerf.Count.PUSH_SCANS,1);
            CFMegaPerf.count(CFMegaPerf.Count.PUSH_CONTACTS,2);
            CFMegaPerf.end(CFMegaPerf.Metric.PUSH_SCAN,begin);
            CFMegaPerf.serverTick(1.25,53);
        }
        Files.writeString(control,"push 4\n");
        for(int i=0;i<20;i++) CFMegaPerf.serverTick(1.1,50);
        if(CFMegaPerf.pushPeriod()!=4 || !CFMegaPerf.pushOnThisTick(4,4))
            throw new AssertionError("Push period control failed");
        if(CFMegaPerf.pushOnThisTick(4,5))throw new AssertionError("Stagger failed");
        Files.writeString(control,"stop\n");
        for(int i=0;i<20;i++) CFMegaPerf.serverTick(1.3,60);
        Path complete=run.resolve("COMPLETE.txt");
        for(int i=0;i<100 && !Files.exists(complete);i++)Thread.sleep(20);
        if(!Files.exists(complete))throw new AssertionError("No report");
        String counters=Files.readString(run.resolve("counters.csv"));
        String stats=Files.readString(run.resolve("summary.txt"));
        if(!counters.contains("PUSH_CONTACTS,44,") || !stats.contains("completed=true") ||
                !stats.contains("push_period_ticks_at_end=4"))
            throw new AssertionError("Bad report: "+List.of(counters,stats));
        System.out.println("CFMegaPerfSelfTest OK");
    }
}
