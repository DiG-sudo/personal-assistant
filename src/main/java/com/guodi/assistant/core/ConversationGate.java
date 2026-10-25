package com.guodi.assistant.core;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Single-process conversation serialization with bounded lock storage. */
public final class ConversationGate {
    // ponytail: 64 stripes may serialize unrelated conversations; use keyed locks if contention matters.
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public ConversationGate() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public <T> T within(String conversationId, Supplier<T> work) {
        var lock = locks[Math.floorMod(conversationId.hashCode(), locks.length)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
