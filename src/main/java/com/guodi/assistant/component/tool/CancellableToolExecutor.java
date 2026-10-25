package com.guodi.assistant.component.tool;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.springframework.ai.tool.ToolCallback;
import com.guodi.assistant.versioning.ComponentRevision;

/** serial@1.1.0 preserves ordinary error results but lets cancellation stop the run. */
@ComponentRevision(id = "serial", version = "1.1.0")
public final class CancellableToolExecutor extends RegisteredToolExecutor {
    public CancellableToolExecutor(List<ToolCallback> callbacks, int resultChars) {
        super(callbacks, resultChars);
    }

    @Override
    protected String failureResult(RuntimeException error) {
        // MethodToolCallback may wrap the exception thrown by an annotated tool.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
        }
        return super.failureResult(error);
    }
}
