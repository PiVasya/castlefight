package dev.cf.client;

import dev.cf.protocol.CFBuildWire;

/**
 * Future currency preflight slot. Castle Fight currently has no currency,
 * prices, or synced balances. Never invent an amount or treat a cached
 * client answer as authoritative. Add resource data + server validation together.
 */
public final class CFBuildEconomy {
    public enum Result { NO_ECONOMY, AFFORDABLE, NOT_AFFORDABLE, BALANCE_UNKNOWN }
    private CFBuildEconomy() { }
    public static Result check(CFBuildWire.Tower tower) {
        // Deliberately disabled until the actual economy exists.
        return Result.NO_ECONOMY;
    }
}
