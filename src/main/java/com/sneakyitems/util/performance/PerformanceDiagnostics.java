package com.sneakyitems.util.performance;

/** No-op diagnostics; ported call sites remain cheap when disabled. */
public final class PerformanceDiagnostics {

    public static final PerformanceExecution EXECUTION = new PerformanceExecution();

    private PerformanceDiagnostics() {}

    public static final class PerformanceExecution {
        public void matchCall() {}

        public AutoCloseable enter(Object operation, Object owner, Object detail) {
            return () -> {};
        }

        public String owner() {
            return "sneakyitems";
        }

        public Runnable task(String owner, Runnable runnable) {
            return runnable;
        }
    }
}
