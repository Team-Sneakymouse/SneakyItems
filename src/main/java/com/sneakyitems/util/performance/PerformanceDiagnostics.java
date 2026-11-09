package com.sneakyitems.util.performance;

import com.sneakyitems.integration.Probe;

/**
 * Static access to this plugin's SneakyDiagnostics probe for the ported (static) magic-item code.
 * Without an attached probe (unit tests, before enable) or without a running capture every call returns at once:
 * names are constants, so nothing is built or allocated. The probe is server-thread only, while matching is public API
 * other plugins may call from any thread: calls from other threads are not recorded.
 */
public final class PerformanceDiagnostics {

    private static volatile Probe probe;
    private static volatile Thread serverThread;

    private PerformanceDiagnostics() {}

    /** SneakyItems attaches its probe on enable (server thread) and detaches it on disable. */
    public static void attach(Probe attached) {
        serverThread = Thread.currentThread();
        probe = attached;
    }

    public static void detach() {
        probe = null;
        serverThread = null;
    }

    private static Probe probe() {
        Probe p = probe;
        return p != null && Thread.currentThread() == serverThread ? p : null;
    }

    public static long start() {
        Probe p = probe();
        return p == null ? 0 : p.start();
    }

    public static void end(String section, long token) {
        Probe p = probe();
        if (p != null && token != 0) p.end(section, token);
    }

    public static void count(String key) {
        Probe p = probe();
        if (p != null) p.count(key);
    }

    public static void count(String key, long n) {
        Probe p = probe();
        if (p != null) p.count(key, n);
    }

    /** The single sampleDue site is MagicItemUpdater.sampleLevels. */
    public static boolean sampleDue() {
        Probe p = probe();
        return p != null && p.sampleDue();
    }

    public static void level(String key, long value) {
        Probe p = probe();
        if (p != null) p.level(key, value);
    }
}
