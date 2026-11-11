package dev.cf;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ai.TargetSelector;

/** Compatibility adapter: all goals use the same authoritative target state. */
final class CFTraceTarget extends TargetSelector {
    private final CFUnit unit;
    CFTraceTarget(CFUnit unit) { super(unit); this.unit = unit; }
    @Override public Entity findTarget() {
        long start = CFDuelPerf.enterMethod();
        try { return unit.targeting().resolve(); }
        finally { CFDuelPerf.exitMethod("CFTraceTarget.findTarget", start); }
    }
}
