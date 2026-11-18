package com.guodi.assistant.core;

import java.time.Duration;
import java.util.concurrent.CancellationException;

/** In-memory control for one CLI window; never persisted as turn state. */
public final class RequestControl {
    private final long heartbeatTimeoutNanos;
    private volatile long lastHeartbeatNanos = System.nanoTime();
    private volatile Cancellation cancellation;

    public RequestControl(Duration heartbeatTimeout) {
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
            throw new IllegalArgumentException("Heartbeat timeout must be positive");
        }
        heartbeatTimeoutNanos = heartbeatTimeout.toNanos();
    }

    public boolean heartbeat() {
        if (expired()) return false;
        lastHeartbeatNanos = System.nanoTime();
        return true;
    }

    public boolean requestCancel() {
        Cancellation active = cancellation;
        if (active == null) return false;
        active.requested = true;
        return true;
    }

    void executionStarted() {
        cancellation = new Cancellation();
    }

    void executionFinished() {
        cancellation = null;
    }

    public void checkStopRequested() {
        Cancellation active = cancellation;
        if ((active != null && active.requested) || expired()) throw new CancellationException();
    }

    boolean expired() {
        return System.nanoTime() - lastHeartbeatNanos >= heartbeatTimeoutNanos;
    }

    /** A late cancellation only changes the execution that received it. */
    private static final class Cancellation {
        private volatile boolean requested;
    }
}
