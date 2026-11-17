package com.guodi.assistant.core;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.FileSessionStore;
import com.guodi.assistant.domain.ConversationInfo;
import com.guodi.assistant.config.AssistantProperties.Control;
import com.guodi.assistant.core.AssistantHarness.Reply;
import com.guodi.assistant.core.event.AgentEvent;

/** Single-process session admission; the calling request executes its own turn. */
public final class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private final FileSessionStore sessionStore;
    private final FileConversationStore transcripts;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AgentRuntime runtime;
    private final Control controlSettings;

    public ConversationService(AgentRuntime runtime, FileSessionStore sessionStore, FileConversationStore transcripts,
            Control controlSettings) {
        this.runtime = runtime;
        this.sessionStore = sessionStore;
        this.transcripts = transcripts;
        this.controlSettings = controlSettings;
    }

    public List<ConversationInfo> list() {
        var infos = new HashMap<String, ConversationInfo>();
        sessionStore.list().forEach(info -> infos.put(info.conversationId(), info));
        for (String id : transcripts.conversationIds()) {
            if (!infos.containsKey(id)) {
                info(id).ifPresent(info -> infos.put(id, info));
            }
        }
        return infos.values().stream().sorted(Comparator.comparing(ConversationInfo::updatedAt).reversed()
                .thenComparing(ConversationInfo::conversationId)).toList();
    }

    public Optional<ConversationInfo> info(String conversationId) {
        return sessionStore.load(conversationId).or(() -> {
            var history = transcripts.load(conversationId);
            if (history.isEmpty()) {
                return Optional.empty();
            }
            var first = history.getFirst();
            String title = first.messages().stream().filter(message -> "user".equals(message.role()))
                    .map(message -> message.text()).findFirst().orElse("未命名会话");
            // Legacy turns did not persist a project path. Reading must not guess or bind one.
            return Optional.of(new ConversationInfo(conversationId, null, title(title),
                    first.startedAt(), history.getLast().startedAt()));
        });
    }

    private ConversationInfo bindProject(String conversationId, SessionMessage message) {
        Instant now = Instant.now();
        ConversationInfo previous = info(conversationId).orElseGet(() -> new ConversationInfo(
                conversationId, null, title(message.text()), now, now));
        String projectPath = message.projectPath().toString();
        if (previous.projectPath() != null && !previous.projectPath().equals(projectPath)) {
            throw new ProjectPathMismatchException(previous.projectPath());
        }
        ConversationInfo bound = new ConversationInfo(conversationId, projectPath, previous.title(),
                previous.createdAt(), now);
        // Commit the binding before any model or tool side effect.
        sessionStore.save(bound);
        return bound;
    }

    private void touch(ConversationInfo info) {
        try {
            sessionStore.save(new ConversationInfo(info.conversationId(), info.projectPath(), info.title(),
                    info.createdAt(), Instant.now()));
        } catch (RuntimeException error) {
            // A list timestamp failure must not hide a committed answer or the original execution error.
            log.warn("Could not update activity time for conversation {}", info.conversationId(), error);
        }
    }

    private static String title(String message) {
        String title = message.replaceAll("[\\p{Cc}\\s]+", " ").strip();
        return title.length() <= 80 ? title : title.substring(0, 80) + "…";
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

    public Control openControl(String conversationId) {
        session(conversationId).openControl();
        return controlSettings;
    }

    public boolean heartbeat(String conversationId) {
        Session session = sessions.get(conversationId);
        return session != null && session.heartbeat();
    }

    public boolean cancel(String conversationId) {
        Session session = sessions.get(conversationId);
        return session != null && session.cancel();
    }

    private Session session(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> new Session(id));
    }

    private final class Session {
        private final String conversationId;
        private final ConcurrentHashMap<UUID, SessionMessage> waiting = new ConcurrentHashMap<>();
        private final ReentrantLock executionLock = new ReentrantLock(true);
        private volatile SteerBuffer steerBuffer;
        private RequestControl control;

        Session(String conversationId) {
            this.conversationId = conversationId;
        }

        synchronized void openControl() {
            control = newControl();
        }

        private RequestControl newControl() {
            return new RequestControl(Duration.ofSeconds(controlSettings.heartbeatTimeoutSeconds()));
        }

        private synchronized RequestControl controlForRequest() {
            // Preserve direct HTTP callers; an expired existing control requires explicit reopening.
            if (control == null) control = newControl();
            return control;
        }

        synchronized boolean heartbeat() {
            return control != null && control.heartbeat();
        }

        boolean cancel() {
            SteerBuffer active = steerBuffer;
            return active != null && active.control().requestCancel();
        }

        Reply submit(SessionMessage message, Consumer<AgentEvent> events) {
            RequestControl requestControl = controlForRequest();
            waiting.put(message.messageId(), message);
            executionLock.lock();
            try {
                SessionMessage claimed = waiting.remove(message.messageId());
                if (claimed == null) {
                    return null;
                }
                requestControl.executionStarted();
                ConversationInfo info = bindProject(conversationId, claimed);
                // Each turn owns its buffer. A late write cannot become another turn's input.
                SteerBuffer active = new SteerBuffer(claimed.projectPath(), new CopyOnWriteArrayList<>(), requestControl);
                steerBuffer = active;
                try {
                    return runtime.chat(conversationId, claimed.text(), claimed.projectPath(), active.steers(), requestControl, events);
                } finally {
                    touch(info);
                }
            } finally {
                requestControl.executionFinished();
                steerBuffer = null;
                executionLock.unlock();
            }
        }

        boolean steer(UUID messageId) {
            SteerBuffer active = steerBuffer;
            if (active == null) {
                return false;
            }
            SessionMessage pending = waiting.get(messageId);
            if (pending == null || !active.projectPath().equals(pending.projectPath())
                    || !waiting.remove(messageId, pending)) {
                return false;
            }
            active.steers().add(pending);
            return true;
        }

        private record SteerBuffer(Path projectPath, List<SessionMessage> steers, RequestControl control) {}
    }

    public static final class ProjectPathMismatchException extends RuntimeException {
        public ProjectPathMismatchException(String projectPath) {
            super("会话已绑定项目目录：" + projectPath + "；请在该目录继续此会话，或创建新会话。");
        }
    }

    public record SessionMessage(UUID messageId, String text, Path projectPath) {}
}
