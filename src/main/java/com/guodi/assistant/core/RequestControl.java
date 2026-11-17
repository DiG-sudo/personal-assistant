package com.guodi.assistant.core;

import java.time.Duration;
import java.util.concurrent.CancellationException;

/** In-memory control for one CLI window; never persisted as turn state. */
public final class RequestControl {
    private final long heartbeatTimeoutNanos;
    private long lastHeartbeatNanos = System.nanoTime();
    private boolean running;
    private boolean cancelRequested;

    public RequestControl(Duration heartbeatTimeout) {
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
            throw new IllegalArgumentException("Heartbeat timeout must be positive");
        }
        heartbeatTimeoutNanos = heartbeatTimeout.toNanos();
    }

    public synchronized boolean heartbeat() {
        if (expired()) return false;
        lastHeartbeatNanos = System.nanoTime();
        return true;
    }

    public synchronized boolean requestCancel() {
        if (!running) return false;
        cancelRequested = true;
        return true;
    }

    synchronized void executionStarted() {
        running = true;
    }

    synchronized void executionFinished() {
        cancelRequested = false;
        running = false;
    }

    public synchronized void checkStopRequested() {
        if (cancelRequested || expired()) throw new CancellationException();
    }

    private boolean expired() {
        return System.nanoTime() - lastHeartbeatNanos >= heartbeatTimeoutNanos;
    }
}
