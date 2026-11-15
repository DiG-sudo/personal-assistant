package com.guodi.assistant.component.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.assistant.component.tool.ToolBinding.Kind;

/** Runs consecutive reads together and waits for them before the next call. */
public final class ToolRoundExecutor {
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ExecutorService readWorkers;

    public ToolRoundExecutor(ToolRegistry registry, ToolExecutor executor, ExecutorService readWorkers) {
        this.registry = registry;
        this.executor = executor;
        this.readWorkers = readWorkers;
    }

    public List<ToolOutcome> executeRound(List<ToolCall> calls, ToolContext context) {
        var results = new ArrayList<ToolOutcome>(calls.size());
        int index = 0;
        while (index < calls.size()) {
            int end = index + 1;
            if (isRead(calls.get(index))) {
                while (end < calls.size() && isRead(calls.get(end))) {
                    end++;
                }
            }

            if (end == index + 1) {
                results.add(executor.execute(calls.get(index), context));
            } else {
                executeReads(calls.subList(index, end), context, results);
            }
            index = end;
        }
        return List.copyOf(results);
    }

    private boolean isRead(ToolCall call) {
        var binding = registry.findBinding(call.name());
        return binding != null && binding.kind() == Kind.READ;
    }

    private void executeReads(List<ToolCall> calls, ToolContext context, List<ToolOutcome> results) {
        var futures = new ArrayList<Future<ToolOutcome>>(calls.size());
        boolean completed = false;
        try {
            for (var call : calls) {
                try {
                    futures.add(readWorkers.submit(() -> executor.execute(call, context)));
                } catch (RejectedExecutionException rejected) {
                    futures.add(CompletableFuture.completedFuture(
                            new ToolOutcome(call, "工具执行失败：只读执行队列不可用。", false)));
                }
            }
            for (var future : futures) {
                results.add(future.get());
            }
            completed = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var cancelled = new CancellationException("工具轮次已中断。");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("工具轮次执行失败。", failed.getCause());
        } finally {
            if (!completed) {
                for (var future : futures) {
                    future.cancel(true);
                }
            }
        }
    }
}
