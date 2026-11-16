package com.guodi.assistant.component.context;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.guodi.assistant.domain.TranscriptMessage;

/** Derived context only. Raw transcript positions belong to the storage checkpoint. */
public record ContextView(Summary summary, List<Entry> messages) {
    public ContextView {
        messages = List.copyOf(messages);
    }

    public static ContextView empty() { return new ContextView(null, List.of()); }

    /** Null summary means no compaction yet; generated summaries have these six fields. */
    public record Summary(String goal, List<String> constraints, Progress progress,
            List<String> keyDecisions, List<String> nextSteps, List<String> criticalContext) {
        public Summary {
            if (goal == null || goal.isBlank()) throw new IllegalArgumentException("Summary goal is required");
            constraints = List.copyOf(constraints);
            Objects.requireNonNull(progress);
            keyDecisions = List.copyOf(keyDecisions);
            nextSteps = List.copyOf(nextSteps);
            criticalContext = List.copyOf(criticalContext);
        }
    }

    public record Progress(List<String> done, List<String> inProgress, List<String> blocked) {
        public Progress {
            done = List.copyOf(done);
            inProgress = List.copyOf(inProgress);
            blocked = List.copyOf(blocked);
        }
    }

    /** Turn identity distinguishes a new question from a steer within the same turn. */
    public record Entry(UUID turnId, TranscriptMessage message) {
        public Entry {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(message);
        }
    }
}
