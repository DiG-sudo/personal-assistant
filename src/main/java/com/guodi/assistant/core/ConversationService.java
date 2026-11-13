package com.guodi.assistant.core;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.guodi.assistant.core.AssistantHarness.Reply;
import com.guodi.assistant.core.event.AgentEvent;

/** Single-process session admission; the calling request executes its own turn. */
public final class ConversationService {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AgentRuntime runtime;

    public ConversationService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /** Returns null when this waiting message has been promoted to a running turn's steer input. */
    public Reply send(String conversationId, SessionMessage message) {
        return send(conversationId, message, event -> {});
    }

    public Reply send(String conversationId, SessionMessage message, Consumer<AgentEvent> events) {
        return session(conversationId).submit(message, events);
    }

    public boolean steer(String conversationId, UUID messageId) {
        Session session = sessions.get(conversationId);
        return session != null && session.steer(messageId);
    }

    private Session session(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> new Session(id, runtime));
    }

    private static final class Session {
        private final String conversationId;
        private final AgentRuntime runtime;
        private final ConcurrentHashMap<UUID, SessionMessage> waiting = new ConcurrentHashMap<>();
        private final ReentrantLock executionLock = new ReentrantLock(true);
        private volatile List<SessionMessage> activeSteers;

        Session(String conversationId, AgentRuntime runtime) {
            this.conversationId = conversationId;
            this.runtime = runtime;
        }

        Reply submit(SessionMessage message, Consumer<AgentEvent> events) {
            waiting.put(message.messageId(), message);
            executionLock.lock();
            try {
                SessionMessage claimed = waiting.remove(message.messageId());
                if (claimed == null) {
                    return null;
                }
                // Each turn owns its buffer. A late write cannot become another turn's input.
                activeSteers = new CopyOnWriteArrayList<>();
                return runtime.chat(conversationId, claimed.text(), activeSteers, events);
            } finally {
                activeSteers = null;
                executionLock.unlock();
            }
        }

        boolean steer(UUID messageId) {
            List<SessionMessage> steers = activeSteers;
            if (steers == null) {
                return false;
            }
            SessionMessage pending = waiting.remove(messageId);
            if (pending == null) {
                return false;
            }
            steers.add(pending);
            return true;
        }

    }

    public record SessionMessage(UUID messageId, String text) {}
}
